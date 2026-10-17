"""Generated operation tests (DESIGN 11): one test per server operation.

Each test stubs the HTTP layer, calls the SDK method with synthetic parameters for every
top-level field, and asserts the method, path (ids filled, application scope expanded), query,
headers, body encoding and the parsed response. Response bodies come from the spec's own
examples, read from openapi/ at test time (so no example value is copied into the repository);
an operation without an example gets a synthetic body.
"""

from __future__ import annotations

import datetime as _dt
import json
import re
from typing import Any

from . import names
from .javagen import HEADER, RESOURCES_PKG, Ctx, file_text, java_string, params_type
from .model import JClass, Model, OpModel, Prop, TypeRef
from .spec import Document

ORG_ID = "00000000-0000-4000-8000-0000000000a1"
APP_ID = "00000000-0000-4000-8000-0000000000a2"
DATE_TIME = "2026-11-01T10:15:30Z"
DATE = "2026-11-01"
FILE_BYTES = [37, 80, 68, 70]  # "%PDF"


class Unsupported(Exception):
    """A synthetic value cannot be built for this type."""


def _uuid(seed: str) -> str:
    n = sum(ord(c) * (i + 1) for i, c in enumerate(seed)) % 0xFFFFFFFFFFFF
    return f"00000000-0000-4000-8000-{n:012x}"


def string_value(schema: dict[str, Any], name: str) -> str:
    fmt = schema.get("format") if isinstance(schema, dict) else None
    if fmt == "uuid":
        return _uuid(name)
    if fmt == "email":
        return "user@example.com"
    if fmt in ("uri", "url"):
        return "https://example.com/callback"
    if fmt in ("ip", "ipv4"):
        return "192.0.2.10"
    pattern = schema.get("pattern") if isinstance(schema, dict) else None
    if pattern == "^[A-Z]{3}$":
        return "USD"
    return "value-" + re.sub(r"[^a-z0-9]+", "-", name.lower()).strip("-")


class Synth:
    """Builds a Java expression and the JSON it must produce, for a type."""

    def __init__(self, ctx: Ctx) -> None:
        self.ctx = ctx

    def value(self, t: TypeRef, schema: dict[str, Any], name: str, depth: int) -> tuple[str, Any]:
        k = t.kind
        if k == "string":
            v = string_value(schema, name)
            return java_string(v), v
        if k == "long":
            return "1L", 1
        if k == "double":
            return "1.5", 1.5
        if k == "boolean":
            return "true", True
        if k == "datetime":
            self.ctx.use("java.time.OffsetDateTime")
            return f'OffsetDateTime.parse("{DATE_TIME}")', DATE_TIME
        if k == "date":
            self.ctx.use("java.time.LocalDate")
            return f'LocalDate.parse("{DATE}")', DATE
        if k == "any":
            return java_string("value"), "value"
        if k == "object":
            self.ctx.use("java.util.Map")
            return 'Map.of("key", "value")', {"key": "value"}
        if k == "file":
            self.ctx.use("me.didit.core.FileUpload")
            return (
                'FileUpload.of(new byte[] {37, 80, 68, 70}, "' + name + '.pdf", "application/pdf")',
                {"$file": name + ".pdf"},
            )
        if k == "list":
            assert t.item is not None
            self.ctx.use("java.util.List")
            items = schema.get("items", {}) if isinstance(schema, dict) else {}
            expr, val = self.value(t.item, items if isinstance(items, dict) else {}, name, depth + 1)
            return f"List.of({expr})", [val]
        if k == "map":
            assert t.item is not None
            self.ctx.use("java.util.Map")
            expr, val = self.value(t.item, {}, name, depth + 1)
            return f'Map.of("key", {expr})', {"key": val}
        if k == "class":
            assert t.cls is not None
            return self.cls_value(t.cls, name, depth)
        raise Unsupported(k)

    def cls_value(self, cls: JClass, name: str, depth: int) -> tuple[str, Any]:
        ref = self.ctx.ref(cls)
        if cls.kind == "enum":
            if not cls.values:
                return f'{ref}.of("value")', "value"
            const = enum_constant(cls, 0)
            return f"{ref}.{const}", cls.values[0]
        if cls.kind == "union":
            variant = cls.variants[0]
            expr, val = self.value(variant.type, {}, name, depth + 1)
            return f"{ref}.of{variant.name}({expr})", val
        if cls.kind == "model":
            if depth > 6:
                if any(p.required for p in cls.props):
                    raise Unsupported("required fields too deep")
                return f"{ref}.builder().build()", {}
            calls, value = self.model_calls(cls, depth, all_props=depth <= 1)
            return f"{ref}.builder(){calls}.build()", value
        raise Unsupported(cls.kind)

    def model_calls(self, cls: JClass, depth: int, all_props: bool) -> tuple[str, dict[str, Any]]:
        calls = []
        value: dict[str, Any] = {}
        chosen = [p for p in cls.props if p.required]
        if all_props:
            chosen = list(cls.props)
        else:
            chosen += [p for p in cls.props if not p.required][:2]
            chosen = [p for p in cls.props if p in chosen]
        for prop in chosen:
            expr, val = self.value(prop.type, prop.schema, prop.json_name, depth + 1)
            calls.append(f".{prop.java_name}({expr})")
            value[prop.json_name] = val
        return "".join(calls), value


