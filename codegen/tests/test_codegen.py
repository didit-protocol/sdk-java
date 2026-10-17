"""Unit tests of the generator, on synthetic specs (python3 -m unittest discover codegen/tests)."""

from __future__ import annotations

import json
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from didit_codegen import javagen, model, names, spec, testgen  # noqa: E402

from tests import fixtures  # noqa: E402

import generate  # noqa: E402


class NamesTest(unittest.TestCase):
    def test_conversions(self) -> None:
        self.assertEqual("ContactDetails", names.pascal("contact_details"))
        self.assertEqual("SessionDecision", names.pascal("SessionDecision"))
        self.assertEqual("V3DsCheck", names.pascal("3ds_check"))
        self.assertEqual("sessionId", names.camel("session_id"))
        self.assertEqual("lastName", names.camel("lastName"))
        self.assertEqual("statusIn", names.camel("status__in"))
        self.assertEqual("xRequestId", names.camel("X-Request-Id"))
        self.assertEqual("_3Ds", names.camel("3ds"))
        self.assertEqual("NOT_STARTED", names.constant("Not Started"))
        self.assertEqual("DRIVER_S_LICENSE", names.constant("Driver's License"))
        self.assertEqual("EMPTY", names.constant(""))
        self.assertEqual("_1_DAY", names.constant("1-day"))
        self.assertEqual("class_", names.safe_member("class"))
        self.assertEqual("builder_", names.safe_member("builder"))
        self.assertEqual("params_", names.safe_param("params"))
        self.assertEqual("CasesNotesResource", names.resource_class("cases.notes"))
        self.assertEqual("SessionsImportsCreate", names.type_prefix("sessions.imports", "create"))

    def test_java_strings_and_nullable_types(self) -> None:
        self.assertEqual('"a\\"b\\\\c\\n\\u00e9\\ud83d\\ude00"', javagen.java_string('a"b\\c\né\U0001F600'))
        self.assertEqual("Outer.@Nullable Inner", javagen.nullable_type("Outer.Inner"))
        self.assertEqual("@Nullable List<A.B>", javagen.nullable_type("List<A.B>"))
        self.assertEqual("@Nullable String", javagen.nullable_type("String"))


class ModelTest(unittest.TestCase):
    def build(self, **kwargs) -> model.Model:
        tmp = Path(tempfile.mkdtemp())
        fixtures.write(tmp, **kwargs)
        return model.build(spec.load(tmp))

    def test_operations_and_resources(self) -> None:
        m = self.build()
        self.assertEqual(
            ["account.login", "pets.create", "pets.delete", "pets.list", "pets.reports.download", "pets.retrieve"],
            sorted(om.op.full_name for om in m.operations),
        )
        self.assertIn("pets.reports", m.resources)
        by_name = {om.op.full_name: om for om in m.operations}
        self.assertEqual("page", by_name["pets.list"].response.kind)
        self.assertEqual("binary", by_name["pets.reports.download"].response.kind)
        self.assertEqual("none", by_name["pets.delete"].response.kind)
        self.assertEqual(["pet_id"], [p.json_name for p in by_name["pets.retrieve"].path_params])

    def test_body_encoding_query_collisions_and_idempotency(self) -> None:
        create = next(om for om in self.build().operations if om.op.full_name == "pets.create")
        self.assertEqual("json_or_multipart", create.body_encoding)
        self.assertTrue(create.idempotency)
        assert create.params_class is not None
        props = {p.json_name: p for p in create.params_class.props}
        self.assertEqual("file", props["photo"].type.kind)
        self.assertEqual("nodeIdQuery", props["node_id_query"].java_name)
        self.assertEqual([("node_id", "node_id_query")], create.query_fields)
        self.assertEqual("status", create.response.type.cls.kind)

    def test_types(self) -> None:
        m = self.build()
        classes = {c.name: c for c in m.classes}
        pet = classes["Pet"]
        props = {p.json_name: p for p in pet.props}
        self.assertEqual("class_", props["class"].java_name)
        self.assertEqual("builder_", props["builder"].java_name)
        self.assertTrue(props["status"].nullable)
        self.assertEqual(["Not Started", "Approved", "approved"], props["status"].type.cls.values)
        self.assertEqual("datetime", props["born_at"].type.kind)
        self.assertEqual("object", props["extra"].type.kind)
        self.assertEqual("map", props["scores"].type.kind)
        self.assertEqual("Pet.Owner", props["owner"].type.cls.qualified)
        union = classes["PetOrRow"]
        self.assertEqual("union", union.kind)
        self.assertEqual(["Pet", "Row"], [v.name for v in union.variants])
        self.assertEqual(("discriminator", "kind", "pet"), union.variants[0].detect)
        self.assertEqual(["kind", "name"], [p.json_name for p in union.props])
        child = classes["Child"]
        self.assertEqual(["id", "age"], [p.json_name for p in child.props])

    def test_missing_naming_fails_with_the_operation(self) -> None:
        overlay = fixtures.clone(fixtures.OVERLAY)
        del overlay["operations"]["GET /v3/pets/"]
        with self.assertRaises(spec.SpecError) as ctx:
            self.build(overlay=overlay)
        self.assertIn("GET /v3/pets/", str(ctx.exception))
        self.assertIn("overlay.json", str(ctx.exception))

    def test_stale_overlay_entry_fails(self) -> None:
        overlay = fixtures.clone(fixtures.OVERLAY)
        overlay["operations"]["GET /v3/gone/"] = {"resource": "gone", "method": "list", "auth": "api_key", "server": True}
        with self.assertRaises(spec.SpecError) as ctx:
            self.build(overlay=overlay)
        self.assertIn("GET /v3/gone/", str(ctx.exception))

    def test_upstream_naming_wins(self) -> None:
        verification = fixtures.clone(fixtures.VERIFICATION)
        verification["paths"]["/v3/pets/"]["get"]["x-didit-sdk"] = {
            "resource": "animals", "method": "list", "auth": "api_key", "server": True
        }
        overlay = fixtures.clone(fixtures.OVERLAY)
        del overlay["operations"]["GET /v3/pets/"]
        names_ = sorted(om.op.full_name for om in self.build(verification=verification, overlay=overlay).operations)
        self.assertIn("animals.list", names_)

    def test_duplicate_method_names_fail(self) -> None:
        overlay = fixtures.clone(fixtures.OVERLAY)
        overlay["operations"]["DELETE /v3/pets/{pet_id}/"]["method"] = "retrieve"
        with self.assertRaises(spec.SpecError):
            self.build(overlay=overlay)

    def test_deprecated_operations_are_generated(self) -> None:
        tmp = Path(tempfile.mkdtemp())
        fixtures.write(tmp)
        (tmp / "openapi" / "deprecated-operations.json").write_text(json.dumps({
            "version": 1,
            "methods": {
                "pets.archive": {
                    "reason": "removed", "replacement": None, "remove_in": "0.4.0", "spec": "verification",
                    "http_method": "POST", "path": "/v3/pets/{pet_id}/archive/",
                    "x-didit-sdk": {"resource": "pets", "method": "archive", "auth": "api_key", "server": True},
                    "operation": {"responses": {"200": {"description": "", "content": {"application/json": {"schema": {"$ref": "#/components/schemas/Archived"}}}}}},
                    "path_parameters": [{"name": "pet_id", "in": "path", "required": True, "schema": {"type": "string"}}],
                    "components": {"Archived": {"type": "object", "properties": {"at": {"type": "string"}}}},
                }
            },
        }), encoding="utf-8")
        m = model.build(spec.load(tmp))
        archive = next(om for om in m.operations if om.op.full_name == "pets.archive")
        self.assertIsNotNone(archive.op.deprecated)
        resources = javagen.emit_resources(m)
        self.assertIn("@Deprecated", resources["PetsResource.java"])
        self.assertIn("goes away in version 0.4.0", resources["PetsResource.java"])
        self.assertIn("Archived.java", javagen.emit_types(m))


