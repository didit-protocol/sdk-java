"""Unit tests of codegen/spec_sync.py: the sync itself, the commit type, the breaking-change
detection and the release notes (DESIGN 3.1 and 13). They never touch the network.

The files in pinned/ are openapi/verification.json as sdk-python committed it at the commit in
each file name: public, pinned copies, so that all seven SDKs test the same upstream changes and
report the same results.
"""

from __future__ import annotations

import contextlib
import hashlib
import io
import json
import os
import re
import shutil
import sys
import tempfile
import unittest
from pathlib import Path
from typing import Any
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import spec_sync  # noqa: E402

from tests import fixtures  # noqa: E402

REPO = Path(__file__).resolve().parents[2]
PINNED_DIR = Path(__file__).resolve().parent / "pinned"
TODAY = "2026-11-06"
NOW = "2026-11-06T06:00:00Z"
# Commit -> the start of the SHA-256 of its openapi/verification.json.
PINNED = {
    "9fe57fc69299601e59cc7a64c1db370fea5c7461": "12b2fdb05572755d",  # sdk-python v1.0.1
    "e002a815773bbd1599256e2774046ee76520c6c8": "d1783bd862848198",
    "fe9f57a1dfb6374bfd391750d2044fc7a206e4ea": "9f8cdf8be8157588",
    "ccc799410f82eee1c25c7ce5abc6caa53846be6b": "1b0c7bdd8d7957bd",
}
NULL = "can now be null"
OBJECT = "type changed from string to object"
# The API change of 2026-11-04: the list sdk-python PR #14 printed for the same pair (the issue
# announces 15 but lists these 14).
BREAKING_2026_11_04 = [
    ("GET /v3/session/{sessionId}/decision/", "registry_checks[].company.registry_data", NULL),
    ("GET /v3/session/{sessionId}/decision/", "registry_checks[].ownership_structure", NULL),
    ("GET /v3/session/{sessionId}/decision/", "signatures[].confirmation.channel", NULL),
    ("POST /v3/email/risk/", "email.email_intelligence.reason_codes[]", OBJECT),
    ("POST /v3/email/risk/", "email.enrichment.reason_codes[]", OBJECT),
    ("POST /v3/kyb/select/", "kyb_registry.registry_data", NULL),
    ("POST /v3/kyb/shareholders/", "kyb_registry.registry_data", NULL),
    ("POST /v3/kyb/ubo/", "kyb_registry.registry_data", NULL),
    ("POST /v3/phone/risk/", "phone.enrichment.reason_codes[]", OBJECT),
    ("POST /v3/phone/risk/", "phone.phone_intelligence.reason_codes[]", OBJECT),
    ("POST /v3/session/import-shared/", "registry_checks[].company.registry_data", NULL),
    ("POST /v3/session/import-shared/", "registry_checks[].ownership_structure", NULL),
    ("POST /v3/session/import-shared/", "signatures[].confirmation.channel", NULL),
    ("POST /v3/session/{sessionId}/signatures/{nodeId}/validate/", "signatures[].confirmation.channel", NULL),
]
API_SECTION = "API changes that can break typed code"


def as_bytes(doc: dict) -> bytes:
    return json.dumps(doc).encode("utf-8")


def pinned(commit: str) -> bytes:
    return (PINNED_DIR / f"verification-{commit}.json").read_bytes()


def section(summary: str, title: str) -> str:
    """The text under the heading that starts with ``title`` (some headings add a count)."""
    after = f"\n{summary}".split(f"\n## {title}", 1)[1]
    return after.split("\n", 1)[1].split("\n## ", 1)[0]


def nested(message: str) -> list[str]:
    return re.findall(r"BEGIN_NESTED_COMMIT\n(.*?)\nEND_NESTED_COMMIT", message, re.S)


def repo_copy() -> Path:
    """A scratch copy of this repository's openapi/ and version."""
    root = Path(tempfile.mkdtemp())
    shutil.copytree(REPO / "openapi", root / "openapi")
    (root / "gradle.properties").write_text("version=1.1.0\n", encoding="utf-8")
    return root


def sync(root: Path, downloads: dict[str, bytes]) -> spec_sync.SyncResult:
    return spec_sync.sync(root, downloads, TODAY, NOW)


def sync_variant(change: Any) -> str:
    """The summary of a sync of this repository's verification.json after ``change``."""
    root = repo_copy()
    spec = json.loads((root / "openapi/verification.json").read_text(encoding="utf-8"))
    change(spec)
    return sync(root, {"verification.json": as_bytes(spec)}).summary


def sync_pair(before: str, after: str) -> spec_sync.SyncResult:
    root = repo_copy()
    (root / "openapi/verification.json").write_bytes(pinned(before))
    return sync(root, {"verification.json": pinned(after)})


