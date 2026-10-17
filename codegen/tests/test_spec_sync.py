"""Unit tests of codegen/spec_sync.py, on synthetic specs."""

from __future__ import annotations

import json
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import spec_sync  # noqa: E402

from tests import fixtures  # noqa: E402


def as_bytes(doc: dict) -> bytes:
    return json.dumps(doc).encode("utf-8")


class SpecSyncTest(unittest.TestCase):
    def setUp(self) -> None:
        self.root = fixtures.write(Path(tempfile.mkdtemp()))

    def run_sync(self, verification: dict, account: dict | None = None) -> tuple[bool, bool, str]:
        files = {
            "verification.json": as_bytes(verification),
            "account.json": as_bytes(account or fixtures.ACCOUNT),
        }
        return spec_sync.sync(self.root, files, "2026-11-01", "2026-11-01T06:23:00Z")

    def test_nothing_changed(self) -> None:
        changed, breaking, summary = self.run_sync(fixtures.VERIFICATION)
        self.assertFalse(changed)
        self.assertFalse(breaking)
        self.assertIn("unchanged", summary)

    def test_added_operation_without_name_is_called_out(self) -> None:
        new = fixtures.clone(fixtures.VERIFICATION)
        new["paths"]["/v3/toys/"] = {"get": {"responses": {"200": {"description": ""}}}}
        changed, breaking, summary = self.run_sync(new)
        self.assertTrue(changed)
        self.assertFalse(breaking)
        self.assertIn("### Added operations (1)", summary)
        self.assertIn("**Action needed:**", summary)
        self.assertIn("`GET /v3/toys/`", summary)
        source = json.loads((self.root / "openapi/SOURCE.json").read_text())
        self.assertEqual("2026-11-01T06:23:00Z", source["verification.json"]["fetched_at"])
        self.assertEqual(64, len(source["verification.json"]["sha256"]))
        self.assertEqual(as_bytes(new), (self.root / "openapi/verification.json").read_bytes())

    def test_removed_operation_is_deprecated_with_its_components(self) -> None:
        new = fixtures.clone(fixtures.VERIFICATION)
        del new["paths"]["/v3/pets/{pet_id}/"]["get"]
        changed, breaking, summary = self.run_sync(new)
        self.assertTrue(changed and breaking)
        self.assertIn("**Breaking:**", summary)
        self.assertIn("until version 0.4.0", summary)
        deprecated = json.loads((self.root / "openapi/deprecated-operations.json").read_text())
        entry = deprecated["methods"]["pets.retrieve"]
        self.assertEqual("removed", entry["reason"])
        self.assertEqual("0.4.0", entry["remove_in"])
        self.assertEqual("GET", entry["http_method"])
        self.assertEqual(["PetOrRow"], sorted(entry["components"]))
        self.assertEqual("pet_id", entry["path_parameters"][0]["name"])
        overlay = json.loads((self.root / "openapi/overlay.json").read_text())
        self.assertNotIn("GET /v3/pets/{pet_id}/", overlay["operations"])

    def test_upstream_naming_drops_the_overlay_entry(self) -> None:
        new = fixtures.clone(fixtures.VERIFICATION)
        new["paths"]["/v3/pets/"]["get"]["x-didit-sdk"] = fixtures.OVERLAY["operations"]["GET /v3/pets/"]
        changed, breaking, summary = self.run_sync(new)
        self.assertTrue(changed)
        self.assertFalse(breaking)
        self.assertIn("Overlay entries dropped", summary)
        overlay = json.loads((self.root / "openapi/overlay.json").read_text())
        self.assertNotIn("GET /v3/pets/", overlay["operations"])

    def test_upstream_rename_deprecates_the_old_method(self) -> None:
        new = fixtures.clone(fixtures.VERIFICATION)
        new["paths"]["/v3/pets/"]["get"]["x-didit-sdk"] = {
            "resource": "pets", "method": "search", "auth": "api_key", "server": True
        }
        changed, breaking, summary = self.run_sync(new)
        self.assertTrue(changed and breaking)
        self.assertIn("is now `pets.search`", summary)
        deprecated = json.loads((self.root / "openapi/deprecated-operations.json").read_text())
        self.assertEqual("renamed", deprecated["methods"]["pets.list"]["reason"])
        self.assertEqual("pets.search", deprecated["methods"]["pets.list"]["replacement"])

    def test_schema_changes(self) -> None:
        new = fixtures.clone(fixtures.VERIFICATION)
        del new["components"]["schemas"]["Pet"]["properties"]["class"]
        new["components"]["schemas"]["Toy"] = {"type": "object"}
        changed, breaking, summary = self.run_sync(new)
        self.assertTrue(changed and breaking)
        self.assertIn("### Added schemas (1)", summary)
        self.assertIn("### Changed schemas (1)", summary)

    def test_helpers(self) -> None:
        self.assertEqual("1.3.0", spec_sync.next_minor("1.2.9"))
        self.assertEqual("the next minor version", spec_sync.next_minor("dev"))
        self.assertEqual({"A", "B"}, spec_sync.refs({"x": [{"$ref": "#/components/schemas/A"}, {"$ref": "#/components/schemas/B"}]}))


if __name__ == "__main__":
    unittest.main()