def enum_constant(cls: JClass, index: int) -> str:
    used: set[str] = {"KNOWN_VALUES"}
    consts: list[str] = []
    for value in cls.values:
        const = names.constant(value)
        base = const
        n = 2
        while const in used:
            const = f"{base}_{n}"
            n += 1
        used.add(const)
        consts.append(const)
    return consts[index]


# -- examples ----------------------------------------------------------------------


def find_example(doc: Document, op: OpModel, status: str) -> Any:
    """The spec example the Java test helper finds for this response, or None."""
    response = op.op.raw.get("responses", {}).get(status)
    if response is None:
        return None
    response, _ = doc.deref(response)
    media = (response.get("content") or {}).get("application/json")
    if not isinstance(media, dict):
        return None
    if "example" in media:
        return media["example"]
    examples = media.get("examples")
    if isinstance(examples, dict) and examples:
        first = next(iter(examples.values()))
        first, _ = doc.deref(first)
        if isinstance(first, dict) and "value" in first:
            return first["value"]
    schema = media.get("schema")
    if isinstance(schema, dict):
        target, _ = doc.deref(schema)
        if isinstance(target, dict) and "example" in target:
            return target["example"]
    return None


def readable(t: TypeRef, value: Any, required: bool, nullable: bool) -> bool:
    """Whether every getter the Java walker calls on ``value`` succeeds."""
    if value is None:
        return not required or nullable
    k = t.kind
    if k == "string":
        return isinstance(value, (str, int, float, bool))
    if k == "long":
        if isinstance(value, bool):
            return False
        if isinstance(value, int):
            return -(2**63) <= value < 2**63
        if isinstance(value, float):
            return value.is_integer()
        if isinstance(value, str):
            return bool(re.fullmatch(r"\s*-?\d+(\.0*)?\s*", value))
        return False
    if k == "double":
        if isinstance(value, bool):
            return False
        if isinstance(value, (int, float)):
            return True
        if isinstance(value, str):
            try:
                float(value)
                return True
            except ValueError:
                return False
        return False
    if k == "boolean":
        return isinstance(value, bool) or value in ("true", "false")
    if k == "datetime":
        if isinstance(value, (int, float)) and not isinstance(value, bool):
            return True
        if not isinstance(value, str):
            return False
        return _datetime_ok(value)
    if k == "date":
        if not isinstance(value, str):
            return False
        try:
            _dt.date.fromisoformat(value[:10])
            return True
        except ValueError:
            return False
    if k == "any":
        return True
    if k == "object":
        return isinstance(value, dict)
    if k == "list":
        assert t.item is not None
        return isinstance(value, list) and all(v is not None and readable(t.item, v, True, False) for v in value)
    if k == "map":
        assert t.item is not None
        return isinstance(value, dict) and all(v is None or readable(t.item, v, True, False) for v in value.values())
    if k == "class":
        assert t.cls is not None
        return cls_readable(t.cls, value)
    return True


def _datetime_ok(value: str) -> bool:
    for candidate in (value, value.replace("Z", "+00:00")):
        try:
            _dt.datetime.fromisoformat(candidate)
            return True
        except ValueError:
            continue
    try:
        _dt.date.fromisoformat(value)
        return True
    except ValueError:
        return False


