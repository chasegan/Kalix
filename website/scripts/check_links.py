#!/usr/bin/env python3
"""Verify every internal link in the built site resolves to a real file.

MkDocs' ``validation.links`` only inspects Markdown-syntax links, so raw HTML
anchors (the Docs-hub tiles, cards and tree navigation) get no protection from
``mkdocs build --strict``. This script closes that gap: it parses every built
HTML page in ``website/site/`` and checks each internal ``href``/``src`` —
however it was authored — against the built file tree.

Run after ``mkdocs build``. Exits non-zero if any link is broken, so CI fails
the deploy rather than shipping a 404.
"""

import os
import sys
from html.parser import HTMLParser
from pathlib import Path
from urllib.parse import unquote, urlparse

HERE = Path(__file__).resolve().parent
SITE = HERE.parent / "site"


class LinkCollector(HTMLParser):
    """Collect href/src attribute values from one page."""

    def __init__(self) -> None:
        super().__init__()
        self.urls: list[str] = []

    def handle_starttag(self, tag: str, attrs: list) -> None:
        for name, value in attrs:
            if name in ("href", "src") and value:
                self.urls.append(value)


def check_url(page: Path, url: str) -> str | None:
    """Return a reason string if ``url`` on ``page`` is broken, else None."""
    parsed = urlparse(url)
    if parsed.scheme or parsed.netloc:
        return None  # external — out of scope
    path = unquote(parsed.path)
    if not path:
        return None  # pure fragment / query
    if path.startswith("/"):
        target = (SITE / path.lstrip("/")).resolve()
    else:
        target = (page.parent / path).resolve()
    if SITE not in (target, *target.parents):
        return "escapes site root"
    if target.is_dir():
        return None if (target / "index.html").exists() else "dir without index.html"
    return None if target.exists() else "missing"


def main() -> int:
    if not SITE.is_dir():
        print(f"[check_links] {SITE} not found — run mkdocs build first.")
        return 1

    pages = sorted(SITE.rglob("*.html"))
    broken: list[tuple[Path, str, str]] = []
    for page in pages:
        collector = LinkCollector()
        collector.feed(page.read_text(encoding="utf-8", errors="replace"))
        for url in collector.urls:
            reason = check_url(page, url)
            if reason:
                broken.append((page, url, reason))

    for page, url, reason in broken:
        print(f"BROKEN [{reason}] {page.relative_to(SITE)} -> {url}")
    print(f"[check_links] {len(pages)} pages checked, {len(broken)} broken links.")
    return 1 if broken else 0


if __name__ == "__main__":
    sys.exit(main())
