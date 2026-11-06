#!/usr/bin/env python3
"""Syncs openapi/ with the published API documents (DESIGN 3.1).

Usage:
    python3 codegen/spec_sync.py [--from-dir DIR] [--summary FILE] [--today YYYY-MM-DD]

It downloads https://docs.didit.me/openapi-25.json and openapi-auth.json (or reads them from
--from-dir), and when either changed it:
  - copies them byte for byte into openapi/ and updates openapi/SOURCE.json;
  - drops overlay.json entries that upstream now carries (identically or not: upstream wins);
  - records server operations that disappeared, or whose SDK name changed, in
    openapi/deprecated-operations.json so the SDK keeps them, deprecated, for one minor version;
  - writes a Markdown summary of added, removed and changed operations and schemas.

It prints changed=true|false and breaking=true|false, also to $GITHUB_OUTPUT when set.
The generator and the tests run afterwards in the workflow; this script never edits code.
"""

from __future__ import annotations

import argparse
import copy
import datetime as dt
import hashlib
import json
import os
import re
import sys
import urllib.request
from pathlib import Path
from typing import Any

ROOT = Path(__file__).resolve().parent.parent
UPSTREAM = {
    "verification.json": "https://docs.didit.me/openapi-25.json",
    "account.json": "https://docs.didit.me/openapi-auth.json",
}
PREFIX = {"verification.json": "", "account.json": "account "}
HTTP_METHODS = ("get", "post", "put", "patch", "delete")


def fetch(url: str) -> bytes:
    request = urllib.request.Request(url, headers={"User-Agent": "didit-sdk-java-spec-sync"})
    with urllib.request.urlopen(request, timeout=60) as response:  # noqa: S310 (fixed https URLs)
        return response.read()


def operations(doc: dict[str, Any], prefix: str) -> dict[str, dict[str, Any]]:
    out = {}
    for path, item in (doc.get("paths") or {}).items():
        for method in HTTP_METHODS:
            if method in item:
                out[f"{prefix}{method.upper()} {path}"] = item[method]
    return out


def naming(key: str, op: dict[str, Any], overlay: dict[str, Any]) -> dict[str, Any] | None:
    return op.get("x-didit-sdk") or overlay.get(key)


def refs(node: Any) -> set[str]:
    found: set[str] = set()
    if isinstance(node, dict):
        ref = node.get("$ref")
        if isinstance(ref, str) and ref.startswith("#/components/schemas/"):
            found.add(ref.rsplit("/", 1)[1])
        for value in node.values():
            found |= refs(value)
    elif isinstance(node, list):
        for value in node:
            found |= refs(value)
    return found


def component_closure(doc: dict[str, Any], start: set[str]) -> dict[str, Any]:
    schemas = (doc.get("components") or {}).get("schemas") or {}
    out: dict[str, Any] = {}
    todo = sorted(start)
    while todo:
        name = todo.pop()
        if name in out or name not in schemas:
            continue
        out[name] = schemas[name]
        todo.extend(sorted(refs(schemas[name]) - set(out)))
    return out


def next_minor(version: str) -> str:
    match = re.match(r"(\d+)\.(\d+)\.(\d+)", version)
    if not match:
        return "the next minor version"
    major, minor = int(match.group(1)), int(match.group(2))
    return f"{major}.{minor + 1}.0"


def current_version(root: Path) -> str:
    props = root / "gradle.properties"
    if props.exists():
        for line in props.read_text(encoding="utf-8").splitlines():
            if line.startswith("version="):
                return line.split("=", 1)[1].strip()
    return "0.0.0"


def describe(key: str, op: dict[str, Any], overlay: dict[str, Any]) -> str:
    sdk = naming(key, op, overlay)
    name = f"`{sdk['resource']}.{sdk['method']}`" if sdk else "(no SDK name)"
    return f"{name} `{key}`"


