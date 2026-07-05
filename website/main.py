"""mkdocs-macros module for the Kalix website build.

Two jobs:

1. Keep ``tokens.css`` a single source of truth. The canonical design-token file
   lives with the design assets (``docs/web/style-guide/demo-pages/tokens.css``);
   this copies it into the site's ``docs/stylesheets/`` on every build so the
   Material theme and the bespoke pages always share one palette.

2. Expose version-dependent values (latest release + the full release list) to
   pages via macros, so a release needs no manual site edits. These are populated
   at build time from the GitHub Releases API (scripts/fetch_releases.py, wired in
   a later phase) and fall back to the committed ``data/releases.json``.
"""

import json
import shutil
from pathlib import Path

HERE = Path(__file__).parent
REPO_ROOT = HERE.parent
CANONICAL_TOKENS = REPO_ROOT / "docs" / "web" / "style-guide" / "demo-pages" / "tokens.css"
TOKENS_DEST = HERE / "docs" / "stylesheets" / "tokens.css"
RELEASES_FALLBACK = HERE / "data" / "releases.json"


def _sync_tokens() -> None:
    """Copy the canonical tokens.css into the docs tree (single source of truth)."""
    if CANONICAL_TOKENS.exists():
        TOKENS_DEST.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(CANONICAL_TOKENS, TOKENS_DEST)


def _load_releases() -> list:
    """Release records, newest first. Build-time fetch writes this file; otherwise
    the committed fallback is used so local/offline builds still work."""
    if RELEASES_FALLBACK.exists():
        try:
            return json.loads(RELEASES_FALLBACK.read_text())
        except json.JSONDecodeError:
            return []
    return []


def define_env(env):
    _sync_tokens()

    releases = _load_releases()
    latest = releases[0]["version"] if releases else "0.0.0"

    # Available in any page as {{ latest_version }} / {{ releases }}.
    env.variables["latest_version"] = latest
    env.variables["releases"] = releases

    @env.macro
    def latest_version_str():
        return latest

    # Remember the value for the post-build substitution below.
    define_env._latest = latest


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
