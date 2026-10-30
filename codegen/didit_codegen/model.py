"""Turns OpenAPI schemas and operations into a Java type model (DESIGN 5 and 5.1)."""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any

from . import names
from .spec import Document, Operation, Spec, SpecError, pointer_escape

SKIPPED_HEADERS = {"authorization", "content-type", "idempotency-key", "accept", "x-api-key"}


@dataclass(eq=False)
class TypeRef:
    """A Java type: a scalar, a container or a generated class."""

    kind: str  # string long double boolean datetime date any object file list map class
    item: "TypeRef | None" = None
    cls: "JClass | None" = None

    def __repr__(self) -> str:  # pragma: no cover - debugging aid
        if self.kind == "class" and self.cls is not None:
            return f"TypeRef({self.cls.qualified})"
        if self.item is not None:
            return f"TypeRef({self.kind}<{self.item!r}>)"
        return f"TypeRef({self.kind})"


@dataclass(eq=False)
class Prop:
    json_name: str
    java_name: str
    type: TypeRef
    required: bool
    nullable: bool
    description: str
    deprecated: bool
    schema: dict[str, Any]
    location: str = "body"  # body query header
    wire_name: str = ""  # query or header name when it differs from json_name

    @property
    def wire(self) -> str:
        return self.wire_name or self.json_name


@dataclass(eq=False)
class Variant:
    name: str
    type: TypeRef
    detect: tuple[Any, ...]
    description: str = ""


@dataclass(eq=False)
class JClass:
    kind: str  # model enum union status
    name: str
    outer: "JClass | None"
    description: str = ""
    deprecated: bool = False
    props: list[Prop] = field(default_factory=list)
    values: list[str] = field(default_factory=list)
    variants: list[Variant] = field(default_factory=list)
    nested: list["JClass"] = field(default_factory=list)
    origin: str = "inline"  # component params response inline
    pointer: str = ""
    doc_name: str = ""
    complete: bool = False

    @property
    def qualified(self) -> str:
        """Name relative to the package: ``Outer.Inner``."""
        return self.name if self.outer is None else f"{self.outer.qualified}.{self.name}"

    @property
    def top(self) -> "JClass":
        return self if self.outer is None else self.outer.top

    def nested_names(self) -> set[str]:
        out = {self.name}
        for cls in self.nested:
            out |= cls.nested_names()
        return out

    def has_files(self) -> bool:
        return any(contains_file(p.type) for p in self.props)


def contains_file(t: TypeRef) -> bool:
    return t.kind == "file" or (t.item is not None and contains_file(t.item))


@dataclass(eq=False)
class PathParam:
    json_name: str
    java_name: str
    description: str


@dataclass(eq=False)
class ResponseModel:
    kind: str  # json none binary page json_node status
    type: TypeRef | None = None
    page_style: str = ""  # next | next_offset
    statuses: list[str] = field(default_factory=list)
    content_type: str = ""


@dataclass(eq=False)
class OpModel:
    op: Operation
    java_method: str
    path_params: list[PathParam]
    params_class: JClass | None
    body_type: TypeRef | None
    body_required: bool
    body_encoding: str | None  # json multipart json_or_multipart
    query_fields: list[tuple[str, str]]  # (query name, params field)
    header_fields: list[tuple[str, str]]
    response: ResponseModel
    idempotency: bool
    summary: str
    description: str

    @property
    def params_required(self) -> bool:
        if self.params_class is not None:
            return any(p.required for p in self.params_class.props)
        return self.body_type is not None and self.body_required


@dataclass
class Model:
    spec: Spec
    classes: list[JClass]  # top-level classes, in emission order
    operations: list[OpModel]
    resources: dict[str, list[OpModel]]


def is_nullable(schema: dict[str, Any]) -> bool:
    if schema.get("nullable") is True:
        return True
    t = schema.get("type")
    if isinstance(t, list) and "null" in t:
        return True
    for key in ("oneOf", "anyOf"):
        for part in schema.get(key, []) or []:
            if isinstance(part, dict) and (part.get("type") == "null" or part.get("enum") == [None]):
                return True
    return False


def description_of(schema: dict[str, Any]) -> str:
    text = schema.get("description") or schema.get("title") or ""
    return str(text).strip()


