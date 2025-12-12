#!/usr/bin/env python3
"""
Sync version across all project files from VERSION file.

Usage:
    python update_version.py

This script reads the version from the VERSION file and updates:
    - Cargo.toml
    - kalixpy/Cargo.toml
"""
import re
from pathlib import Path


def main():
    root = Path(__file__).parent
    version_file = root / "VERSION"

    if not version_file.exists():
        print(f"Error: {version_file} not found")
        return 1

    version = version_file.read_text().strip()
    print(f"Version from VERSION file: {version}")

    cargo_files = [
        root / "Cargo.toml",
        root / "kalixpy" / "Cargo.toml"
    ]

    for cargo_file in cargo_files:
        if not cargo_file.exists():
            print(f"Warning: {cargo_file} not found, skipping")
            continue

        content = cargo_file.read_text()
        new_content = re.sub(
            r'^version = "[^"]+"',
            f'version = "{version}"',
            content,
            count=1,
            flags=re.MULTILINE
        )

        if content != new_content:
            cargo_file.write_text(new_content)
            print(f"Updated {cargo_file.relative_to(root)} to version {version}")
        else:
            print(f"{cargo_file.relative_to(root)} already at version {version}")

    print("\nDone! Next steps:")
    print("  1. Build both projects to verify")
    print("  2. Commit changes")
    print(f"  3. Tag release: git tag v{version}")
    return 0


if __name__ == "__main__":
    exit(main())