class SpecSyncTest(unittest.TestCase):
    """The sync on small synthetic documents."""

    def setUp(self) -> None:
        self.root = fixtures.write(Path(tempfile.mkdtemp()))

    def run_sync(self, verification: dict, account: dict | None = None) -> spec_sync.SyncResult:
        files = {
            "verification.json": as_bytes(verification),
            "account.json": as_bytes(account or fixtures.ACCOUNT),
        }
        return spec_sync.sync(self.root, files, "2026-11-01", "2026-11-01T06:23:00Z")

    def test_nothing_changed(self) -> None:
        result = self.run_sync(fixtures.VERIFICATION)
        self.assertFalse(result.changed)
        self.assertEqual([], result.breaking)
        self.assertIn("unchanged", result.summary)

    def test_added_operation_without_name_is_called_out(self) -> None:
        new = fixtures.clone(fixtures.VERIFICATION)
        new["paths"]["/v3/toys/"] = {"get": {"responses": {"200": {"description": ""}}}}
        result = self.run_sync(new)
        self.assertTrue(result.changed)
        self.assertEqual("feat", result.commit_type)
        self.assertEqual([], result.breaking)
        self.assertIn("## Added operations (1)", result.summary)
        self.assertIn("- `GET /v3/toys/`: **no SDK naming**", result.summary)
        source = json.loads((self.root / "openapi/SOURCE.json").read_text())
        self.assertEqual("2026-11-01T06:23:00Z", source["verification.json"]["fetched_at"])
        self.assertEqual(64, len(source["verification.json"]["sha256"]))
        self.assertEqual(spec_sync.UPSTREAM["verification.json"], source["verification.json"]["url"])
        self.assertNotIn("fetched_at", source["account.json"])
        self.assertEqual(as_bytes(new), (self.root / "openapi/verification.json").read_bytes())

    def test_removed_operation_is_deprecated_with_its_components(self) -> None:
        new = fixtures.clone(fixtures.VERIFICATION)
        del new["paths"]["/v3/pets/{pet_id}/"]["get"]
        result = self.run_sync(new)
        self.assertEqual("feat", result.commit_type)
        self.assertEqual(
            [
                "`GET /v3/pets/{pet_id}/`: operation removed; `pets.retrieve` stays as a deprecated method "
                "until version 0.4.0"
            ],
            [b.describe() for b in result.breaking],
        )
        self.assertIn("kept as a deprecated method until version 0.4.0", section(result.summary, "Removed"))
        deprecated = json.loads((self.root / "openapi/deprecated-operations.json").read_text())
        entry = deprecated["methods"]["pets.retrieve"]
        self.assertEqual("removed", entry["reason"])
        self.assertEqual("0.4.0", entry["remove_in"])
        self.assertEqual("GET", entry["http_method"])
        self.assertEqual(["PetOrRow"], sorted(entry["components"]))
        self.assertEqual("pet_id", entry["path_parameters"][0]["name"])
        self.assertNotIn("parameters", entry["operation"])
        overlay = json.loads((self.root / "openapi/overlay.json").read_text())
        self.assertNotIn("GET /v3/pets/{pet_id}/", overlay["operations"])

    def test_a_moved_operation_keeps_its_name_and_is_compared(self) -> None:
        new = fixtures.clone(fixtures.VERIFICATION)
        new["paths"]["/v3/animals/{pet_id}/report/"] = new["paths"].pop("/v3/pets/{pet_id}/report/")
        overlay = fixtures.clone(fixtures.OVERLAY)
        new["paths"]["/v3/animals/{pet_id}/report/"]["get"]["x-didit-sdk"] = overlay["operations"][
            "GET /v3/pets/{pet_id}/report/"
        ]
        result = self.run_sync(new)
        self.assertEqual([], result.breaking)
        self.assertIn("moved to `GET /v3/animals/{pet_id}/report/`", result.summary)
        self.assertIn(
            "- `pets.reports.download` moved from `GET /v3/pets/{pet_id}/report/` to "
            "`GET /v3/animals/{pet_id}/report/`: x-didit-sdk: now ",
            section(result.summary, "Moved or reassigned methods"),
        )
        self.assertFalse((self.root / "openapi/deprecated-operations.json").exists())

    def test_upstream_naming_drops_the_overlay_entry(self) -> None:
        new = fixtures.clone(fixtures.VERIFICATION)
        new["paths"]["/v3/pets/"]["get"]["x-didit-sdk"] = fixtures.OVERLAY["operations"]["GET /v3/pets/"]
        result = self.run_sync(new)
        self.assertTrue(result.changed)
        # Upstream adopting the overlay entry unchanged changes nothing a caller can observe.
        self.assertEqual("chore", result.commit_type)
        self.assertEqual([], result.breaking)
        self.assertIn("Dropped 1 overlay entries that upstream now carries.", result.summary)
        overlay = json.loads((self.root / "openapi/overlay.json").read_text())
        self.assertNotIn("GET /v3/pets/", overlay["operations"])

    def test_upstream_rename_deprecates_the_old_method(self) -> None:
        new = fixtures.clone(fixtures.VERIFICATION)
        new["paths"]["/v3/pets/"]["get"]["x-didit-sdk"] = {
            "resource": "pets",
            "method": "search",
            "auth": "api_key",
            "server": True,
        }
        result = self.run_sync(new)
        self.assertEqual("feat", result.commit_type)
        # A rename keeps the old name as a deprecated alias: listed, not counted.
        self.assertEqual([], result.breaking)
        self.assertIn("`pets.list` renamed to `pets.search`", section(result.summary, "Renamed methods"))
        self.assertIn("upstream x-didit-sdk differs from the overlay; upstream wins", result.summary)
        deprecated = json.loads((self.root / "openapi/deprecated-operations.json").read_text())
        self.assertEqual("renamed", deprecated["methods"]["pets.list"]["reason"])
        self.assertEqual("pets.search", deprecated["methods"]["pets.list"]["replacement"])

    def test_schema_changes(self) -> None:
        new = fixtures.clone(fixtures.VERIFICATION)
        del new["components"]["schemas"]["Pet"]["properties"]["class"]
        new["components"]["schemas"]["Toy"] = {"type": "object"}
        result = self.run_sync(new)
        self.assertEqual("feat", result.commit_type)
        self.assertIn(("GET /v3/pets/", "results[].class", "removed"), [(b.operation, b.path, b.kind) for b in result.breaking])
        schemas = section(result.summary, "Schemas")
        self.assertIn("- verification.json added: `Toy`", schemas)
        self.assertIn("- verification.json changed: `Pet`", schemas)

    def test_refuses_a_download_that_is_not_an_openapi_document(self) -> None:
        for data in (b"<html>maintenance</html>", b"[]", b'{"openapi": "2.0", "paths": {"/": {}}}', b'{"openapi": "3.1.0"}'):
            with self.subTest(data=data), self.assertRaises(spec_sync.SpecSyncError):
                spec_sync.sync(self.root, {"verification.json": data}, TODAY, NOW)
        self.assertEqual(as_bytes(fixtures.VERIFICATION), (self.root / "openapi/verification.json").read_bytes())

    def test_helpers(self) -> None:
        self.assertEqual("1.3.0", spec_sync.next_minor("1.2.9"))
        self.assertEqual("the next minor version", spec_sync.next_minor("dev"))
        self.assertEqual(
            {"A", "B"}, spec_sync.refs({"x": [{"$ref": "#/components/schemas/A"}, {"$ref": "#/components/schemas/B"}]})
        )


