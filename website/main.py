"""mkdocs-macros module for the Kalix website build.

Jobs:

1. Keep ``tokens.css`` a single source of truth. The canonical design-token file
   lives with the design assets (``docs/web/style-guide/demo-pages/tokens.css``);
   this copies it into the site's ``docs/stylesheets/`` on every build so the
   Material theme and the bespoke pages always share one palette.

2. Expose the current version (from the repo ``VERSION`` file, maintained by
   bump-version.py) to pages, and render the Downloads page from per-release
   markdown files in ``data/releases/`` — one file per release, front-matter for
   the mechanical bits (URLs/pip) and a markdown body for the notes.

3. Auto-stub a release file for the current version if one doesn't exist yet, so
   a new release is never silently missing.
"""

import datetime
import json
from pathlib import Path

import yaml

HERE = Path(__file__).parent
REPO_ROOT = HERE.parent
CANONICAL_TOKENS = REPO_ROOT / "docs" / "web" / "style-guide" / "demo-pages" / "tokens.css"
TOKENS_DEST = HERE / "docs" / "stylesheets" / "tokens.css"
VERSION_FILE = REPO_ROOT / "VERSION"
RELEASES_DIR = HERE / "data" / "releases"
HEALTH_DATA = HERE / "data" / "health.json"

# New releases are built on GitHub with assets following this pattern. macOS is
# not built yet, so auto-stubs pre-fill only Windows and Linux.
ASSET_URL = ("https://github.com/chasegan/Kalix/releases/download/"
             "v{v}/KalixIDE-{plat}-{v}-Portable.zip")
STUB_PLATFORMS = ("Windows", "Linux")
DL_LABELS = (("windows", "Windows"), ("macos", "macOS"),
             ("linux", "Linux"), ("docs", "Docs"))


def _sync_tokens() -> None:
    """Copy the canonical tokens.css into the docs tree (single source of truth).

    Only writes when the content actually differs. The destination lives inside
    docs_dir, which `mkdocs serve` watches — rewriting it every build (even with
    identical bytes) would churn the mtime and trigger an endless rebuild/reload
    loop. The content-compare guard breaks that loop.
    """
    if not CANONICAL_TOKENS.exists():
        return
    new = CANONICAL_TOKENS.read_bytes()
    if TOKENS_DEST.exists() and TOKENS_DEST.read_bytes() == new:
        return
    TOKENS_DEST.parent.mkdir(parents=True, exist_ok=True)
    TOKENS_DEST.write_bytes(new)


def _current_version() -> str:
    """Current release version (no leading 'v'), from the repo VERSION file."""
    try:
        return VERSION_FILE.read_text().strip() or "0.0.0"
    except OSError:
        return "0.0.0"


def _stub_release(version: str) -> bool:
    """Write a skeleton ``data/releases/<version>.md`` if none exists.

    Assets are pre-filled with the standard GitHub pattern (Windows + Linux); the
    maintainer then writes the notes (and adds macOS / fixes URLs if needed).
    Returns True if a stub was created.
    """
    dest = RELEASES_DIR / f"{version}.md"
    if dest.exists():
        return False
    RELEASES_DIR.mkdir(parents=True, exist_ok=True)
    assets = "".join(
        f'  {plat.lower()}: "{ASSET_URL.format(v=version, plat=plat)}"\n'
        for plat in STUB_PLATFORMS
    )
    dest.write_text(
        "---\n"
        f'version: "{version}"\n'
        f"date: {datetime.date.today().isoformat()}\n"
        "prerelease: false\n"
        f"assets:\n{assets}"
        f'pip: "pip install kalix=={version}"\n'
        "---\n\n"
        f"<!-- TODO: write the release notes for v{version} -->\n",
        encoding="utf-8",
    )
    return True


def _version_key(v):
    return tuple(int(p) if p.isdigit() else 0 for p in str(v).split("."))


def _load_release_files():
    """(front-matter, body) for every data/releases/*.md, newest version first."""
    records = []
    if RELEASES_DIR.exists():
        for f in RELEASES_DIR.glob("*.md"):
            text = f.read_text(encoding="utf-8")
            if not text.startswith("---"):
                continue
            _, fm_raw, body = text.split("---", 2)
            fm = yaml.safe_load(fm_raw) or {}
            if "version" in fm:
                records.append((fm, body.strip()))
    records.sort(key=lambda r: _version_key(r[0]["version"]), reverse=True)
    return records


def _render_release(fm, body, is_latest) -> str:
    ver = str(fm["version"])
    pills = []
    if is_latest:
        pills.append('<span class="kx-pill kx-pill--accent">Latest</span>')
    if fm.get("prerelease"):
        pills.append('<span class="kx-pill">Pre-release</span>')
    links = []
    for key, label in DL_LABELS:
        url = (fm.get("assets") or {}).get(key)
        if not url:
            continue
        fname = str(url).rsplit("/", 1)[-1]
        ext = fname.rsplit(".", 1)[-1] if "." in fname else ""
        links.append(f'<a href="{url}">{label}&nbsp;.{ext}</a>' if ext
                     else f'<a href="{url}">{label}</a>')
    parts = [
        '<div class="kx-release" markdown>', "",
        f"## v{ver}", "",
        (f'<div class="kx-release-head">'
         f'<span class="kx-release-date">{str(fm.get("date", ""))}</span>'
         + "".join(pills) + "</div>"), "",
    ]
    if body:
        parts += [body, ""]
    if links:
        parts += ['<div class="kx-release-dl">' + "".join(links) + "</div>", ""]
    if fm.get("pip"):
        parts += [f'<code class="kx-release-pip">{fm["pip"]}</code>', ""]
    parts.append("</div>")
    return "\n".join(parts)


def define_env(env):
    _sync_tokens()

    version = _current_version()
    _stub_release(version)  # ensure a release file exists for the current version

    health = {}
    if HEALTH_DATA.exists():
        try:
            health = json.loads(HEALTH_DATA.read_text())
        except json.JSONDecodeError:
            health = {}

    # Available in any page as {{ latest_version }} / {{ health }}.
    env.variables["latest_version"] = version
    env.variables["health"] = health
    define_env._latest = version

    @env.macro
    def latest_version_str():
        return version

    @env.macro
    def render_releases():
        records = _load_release_files()
        latest = records[0][0]["version"] if records else None
        return "\n\n".join(
            _render_release(fm, body, str(fm["version"]) == str(latest))
            for fm, body in records
        )


def on_post_build(env):
    """Substitute %%LATEST_VERSION%% in built pages that macros doesn't render.

    Bespoke pass-through HTML (the landing page, etc.) isn't processed by the
    macros/Jinja pipeline, so version references there use a plain placeholder
    that we replace here, once, against the built output.
    """
    latest = getattr(define_env, "_latest", "0.0.0")
    site_dir = Path(env.conf["site_dir"])
    for html in site_dir.rglob("*.html"):
        text = html.read_text(encoding="utf-8")
        if "%%LATEST_VERSION%%" in text:
            html.write_text(text.replace("%%LATEST_VERSION%%", latest), encoding="utf-8")
