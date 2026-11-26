import runpy
import unittest
from pathlib import Path
from unittest.mock import patch


checker = runpy.run_path(
    str(Path(__file__).resolve().parents[2] / ".github/scripts/check-release.py")
)


class ReleaseMetadataTest(unittest.TestCase):
    def check(self, proposed, title, notes, published="v1.44.0"):
        pr = {"title": f"chore(main): release {title}", "body": f"## [{notes}](compare)"}
        checker["check_release"](pr, proposed, published)

    def test_stale_pr_title(self):
        with self.assertRaisesRegex(ValueError, "title"):
            self.check("1.45.0", "1.44.0", "1.44.0")

    def test_stale_notes(self):
        with self.assertRaisesRegex(ValueError, "heading"):
            self.check("1.45.0", "1.45.0", "1.44.0")

    def test_already_published(self):
        for proposed in ("1.12.0", "1.44.0"):
            with self.subTest(proposed=proposed), self.assertRaisesRegex(ValueError, "exceed"):
                self.check(proposed, proposed, proposed)

    def test_minor_release(self):
        self.check("1.45.0", "1.45.0", "1.45.0")
        self.check("1.10.0", "1.10.0", "1.10.0", "v1.9.0")

    def test_large_notes(self):
        repo = "didit-protocol/sdk-java"
        branch = "release-please--branches--main--components--didit-java"
        pointer = (
            "This release is too large to preview in the pull request body. "
            f"View the full release notes here: https://github.com/{repo}/blob/"
            f"{branch}--release-notes/release-notes.md"
        )
        with patch("subprocess.check_output", return_value="full notes") as fetch:
            self.assertEqual(checker["release_notes"](pointer, repo, branch), "full notes")
            self.assertIn(f"repos/{repo}/contents/release-notes.md?ref={branch}--release-notes",
                          fetch.call_args.args[0])
