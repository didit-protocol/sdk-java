"""Runs codegen/spec_sync.py on every case of openapi/breaking-vectors.json (DESIGN 3.1).

The vectors are generated in sdk-python and copied here byte for byte, so that all seven SDKs
classify the same upstream changes the same way. Never edit them: a rule change lands in
sdk-python first. Nothing is skipped.
"""

from __future__ import annotations

import hashlib
import json
import sys
import tempfile
import unittest
from pathlib import Path
from typing import Any

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import spec_sync  # noqa: E402

REPO = Path(__file__).resolve().parents[2]
VECTORS = REPO / "openapi" / "breaking-vectors.json"
RULES = {"Responses", "Requests", "Operations", "Unions", "Success statuses", "Release notes"}


def classify(before: dict[str, Any], after: dict[str, Any]) -> tuple[str, list[str]]:
    """What the sync makes of verification.json changing from ``before`` to ``after``: the commit
    type and the counted breaking changes, normalized, distinct and sorted."""
    with tempfile.TemporaryDirectory() as tmp:
        root = Path(tmp)
        (root / "openapi").mkdir()
        (root / "openapi/verification.json").write_text(json.dumps(before), encoding="utf-8")
        (root / "openapi/account.json").write_text(json.dumps({"openapi": "3.1.0", "paths": {}}), encoding="utf-8")
        (root / "openapi/overlay.json").write_text(json.dumps({"operations": {}}), encoding="utf-8")
        downloads = {"verification.json": json.dumps(after).encode("utf-8")}
        result = spec_sync.sync(root, downloads, "2026-01-01", "2026-01-01T00:00:00Z", version="1.0.0")
    return f"{result.commit_type}(spec)", sorted({b.normalized() for b in result.breaking})


class BreakingVectorsTest(unittest.TestCase):
    def setUp(self) -> None:
        self.data = VECTORS.read_bytes()
        self.doc = json.loads(self.data)

    def test_the_file_is_the_recorded_upstream_copy(self) -> None:
        source = json.loads((REPO / "openapi/SOURCE.json").read_text(encoding="utf-8"))
        self.assertEqual(source["breaking-vectors.json"]["sha256"], hashlib.sha256(self.data).hexdigest())
        self.assertEqual(1, self.doc["version"])
        self.assertEqual("docs/DESIGN.md section 3.1", self.doc["rules"])
        names = [case["name"] for case in self.doc["cases"]]
        self.assertEqual(len(names), len(set(names)))
        self.assertEqual(RULES, {case["rule"] for case in self.doc["cases"]})

    def test_every_case(self) -> None:
        for case in self.doc["cases"]:
            with self.subTest(case["name"]):
                expected = (case["expect"]["type"], case["expect"]["breaking"])
                self.assertEqual(expected, classify(case["before"], case["after"]))


if __name__ == "__main__":
    unittest.main()