class Builder:
    """Walks the spec and builds the Java type model."""

    def __init__(self, spec: Spec) -> None:
        self.spec = spec
        self.by_pointer: dict[tuple[str, str], TypeRef] = {}
        self.top_names: dict[str, JClass] = {}
        self.top_classes: list[JClass] = []
        self.component_names: dict[tuple[str, str], str] = {}
        self._plan_component_names()

    # -- naming -----------------------------------------------------------------

    def _plan_component_names(self) -> None:
        taken: set[str] = set()
        for spec_name in ("verification", "account"):
            doc = self.spec.docs[spec_name]
            for comp in sorted(doc.data.get("components", {}).get("schemas", {})):
                java = names.pascal(comp)
                if java in taken or java in names.RESERVED_TYPE_NAMES:
                    java = ("Account" if spec_name == "account" else "Api") + java
                if java in taken:
                    raise SpecError(f"component {comp!r} cannot get a unique Java name")
                taken.add(java)
                self.component_names[(spec_name, comp)] = java

    def _new_class(
        self, kind: str, name: str, outer: JClass | None, doc: Document, pointer: str, origin: str
    ) -> JClass:
        if outer is None:
            if name in self.top_names or name in names.RESERVED_TYPE_NAMES:
                raise SpecError(f"two generated types are both named {name} ({pointer})")
            cls = JClass(kind, name, None, origin=origin, pointer=pointer, doc_name=doc.name)
            self.top_names[name] = cls
            self.top_classes.append(cls)
            return cls
        used = {c.name for c in outer.nested}
        enclosing = set()
        o: JClass | None = outer
        while o is not None:
            enclosing.add(o.name)
            o = o.outer
        base = name
        candidate = base
        counter = 2
        while candidate in used or candidate in enclosing or candidate in names.RESERVED_TYPE_NAMES:
            candidate = f"{base}Value" if counter == 2 else f"{base}{counter}"
            counter += 1
        cls = JClass(kind, candidate, outer, origin=origin, pointer=pointer, doc_name=doc.name)
        outer.nested.append(cls)
        return cls

    # -- schemas ----------------------------------------------------------------

    def component(self, doc: Document, comp: str) -> TypeRef:
        spec_name = "account" if doc.name == "account.json" else "verification"
        pointer = f"#/components/schemas/{pointer_escape(comp)}"
        key = (doc.name, pointer)
        if key in self.by_pointer:
            return self.by_pointer[key]
        schema = doc.data["components"]["schemas"][comp]
        java = self.component_names[(spec_name, comp)]
        return self.schema_type(doc, schema, pointer, None, java, origin="component")

    def ref_type(self, doc: Document, ref: str, outer: JClass | None, hint: str) -> TypeRef:
        prefix = "#/components/schemas/"
        if ref.startswith(prefix) and "/" not in ref[len(prefix):]:
            comp = ref[len(prefix):].replace("~1", "/").replace("~0", "~")
            return self.component(doc, comp)
        key = (doc.name, ref)
        if key in self.by_pointer:
            return self.by_pointer[key]
        target = doc.resolve_pointer(ref)
        return self.schema_type(doc, target, ref, outer, hint)

    def schema_type(
        self,
        doc: Document,
        schema: Any,
        pointer: str,
        outer: JClass | None,
        hint: str,
        origin: str = "inline",
        context: str = "json",
    ) -> TypeRef:
        """Java type for ``schema`` found at ``pointer``; ``hint`` names new classes."""
        if not isinstance(schema, dict):
            return TypeRef("any")
        key = (doc.name, pointer)
        if key in self.by_pointer:
            return self.by_pointer[key]
        if "$ref" in schema:
            return self.ref_type(doc, schema["$ref"], outer, hint)

        all_of = schema.get("allOf")
        if all_of:
            structural = [k for k in ("properties", "oneOf", "anyOf", "items", "enum") if k in schema]
            if len(all_of) == 1 and not structural:
                part = all_of[0]
                return self.schema_type(doc, part, f"{pointer}/allOf/0", outer, hint, origin, context)
            return self._model(doc, schema, pointer, outer, hint, origin)

        variants = schema.get("oneOf") or schema.get("anyOf")
        if variants:
            word = "oneOf" if "oneOf" in schema else "anyOf"
            non_null = [
                (i, v)
                for i, v in enumerate(variants)
                if not (isinstance(v, dict) and (v.get("type") == "null" or v.get("enum") == [None]))
            ]
            if len(non_null) == 1:
                i, v = non_null[0]
                return self.schema_type(doc, v, f"{pointer}/{word}/{i}", outer, hint, origin, context)
            return self._union(doc, schema, pointer, outer, hint, origin, word, context)

        if "enum" in schema:
            values = [v for v in schema["enum"] if v is not None]
            if values and all(isinstance(v, str) for v in values) and schema.get("type") in (None, "string"):
                return self._enum(doc, schema, pointer, outer, hint, origin)
            if all(isinstance(v, bool) for v in values):
                return TypeRef("boolean")
            if all(isinstance(v, int) and not isinstance(v, bool) for v in values):
                return TypeRef("long")
            return TypeRef("any")

        t = schema.get("type")
        if isinstance(t, list):
            non_null_types = [x for x in t if x != "null"]
            t = non_null_types[0] if len(non_null_types) == 1 else None
        if t == "object" or (t is None and "properties" in schema):
            if schema.get("properties"):
                return self._model(doc, schema, pointer, outer, hint, origin)
            extra = schema.get("additionalProperties")
            if isinstance(extra, dict) and extra:
                item = self.schema_type(
                    doc, extra, f"{pointer}/additionalProperties", outer, hint + "Value", context=context
                )
                return self._remember(key, TypeRef("map", item=item))
            return self._remember(key, TypeRef("object"))
        if t == "array":
            item_schema = schema.get("items", {})
            item = self.schema_type(doc, item_schema, f"{pointer}/items", outer, hint, context=context)
            return self._remember(key, TypeRef("list", item=item))
        if t == "string":
            fmt = schema.get("format")
            if fmt == "date-time":
                return TypeRef("datetime")
            if fmt == "date":
                return TypeRef("date")
            if fmt == "binary":
                return TypeRef("file") if context == "multipart" else TypeRef("string")
            return TypeRef("string")
        if t == "integer":
            return TypeRef("long")
        if t == "number":
            return TypeRef("double")
        if t == "boolean":
            return TypeRef("boolean")
        return TypeRef("any")

    def _remember(self, key: tuple[str, str], t: TypeRef) -> TypeRef:
        self.by_pointer[key] = t
        return t

    def _enum(
        self, doc: Document, schema: dict[str, Any], pointer: str, outer: JClass | None, hint: str, origin: str
    ) -> TypeRef:
        cls = self._new_class("enum", hint, outer, doc, pointer, origin)
        t = self._remember((doc.name, pointer), TypeRef("class", cls=cls))
        seen: list[str] = []
        for v in schema["enum"]:
            if isinstance(v, str) and v not in seen:
                seen.append(v)
        cls.values = seen
        cls.description = description_of(schema)
        cls.deprecated = bool(schema.get("deprecated"))
        cls.complete = True
        return t

    def _collect_object(
        self, doc: Document, schema: dict[str, Any], pointer: str, depth: int = 0
    ) -> tuple[list[tuple[str, dict[str, Any], str, Document]], set[str], list[TypeRef]]:
        """Flattens allOf. Returns (name, schema, pointer, doc) per property, required names, $ref parts."""
        if depth > 20:
            raise SpecError(f"allOf nesting too deep at {pointer}")
        props: list[tuple[str, dict[str, Any], str, Document]] = []
        required: set[str] = set(schema.get("required", []) or [])
        refs: list[TypeRef] = []
        for i, part in enumerate(schema.get("allOf", []) or []):
            part_pointer = f"{pointer}/allOf/{i}"
            if "$ref" in part:
                target, last = doc.deref(part)
                target_pointer = last or part_pointer
                sub_props, sub_required, sub_refs = self._collect_object(doc, target, target_pointer, depth + 1)
                refs.append(self.ref_type(doc, part["$ref"], None, "Part"))
                refs.extend(sub_refs)
            else:
                sub_props, sub_required, sub_refs = self._collect_object(doc, part, part_pointer, depth + 1)
                refs.extend(sub_refs)
            for item in sub_props:
                props = [p for p in props if p[0] != item[0]]
                props.append(item)
            required |= sub_required
        for prop_name, prop_schema in (schema.get("properties") or {}).items():
            props = [p for p in props if p[0] != prop_name]
            props.append((prop_name, prop_schema, f"{pointer}/properties/{pointer_escape(prop_name)}", doc))
        return props, required, refs

    def _model(
        self,
        doc: Document,
        schema: dict[str, Any],
        pointer: str,
        outer: JClass | None,
        hint: str,
        origin: str,
        context: str = "json",
        skip_read_only: bool = False,
        extra_props: list[Prop] | None = None,
    ) -> TypeRef:
        cls = self._new_class("model", hint, outer, doc, pointer, origin)
        t = self._remember((doc.name, pointer), TypeRef("class", cls=cls))
        cls.description = description_of(schema)
        cls.deprecated = bool(schema.get("deprecated"))
        flat, required, _ = self._collect_object(doc, schema, pointer)
        used: set[str] = set()
        for prop in extra_props or []:
            used.add(prop.java_name)
            cls.props.append(prop)
        for prop_name, prop_schema, prop_pointer, prop_doc in flat:
            resolved, _ = prop_doc.deref(prop_schema)
            if skip_read_only and isinstance(resolved, dict) and resolved.get("readOnly"):
                continue
            if any(p.json_name == prop_name for p in cls.props):
                raise SpecError(f"{pointer}: property {prop_name!r} is both a parameter and a body field")
            java = names.safe_member(names.camel(prop_name))
            base = java
            n = 2
            while java in used:
                java = f"{base}{n}"
                n += 1
            used.add(java)
            owner = cls
            ptype = self.schema_type(
                prop_doc, prop_schema, prop_pointer, owner, names.pascal(prop_name), context=context
            )
            wrapper = prop_schema if isinstance(prop_schema, dict) else {}
            nullable = is_nullable(wrapper) or (isinstance(resolved, dict) and is_nullable(resolved))
            desc = description_of(wrapper) or (description_of(resolved) if isinstance(resolved, dict) else "")
            deprecated = bool(wrapper.get("deprecated")) or (
                isinstance(resolved, dict) and bool(resolved.get("deprecated"))
            )
            cls.props.append(
                Prop(prop_name, java, ptype, prop_name in required, nullable, desc, deprecated, resolved)
            )
        cls.complete = True
        return t

    def _union(
        self,
        doc: Document,
        schema: dict[str, Any],
        pointer: str,
        outer: JClass | None,
        hint: str,
        origin: str,
        word: str,
        context: str,
    ) -> TypeRef:
        cls = self._new_class("union", hint, outer, doc, pointer, origin)
        t = self._remember((doc.name, pointer), TypeRef("class", cls=cls))
        cls.description = description_of(schema)
        raw_variants = schema[word]
        resolved = []
        for i, v in enumerate(raw_variants):
            target, last = doc.deref(v)
            if isinstance(target, dict) and (target.get("type") == "null" or target.get("enum") == [None]):
                continue
            resolved.append((i, v, target, last))

        # An implicit discriminator: every variant is an object with a one-value enum
        # on the same property.
        disc_prop = None
        candidates = None
        for _, _, target, _ in resolved:
            props = target.get("properties", {}) if isinstance(target, dict) else {}
            singles = {
                k for k, s in props.items() if isinstance(s, dict) and len([e for e in s.get("enum", []) if e is not None]) == 1
            }
            candidates = singles if candidates is None else candidates & singles
        if candidates:
            disc_prop = sorted(candidates)[0]
            values = [target["properties"][disc_prop]["enum"][0] for _, _, target, _ in resolved]
            if len(set(values)) != len(values):
                disc_prop = None

        used: set[str] = set()
        for index, (i, v, target, last) in enumerate(resolved):
            vpointer = f"{pointer}/{word}/{i}"
            vtype_kind = target.get("type") if isinstance(target, dict) else None
            if disc_prop:
                vname = names.pascal(str(target["properties"][disc_prop]["enum"][0]))
                detect: tuple[Any, ...] = ("discriminator", disc_prop, target["properties"][disc_prop]["enum"][0])
            elif "$ref" in v:
                ref_t = self.ref_type(doc, v["$ref"], None, "Variant")
                vname = ref_t.cls.name if ref_t.cls is not None else names.pascal(v["$ref"].rsplit("/", 1)[-1])
                detect = self._shape_detect(target)
            elif isinstance(target, dict) and target.get("title") and len(names.words(target["title"])) <= 4:
                vname = names.pascal(target["title"])
                detect = self._shape_detect(target)
            elif vtype_kind in ("string", "integer", "number", "boolean", "array"):
                vname = {"string": "String", "integer": "Long", "number": "Double", "boolean": "Boolean", "array": "List"}[
                    vtype_kind
                ]
                detect = ("json_type", vtype_kind)
            else:
                first = (description_of(target) if isinstance(target, dict) else "").split(".")[0]
                vname = names.pascal(first) if first and len(names.words(first)) <= 3 else f"Variant{index + 1}"
                detect = self._shape_detect(target)
            base = vname
            n = 2
            while vname in used:
                vname = f"{base}{n}"
                n += 1
            used.add(vname)
            vt = self.schema_type(doc, v, vpointer, cls, vname, context=context)
            desc = description_of(target) if isinstance(target, dict) else ""
            cls.variants.append(Variant(vname, vt, detect, desc))
        cls.props = self._common_props(cls)
        # Shape detection needs the properties unique to each object variant.
        for variant in cls.variants:
            if variant.detect[0] == "shape":
                others: set[str] = set()
                for other in cls.variants:
                    if other is not variant and other.detect[0] == "shape":
                        others |= set(other.detect[2])
                unique = [p for p in variant.detect[2] if p not in others]
                variant.detect = ("shape", variant.detect[1], tuple(unique))
        cls.complete = True
        return t

    def _common_props(self, cls: JClass) -> list[Prop]:
        """Fields every object variant declares, readable without picking a variant."""
        models = [v.type.cls for v in cls.variants if v.type.kind == "class" and v.type.cls is not None]
        if len(models) != len(cls.variants) or not models or any(m.kind != "model" for m in models):
            return []
        out: list[Prop] = []
        for prop in models[0].props:
            others = [next((p for p in m.props if p.json_name == prop.json_name), None) for m in models[1:]]
            if any(o is None for o in others):
                continue
            ptype: TypeRef | None = prop.type
            for other in others:
                assert other is not None
                ptype = _common_type(ptype, other.type) if ptype is not None else None
            if ptype is None:
                continue
            required = prop.required and all(o is not None and o.required for o in others)
            nullable = prop.nullable or any(o is not None and o.nullable for o in others)
            out.append(
                Prop(prop.json_name, prop.java_name, ptype, required, nullable, prop.description, prop.deprecated, prop.schema)
            )
        return out

    def _shape_detect(self, target: Any) -> tuple[Any, ...]:
        if not isinstance(target, dict):
            return ("json_type", "any")
        t = target.get("type")
        if t in ("string", "integer", "number", "boolean", "array"):
            return ("json_type", t)
        required = tuple(target.get("required", []) or [])
        props = tuple((target.get("properties") or {}).keys())
        return ("shape", required, props)

    # -- operations -------------------------------------------------------------

    def operation(self, op: Operation) -> OpModel:
        doc = op.doc
        prefix = names.type_prefix(op.resource, op.method)
        java_method = names.safe_method(names.camel(op.method))
        params = op.parameters()

        path_params: list[PathParam] = []
        for segment in _path_names(op.path):
            if op.application_scoped and segment in ("organization_id", "application_id"):
                continue
            param = next((p for p in params if p["in"] == "path" and p["name"] == segment), None)
            if param is None:
                raise SpecError(f"{op.key}: path parameter {segment!r} is not declared")
            path_params.append(
                PathParam(segment, names.safe_param(names.camel(segment)), str(param.get("description", "")).strip())
            )

        query_props: list[Prop] = []
        header_fields: list[tuple[str, str]] = []
        idempotency = False
        params_cls_holder: dict[str, JClass] = {}

        def params_owner() -> JClass:
            if "cls" not in params_cls_holder:
                params_cls_holder["cls"] = self._new_class(
                    "model", prefix + "Params", None, doc, f"{op.pointer}/x-params", "params"
                )
            return params_cls_holder["cls"]

        raw_query: list[dict[str, Any]] = []
        for param in params:
            location = param["in"]
            if location == "header":
                if param["name"].lower() == "idempotency-key":
                    idempotency = True
                if param["name"].lower() in SKIPPED_HEADERS:
                    continue
            if location in ("query", "header"):
                raw_query.append(param)

        body = op.raw.get("requestBody")
        body_schema = None
        body_pointer = ""
        body_encoding = None
        body_required = False
        content_type = None
        if body is not None:
            body, body_ref = doc.deref(body)
            base_pointer = body_ref or f"{op.pointer}/requestBody"
            body_required = bool(body.get("required"))
            content = body.get("content", {})
            content_type, body_encoding = choose_request_content(doc, content, op)
            if content_type is not None:
                body_schema = content[content_type].get("schema", {})
                body_pointer = f"{base_pointer}/content/{pointer_escape(content_type)}/schema"

        params_class: JClass | None = None
        body_type: TypeRef | None = None
        context = "multipart" if body_encoding in ("multipart", "json_or_multipart") else "json"
        if body_schema is not None:
            target, _ = doc.deref(body_schema)
            direct = _direct_ref(body_schema)
            if direct is not None and not raw_query:
                body_type = self.ref_type(doc, direct, None, prefix + "Params")
                if body_type.kind != "class" or body_type.cls is None or body_type.cls.kind != "model":
                    raise SpecError(f"{op.key}: request body must be an object")
                if context == "multipart" and not body_type.cls.has_files():
                    pass
            else:
                if not isinstance(target, dict) or not (
                    target.get("type") == "object" or "properties" in target or "allOf" in target
                ):
                    raise SpecError(f"{op.key}: request body schema must be an object")
                cls_owner = params_owner()
                self.by_pointer[(doc.name, f"{op.pointer}/x-params")] = TypeRef("class", cls=cls_owner)
                query_props = self._params_props(doc, op, raw_query, cls_owner)
                self._fill_params(cls_owner, doc, target, body_pointer, query_props, context)
                params_class = cls_owner
        elif raw_query:
            cls_owner = params_owner()
            query_props = self._params_props(doc, op, raw_query, cls_owner)
            cls_owner.props = query_props
            cls_owner.complete = True
            params_class = cls_owner

        if params_class is not None:
            params_class.description = f"Parameters for {{@code {op.resource}.{op.method}}}."
            for prop in params_class.props:
                if prop.location == "header":
                    header_fields.append((prop.wire, prop.json_name))

        response = self._response(op, prefix)
        summary = str(op.raw.get("summary", "")).strip()
        description = str(op.raw.get("description", "")).strip()
        return OpModel(
            op,
            java_method,
            path_params,
            params_class,
            body_type,
            body_required,
            body_encoding,
            [(p.wire, p.json_name) for p in query_props if p.location == "query"],
            header_fields,
            response,
            idempotency,
            summary,
            description,
        )

    def _params_props(
        self, doc: Document, op: Operation, raw: list[dict[str, Any]], owner: JClass
    ) -> list[Prop]:
        props: list[Prop] = []
        used: set[str] = set()
        for param in raw:
            schema = param.get("schema", {})
            target, _ = doc.deref(schema)
            pointer = f"{op.pointer}/x-param-{param['in']}-{pointer_escape(param['name'])}"
            ptype = self.schema_type(doc, schema, pointer, owner, names.pascal(param["name"]))
            java = names.safe_member(names.camel(param["name"]))
            base = java
            n = 2
            while java in used:
                java = f"{base}{n}"
                n += 1
            used.add(java)
            props.append(
                Prop(
                    param["name"],
                    java,
                    ptype,
                    bool(param.get("required")),
                    is_nullable(target) if isinstance(target, dict) else False,
                    str(param.get("description", "")).strip(),
                    bool(param.get("deprecated")),
                    target if isinstance(target, dict) else {},
                    location=param["in"],
                )
            )
        return props

    def _fill_params(
        self,
        cls: JClass,
        doc: Document,
        schema: dict[str, Any],
        pointer: str,
        query_props: list[Prop],
        context: str,
    ) -> None:
        # A query parameter named like a body field keeps both: the query one gets a
        # "_query" suffix (Java name nodeIdQuery), like the other SDKs.
        body_names = {name for name, _, _, _ in self._collect_object(doc, schema, pointer)[0]}
        for prop in query_props:
            if prop.json_name in body_names:
                prop.wire_name = prop.json_name
                prop.json_name = prop.json_name + "_query"
                prop.java_name = names.safe_member(names.camel(prop.json_name))
        temp = self._model(doc, schema, pointer + "#tmp", cls, "Tmp", "params", context, True, query_props)
        tmp_cls = temp.cls
        assert tmp_cls is not None
        # Move the flattened properties and nested classes into the Params class.
        cls.props = tmp_cls.props
        cls.nested.remove(tmp_cls)
        for nested in tmp_cls.nested:
            nested.outer = cls
            cls.nested.append(nested)
        cls.description = tmp_cls.description
        cls.complete = True
        self.by_pointer[(doc.name, pointer)] = TypeRef("class", cls=cls)

    def _response(self, op: Operation, prefix: str) -> ResponseModel:
        doc = op.doc
        responses = op.raw.get("responses", {})
        codes = sorted(c for c in responses if str(c).startswith("2"))
        if not codes:
            return ResponseModel("none")
        entries = []
        for code in codes:
            resp, resp_ref = doc.deref(responses[code])
            base = resp_ref or f"{op.pointer}/responses/{code}"
            content = resp.get("content") or {}
            json_ct = next((ct for ct in content if ct == "application/json" or ct.endswith("+json")), None)
            if json_ct is not None:
                schema = content[json_ct].get("schema")
                if schema is None:
                    entries.append((code, "json_node", None, base, json_ct))
                else:
                    entries.append((code, "json", schema, f"{base}/content/{pointer_escape(json_ct)}/schema", json_ct))
            elif content:
                ct = next(iter(content))
                entries.append((code, "binary", None, base, ct))
            elif code == "204":
                entries.append((code, "none", None, base, ""))
            else:
                entries.append((code, "json_node", None, base, ""))

        kinds = {e[1] for e in entries}
        if kinds == {"binary"}:
            return ResponseModel("binary", statuses=codes, content_type=entries[0][4])
        if kinds == {"none"}:
            return ResponseModel("none", statuses=codes)
        json_entries = [e for e in entries if e[1] == "json"]
        if not json_entries:
            return ResponseModel("json_node", statuses=codes)

        # Same schema for every JSON success status: one type.
        targets = []
        for e in json_entries:
            target, last = doc.deref(e[2])
            targets.append((id(target), e))
        unique = {t for t, _ in targets}
        if len(unique) == 1 and len(json_entries) == len(entries):
            code, _, schema, pointer, _ = json_entries[0]
            hint = prefix + "Response"
            rtype = self._response_type(doc, schema, pointer, hint)
            page = self._page(op, doc, schema, rtype)
            if page is not None:
                style, item = page
                return ResponseModel("page", type=item, page_style=style, statuses=codes)
            return ResponseModel("json", type=rtype, statuses=codes)

        # Different shapes per status: a status union.
        cls = self._new_class("status", prefix + "Response", None, doc, f"{op.pointer}/x-status", "response")
        for code, kind, schema, pointer, _ in entries:
            vname = STATUS_NAMES.get(code, f"Status{code}")
            if kind == "json":
                vt = self.schema_type(doc, schema, pointer, cls, vname)
            else:
                vt = TypeRef("any")
            cls.variants.append(Variant(vname, vt, ("status", int(code))))
        cls.description = f"Response of {{@code {op.resource}.{op.method}}}; the shape depends on the HTTP status."
        cls.complete = True
        return ResponseModel("json", type=TypeRef("class", cls=cls), statuses=codes)

    def _response_type(self, doc: Document, schema: dict[str, Any], pointer: str, hint: str) -> TypeRef:
        if "$ref" in schema:
            return self.ref_type(doc, schema["$ref"], None, hint)
        return self.schema_type(doc, schema, pointer, None, hint, origin="response")

    def _page(
        self, op: Operation, doc: Document, schema: dict[str, Any], rtype: TypeRef
    ) -> tuple[str, TypeRef] | None:
        if op.http_method != "get" or rtype.kind != "class" or rtype.cls is None or rtype.cls.kind != "model":
            return None
        props = {p.json_name: p for p in rtype.cls.props}
        query = {p["name"] for p in op.parameters() if p["in"] == "query"}
        results = props.get("results")
        if results is None or "offset" not in query:
            return None
        if results.type.kind != "list" or results.type.item is None:
            return None
        if "next" in props and "count" in props:
            return "next", results.type.item
        return None

    def build(self) -> Model:
        # Components first, so their names are stable.
        for spec_name in ("verification", "account"):
            doc = self.spec.docs[spec_name]
            for comp in sorted(doc.data.get("components", {}).get("schemas", {})):
                self.component(doc, comp)
        ops: list[OpModel] = []
        for op in self.spec.server_operations():
            ops.append(self.operation(op))
        resources: dict[str, list[OpModel]] = {}
        for om in ops:
            resources.setdefault(om.op.resource, []).append(om)
        for res in list(resources):
            parts = res.split(".")
            for i in range(1, len(parts)):
                resources.setdefault(".".join(parts[:i]), [])
        check_resource_names(resources)
        return Model(self.spec, sorted(self.top_classes, key=lambda c: c.name), ops, dict(sorted(resources.items())))


