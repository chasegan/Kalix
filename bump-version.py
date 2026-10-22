#!/usr/bin/env python3
"""Bump Kalix version across VERSION, Cargo.toml, python/Cargo.toml,
python/pyproject.toml, Cargo.lock and python/uv.lock.

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


def bump_lock(text, version, package):
    """Rewrite `package`'s own version inside its [[package]] block in a lockfile.

    Both Cargo.lock and uv.lock record the workspace's own crates or project as
    packages, and that version is a mirror of the manifest rather than a
    resolution result — so writing it here produces exactly what `cargo` or
    `uv lock` would. Doing it this way (instead of shelling out) keeps this
    script stdlib-only and offline, and stops a version bump from quietly
    re-resolving dependencies into the same commit. CI builds the wheel with
    `--locked`, so a Cargo.lock left behind would fail the release.

    Scans block-by-block rather than matching a fixed key order, so it does not
    depend on how uv happens to order or space the keys it writes. Raises
    ValueError unless exactly one block matched — a loud failure beats a
    silently stale lockfile.
    """
    lines = text.splitlines(keepends=True)
    starts = [i for i, ln in enumerate(lines) if ln.strip() == "[[package]]"]
    bounds = zip(starts, starts[1:] + [len(lines)])

    name_re = re.compile(rf'^\s*name\s*=\s*"{re.escape(package)}"\s*$')
    version_re = re.compile(r'^(\s*version\s*=\s*")[^"]*(")')

    hits = [(s, e) for s, e in bounds
            if any(name_re.match(lines[i]) for i in range(s, e))]
    if len(hits) != 1:
        raise ValueError(
            f"expected 1 [[package]] block named {package!r}, found {len(hits)}")

    start, end = hits[0]
    for i in range(start, end):
        if version_re.match(lines[i]):
            lines[i] = version_re.sub(rf"\g<1>{version}\g<2>", lines[i], count=1)
            return "".join(lines)
    raise ValueError(f"[[package]] block named {package!r} has no version line")


# newline="" on both ends so the file's existing line endings survive the
# round-trip untouched (these are long enough that a wholesale CRLF/LF
# flip would bury the one-line change).
for rel, packages in (("Cargo.lock", ("kalix", "kalix-python")),
                      ("python/uv.lock", ("kalix",))):
    lock = root / rel
    with open(lock, encoding="utf-8", newline="") as f:
        lock_text = f.read()
    for package in packages:
        lock_text = bump_lock(lock_text, new, package)
    with open(lock, "w", encoding="utf-8", newline="") as f:
        f.write(lock_text)

print(f"Bumped to {new}")
