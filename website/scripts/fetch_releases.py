#!/usr/bin/env python3
"""Fetch Kalix releases from the GitHub API into the site's release data.

Runs at build time (in CI, with the Actions-provided GITHUB_TOKEN for an
authenticated, high-rate-limit request). Writes ``website/data/releases.json``,
the single source of truth for the Downloads page and the ``latest_version``
used across the site.

Design decision: on ANY failure (offline, rate-limited, API error) this leaves
the committed ``releases.json`` untouched, so it is a genuine fallback rather
than something the build regularly depends on.
"""

import json
import os
import sys
from pathlib import Path
from urllib.request import Request, urlopen
from urllib.error import URLError, HTTPError

REPO = os.environ.get("KALIX_REPO", "chasegan/Kalix")
API = f"https://api.github.com/repos/{REPO}/releases?per_page=100"
OUT = Path(__file__).resolve().parent.parent / "data" / "releases.json"

# Map a release asset's filename suffix to a platform key used by the template.
SUFFIX_PLATFORM = {
    ".exe": "windows",
    ".msi": "windows",
    ".dmg": "macos",
    ".pkg": "macos",
    ".appimage": "linux",
    ".zip": "docs",
}


def classify_assets(assets):
    out = {}
    for a in assets:
        name = a.get("name", "")
        lower = name.lower()
        for suffix, key in SUFFIX_PLATFORM.items():
            if lower.endswith(suffix):
                out.setdefault(key, a.get("browser_download_url"))
                break
    return out


def changelog_bullets(body: str):
    if not body:
        return []
    bullets = []
    for line in body.splitlines():
        s = line.strip()
        if s.startswith(("- ", "* ")):
            bullets.append(s[2:].strip())
    # Fall back to the first few non-empty lines if the body isn't a bullet list.
    if not bullets:
        bullets = [l.strip() for l in body.splitlines() if l.strip()][:4]
    return bullets[:8]


def main() -> int:
    headers = {"Accept": "application/vnd.github+json", "User-Agent": "kalix-site-build"}
    token = os.environ.get("GITHUB_TOKEN")
    if token:
        headers["Authorization"] = f"Bearer {token}"

    try:
        req = Request(API, headers=headers)
        with urlopen(req, timeout=20) as resp:
            data = json.loads(resp.read().decode("utf-8"))
    except (HTTPError, URLError, TimeoutError, ValueError) as e:
        print(f"[fetch_releases] fetch failed ({e}); keeping committed fallback.", file=sys.stderr)
        return 0

    releases = []
    for r in data:
        if r.get("draft"):
            continue
        tag = (r.get("tag_name") or "").lstrip("v")
        if not tag:
            continue
        releases.append({
            "version": tag,
            "date": (r.get("published_at") or "")[:10],
            "prerelease": bool(r.get("prerelease")),
            "changelog": changelog_bullets(r.get("body") or ""),
            "assets": classify_assets(r.get("assets") or []),
            "pip": f"pip install kalix=={tag}",
            "github": r.get("html_url"),
        })

    if not releases:
        print("[fetch_releases] API returned no usable releases; keeping fallback.", file=sys.stderr)
        return 0

    # Newest first; mark the newest non-prerelease as latest.
    for r in releases:
        r["latest"] = False
    for r in releases:
        if not r["prerelease"]:
            r["latest"] = True
            break

    OUT.write_text(json.dumps(releases, indent=2) + "\n", encoding="utf-8")
    print(f"[fetch_releases] wrote {len(releases)} releases -> {OUT}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
