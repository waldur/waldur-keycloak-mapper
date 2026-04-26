#!/usr/bin/env python3
"""Bump the <revision> property in pom.xml.

CI overrides this at deploy time via `mvn -Drevision=$CI_COMMIT_TAG`, but keeping
the fallback in sync with the latest tag means local builds (and IDE artifact
naming) produce the right version on disk.

Usage:
    python3 scripts/bump_pom.py <VERSION>
    python3 scripts/bump_pom.py --check
"""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
POM = ROOT / "pom.xml"

REVISION_RE = re.compile(r"(<revision>)([^<]+)(</revision>)")
VERSION_RE = re.compile(r"^\d+\.\d+\.\d+(-rc\.\d+)?$")


def find_revision(text: str) -> str | None:
    m = REVISION_RE.search(text)
    return m.group(2) if m else None


def bump(version: str) -> int:
    text = POM.read_text()
    current = find_revision(text)
    if current is None:
        print(f"Error: <revision> not found in {POM}", file=sys.stderr)
        return 1

    if current == version:
        print(f"  pom.xml: <revision> already at {version}, no change")
        return 0

    new_text = REVISION_RE.sub(rf"\g<1>{version}\g<3>", text, count=1)
    POM.write_text(new_text)
    print(f"  pom.xml: <revision> {current} -> {version}")
    return 0


def check() -> int:
    text = POM.read_text()
    current = find_revision(text)
    if current is None:
        print(f"Error: <revision> not found in {POM}", file=sys.stderr)
        return 1
    print(current)
    return 0


def main() -> int:
    p = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    g = p.add_mutually_exclusive_group(required=True)
    g.add_argument("version", nargs="?", help="Version string (e.g. 1.4.0)")
    g.add_argument("--check", action="store_true", help="Print current <revision> and exit")
    args = p.parse_args()

    if args.check:
        return check()

    if not VERSION_RE.match(args.version):
        print(
            f"Error: '{args.version}' is not a valid version (expected X.Y.Z or X.Y.Z-rc.N)",
            file=sys.stderr,
        )
        return 1

    return bump(args.version)


if __name__ == "__main__":
    raise SystemExit(main())