class PinnedChangesTest(unittest.TestCase):
    """The two upstream changes every SDK classifies the same way (the issue's fixtures)."""

    def test_fixtures_are_the_pinned_upstream_documents(self) -> None:
        for commit, digest in PINNED.items():
            self.assertTrue(hashlib.sha256(pinned(commit)).hexdigest().startswith(digest), commit)

    def test_api_change_of_2026_11_04_is_a_minor_release_listing_every_breaking_change(self) -> None:
        result = sync_pair("9fe57fc69299601e59cc7a64c1db370fea5c7461", "e002a815773bbd1599256e2774046ee76520c6c8")
        self.assertEqual("feat", result.commit_type)
        self.assertEqual(BREAKING_2026_11_04, [(b.operation, b.path, b.kind) for b in result.breaking])
        self.assertEqual({"response"}, {b.where for b in result.breaking})

        # The pull request body opens with them, one line each.
        lines = result.summary.splitlines()
        self.assertEqual("## Breaking changes", lines[0])
        self.assertEqual([f"- {b.describe()}" for b in result.breaking], lines[1 : 1 + len(BREAKING_2026_11_04)])
        self.assertEqual(
            "- `GET /v3/session/{sessionId}/decision/` response `registry_checks[].company.registry_data`: "
            "can now be null",
            lines[1],
        )
        self.assertIn("a minor version, never a major one", section(result.summary, "Release"))

        # The release notes repeat them; never "!" or a BREAKING CHANGE footer (a major).
        message = result.commit_message()
        self.assertEqual("feat(spec): sync API spec 2026-11-06", message.splitlines()[0])
        self.assertNotIn("BREAKING", message)
        self.assertEqual([f"api(spec): {b.describe()}" for b in result.breaking], nested(message))
        self.assertFalse(any(c in line for line in nested(message) for c in "<>"))

    def test_documentation_only_change_of_2026_11_06_releases_nothing(self) -> None:
        result = sync_pair("fe9f57a1dfb6374bfd391750d2044fc7a206e4ea", "ccc799410f82eee1c25c7ce5abc6caa53846be6b")
        self.assertTrue(result.changed)
        self.assertEqual("chore", result.commit_type)
        self.assertEqual([], result.breaking)
        self.assertEqual("None detected.", section(result.summary, "Breaking changes").strip())
        self.assertIn("releases nothing on its own", section(result.summary, "Release"))
        self.assertIn("`POST /v3/poa/`", section(result.summary, "Changed operations"))
        self.assertEqual("chore(spec): sync API spec 2026-11-06\n", result.commit_message())
        self.assertEqual((None, {}), release(result.commit_message()))


def release(message: str) -> tuple[str | None, dict[str, list[str]]]:
    """What release-please makes of a squash-merged sync whose BEGIN_COMMIT_OVERRIDE block holds
    ``message``, read with release-please-config.json: the version bump and the release notes by
    section. The first line and each nested block are one commit; only types with a visible
    section reach the notes, and without one nothing is released."""
    config = json.loads((REPO / "release-please-config.json").read_text(encoding="utf-8"))
    sections = {s["type"]: s for s in config["packages"]["."]["changelog-sections"]}
    notes: dict[str, list[str]] = {}
    bump = "patch"
    for commit in [message.splitlines()[0], *nested(message)]:
        match = re.fullmatch(r"(\w+)(?:\((\w+)\))?(!?): (.+)", commit)
        assert match, commit
        kind, _, bang, text = match.groups()
        if bang or "BREAKING CHANGE" in message:
            bump = "major"
        elif kind == "feat" and bump != "major":
            bump = "minor"
        entry = sections.get(kind)
        if entry and not entry.get("hidden"):
            notes.setdefault(entry["section"], []).append(text)
    return (bump if notes else None), notes


class ReleaseNotesTest(unittest.TestCase):
    def test_breaking_api_changes_have_their_own_section_in_a_minor_release(self) -> None:
        result = sync_pair("9fe57fc69299601e59cc7a64c1db370fea5c7461", "e002a815773bbd1599256e2774046ee76520c6c8")
        bump, notes = release(result.commit_message())
        self.assertEqual("minor", bump)
        self.assertEqual(
            {"Features": ["sync API spec 2026-11-06"], API_SECTION: [b.describe() for b in result.breaking]}, notes
        )
        # A documentation-only sync (chore, a hidden section) proposes no release on its own.
        self.assertEqual((None, {}), release("chore(spec): sync API spec 2026-11-06\n"))

    def test_config_keeps_release_please_defaults_and_adds_the_api_section(self) -> None:
        config = json.loads((REPO / "release-please-config.json").read_text(encoding="utf-8"))
        package = config["packages"]["."]
        sections = {s["type"]: s for s in package["changelog-sections"]}
        self.assertEqual({"type": "api", "section": API_SECTION}, sections["api"])
        for kind, title in (("feat", "Features"), ("fix", "Bug Fixes"), ("perf", "Performance Improvements"), ("revert", "Reverts")):
            self.assertEqual({"type": kind, "section": title}, sections[kind])
        for kind in ("docs", "chore", "ci", "test", "build", "refactor", "style"):
            self.assertIs(True, sections[kind]["hidden"], kind)
        self.assertEqual("simple", package["release-type"])

    def test_release_notes_list_every_breaking_change(self) -> None:
        many = [spec_sync.BreakingChange(f"GET /v3/thing-{i}/", "response", "a", "removed") for i in range(503)]
        message = spec_sync.SyncResult(True, "", TODAY, "feat", many).commit_message()
        self.assertEqual([f"api(spec): {b.describe()}" for b in many], nested(message))


