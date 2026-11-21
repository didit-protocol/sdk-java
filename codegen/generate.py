#!/usr/bin/env python3
"""Generates the Java SDK's generated layer from openapi/ (DESIGN 3 and 5).

Usage:
    python3 codegen/generate.py          # write the generated files
    python3 codegen/generate.py --check  # fail when the committed files differ

It reads only openapi/ and writes:
    src/main/java/me/didit/types/       one file per top-level type
    src/main/java/me/didit/resources/   one file per resource, plus DiditResources
    src/test/java/me/didit/resources/   one generated test class per resource
"""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from didit_codegen import javagen, model, spec, testgen  # noqa: E402

ROOT = Path(__file__).resolve().parent.parent
OUTPUTS = {
    "src/main/java/me/didit/types": javagen.emit_types,
    "src/main/java/me/didit/resources": javagen.emit_resources,
    "src/test/java/me/didit/resources": testgen.emit_tests,
}


def render(root: Path) -> dict[Path, str]:
    """Every generated file, by path relative to ``root``."""
    loaded = spec.load(root)
    built = model.build(loaded)
    files: dict[Path, str] = {}
    for directory, emit in OUTPUTS.items():
        for name, text in emit(built).items():
            files[Path(directory) / name] = text
    return files


def existing(root: Path) -> dict[Path, str]:
    out: dict[Path, str] = {}
    for directory in OUTPUTS:
        base = root / directory
        if not base.exists():
            continue
        for path in sorted(base.rglob("*.java")):
            out[path.relative_to(root)] = path.read_text(encoding="utf-8")
    return out


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--check", action="store_true", help="fail when the generated files are stale")
    parser.add_argument("--root", type=Path, default=ROOT, help="repository root")
    args = parser.parse_args(argv)
    root: Path = args.root.resolve()
    try:
        wanted = render(root)
    except spec.SpecError as error:
        print(f"codegen: error: {error}", file=sys.stderr)
        return 2
    current = existing(root)
    changed = sorted(p for p, text in wanted.items() if current.get(p) != text)
    removed = sorted(p for p in current if p not in wanted)
    if args.check:
        if changed or removed:
            print("codegen: the generated code is stale; run python3 codegen/generate.py", file=sys.stderr)
            for path in changed[:50]:
                print(f"  changed: {path}", file=sys.stderr)
            for path in removed[:50]:
                print(f"  removed: {path}", file=sys.stderr)
            extra = len(changed) + len(removed) - 100
            if extra > 0:
                print(f"  ... and {extra} more", file=sys.stderr)
            return 1
        print(f"codegen: {len(wanted)} generated files are up to date")
        return 0
    for path in changed:
        target = root / path
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(wanted[path], encoding="utf-8", newline="\n")
    for path in removed:
        (root / path).unlink()
    print(f"codegen: wrote {len(changed)} files, removed {len(removed)}, {len(wanted)} generated files in total")
    return 0


if __name__ == "__main__":
    sys.exit(main())
