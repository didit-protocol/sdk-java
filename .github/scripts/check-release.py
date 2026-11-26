"""Check the release metadata before CI approves a release PR."""

import json
import os
from pathlib import Path
import re
import subprocess
import sys


def release_notes(body, repo, branch):
    # Release Please puts oversized notes on a sibling branch.
    notes_branch = f"{branch}--release-notes"
    url = f"https://github.com/{repo}/blob/{notes_branch}/release-notes.md"
    if body.strip() == (
        "This release is too large to preview in the pull request body. "
        f"View the full release notes here: {url}"
    ):
        return subprocess.check_output(
            ["gh", "api", f"repos/{repo}/contents/release-notes.md?ref={notes_branch}",
             "-H", "Accept: application/vnd.github.raw+json"],
            text=True,
        )
    return body


def check_release(pr, proposed, published_tag):
    version = r"(?:0|[1-9][0-9]*)(?:\.(?:0|[1-9][0-9]*)){2}"
    if not re.fullmatch(version, proposed) or not re.fullmatch("v" + version, published_tag):
        raise ValueError("Expected a stable release version in the manifest and published tag")
    if pr["title"] != f"chore(main): release {proposed}":
        raise ValueError(f"Release PR title must name manifest version {proposed}")
    headings = re.findall(r"^## \[([^\]]+)\]\(", pr["body"], re.MULTILINE)
    if headings != [proposed]:
        raise ValueError(f"Release notes must have a release heading for {proposed}")
    if tuple(map(int, proposed.split("."))) <= tuple(map(int, published_tag[1:].split("."))):
        raise ValueError(f"Proposed version {proposed} must exceed published {published_tag}")


if __name__ == "__main__":
    manifest = json.loads(Path(".release-please-manifest.json").read_text())
    try:
        pr = json.load(sys.stdin)
        branch = os.environ.get("GITHUB_HEAD_REF") or os.environ["GITHUB_REF_NAME"]
        pr["body"] = release_notes(pr["body"], os.environ["GITHUB_REPOSITORY"], branch)
        check_release(pr, manifest["."], sys.argv[1])
    except ValueError as error:
        sys.exit(str(error))