OBJ: dict[str, Any] = {"type": "object", "properties": {"a": {"type": "string"}}, "required": ["a"]}
STATUS: dict[str, Any] = {"type": "string", "enum": ["a", "b"]}


def diff(old: Any, new: Any, *, request: bool = False) -> list[tuple[str, str, bool]]:
    out: list[spec_sync.Change] = []
    spec_sync.diff_schema(old, new, "body", request=request, out=out)
    return [(c.path, c.kind, c.breaking) for c in out]


def op(*parameters: Any, responses: Any = None, body: Any = None) -> dict[str, Any]:
    out: dict[str, Any] = {"parameters": list(parameters), "responses": responses or {}}
    if body is not None:
        out["requestBody"] = {"content": {"application/json": {"schema": body}}}
    return out


def json_response(schema: Any) -> dict[str, Any]:
    return {"description": "ok", "content": {"application/json": {"schema": schema}}}


class DetectionTest(unittest.TestCase):
    """The DESIGN 3.1 list, on synthetic schemas."""

    def test_counted_in_requests_and_responses(self) -> None:
        for request in (True, False):
            with self.subTest(request=request):
                # A type change, in either direction.
                self.assertEqual(
                    [("", "type changed from integer to number", True)],
                    diff({"type": "integer"}, {"type": "number"}, request=request),
                )
                self.assertEqual(
                    [("", "type changed from number to integer", True)],
                    diff({"type": "number"}, {"type": "integer"}, request=request),
                )
                self.assertEqual(
                    [("", OBJECT, True)], diff({"type": "string"}, {"type": "object", "properties": {}}, request=request)
                )
                # A value that can now be null, however the document says it.
                for nullable in (
                    {"type": "string", "nullable": True},
                    {"type": ["string", "null"]},
                    {"anyOf": [{"type": "string"}, {"type": "null"}]},
                    {"oneOf": [{"type": "string"}, {"enum": [None]}]},
                    {"allOf": [{"type": "string"}], "nullable": True},
                ):
                    self.assertEqual([("", NULL, True)], diff({"type": "string"}, nullable, request=request), nullable)
                self.assertEqual([("", NULL, True)], diff(STATUS, {**STATUS, "enum": ["a", "b", None]}, request=request))
                # A removed field or enum value, nested in objects and array items.
                self.assertEqual([("a", "removed", True)], diff(OBJ, {"type": "object", "properties": {}}, request=request))
                self.assertEqual([("", 'enum value "b" removed', True)], diff(STATUS, {**STATUS, "enum": ["a"]}, request=request))
                listed = {"type": "array", "items": {"type": "object", "properties": {"s": STATUS}}}
                narrowed = {"type": "array", "items": {"type": "object", "properties": {"s": {**STATUS, "enum": ["b"]}}}}
                self.assertEqual([("[].s", 'enum value "a" removed', True)], diff(listed, narrowed, request=request))
                # A union that loses a variant.
                two = {"oneOf": [OBJ, {**OBJ, "properties": {"b": {"type": "string"}}}]}
                removed = [("", "type changed: 1 oneOf variant(s) removed", True)]
                self.assertEqual(removed, diff(two, {"oneOf": [OBJ]}, request=request))
                self.assertEqual(removed, diff(two, OBJ, request=request))
                self.assertEqual(
                    [("", "type changed from integer or string to string", True)],
                    diff({"oneOf": [{"type": "string"}, {"type": "integer"}]}, {"type": "string"}, request=request),
                )

    def test_a_string_format_with_its_own_type_is_a_type_change(self) -> None:
        # The generated code types these strings as OffsetDateTime, LocalDate and a file.
        plain = {"type": "string"}
        stamp = {"type": ["string", "null"], "format": "date-time"}
        listed = {"type": "array", "items": plain}
        for request in (True, False):
            with self.subTest(request=request):
                for fmt in ("date-time", "date", "binary"):
                    typed = {"type": "string", "format": fmt}
                    self.assertEqual(
                        [("", f"type changed from string to string ({fmt})", True)], diff(plain, typed, request=request)
                    )
                    self.assertEqual(
                        [("", f"type changed from string ({fmt}) to string", True)], diff(typed, plain, request=request)
                    )
                self.assertEqual(
                    [("", NULL, True), ("", "type changed from string (date) to string (date-time)", True)],
                    diff({"type": "string", "format": "date"}, stamp, request=request),
                )
                self.assertEqual(
                    [("[]", NULL, True), ("[]", "type changed from string to string (date-time)", True)],
                    diff(listed, {**listed, "items": stamp}, request=request),
                )

    def test_a_schema_that_allows_any_value_is_a_type_change(self) -> None:
        # Without a type the generated code types a value as JsonNode.
        uuid = {"type": "string", "format": "uuid"}
        listed = {"type": "array", "items": uuid}
        either = {"oneOf": [{"type": "string"}, {"type": "integer"}]}
        for request in (True, False):
            with self.subTest(request=request):
                self.assertEqual(
                    [("", "type changed from string to any", True)], diff(uuid, {"format": "uuid"}, request=request)
                )
                self.assertEqual([("", "type changed from any to integer", True)], diff({}, {"type": "integer"}, request=request))
                self.assertEqual(
                    [("[]", "type changed from string to any", True)], diff(listed, {"type": "array"}, request=request)
                )
                self.assertEqual(
                    [("", "type changed from integer or string to any", True)],
                    diff(either, {"oneOf": [{"type": "string"}, {}]}, request=request),
                )

    def test_newly_required_request_fields_count_and_response_requiredness_does_not(self) -> None:
        optional = {**OBJ, "required": []}
        self.assertEqual([("a", "now required", True)], diff(optional, OBJ, request=True))
        added = {"type": "object", "properties": {**OBJ["properties"], "b": {"type": "string"}}}
        self.assertEqual([("b", "added (required)", True)], diff(optional, {**added, "required": ["b"]}, request=True))
        self.assertEqual([("b", "added", False)], diff(optional, added, request=True))
        self.assertEqual([("a", "now always present", False)], diff(optional, OBJ))
        self.assertEqual([("a", "no longer always present", False)], diff(OBJ, optional))
        self.assertEqual([("a", "no longer required", False)], diff(OBJ, optional, request=True))

    def test_listed_but_not_counted(self) -> None:
        self.assertEqual([("", 'enum values added ["c"]', False)], diff(STATUS, {**STATUS, "enum": ["a", "b", "c"]}))
        self.assertEqual([("", 'now limited to ["a", "b"]', False)], diff({"type": "string"}, STATUS))
        self.assertEqual([("", "no longer limited to fixed values", False)], diff(STATUS, {"type": "string"}))
        self.assertEqual(
            [("", "no longer accepts null", False)],
            diff({"type": "string", "nullable": True}, {"type": "string"}, request=True),
        )
        self.assertEqual(
            [("", "other constraints changed (format, pattern, limits or default)", False)],
            diff({"type": "string"}, {"type": "string", "format": "uuid"}),
        )
        self.assertEqual(
            [("", "1 oneOf variant(s) added", False)],
            diff({"oneOf": [OBJ]}, {"oneOf": [OBJ, {**OBJ, "title": "B", "properties": {}}]}),
        )

    def test_an_optional_value_reads_as_nullable_not_as_its_fields_removed(self) -> None:
        self.assertEqual([("", NULL, True)], diff(OBJ, {"anyOf": [OBJ, {"type": "null"}]}))
        self.assertEqual([("", NULL, True)], diff(OBJ, {"oneOf": [OBJ, {"enum": [None]}], "description": "or null"}))
        self.assertEqual([("", "can no longer be null", False)], diff({"anyOf": [OBJ, {"type": "null"}]}, OBJ))

    def test_all_of_is_read_through(self) -> None:
        self.assertEqual(
            [("", 'enum value "b" removed', True)], diff({"allOf": [STATUS]}, {"allOf": [{**STATUS, "enum": ["a"]}]})
        )
        # Array items behind allOf (a field that wraps a component to add a description).
        events = {"type": "array", "items": {"type": "string"}}
        self.assertEqual(
            [("events[]", "type changed from string to integer", True)],
            diff(
                {"type": "object", "properties": {"events": {"allOf": [events]}}},
                {"type": "object", "properties": {"events": {"allOf": [{**events, "items": {"type": "integer"}}]}}},
                request=True,
            ),
        )

    def test_union_variants_are_compared_with_the_variant_they_became(self) -> None:
        a = {"type": "object", "title": "A", "properties": {"kind": STATUS}}
        b = {"type": "object", "properties": {"code": {"type": "string"}, "score": {"type": "number"}}}
        c = {"type": "object", "properties": {"url": {"type": "string"}}}
        b2 = {**b, "properties": {**b["properties"], "code": {"type": "integer"}}}
        retyped = "type changed from string to integer"
        added = ("", "1 oneOf variant(s) added", False)
        # A variant added, wherever it goes, or removed hides no change in the others; a variant is
        # named by its new position.
        self.assertEqual([("(oneOf 2).code", retyped, True), added], diff({"oneOf": [a, b]}, {"oneOf": [a, b2, c]}))
        self.assertEqual([("(oneOf 3).code", retyped, True), added], diff({"oneOf": [a, b]}, {"oneOf": [c, a, b2]}))
        self.assertEqual(
            [("(oneOf 2).code", retyped, True), ("", "type changed: 1 oneOf variant(s) removed", True)],
            diff({"oneOf": [a, b, c]}, {"oneOf": [a, b2]}),
        )
        # A schema that becomes a union is compared with the variant it became.
        self.assertEqual([("(oneOf 1).code", retyped, True), added], diff(b, {"oneOf": [b2, c]}))
        self.assertEqual(
            [("code", retyped, True), ("", "type changed: 1 anyOf variant(s) removed", True)], diff({"anyOf": [b, c]}, b2)
        )
        # A variant of a new type changes the union's type and still compares the others.
        self.assertEqual(
            [("", "type changed from object to object or string", True), ("(oneOf 2).code", retyped, True), added],
            diff({"oneOf": [a, b]}, {"oneOf": [a, b2, {"type": "string"}]}),
        )
        # Variants in another order are the same union.
        self.assertEqual([("", "oneOf variants reordered", False)], diff({"oneOf": [a, b]}, {"oneOf": [b, a]}))
        # Arrays of objects pair with arrays, whatever else comes or goes.
        first = {"type": "object", "properties": {"service_id": {"type": "string"}, "outcome": {"type": "string"}}}
        changed = {**first, "properties": {**first["properties"], "service_id": {"type": "integer"}}}
        self.assertEqual(
            [
                ("", "type changed from array or object to array or object or string", True),
                ("(oneOf 1)[].service_id", retyped, True),
                added,
            ],
            diff(
                {"oneOf": [{"type": "array", "items": first}, c]},
                {"oneOf": [{"type": "array", "items": changed}, c, {"type": "string"}]},
            ),
        )

    def test_union_variants_that_refer_to_components_are_followed(self) -> None:
        doc = {"components": {"schemas": {"A": OBJ, "B": {**OBJ, "properties": {"b": {"type": "string"}}}}}}
        new_doc = {"components": {"schemas": {"A": OBJ, "B": {**OBJ, "properties": {"b": {"type": "integer"}}}}}}
        union = {"oneOf": [{"$ref": "#/components/schemas/B"}, {"$ref": "#/components/schemas/A"}]}
        reordered = {"oneOf": [{"$ref": "#/components/schemas/A"}, {"$ref": "#/components/schemas/B"}, {"type": "string"}]}
        found = spec_sync.describe_change(
            doc, op(responses={"200": json_response(union)}), new_doc, op(responses={"200": json_response(reordered)})
        )
        self.assertEqual(
            [
                ("", "type changed from object to object or string", True),
                ("(oneOf 2).b", "type changed from string to integer", True),
                ("", "1 oneOf variant(s) added", False),
                ("", "oneOf variants reordered", False),
            ],
            [(c.path, c.kind, c.breaking) for c in found],
        )

    def test_documentation_never_counts_but_a_field_named_like_it_does(self) -> None:
        documented = {**OBJ, "description": "now documented", "title": "Thing", "example": {"a": "z"}, "x-codeSamples": []}
        self.assertEqual([], diff(OBJ, documented))
        fields = {"type": "object", "properties": {"description": {"type": "string"}, "tags": {}}}
        self.assertEqual([("description", "removed", True)], diff(fields, {"type": "object", "properties": {"tags": {}}}))
        self.assertEqual(
            [("title", "type changed from string to integer", True)],
            diff(
                {"type": "object", "properties": {"title": {"type": "string"}}},
                {"type": "object", "properties": {"title": {"type": "integer"}}},
            ),
        )

    def test_parameters(self) -> None:
        limit = {"name": "limit", "in": "query", "schema": {"type": "integer"}}
        where = "parameter `limit` (query)"

        def changes(old: Any, new: Any) -> list[tuple[str, str, bool]]:
            return [(c.where, c.kind, c.breaking) for c in spec_sync.describe_change({}, old, {}, new)]

        required_null = {**limit, "required": True, "schema": {"type": "integer", "nullable": True}}
        self.assertEqual([(where, "now required", True), (where, NULL, True)], changes(op(limit), op(required_null)))
        self.assertEqual(
            [(where, "type changed from integer to string", True)], changes(op(limit), op({**limit, "schema": {"type": "string"}}))
        )
        self.assertEqual(
            [(where, "removed", False), ("parameter `page` (query)", "added", False)], changes(op(limit), op({**limit, "name": "page"}))
        )
        self.assertEqual([(where, "added (required)", True)], changes(op(), op({**limit, "required": True})))

    def test_every_success_response_is_compared_and_reported_once(self) -> None:
        gone = {"type": "object", "properties": {}}
        old = op(responses={"200": json_response(OBJ), "202": json_response(OBJ), "400": json_response(OBJ)})
        both = op(responses={"200": json_response(gone), "202": json_response(gone), "400": json_response(gone)})
        found = spec_sync.describe_change({}, old, {}, both)
        self.assertEqual([("response", "a", "removed")], [(c.where, c.path, c.kind) for c in found])
        only_202 = op(responses={"200": json_response(OBJ), "202": json_response(gone)})
        found = spec_sync.describe_change({}, old, {}, only_202)
        self.assertEqual([("a", "removed")], [(c.path, c.kind) for c in found])

    def test_a_success_status_only_one_side_has_changes_the_result(self) -> None:
        # The method returns every 2xx body, and null when a 2xx response has no body.
        job = {"type": "object", "properties": {"job_id": {"type": "string"}}}
        one = {"200": json_response(OBJ)}
        no_body = {"204": {"description": "No content"}}

        def changes(old: Any, new: Any) -> list[tuple[str, bool]]:
            found = spec_sync.describe_change({}, op(responses=old), {}, op(responses=new))
            return [(c.kind, c.breaking) for c in found]

        self.assertEqual([("can now be null (status 204 has no body)", True)], changes(one, {**one, **no_body}))
        self.assertEqual([("can no longer be null (status 204 removed)", False)], changes({**one, **no_body}, one))
        two = {**one, "202": json_response(job)}
        self.assertEqual([("status 202 added with another body", False)], changes(one, two))
        removed = "type changed: status 202 removed, and no other status returns its body"
        self.assertEqual([(removed, True)], changes(two, one))
        # The same body under one more or one less status changes no type.
        same = {**one, "202": json_response(OBJ)}
        self.assertEqual([("status 202 added", False)], changes(one, same))
        self.assertEqual([("status 202 removed", False)], changes(same, one))

    def test_a_body_that_appears_is_compared_field_by_field(self) -> None:
        found = spec_sync.describe_change({}, op(), {}, op(body={**OBJ, "required": []}))
        self.assertEqual([("request body", "a", "added", False)], [(c.where, c.path, c.kind, c.breaking) for c in found])

    def test_a_removed_media_type_is_compared_with_the_one_that_replaces_it(self) -> None:
        # The generated Params class reads the multipart body when only it carries files.
        text = {"type": "string"}
        as_json = {"type": "object", "properties": {"url": text, "note": text}, "required": ["url"]}
        multipart = {"type": "object", "properties": {"file": {"type": "string", "format": "binary"}, "note": text}}
        both = {"application/json": as_json, "multipart/form-data": multipart}

        def changes(old: dict[str, Any], new: dict[str, Any]) -> list[tuple[str, str, bool]]:
            def body(content: dict[str, Any]) -> dict[str, Any]:
                return {**op(), "requestBody": {"content": {m: {"schema": s} for m, s in content.items()}}} if content else op()

            return [(c.path, c.kind, c.breaking) for c in spec_sync.describe_change({}, body(old), {}, body(new))]

        def moved(old: dict[str, Any], new: dict[str, Any]) -> tuple[str, str, bool]:
            return ("", f"content type {', '.join(sorted(old))} -> {', '.join(sorted(new))}", False)

        # Against a media type both bodies have, only what breaks counts: the rest was already there.
        only_json = {"application/json": as_json}
        self.assertEqual(
            [moved(both, only_json), ("file", "removed", True), ("url", "added (required)", True)], changes(both, only_json)
        )
        # A media type that changes name is compared in full.
        extra = {**as_json, "properties": {**as_json["properties"], "extra": text}}
        renamed = {"application/vnd.didit+json": extra, "multipart/form-data": multipart}
        self.assertEqual([moved(both, renamed), ("extra", "added", False)], changes(both, renamed))
        # An identical media type removed changes nothing else.
        form = {"application/json": as_json, "application/x-www-form-urlencoded": as_json}
        self.assertEqual([moved(form, only_json)], changes(form, only_json))
        # A body that disappears loses the fields of every media type.
        gone = [("note", "removed", True), ("url", "removed", True), ("file", "removed", True)]
        self.assertEqual(gone, changes(both, {}))

    def test_references_are_followed(self) -> None:
        doc = {"components": {"schemas": {"Obj": OBJ}}}
        new_doc = {"components": {"schemas": {"Obj": {**OBJ, "properties": {}}}}}
        ref = {"$ref": "#/components/schemas/Obj"}
        operation = op(responses={"200": json_response({"type": "array", "items": ref})}, body=ref)
        found = spec_sync.describe_change(doc, operation, new_doc, operation)
        self.assertEqual(
            [("request body", "a", "removed", True), ("response", "[].a", "removed", True)],
            [(c.where, c.path, c.kind, c.breaking) for c in found],
        )


