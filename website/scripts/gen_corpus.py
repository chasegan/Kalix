"""Concatenate the documentation into one text file for the help assistant.

Writes docs/llm/corpus.txt, which MkDocs copies into the built site at
/llm/corpus.txt. The Cloudflare Worker at api.kalix.org fetches that URL and
places it in the model's cached system prompt, so the assistant always answers
from the docs as last published. Run before `mkdocs build`, like gen_health.py.
"""

from __future__ import annotations

import re
from pathlib import Path

WEBSITE = Path(__file__).resolve().parent.parent
DOCS = WEBSITE / "docs"
OUT = DOCS / "llm" / "corpus.txt"

# Sections worth answering questions from. Everything else (landing page,
# contact, downloads, internal notes) is excluded.
INCLUDE = ("docs", "tutorials", "code")

FRONT_MATTER = re.compile(r"\A---\n.*?\n---\n", re.DOTALL)


def page_url(md: Path) -> str:
    """Site URL for a Markdown source file, following MkDocs' directory URLs."""
    rel = md.relative_to(DOCS).with_suffix("")
    if rel.name == "index":
        rel = rel.parent
    return f"https://kalix.org/{rel.as_posix()}/".replace("//", "/").replace("https:/", "https://")


def main() -> None:
    pages = sorted(
        md for section in INCLUDE for md in (DOCS / section).rglob("*.md")
    )
    parts = []
    for md in pages:
        body = FRONT_MATTER.sub("", md.read_text(encoding="utf-8")).strip()
        if body:
            parts.append(f"<page url=\"{page_url(md)}\">\n{body}\n</page>")

    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text("\n\n".join(parts) + "\n", encoding="utf-8")
    print(f"wrote {OUT.relative_to(WEBSITE)}: {len(parts)} pages, {OUT.stat().st_size:,} bytes")


if __name__ == "__main__":
    main()