class EmitTest(unittest.TestCase):
    def test_emits_compilable_shapes(self) -> None:
        tmp = Path(tempfile.mkdtemp())
        fixtures.write(tmp)
        m = model.build(spec.load(tmp))
        types = javagen.emit_types(m)
        pet = types["Pet.java"]
        self.assertTrue(pet.startswith(javagen.HEADER))
        self.assertIn("public final class Pet extends JsonModel", pet)
        self.assertIn("public String builder_()", pet)
        self.assertIn("public Optional<Pet.Status> status()", pet)
        self.assertIn("public Builder status(Pet.@Nullable Status status)", pet)
        self.assertIn("public static final Status APPROVED_2 = new Status(\"approved\");", pet)
        resources = javagen.emit_resources(m)
        pets = resources["PetsResource.java"]
        self.assertIn("public Page<Pet> list()", pets)
        self.assertIn(".queryField(\"node_id\", \"node_id_query\")", pets)
        self.assertIn(".body(ApiRequest.BodyEncoding.JSON_OR_MULTIPART)", pets)
        self.assertIn("public CompletableFuture<Void> deleteAsync(String petId)", pets)
        self.assertIn(".accept(\"application/pdf\")", resources["PetsReportsResource.java"])
        self.assertIn("public final PetsResource pets()", resources["DiditResources.java"])
        self.assertIn(".account()", resources["AccountResource.java"])
        tests = testgen.emit_tests(m)
        self.assertIn("void create()", tests["PetsResourceTest.java"])
        self.assertIn("request.multipartFiles()", tests["PetsResourceTest.java"])

    def test_check_mode(self) -> None:
        tmp = Path(tempfile.mkdtemp())
        fixtures.write(tmp)
        self.assertEqual(0, generate.main(["--root", str(tmp)]))
        self.assertEqual(0, generate.main(["--root", str(tmp), "--check"]))
        stale = tmp / "src/main/java/me/didit/types/Stale.java"
        stale.write_text("// old\n", encoding="utf-8")
        self.assertEqual(1, generate.main(["--root", str(tmp), "--check"]))
        self.assertEqual(0, generate.main(["--root", str(tmp)]))
        self.assertFalse(stale.exists())
        overlay = fixtures.clone(fixtures.OVERLAY)
        del overlay["operations"]["GET /v3/pets/"]
        fixtures.write(tmp, overlay=overlay)
        self.assertEqual(2, generate.main(["--root", str(tmp), "--check"]))


if __name__ == "__main__":
    unittest.main()