OP = "/programmatic/resend-otp/"


class CommitTypeTest(unittest.TestCase):
    """feat when a caller can observe the change, chore for documentation (DESIGN 3.1)."""

    def account_sync(self, change: Any) -> spec_sync.SyncResult:
        root = repo_copy()
        spec = json.loads((root / "openapi/account.json").read_text(encoding="utf-8"))
        change(spec)
        return sync(root, {"account.json": as_bytes(spec)})

    def test_feat_when_a_caller_can_observe_it_and_chore_for_documentation(self) -> None:
        def response(spec: Any) -> Any:
            return spec["paths"][OP]["post"]["responses"]["200"]["content"]["application/json"]["schema"]

        cases = {
            "description": (lambda s: s["paths"][OP]["post"].update(description="Reworded."), "chore"),
            "code samples": (lambda s: s["paths"][OP]["post"].update({"x-codeSamples": []}), "chore"),
            "info": (lambda s: s["info"].update(version="9.9.9"), "chore"),
            "tags": (lambda s: s.setdefault("tags", []).append({"name": "New tag"}), "chore"),
            "schema description": (lambda s: response(s).update(description="Reworded."), "chore"),
            "field named description": (
                lambda s: response(s)["properties"].update(description={"type": "string"}),
                "feat",
            ),
            "new field": (lambda s: response(s)["properties"].update(extra={"type": "string"}), "feat"),
            "deprecated": (lambda s: s["paths"][OP]["post"].update(deprecated=True), "feat"),
            "rename": (lambda s: s["paths"][OP]["post"]["x-didit-sdk"].update(method="resend"), "feat"),
        }
        for name, (change, commit_type) in cases.items():
            with self.subTest(name):
                result = self.account_sync(change)
                self.assertTrue(result.changed)
                self.assertEqual(commit_type, result.commit_type)
                self.assertEqual([], result.breaking)
                self.assertEqual(f"{commit_type}(spec): sync API spec 2026-11-06\n", result.commit_message())

    def test_a_description_that_names_a_java_variant_is_feat(self) -> None:
        # The generator names an inline union variant after its title or the first sentence of its
        # description (model.Builder._union): AMLMonitoringToggleResult.CostPreview.
        def variant(spec: Any) -> Any:
            first = spec["components"]["schemas"]["AMLMonitoringToggleResult"]["oneOf"][0]
            self.assertTrue(first["description"].startswith("Cost preview. "))
            return first

        cases = {
            "renames the variant": (lambda s: variant(s).update(description="Estimated cost. Nothing is written."), "feat"),
            "same Java name": (lambda s: variant(s).update(description="Cost Preview. Nothing is written."), "chore"),
            "names a numbered variant": (lambda s: variant(s).update(title="Cost estimate"), "feat"),
            "title outside a union": (
                lambda s: s["components"]["schemas"]["TransactionVerificationSessionAction"].update(title="Renamed"),
                "chore",
            ),
        }
        for name, (change, commit_type) in cases.items():
            with self.subTest(name):
                root = repo_copy()
                spec = json.loads((root / "openapi/verification.json").read_text(encoding="utf-8"))
                change(spec)
                result = sync(root, {"verification.json": as_bytes(spec)})
                self.assertEqual(commit_type, result.commit_type)
                self.assertEqual([], result.breaking)
                self.assertEqual(commit_type == "feat", "## Java union variant names (1)" in result.summary)
        # A variant the API adds renames none (it is listed with the operations that return it).
        added = sync_variant(
            lambda s: s["components"]["schemas"]["AMLMonitoringToggleResult"]["oneOf"].append({"type": "string"})
        )
        self.assertNotIn("## Java union variant names", added)
        self.assertIn(
            "- `AMLMonitoringToggleResult`: `CostPreview` -> `EstimatedCost`",
            section(
                sync_variant(lambda s: variant(s).update(description="Estimated cost. Nothing is written.")),
                "Java union variant names",
            ),
        )

    def test_java_variant_names_are_the_generated_ones(self) -> None:
        names = spec_sync.java_variant_names(REPO)
        assert names is not None
        self.assertEqual(
            ("AMLMonitoringToggleResult", ["CostPreview", "Variant2"]),
            names[("verification.json", "#/components/schemas/AMLMonitoringToggleResult")],
        )
        # Every generated union, and its is/as/of methods, has the names the sync compares.
        union = (REPO / "src/main/java/me/didit/types/AMLMonitoringToggleResult.java").read_text(encoding="utf-8")
        for variant in ("CostPreview", "Variant2"):
            self.assertIn(f"public boolean is{variant}()", union)
        # Documents the generator cannot build compare no names; its own check reports them.
        root = repo_copy()
        spec = json.loads((root / "openapi/verification.json").read_text(encoding="utf-8"))
        spec["paths"]["/v3/unnamed/"] = {"get": {"responses": {"200": json_response(OBJ)}}}
        (root / "openapi/verification.json").write_text(json.dumps(spec), encoding="utf-8")
        self.assertIsNone(spec_sync.java_variant_names(root))

    def test_a_new_webhook_event_is_feat(self) -> None:
        root = repo_copy()
        spec = json.loads((root / "openapi/verification.json").read_text(encoding="utf-8"))
        spec["x-webhooks"]["synthetic.event"] = spec["x-webhooks"]["status.updated"]
        self.assertEqual("feat", sync(root, {"verification.json": as_bytes(spec)}).commit_type)


