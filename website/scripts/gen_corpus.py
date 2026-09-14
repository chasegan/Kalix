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

# Sections worth answering questions from. The landing page and internal notes
# are excluded.
INCLUDE = ("docs", "tutorials", "code")

# Top-level pages that are not documentation but that users ask the assistant
# about. Without these the assistant still answers "where do I download Kalix?"
# and "how do I get support?" — but, having no <page url> to anchor on, it
# invents the address from the .md source links in the docs body and emits a
# 404 (kalix.org/downloads.md). Including them supplies the real URL.
INCLUDE_PAGES = ("contact.md", "downloads.md")

FRONT_MATTER = re.compile(r"\A---\n.*?\n---\n", re.DOTALL)

# Email addresses are obfuscated in the page source so they never appear in the
# raw HTML; docs/javascripts/cf-email.js decodes them in the browser. The
# assistant has no browser, so decode them here too — otherwise it sees only
# "[email protected]" placeholders and invents an address when asked how to
# reach the team. Same XOR scheme as cf-email.js: the first hex byte is the key.
CF_EMAIL_LINK = re.compile(
    r'<a href="#"((?: [a-z-]+="[^"]*")*)>'
    r'<span class="__cf_email__" data-cfemail="([0-9a-f]+)">'
    r'\[email(?:&#160;|&nbsp;| )protected\]</span></a>'
)
CF_EMAIL_SPAN = re.compile(
    r'<span class="__cf_email__" data-cfemail="([0-9a-f]+)">.*?</span>'
)


def cf_decode(hex_str: str) -> str:
    """Reverse cf-email.js's obfuscation: XOR every byte with the first."""
    raw = bytes.fromhex(hex_str)
    key = raw[0]
    return "".join(chr(b ^ key) for b in raw[1:])


def reveal_emails(body: str) -> str:
    """Replace obfuscated address markup with the plain address."""
    body = CF_EMAIL_LINK.sub(
        lambda m: f'<a href="mailto:{cf_decode(m.group(2))}"{m.group(1)}>'
                  f'{cf_decode(m.group(2))}</a>',
        body,
    )
    return CF_EMAIL_SPAN.sub(lambda m: cf_decode(m.group(1)), body)


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
    pages += [DOCS / name for name in INCLUDE_PAGES if (DOCS / name).is_file()]
    parts = []
    for md in pages:
        body = FRONT_MATTER.sub("", md.read_text(encoding="utf-8")).strip()
        body = reveal_emails(body)
        if body:
            parts.append(f"<page url=\"{page_url(md)}\">\n{body}\n</page>")

    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text("\n\n".join(parts) + "\n", encoding="utf-8")
    print(f"wrote {OUT.relative_to(WEBSITE)}: {len(parts)} pages, {OUT.stat().st_size:,} bytes")


if __name__ == "__main__":
    main()