def detect(variant_detect: tuple[Any, ...], value: Any, status: int | None = None) -> bool:
    kind = variant_detect[0]
    if kind == "discriminator":
        return isinstance(value, dict) and value.get(variant_detect[1]) == variant_detect[2]
    if kind == "shape":
        if not isinstance(value, dict):
            return False
        required, unique = variant_detect[1], variant_detect[2]
        if required:
            return all(r in value for r in required)
        if unique:
            return any(u in value for u in unique)
        return True
    if kind == "json_type":
        jt = variant_detect[1]
        if jt == "string":
            return isinstance(value, str)
        if jt == "integer":
            return isinstance(value, int) and not isinstance(value, bool)
        if jt == "number":
            return isinstance(value, (int, float)) and not isinstance(value, bool)
        if jt == "boolean":
            return isinstance(value, bool)
        if jt == "array":
            return isinstance(value, list)
        return value is not None
    if kind == "status":
        return status == variant_detect[1]
    return False


def cls_readable(cls: JClass, value: Any, status: int | None = None) -> bool:
    if cls.kind == "enum":
        return isinstance(value, str)
    if cls.kind == "model":
        if not isinstance(value, dict):
            return False
        return all(
            readable(p.type, value.get(p.json_name), p.required and not p.nullable, p.nullable or not p.required)
            and (not (p.required and not p.nullable) or value.get(p.json_name) is not None)
            for p in cls.props
            if p.type.kind != "file"
        )
    if cls.kind in ("union", "status"):
        if cls.kind == "union" and cls.props:
            if not isinstance(value, dict):
                return False
            for p in cls.props:
                v = value.get(p.json_name)
                if p.required and not p.nullable and v is None:
                    return False
                if v is not None and not readable(p.type, v, True, False):
                    return False
        for variant in cls.variants:
            if detect(variant.detect, value, status) and not readable(variant.type, value, True, False):
                return False
        return True
    return True


def synthetic_response(t: TypeRef | None, depth: int = 0) -> Any:
    """A minimal JSON value the type reads without errors."""
    if t is None:
        return {}
    k = t.kind
    if k == "string":
        return "value"
    if k == "long":
        return 1
    if k == "double":
        return 1.5
    if k == "boolean":
        return True
    if k == "datetime":
        return DATE_TIME
    if k == "date":
        return DATE
    if k in ("any", "object"):
        return {}
    if k == "list":
        return []
    if k == "map":
        return {}
    if k == "class":
        assert t.cls is not None
        cls = t.cls
        if cls.kind == "enum":
            return cls.values[0] if cls.values else "value"
        if cls.kind == "union":
            v = synthetic_response(cls.variants[0].type, depth + 1)
            if isinstance(v, dict):
                for p in cls.props:
                    if p.required and p.json_name not in v:
                        v[p.json_name] = synthetic_response(p.type, depth + 1)
                if cls.variants[0].detect[0] == "discriminator":
                    v[cls.variants[0].detect[1]] = cls.variants[0].detect[2]
            return v
        if cls.kind == "model":
            if depth > 8:
                return {}
            return {
                p.json_name: synthetic_response(p.type, depth + 1)
                for p in cls.props
                if p.required and p.type.kind != "file"
            }
    return {}


# -- emission ----------------------------------------------------------------------


def expected_query_value(val: Any) -> list[str]:
    if isinstance(val, bool):
        return ["true" if val else "false"]
    if isinstance(val, list):
        out: list[str] = []
        for v in val:
            out += expected_query_value(v)
        return out
    if isinstance(val, (dict,)):
        return [json.dumps(val, separators=(",", ":"))]
    return [str(val)]


def multipart_expectation(body: dict[str, Any]) -> tuple[dict[str, list[str]], dict[str, list[str]]]:
    fields: dict[str, list[str]] = {}
    files: dict[str, list[str]] = {}

    def add(name: str, val: Any) -> None:
        if val is None:
            return
        if isinstance(val, dict) and "$file" in val:
            files.setdefault(name, []).append(val["$file"])
            return
        if isinstance(val, list):
            if all(isinstance(v, dict) and "$file" in v for v in val):
                for v in val:
                    add(name, v)
                return
            if all(not isinstance(v, (dict, list)) for v in val):
                for v in val:
                    add(name, v)
                return
            fields.setdefault(name, []).append(json.dumps(val, separators=(",", ":"), ensure_ascii=False))
            return
        if isinstance(val, dict):
            fields.setdefault(name, []).append(json.dumps(val, separators=(",", ":"), ensure_ascii=False))
            return
        if isinstance(val, bool):
            fields.setdefault(name, []).append("true" if val else "false")
            return
        fields.setdefault(name, []).append(str(val))

    for name, val in body.items():
        add(name, val)
    return fields, files