def _common_type(a: TypeRef, b: TypeRef) -> TypeRef | None:
    """The type two variants' fields share, or None when they differ."""
    if a.kind != b.kind:
        return None
    if a.kind in ("list", "map"):
        assert a.item is not None and b.item is not None
        item = _common_type(a.item, b.item)
        return None if item is None else TypeRef(a.kind, item=item)
    if a.kind == "class":
        if a.cls is b.cls:
            return a
        if a.cls is not None and b.cls is not None and a.cls.kind == "enum" and b.cls.kind == "enum":
            return TypeRef("string")
        return None
    return a


STATUS_NAMES = {"200": "Ok", "201": "Created", "202": "Accepted", "203": "NonAuthoritative", "206": "PartialContent"}


def _direct_ref(schema: dict[str, Any]) -> str | None:
    if "$ref" in schema:
        return str(schema["$ref"])
    all_of = schema.get("allOf")
    structural = [k for k in ("properties", "oneOf", "anyOf", "items", "enum") if k in schema]
    if all_of and len(all_of) == 1 and "$ref" in all_of[0] and not structural:
        return str(all_of[0]["$ref"])
    return None


def _path_names(path: str) -> list[str]:
    out = []
    for segment in path.split("/"):
        if segment.startswith("{") and segment.endswith("}"):
            out.append(segment[1:-1])
    return out