class AuthenticationDocumentTest(unittest.TestCase):
    """authentication.json is synced, never generated or deprecated from (DESIGN 10)."""

    def setUp(self) -> None:
        self.root = repo_copy()
        self.spec = json.loads((self.root / "openapi/authentication.json").read_text(encoding="utf-8"))

    def test_the_sync_downloads_the_authentication_document(self) -> None:
        self.assertEqual("https://docs.didit.me/openapi-authentication.json", spec_sync.UPSTREAM["authentication.json"])
        source = json.loads((REPO / "openapi/SOURCE.json").read_text(encoding="utf-8"))
        data = (REPO / "openapi/authentication.json").read_bytes()
        self.assertEqual(spec_sync.UPSTREAM["authentication.json"], source["authentication.json"]["url"])
        self.assertEqual(hashlib.sha256(data).hexdigest(), source["authentication.json"]["sha256"])
        # The generator never reads it.
        sys.path.insert(0, str(REPO / "codegen"))
        from didit_codegen import spec as generator_spec

        self.assertNotIn("authentication.json", [entry[1] for entry in generator_spec.SPECS])

    def test_a_removed_authentication_operation_is_breaking_and_never_deprecated(self) -> None:
        del self.spec["paths"]["/session/"]["delete"]
        result = sync(self.root, {"authentication.json": as_bytes(self.spec)})
        self.assertEqual("feat", result.commit_type)
        self.assertEqual(
            ["`authentication DELETE /session/`: operation removed (`authentication.revoke_session`)"],
            [b.describe() for b in result.breaking],
        )
        self.assertIn(
            "- `authentication.revoke_session` was removed: remove it", section(result.summary, "Authentication module")
        )
        self.assertIn("AuthenticationContractTest", section(result.summary, "Authentication module"))
        self.assertFalse((self.root / "openapi/deprecated-operations.json").exists())

    def test_a_renamed_authentication_method_names_the_module_change(self) -> None:
        self.spec["paths"]["/token/"]["post"]["x-didit-sdk"]["variants"][1]["method"] = "refresh_tokens"
        result = sync(self.root, {"authentication.json": as_bytes(self.spec)})
        self.assertEqual("feat", result.commit_type)
        self.assertEqual([], result.breaking)
        module = section(result.summary, "Authentication module")
        self.assertIn("- `authentication.refresh_tokens` is new: add it", module)
        self.assertIn("- `authentication.refresh` was removed: remove it", module)
        self.assertIn("## Renamed methods (0)", result.summary)

    def test_authentication_documentation_change_is_chore(self) -> None:
        self.spec["paths"]["/session/"]["get"]["description"] = "Reworded."
        result = sync(self.root, {"authentication.json": as_bytes(self.spec)})
        self.assertEqual("chore", result.commit_type)
        self.assertNotIn("## Authentication module", result.summary)