def java_map_of_lists(d: dict[str, list[str]], ctx: Ctx) -> str:
    ctx.use("java.util.Map")
    ctx.use("java.util.List")
    if not d:
        return "Map.of()"
    entries = ", ".join(
        f"Map.entry({java_string(k)}, List.of({', '.join(java_string(v) for v in vs)}))" for k, vs in d.items()
    )
    return f"Map.ofEntries({entries})"


def emit_test(om: OpModel, ctx: Ctx) -> str:
    op = om.op
    synth = Synth(ctx)
    resource_chain = "".join(f".{names.camel(seg)}()" for seg in op.resource.split("."))
    lines: list[str] = []
    args: list[str] = []
    path = op.path
    for segment in re.findall(r"\{([^}]+)\}", op.path):
        if op.application_scoped and segment == "organization_id":
            path = path.replace("{organization_id}", ORG_ID)
            continue
        if op.application_scoped and segment == "application_id":
            path = path.replace("{application_id}", APP_ID)
            continue
        value = _uuid(op.full_name + segment)
        path = path.replace("{" + segment + "}", value)
        args.append(java_string(value))

    query: dict[str, list[str]] = {}
    headers: dict[str, str] = {}
    body: dict[str, Any] | None = None
    ptype = params_type(om, ctx)
    if ptype is not None:
        cls = om.params_class or (om.body_type.cls if om.body_type is not None else None)
        assert cls is not None
        calls, value = synth.model_calls(cls, 1, all_props=True)
        lines.append(f"    {ptype} params = {ptype}.builder(){calls}.build();")
        args.append("params")
        props = {p.json_name: p for p in cls.props}
        body = {}
        for key, val in value.items():
            prop: Prop = props[key]
            if om.params_class is not None and prop.location == "query":
                query[prop.wire] = expected_query_value(val)
            elif om.params_class is not None and prop.location == "header":
                headers[prop.wire] = expected_query_value(val)[0]
            else:
                body[key] = val
    if om.idempotency:
        ctx.use("me.didit.core.RequestOptions")
        lines.append('    RequestOptions options = RequestOptions.builder().idempotencyKey("idem-1").build();')
        args.append("options")
        headers["Idempotency-Key"] = "idem-1"

    # Response stub
    r = om.response
    status = r.statuses[0] if r.statuses else "200"
    example = None
    if r.kind in ("json", "page", "json_node"):
        example = find_example(op.doc, om, status)
    if r.kind == "none":
        lines.append(f"    transport.respond({status}, \"\");")
    elif r.kind == "binary":
        lines.append(f"    transport.respondBinary({status}, {java_string(r.content_type)}, new byte[] {{37, 80, 68, 70}});")
    else:
        if example is not None:
            lines.append(
                f"    JsonNode example = SpecExamples.response({java_string(op.doc.name)}, "
                f"{java_string(op.http_method.upper())}, {java_string(op.path)}, {java_string(status)});"
            )
        else:
            synthetic = synthetic_response(r.type) if r.kind in ("json", "page") else {"ok": True}
            if r.kind == "page":
                synthetic = {"count": 0, "next": None, "previous": None, "results": []}
            text = json.dumps(synthetic, separators=(",", ":"), sort_keys=True)
            lines.append(f"    JsonNode example = TestJson.parse({java_string(text)});")
        ctx.use("com.fasterxml.jackson.databind.JsonNode")
        lines.append(f"    transport.respondJson({status}, example);")

    call = f"client{resource_chain}.{om.java_method}({', '.join(args)})"
    if r.kind == "none":
        lines.append(f"    {call};")
    else:
        lines.append(f"    var result = {call};")

    lines.append("    RecordedRequest request = transport.only();")
    lines.append(f"    assertEquals({java_string(op.http_method.upper())}, request.method());")
    lines.append(f"    assertEquals({java_string(path)}, request.path());")
    lines.append(f"    assertEquals({java_map_of_lists(query, ctx)}, request.query());")
    auth = op.sdk.get("auth")
    if auth == "api_key":
        lines.append('    assertEquals("test-api-key", request.header("x-api-key"));')
        lines.append('    assertEquals("https://verification.didit.me", request.origin());')
    elif auth == "account_token":
        lines.append('    assertEquals("Bearer test-account-token", request.header("Authorization"));')
        lines.append('    assertEquals(null, request.header("x-api-key"));')
        lines.append('    assertEquals("https://apx.didit.me", request.origin());')
    else:
        lines.append('    assertEquals(null, request.header("x-api-key"));')
        lines.append('    assertEquals(null, request.header("Authorization"));')
    for name, value in headers.items():
        lines.append(f"    assertEquals({java_string(value)}, request.header({java_string(name)}));")
    if r.kind == "binary":
        lines.append(f"    assertEquals({java_string(r.content_type + ', application/json')}, request.header(\"Accept\"));")

    if body is not None and (om.body_encoding is not None):
        has_files = any(isinstance(v, dict) and "$file" in v or isinstance(v, list) and v and isinstance(v[0], dict) and "$file" in v[0] for v in body.values())
        if om.body_encoding == "multipart" or (om.body_encoding == "json_or_multipart" and has_files):
            fields, files = multipart_expectation(body)
            lines.append(f"    assertEquals({java_map_of_lists(fields, ctx)}, request.multipartFields());")
            lines.append(f"    assertEquals({java_map_of_lists(files, ctx)}, request.multipartFiles());")
        else:
            text = json.dumps(body, separators=(",", ":"), ensure_ascii=False)
            lines.append(f"    TestJson.assertJsonEquals({java_string(text)}, request.body());")
    else:
        lines.append("    assertEquals(0, request.body().length);")

    # Response checks
    if r.kind == "binary":
        lines.append("    assertArrayEquals(new byte[] {37, 80, 68, 70}, result.bytes());")
    elif r.kind == "page":
        lines.append("    TestJson.assertJsonEquals(example, result._json());")
        lines.append('    assertEquals(example.path("results").size(), result.results().size());')
        if _response_readable(om, example, status):
            lines.append("    TestModels.readAll(result.results());")
    elif r.kind == "json":
        lines.append("    TestJson.assertJsonEquals(example, Json.toNode(result));")
        if r.type is not None and r.type.kind == "class" and r.type.cls is not None and r.type.cls.kind == "status":
            lines.append(f"    assertEquals({status}, result.statusCode());")
        if _response_readable(om, example, status):
            lines.append("    TestModels.readAll(result);")
    elif r.kind == "json_node":
        lines.append("    TestJson.assertJsonEquals(example, result);")

    body_text = "\n".join(lines)
    return f"  @Test\n  void {om.java_method}() {{\n{body_text}\n  }}\n"