def _schema_has_files(doc: Document, schema: Any) -> bool:
    target, _ = doc.deref(schema)
    if not isinstance(target, dict):
        return False
    if target.get("format") == "binary":
        return True
    for part in target.get("allOf", []) or []:
        if _schema_has_files(doc, part):
            return True
    for prop in (target.get("properties") or {}).values():
        resolved, _ = doc.deref(prop)
        if isinstance(resolved, dict):
            if resolved.get("format") == "binary":
                return True
            if resolved.get("type") == "array" and _schema_has_files(doc, resolved.get("items", {})):
                return True
    return False


def choose_request_content(
    doc: Document, content: dict[str, Any], op: Operation
) -> tuple[str | None, str | None]:
    """Picks the request body encoding: JSON, unless only multipart can carry the files."""
    has_json = "application/json" in content
    multipart = content.get("multipart/form-data")
    if multipart is not None and _schema_has_files(doc, multipart.get("schema", {})):
        return "multipart/form-data", ("json_or_multipart" if has_json else "multipart")
    if has_json:
        return "application/json", "json"
    if multipart is not None:
        return "multipart/form-data", "multipart"
    if "application/x-www-form-urlencoded" in content:
        raise SpecError(f"{op.key}: form-urlencoded only bodies are not supported")
    if content:
        raise SpecError(f"{op.key}: unsupported request content types {sorted(content)}")
    return None, None


def check_resource_names(resources: dict[str, list[OpModel]]) -> None:
    for res, ops in resources.items():
        children = {r.split(".")[-1] for r in resources if r.startswith(res + ".") and r.count(".") == res.count(".") + 1}
        methods = [om.java_method for om in ops]
        dupes = {m for m in methods if methods.count(m) > 1}
        if dupes:
            raise SpecError(f"resource {res} has duplicate methods {sorted(dupes)}")
        child_java = {names.camel(c) for c in children}
        clash = (set(methods) & child_java) | ({"withRawResponse", "withOptions"} & set(methods))
        if clash:
            raise SpecError(f"resource {res}: method names clash with sub-resources: {sorted(clash)}")


def build(spec: Spec) -> Model:
    return Builder(spec).build()
