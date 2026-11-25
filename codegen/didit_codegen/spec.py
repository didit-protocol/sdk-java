"""Loads ``openapi/`` and resolves the SDK naming of every operation (DESIGN 3 and 4)."""

from __future__ import annotations

import copy
import json
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

HTTP_METHODS = ("get", "post", "put", "patch", "delete")
SPECS = (("verification", "verification.json", ""), ("account", "account.json", "account "))
AUTH_KINDS = ("api_key", "account_token", "none", "device_token", "public")
APPLICATION_PREFIX = "/v3/organization/{organization_id}/application/{application_id}/"


class SpecError(Exception):
    """The OpenAPI documents cannot be turned into an SDK; the message says why."""


def pointer_escape(token: str) -> str:
    return token.replace("~", "~0").replace("/", "~1")


def pointer_unescape(token: str) -> str:
    return token.replace("~1", "/").replace("~0", "~")


@dataclass
class Document:
    """One OpenAPI document with JSON pointer resolution."""

    name: str
    data: dict[str, Any]

    def resolve_pointer(self, ref: str) -> Any:
        if not ref.startswith("#/"):
            raise SpecError(f"{self.name}: only local $ref values are supported, got {ref!r}")
        node: Any = self.data
        for token in ref[2:].split("/"):
            token = pointer_unescape(token)
            if isinstance(node, list):
                node = node[int(token)]
            elif isinstance(node, dict) and token in node:
                node = node[token]
            else:
                raise SpecError(f"{self.name}: unresolvable $ref {ref!r}")
        return node

    def deref(self, node: Any) -> tuple[Any, str | None]:
        """Follows ``$ref`` chains. Returns the target and the last pointer followed."""
        last = None
        seen = set()
        while isinstance(node, dict) and "$ref" in node:
            ref = node["$ref"]
            if ref in seen:
                raise SpecError(f"{self.name}: $ref cycle at {ref!r}")
            seen.add(ref)
            last = ref
            node = self.resolve_pointer(ref)
        return node, last


@dataclass
class Operation:
    spec: str
    doc: Document
    http_method: str
    path: str
    raw: dict[str, Any]
    path_item: dict[str, Any]
    sdk: dict[str, Any]
    pointer: str
    deprecated: dict[str, Any] | None = None
    extra: dict[str, Any] = field(default_factory=dict)

    @property
    def key(self) -> str:
        prefix = "" if self.spec == "verification" else "account "
        return f"{prefix}{self.http_method.upper()} {self.path}"

    @property
    def resource(self) -> str:
        return str(self.sdk["resource"])

    @property
    def method(self) -> str:
        return str(self.sdk["method"])

    @property
    def full_name(self) -> str:
        return f"{self.resource}.{self.method}"

    @property
    def application_scoped(self) -> bool:
        return self.sdk.get("scope") == "application"

    def parameters(self) -> list[dict[str, Any]]:
        merged: dict[tuple[str, str], dict[str, Any]] = {}
        for raw in list(self.path_item.get("parameters", [])) + list(self.raw.get("parameters", [])):
            param, _ = self.doc.deref(raw)
            merged[(param["in"], param["name"])] = param
        return list(merged.values())


@dataclass
class Spec:
    root: Path
    docs: dict[str, Document]
    overlay: dict[str, Any]
    operations: list[Operation]
    deprecated_file: dict[str, Any]

    def server_operations(self) -> list[Operation]:
        return [op for op in self.operations if op.sdk.get("server")]


def load_json(path: Path) -> Any:
    with path.open(encoding="utf-8") as handle:
        return json.load(handle)


def validate_sdk(key: str, sdk: Any, origin: str) -> dict[str, Any]:
    if not isinstance(sdk, dict):
        raise SpecError(f"{key}: x-didit-sdk from {origin} must be an object")
    for required in ("resource", "method", "auth", "server"):
        if required not in sdk:
            raise SpecError(f"{key}: x-didit-sdk from {origin} lacks {required!r}")
    if sdk["auth"] not in AUTH_KINDS:
        raise SpecError(f"{key}: unknown auth {sdk['auth']!r} in x-didit-sdk from {origin}")
    for segment in str(sdk["resource"]).split("."):
        if not segment or not segment.replace("_", "a").isalnum() or not segment[0].isalpha():
            raise SpecError(f"{key}: resource {sdk['resource']!r} is not a dot path of snake_case names")
    if not str(sdk["method"]).replace("_", "a").isalnum():
        raise SpecError(f"{key}: method {sdk['method']!r} is not snake_case")
    return sdk