def _response_readable(om: OpModel, example: Any, status: str) -> bool:
    r = om.response
    if example is None:
        return True
    if r.kind == "page":
        assert r.type is not None
        items = example.get("results") if isinstance(example, dict) else None
        return isinstance(items, list) and all(readable(r.type, v, True, False) for v in items)
    if r.type is None:
        return True
    if r.type.kind == "class" and r.type.cls is not None and r.type.cls.kind == "status":
        return cls_readable(r.type.cls, example, int(status))
    return readable(r.type, example, True, False)


def emit_tests(model: Model) -> dict[str, str]:
    files: dict[str, str] = {}
    for resource, ops in model.resources.items():
        if not ops:
            continue
        ctx = Ctx(RESOURCES_PKG, None)
        for imp in (
            "org.junit.jupiter.api.Test",
            "me.didit.Didit",
            "me.didit.testing.MockTransport",
            "me.didit.testing.RecordedRequest",
            "me.didit.testing.SpecExamples",
            "me.didit.testing.TestJson",
            "me.didit.testing.TestModels",
            "me.didit.core.Json",
        ):
            ctx.use(imp)
        tests = [emit_test(om, ctx) for om in ops]
        cls_name = names.resource_class(resource) + "Test"
        statics = (
            "import static org.junit.jupiter.api.Assertions.assertArrayEquals;\n"
            "import static org.junit.jupiter.api.Assertions.assertEquals;\n\n"
        )
        body = (
            f"/** One test per operation of {{@code {resource}}}, against a stubbed HTTP layer. */\n"
            '@SuppressWarnings({"deprecation", "unused"})\n'
            f"final class {cls_name} {{\n"
            "  private final MockTransport transport = new MockTransport();\n"
            "  private final Didit client = transport.client();\n\n" + "\n".join(tests) + "}\n"
        )
        text = file_text(ctx, body)
        text = text.replace(f"package {RESOURCES_PKG};\n\n", f"package {RESOURCES_PKG};\n\n{statics}", 1)
        files[f"{cls_name}.java"] = text
    _ = HEADER
    return files