def sync(
    root: Path,
    new_files: dict[str, bytes],
    today: str,
    now: str,
) -> tuple[bool, bool, str]:
    """Applies the new upstream files to ``root``. Returns (changed, breaking, summary)."""
    openapi = root / "openapi"
    old_files = {name: (openapi / name).read_bytes() for name in UPSTREAM}
    changed_files = [name for name in UPSTREAM if new_files[name] != old_files[name]]
    if not changed_files:
        return False, False, "The upstream API documents are unchanged.\n"

    overlay_path = openapi / "overlay.json"
    overlay_doc = json.loads(overlay_path.read_text(encoding="utf-8"))
    overlay: dict[str, Any] = overlay_doc.get("operations", {})
    deprecated_path = openapi / "deprecated-operations.json"
    deprecated_doc = (
        json.loads(deprecated_path.read_text(encoding="utf-8"))
        if deprecated_path.exists()
        else {
            "description": "Server operations that left the API, kept deprecated for one minor version (DESIGN 3.1). Written by codegen/spec_sync.py.",
            "version": 1,
            "methods": {},
        }
    )
    remove_in = next_minor(current_version(root))

    added: list[str] = []
    removed: list[str] = []
    changed_ops: list[str] = []
    renamed: list[str] = []
    dropped_overlay: list[str] = []
    schema_added: list[str] = []
    schema_removed: list[str] = []
    schema_changed: list[str] = []
    needs_naming: list[str] = []
    breaking = False

    new_names: set[str] = set()
    for name in UPSTREAM:
        new_doc = json.loads(new_files[name])
        for key, op in operations(new_doc, PREFIX[name]).items():
            sdk = naming(key, op, overlay)
            if sdk and sdk.get("server"):
                new_names.add(f"{sdk['resource']}.{sdk['method']}")

    for name in UPSTREAM:
        old_doc = json.loads(old_files[name])
        new_doc = json.loads(new_files[name])
        old_ops = operations(old_doc, PREFIX[name])
        new_ops = operations(new_doc, PREFIX[name])
        for key in sorted(set(new_ops) - set(old_ops)):
            added.append(describe(key, new_ops[key], overlay))
            if not naming(key, new_ops[key], overlay):
                needs_naming.append(key)
        for key in sorted(set(old_ops) & set(new_ops)):
            old_op, new_op = old_ops[key], new_ops[key]
            upstream_sdk = new_op.get("x-didit-sdk")
            if upstream_sdk is not None and key in overlay:
                if overlay[key] != upstream_sdk:
                    renamed.append(
                        f"`{key}`: `{overlay[key]['resource']}.{overlay[key]['method']}` is now "
                        f"`{upstream_sdk['resource']}.{upstream_sdk['method']}`"
                    )
                    if overlay[key].get("server") and (
                        f"{overlay[key]['resource']}.{overlay[key]['method']}" not in new_names
                    ):
                        record_deprecation(
                            deprecated_doc, old_doc, name, key, old_op, overlay[key], "renamed",
                            f"{upstream_sdk['resource']}.{upstream_sdk['method']}", remove_in,
                        )
                    breaking = True
                del overlay[key]
                dropped_overlay.append(key)
            if json.dumps(old_op, sort_keys=True) != json.dumps(new_op, sort_keys=True):
                changed_ops.append(describe(key, new_op, overlay))
        for key in sorted(set(old_ops) - set(new_ops)):
            sdk = naming(key, old_ops[key], overlay)
            removed.append(describe(key, old_ops[key], overlay))
            if sdk and sdk.get("server") and f"{sdk['resource']}.{sdk['method']}" not in new_names:
                record_deprecation(deprecated_doc, old_doc, name, key, old_ops[key], sdk, "removed", None, remove_in)
                breaking = True
            overlay.pop(key, None)

        old_schemas = (old_doc.get("components") or {}).get("schemas") or {}
        new_schemas = (new_doc.get("components") or {}).get("schemas") or {}
        schema_added += [f"`{s}` ({name})" for s in sorted(set(new_schemas) - set(old_schemas))]
        for s in sorted(set(old_schemas) - set(new_schemas)):
            schema_removed.append(f"`{s}` ({name})")
            breaking = True
        for s in sorted(set(old_schemas) & set(new_schemas)):
            if json.dumps(old_schemas[s], sort_keys=True) != json.dumps(new_schemas[s], sort_keys=True):
                schema_changed.append(f"`{s}` ({name})")
                old_props = set((old_schemas[s].get("properties") or {}))
                new_props = set((new_schemas[s].get("properties") or {}))
                if old_props - new_props:
                    breaking = True

    # Write the files.
    for name in changed_files:
        (openapi / name).write_bytes(new_files[name])
    source_path = openapi / "SOURCE.json"
    source = json.loads(source_path.read_text(encoding="utf-8"))
    for name in UPSTREAM:
        entry = source.setdefault(name, {"url": UPSTREAM[name]})
        entry["sha256"] = hashlib.sha256(new_files[name]).hexdigest()
        if name in changed_files:
            entry["fetched_at"] = now
    source_path.write_text(json.dumps(source, indent=1, ensure_ascii=False) + "\n", encoding="utf-8")
    overlay_doc["operations"] = dict(sorted(overlay.items()))
    overlay_path.write_text(json.dumps(overlay_doc, indent=1, ensure_ascii=False) + "\n", encoding="utf-8")
    if deprecated_doc["methods"] or deprecated_path.exists():
        deprecated_doc["methods"] = dict(sorted(deprecated_doc["methods"].items()))
        deprecated_path.write_text(json.dumps(deprecated_doc, indent=1, ensure_ascii=False) + "\n", encoding="utf-8")

    lines = [f"Automated sync of the API documents on {today}: {', '.join(changed_files)}.", ""]

    def section(title: str, items: list[str]) -> None:
        if items:
            lines.append(f"### {title} ({len(items)})")
            lines.extend(f"- {item}" for item in items[:200])
            if len(items) > 200:
                lines.append(f"- ... and {len(items) - 200} more")
            lines.append("")

    if breaking:
        lines += [
            "**Breaking:** operations or schemas were removed or renamed. Removed SDK methods stay, "
            f"deprecated, until version {remove_in} (openapi/deprecated-operations.json).",
            "",
        ]
    if needs_naming:
        lines += [
            "**Action needed:** these new operations have no SDK name; add them to openapi/overlay.json "
            "(or upstream) or the generator fails:",
            "",
        ]
        lines += [f"- `{k}`" for k in needs_naming] + [""]
    section("Added operations", added)
    section("Removed operations", removed)
    section("Renamed operations", renamed)
    section("Changed operations", changed_ops)
    section("Added schemas", schema_added)
    section("Removed schemas", schema_removed)
    section("Changed schemas", schema_changed)
    section("Overlay entries dropped because upstream carries x-didit-sdk", [f"`{k}`" for k in dropped_overlay])
    return True, breaking, "\n".join(lines).rstrip() + "\n"