class CommandLineTest(unittest.TestCase):
    def test_writes_the_outputs_the_summary_and_the_commit_message(self) -> None:
        root = repo_copy()
        upstream = Path(tempfile.mkdtemp())
        for name in spec_sync.UPSTREAM:
            shutil.copy(root / "openapi" / name, upstream / name)
        spec = json.loads((upstream / "verification.json").read_text(encoding="utf-8"))
        del spec["paths"]["/v3/billing/balance/"]
        (upstream / "verification.json").write_text(json.dumps(spec), encoding="utf-8")
        out = Path(tempfile.mkdtemp())
        output, message, summary = out / "github_output", out / "message.txt", out / "summary.md"
        args = ["--root", str(root), "--from-dir", str(upstream), "--today", TODAY]
        args += ["--summary", str(summary), "--commit-message", str(message)]
        with mock.patch.dict(os.environ, {"GITHUB_OUTPUT": str(output)}), contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(0, spec_sync.main(args))
        outputs = dict(line.split("=", 1) for line in output.read_text(encoding="utf-8").splitlines())
        self.assertEqual({"changed": "true", "type": "feat", "date": TODAY, "breaking": "1"}, outputs)
        self.assertEqual(
            "feat(spec): sync API spec 2026-11-06\n\nBEGIN_NESTED_COMMIT\n"
            "api(spec): `GET /v3/billing/balance/`: operation removed; `billing.retrieve_balance` "
            "stays as a deprecated method until version 1.2.0\n"
            "END_NESTED_COMMIT\n",
            message.read_text(encoding="utf-8"),
        )
        self.assertTrue(summary.read_text(encoding="utf-8").startswith("## Breaking changes\n- `GET /v3/billing/balance/`"))

    def test_refuses_a_broken_download_and_writes_nothing(self) -> None:
        root = repo_copy()
        upstream = Path(tempfile.mkdtemp())
        for name in spec_sync.UPSTREAM:
            shutil.copy(root / "openapi" / name, upstream / name)
        (upstream / "account.json").write_text("<html>maintenance</html>", encoding="utf-8")
        before = (root / "openapi/SOURCE.json").read_bytes()
        with contextlib.redirect_stderr(io.StringIO()) as err:
            self.assertEqual(1, spec_sync.main(["--root", str(root), "--from-dir", str(upstream)]))
        self.assertIn("account.json: the download is not JSON", err.getvalue())
        self.assertEqual(before, (root / "openapi/SOURCE.json").read_bytes())


if __name__ == "__main__":
    unittest.main()