def load(root: Path) -> Spec:
    """Reads ``openapi/`` under ``root`` and returns every operation with its SDK naming."""
    openapi = root / "openapi"
    overlay = load_json(openapi / "overlay.json")
    overlay_ops: dict[str, Any] = overlay.get("operations", {})
    deprecated_path = openapi / "deprecated-operations.json"
    deprecated_file = load_json(deprecated_path) if deprecated_path.exists() else {"version": 1, "methods": {}}

    docs: dict[str, Document] = {}
    operations: list[Operation] = []
    missing: list[str] = []
    for spec_name, file_name, key_prefix in SPECS:
        data = load_json(openapi / file_name)
        doc = Document(file_name, data)
        docs[spec_name] = doc
        for path, path_item in data.get("paths", {}).items():
            for http_method in HTTP_METHODS:
                raw = path_item.get(http_method)
                if raw is None:
                    continue
                key = f"{key_prefix}{http_method.upper()} {path}"
                if "x-didit-sdk" in raw:
                    sdk = validate_sdk(key, raw["x-didit-sdk"], file_name)
                elif key in overlay_ops:
                    sdk = validate_sdk(key, overlay_ops[key], "overlay.json")
                else:
                    missing.append(key)
                    continue
                pointer = f"#/paths/{pointer_escape(path)}/{http_method}"
                operations.append(
                    Operation(spec_name, doc, http_method, path, raw, path_item, sdk, pointer)
                )
    if missing:
        lines = "\n".join(f"  - {key}" for key in sorted(missing))
        raise SpecError(
            "These operations have no SDK naming (x-didit-sdk). Add an entry for each to "
            "openapi/overlay.json (or upstream), see docs/DESIGN.md section 4:\n" + lines
        )

    stale = sorted(set(overlay_ops) - {op.key for op in operations})
    if stale:
        lines = "\n".join(f"  - {key}" for key in stale)
        raise SpecError("openapi/overlay.json names operations the spec no longer has:\n" + lines)

    current = {op.full_name for op in operations if op.sdk.get("server")}
    for full_name, entry in sorted(deprecated_file.get("methods", {}).items()):
        if full_name in current:
            continue
        spec_name = entry.get("spec", "verification")
        doc = docs[spec_name]
        doc.data.setdefault("x-deprecated-operations", {})
        components = doc.data.setdefault("components", {}).setdefault("schemas", {})
        for name, schema in entry.get("components", {}).items():
            components.setdefault(name, copy.deepcopy(schema))
        doc.data["x-deprecated-operations"][full_name] = {
            "operation": copy.deepcopy(entry["operation"]),
            "path_item": {"parameters": copy.deepcopy(entry.get("path_parameters", []))},
        }
        sdk = validate_sdk(full_name, entry["x-didit-sdk"], "deprecated-operations.json")
        pointer = f"#/x-deprecated-operations/{pointer_escape(full_name)}/operation"
        stored = doc.data["x-deprecated-operations"][full_name]
        operations.append(
            Operation(
                spec_name,
                doc,
                entry["http_method"].lower(),
                entry["path"],
                stored["operation"],
                stored["path_item"],
                sdk,
                pointer,
                deprecated=entry,
            )
        )

    seen: dict[str, Operation] = {}
    for op in operations:
        if not op.sdk.get("server"):
            continue
        if op.application_scoped and not op.path.startswith(APPLICATION_PREFIX):
            raise SpecError(f"{op.key}: scope application needs a path under {APPLICATION_PREFIX}")
        other = seen.get(op.full_name)
        if other is not None:
            raise SpecError(f"{op.key} and {other.key} both map to {op.full_name}")
        seen[op.full_name] = op
    return Spec(root, docs, overlay, operations, deprecated_file)
