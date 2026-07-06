#!/usr/bin/env python3
"""Create a ``data/releases/<version>.md`` stub if one doesn't exist.

Usage:
    python scripts/gen_release_stub.py          # uses the repo VERSION file
    python scripts/gen_release_stub.py 0.4.0    # explicit version

The site build (main.py) also auto-stubs the current version on every build;
this exposes the same logic for manual use, so notes can be written ahead of a
build. Assets are pre-filled with the standard GitHub pattern (Windows + Linux).
"""
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))  # website/ (main.py)
import main

version = sys.argv[1] if len(sys.argv) > 1 else main._current_version()
created = main._stub_release(version)
print(f"{'created' if created else 'already exists'}: {main.RELEASES_DIR / f'{version}.md'}")