def record_deprecation(
    doc: dict[str, Any],
    old_doc: dict[str, Any],
    file_name: str,
    key: str,
    op: dict[str, Any],
    sdk: dict[str, Any],
    reason: str,
    replacement: str | None,
    remove_in: str,
) -> None:
    method, path = key.split(" ", 1) if not key.startswith("account ") else key[len("account ") :].split(" ", 1)
    path_item = (old_doc.get("paths") or {}).get(path, {})
    entry = {
        "reason": reason,
        "replacement": replacement,
        "remove_in": remove_in,
        "spec": "verification" if file_name == "verification.json" else "account",
        "http_method": method,
        "path": path,
        "x-didit-sdk": copy.deepcopy(sdk),
        "operation": copy.deepcopy(op),
        "path_parameters": copy.deepcopy(path_item.get("parameters", [])),
        "components": component_closure(old_doc, refs(op) | refs(path_item.get("parameters", []))),
    }
    doc.setdefault("methods", {})[f"{sdk['resource']}.{sdk['method']}"] = entry


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--from-dir", type=Path, help="read the upstream files from this directory")
    parser.add_argument("--summary", type=Path, help="write the Markdown summary here")
    parser.add_argument("--today", default=dt.date.today().isoformat())
    parser.add_argument("--root", type=Path, default=ROOT)
    args = parser.parse_args(argv)
    new_files: dict[str, bytes] = {}
    for name, url in UPSTREAM.items():
        new_files[name] = (args.from_dir / name).read_bytes() if args.from_dir else fetch(url)
        json.loads(new_files[name])  # refuse anything that is not JSON
    now = dt.datetime.now(dt.timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z")
    changed, breaking, summary = sync(args.root.resolve(), new_files, args.today, now)
    if args.summary:
        args.summary.write_text(summary, encoding="utf-8")
    outputs = f"changed={'true' if changed else 'false'}\nbreaking={'true' if breaking else 'false'}\n"
    sys.stdout.write(outputs)
    github_output = os.environ.get("GITHUB_OUTPUT")
    if github_output:
        with open(github_output, "a", encoding="utf-8") as handle:
            handle.write(outputs)
    return 0


if __name__ == "__main__":
    sys.exit(main())
