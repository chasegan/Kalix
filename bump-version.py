#!/usr/bin/env python3
"""Bump Kalix version across VERSION, Cargo.toml, python/Cargo.toml, python/pyproject.toml.

Usage:
    python bump-version.py 0.3.0   # bump to 0.3.0
    python bump-version.py         # re-sync from current VERSION file
"""
import re
import sys
from pathlib import Path

root = Path(__file__).resolve().parent

if len(sys.argv) == 1:
    new = (root / "VERSION").read_text().strip()
elif len(sys.argv) == 2:
    new = sys.argv[1]
    (root / "VERSION").write_text(new + "\n")
else:
    sys.exit("Usage: python bump-version.py [NEW_VERSION]")
for rel in ("Cargo.toml", "python/Cargo.toml", "python/pyproject.toml"):
    p = root / rel
    p.write_text(
        re.sub(r'^(version\s*=\s*")[^"]+(")', rf'\g<1>{new}\g<2>',
               p.read_text(), count=1, flags=re.MULTILINE)
    )

print(f"Bumped to {new}")
