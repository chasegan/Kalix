#!/usr/bin/env python3
"""Migrate the Notion export into the docs/ tree as clean Markdown.

One-off / re-runnable. Reads the Notion HTML export under
``ignored/Notion_export/`` and, per the migration map below, writes Markdown into
``website/docs/`` with:

- the page body only (Notion header/properties/cover chrome stripped),
- fenced code blocks with their language preserved,
- Notion equations rendered as inline code (the export carries no LaTeX source),
- per-page images copied into ``docs/assets/<slug>/`` with corrected relative
  paths,
- the Glossary and the Nodes index generated from their CSV database exports.

Nothing is dropped: every source page has a destination (some merged). Pages the
site excludes from the build (research notes) are still written to source.
"""

import csv
import posixpath
import re
import shutil
from pathlib import Path
from urllib.parse import unquote

from bs4 import BeautifulSoup, NavigableString
from markdownify import MarkdownConverter

HERE = Path(__file__).resolve().parent
WEBSITE = HERE.parent
REPO_ROOT = WEBSITE.parent
DOCS = WEBSITE / "docs"
ASSETS = DOCS / "assets"

EXPORT_ROOT = next((REPO_ROOT / "ignored" / "Notion_export").glob("ExportBlock-*/Kalix User Guide"), None)

# destination (relative to docs/) -> [source page name prefixes, in order].
# A dest with multiple sources concatenates them (2nd+ demoted under an H2).
MAP = {
    "getting-started.md": ["Getting Started"],
    "concepts/model-file-structure.md": ["Model File Structure"],
    "concepts/conventions.md": ["Conventions"],
    "concepts/ordering.md": ["Ordering", "How Orders Propagate"],
    "concepts/dynamic-expressions/index.md": ["Dynamic Expressions"],
    "concepts/dynamic-expressions/referencing-input-data.md": ["Referencing Input Data"],
    "concepts/dynamic-expressions/referencing-model-results.md": ["Referencing Model Results"],
    "concepts/dynamic-expressions/simulation-context-vars.md": ["Simulation Context Vars"],
    "concepts/dynamic-expressions/constants.md": ["Constants"],
    "concepts/parameter-types.md": ["Parameter types", "Table parameters"],
    "concepts/data-cache.md": ["Data cache"],
    "concepts/input-data.md": ["Input data", "Declaring Input Data", "Supported Data Formats"],
    "concepts/model-outputs.md": ["Model outputs"],
    "nodes/inflow.md": ["Inflow"],
    "nodes/gr4j.md": ["GR4J"],
    "nodes/sacramento.md": ["Sacramento"],
    "nodes/storage.md": ["Storage"],
    "nodes/storage-tables.md": ["Inverted Pyramid Storage Tables"],
    "nodes/routing.md": ["Routing"],
    "nodes/confluence.md": ["Confluence"],
    "nodes/splitter.md": ["Splitter"],
    "nodes/loss.md": ["Loss"],
    "nodes/gauge.md": ["Gauge"],
    "nodes/regulated-user.md": ["Regulated_User"],
    "nodes/unregulated-user.md": ["Unregulated_User"],
    "nodes/order-control.md": ["Order_Control"],
    "nodes/blackhole.md": ["Blackhole"],
    "using/ide.md": ["Kalix IDE"],
    "using/cli.md": ["Commandline"],
    "using/python.md": ["Python bindings"],
    "using/run-manager.md": ["Run management"],
    "optimisation/index.md": ["Optimisation"],
    "optimisation/objective-functions.md": ["Objective functions"],
    "optimisation/algorithms/sce.md": ["Shuffled Complex Evolution"],
    "optimisation/algorithms/cma-es.md": ["Covariance Matrix Adaptation"],
    "optimisation/algorithms/differential-evolution.md": ["Differential Evolution"],
    "optimisation/algorithms/dream.md": ["Dream"],
    "optimisation/algorithms/sc-sahel.md": ["Shuffled Complex Self Adaptive"],
    "optimisation/reparameterizations.md": ["Reparameterizations"],
    "reference/technical/gr4j.md": ["Technical Reference"],  # resolved specially below
    "developing/start-developing.md": ["Start Developing"],
    "developing/dev-stack.md": ["The Dev Stack"],
    "developing/gory-details/index.md": ["Gory Details"],
    "developing/gory-details/adrs.md": ["Architecture Decision Records"],
    "developing/gory-details/python-api-brainstorm.md": ["Brainstorming the Python API"],
    # tutorials/index.md is a hand-built design page (timeline) — not migrated.
    "tutorials/01-first-model.md": ["Tutorial 1"],
    "tutorials/02-expressions.md": ["Tutorial 2"],
    "tutorials/03-paths.md": ["Tutorial 3"],
    "tutorials/04-commandline.md": ["Tutorial 4"],
    "tutorials/05-python.md": ["Tutorial 5"],
    "tutorials/06-optimisation-cli.md": ["Tutorial 12"],
    "tutorials/07-optimisation-python.md": ["Tutorial 13"],
    # Setup how-tos: live under Docs > Using Kalix (not Tutorials).
    "using/kalix-binary-path.md": ["Specifying the Kalix binary path"],
    "using/notebooks-uv.md": ["Running Python Notebooks"],
}


# Documentation pages live under the /docs/ URL prefix (clean hub URL + plain
# links); tutorials and the top-level pages stay where they are.
def _phys(dest: str) -> str:
    return dest if dest.startswith("tutorials/") else "docs/" + dest


MAP = {_phys(k): v for k, v in MAP.items()}


# Notion page name (lowercased) -> destination .md, for rewriting cross-links.
# A value of None means "unwrap the link to plain text" (dropped/bespoke pages).
NAME_TO_DEST = {}
for _dest, _sources in MAP.items():
    for _s in _sources:
        NAME_TO_DEST[_s.lower()] = _dest
NAME_TO_DEST.update({
    "glossary": "docs/reference/glossary.md",
    "nodes": "docs/nodes/index.md",
    "downloads": "downloads.md",
    "contact details": None,   # bespoke page — keep the text, drop the link
    "contact": None,
    "kalix user guide": None,  # site root
})

# Pages kept in source but excluded from the built site — links to them are dead,
# so unwrap them to plain text (mirrors mkdocs.yml exclude_docs).
EXCLUDED_DESTS = {
    "docs/optimisation/algorithms/sc-sahel.md",
    "docs/optimisation/reparameterizations.md",
    "docs/developing/gory-details/python-api-brainstorm.md",
}

# Title overrides: the "Components" nav pages are titled by their model-file
# section marker rather than their Notion name.
TITLE_OVERRIDE = {
    "docs/concepts/model-file-structure.md": "[kalix]",
    "docs/concepts/input-data.md": "[inputs]",
    "docs/concepts/dynamic-expressions/constants.md": "[constants]",
    "docs/concepts/model-outputs.md": "[outputs]",
}


def _strip_hash_name(fname: str) -> str:
    n = unquote(fname).rsplit("/", 1)[-1]
    n = re.sub(r"\.html$", "", n, flags=re.I)
    n = re.sub(r"\s+[0-9a-f]{32}$", "", n)
    return n.strip()


def _resolve_target(name: str):
    """Return the destination .md for a Notion page name, or None to unwrap."""
    key = name.lower()
    if key in NAME_TO_DEST:
        return NAME_TO_DEST[key]
    for k, v in NAME_TO_DEST.items():  # prefix match (Notion truncates some names)
        if k and (key.startswith(k) or k.startswith(key)):
            return v
    return None


def find_html(prefix: str, subdir: str | None = None):
    """Locate the Notion HTML file whose name starts with `prefix`.

    `subdir` restricts the search to a top-level section folder (prefix-matched,
    since Notion folder names may carry a hash) — used to disambiguate pages that
    share a name (e.g. the GR4J *node* page vs the GR4J *technical reference*).
    """
    root = EXPORT_ROOT
    if subdir is not None:
        cand = sorted(d for d in EXPORT_ROOT.glob(f"{subdir}*") if d.is_dir())
        if cand:
            root = cand[0]
    matches = sorted(root.rglob(f"{prefix}*.html"))
    return matches[0] if matches else None


class NotionConverter(MarkdownConverter):
    """markdownify tuned for Notion: fenced code keeps its language."""

    def convert_pre(self, el, text, *args, **kwargs):
        code = el.find("code")
        lang = ""
        if code and code.get("class"):
            for c in code["class"]:
                if c.startswith("language-"):
                    lang = c[len("language-"):]
        body = el.get_text()
        return f"\n\n```{lang}\n{body.rstrip()}\n```\n\n"


def preprocess_math(soup):
    """Render Notion equations as inline code.

    This Notion export has no LaTeX/MathML annotation (only the rendered
    ``.katex-html``), so faithful LaTeX cannot be recovered. Emit the rendered
    text as inline code (monospace) — honest given the source, and avoids the
    corrupt empty ``$$`` delimiters that pretending it were LaTeX would produce.
    Real equations can be re-authored in ``$...$`` later (MathJax is wired up).
    """
    for eq in soup.select("span.notion-text-equation-token, span.katex"):
        if eq.parent is None:
            continue  # already replaced as a child of an equation token
        txt = re.sub(r"[\u200b\u200c\u200d\ufeff]", "", eq.get_text())  # strip zero-width chars
        txt = re.sub(r"\s+", " ", txt).strip()
        eq.replace_with(NavigableString(f"`{txt}`" if txt else ""))


def process_images(soup, src_dir: Path, slug: str, depth: int):
    """Copy local images into docs/assets/<slug>/ and rewrite their src."""
    prefix = "../" * depth
    dest_dir = ASSETS / slug
    for img in soup.find_all("img"):
        src = img.get("src", "")
        if not src or src.startswith(("http://", "https://", "data:")):
            img.decompose()  # drop Notion icon SVGs / remote assets
            continue
        src_path = (src_dir / unquote(src)).resolve()  # Notion URL-encodes spaces (%20)
        if not src_path.exists():
            img.decompose()
            continue
        dest_dir.mkdir(parents=True, exist_ok=True)
        fname = re.sub(r"[^A-Za-z0-9._-]", "_", src_path.name)
        shutil.copyfile(src_path, dest_dir / fname)
        img["src"] = f"{prefix}assets/{slug}/{fname}"
        img.attrs.pop("srcset", None)
        # Notion wraps images in a zoom <a href="...original...">. glightbox
        # already handles zoom, so unwrap the anchor (its href is un-rewritten).
        if img.parent and img.parent.name == "a":
            img.parent.unwrap()


def rewrite_anchors(body, src_dir: Path, slug: str, depth: int, source_dir: str):
    """Rewrite/copy links in the soup (no regex — handles parens in filenames):
    Notion .html cross-links -> a relative link to the destination .md (resolved
    against the source file's directory, as MkDocs expects); local file
    attachments (PDFs) copied into assets; unresolved links unwrapped to text."""
    prefix = "../" * depth
    dest_dir = ASSETS / slug
    for a in list(body.find_all("a")):
        href = a.get("href", "")
        if not href or href.startswith(("http://", "https://", "mailto:", "#")):
            continue
        raw = unquote(href)
        base, _, frag = raw.partition("#")
        if base.lower().endswith(".html"):
            target = _resolve_target(_strip_hash_name(base))
            if target and target not in EXCLUDED_DESTS:
                rel = posixpath.relpath(target, source_dir or ".")
                a["href"] = rel + (("#" + frag) if frag else "")
            else:
                a.unwrap()  # dropped, bespoke, or build-excluded page
            continue
        p = (src_dir / base).resolve()
        if p.exists() and p.is_file():
            dest_dir.mkdir(parents=True, exist_ok=True)
            fn = re.sub(r"[^A-Za-z0-9._-]", "_", p.name)
            shutil.copyfile(p, dest_dir / fn)
            a["href"] = f"{prefix}assets/{slug}/{fn}"
        else:
            a.unwrap()


def convert_page(html_path: Path, slug: str, depth: int, source_dir: str):
    soup = BeautifulSoup(html_path.read_text(encoding="utf-8", errors="replace"), "lxml")

    title_el = soup.select_one("h1.page-title")
    title = title_el.get_text().strip() if title_el else html_path.stem
    title = re.sub(r"\s+[0-9a-f]{32}$", "", title).strip()

    body = soup.select_one(".page-body") or soup.select_one("article") or soup
    # strip Notion chrome
    for sel in (".page-header-icon", ".page-cover-image", ".properties",
                ".page-description", "header", "style", "script"):
        for el in body.select(sel):
            el.decompose()

    preprocess_math(soup)
    process_images(body, html_path.parent, slug, depth)
    rewrite_anchors(body, html_path.parent, slug, depth, source_dir)

    md = NotionConverter(heading_style="ATX", bullets="-").convert_soup(body)
    md = re.sub(r"\n{3,}", "\n\n", md).strip()
    return title, md


def dest_slug(dest_rel: str) -> str:
    return dest_rel.replace("/", "-").rsplit(".", 1)[0]


def write_page(dest_rel: str, sources: list[str]):
    depth = len(dest_rel.split("/")) - 1
    slug = dest_slug(dest_rel)
    parts = []
    title = None

    for i, name in enumerate(sources):
        subdir = None
        # Technical Reference GR4J/Sacramento live in their own folder
        if dest_rel.startswith("docs/reference/technical/"):
            subdir = "Technical Reference"
            name = {"docs/reference/technical/gr4j.md": "GR4J",
                    "docs/reference/technical/sacramento.md": "Sacramento"}[dest_rel]
        html_path = find_html(name, subdir)
        if not html_path:
            print(f"  ! source not found for '{name}' -> {dest_rel}")
            continue
        t, md = convert_page(html_path, slug, depth, posixpath.dirname(dest_rel))
        if i == 0:
            title = t
            parts.append(md)
        else:
            parts.append(f"\n\n## {t}\n\n{md}")

    if title is None:
        print(f"  ! no content for {dest_rel} (left as stub)")
        return False

    out = DOCS / dest_rel
    out.parent.mkdir(parents=True, exist_ok=True)
    content = "\n".join(parts).strip()  # cross-links already rewritten in the soup
    title = TITLE_OVERRIDE.get(dest_rel, title)
    # Quote the YAML title: some are "[kalix]" etc., and a leading [ is a YAML list.
    front = f'---\ntitle: "{title}"\n---\n\n# {title}\n\n'
    out.write_text(front + content + "\n", encoding="utf-8")
    return True


def gen_glossary():
    gloss = next(EXPORT_ROOT.rglob("Glossary *.csv"), None)
    if not gloss:
        return
    rows = list(csv.DictReader(gloss.read_text(encoding="utf-8").splitlines()))
    lines = ["---", "title: Glossary", "---", "", "# Glossary", "",
             "Key terms used across Kalix.", "", "| Term | Description |", "| --- | --- |"]
    for r in rows:
        term = (r.get("Term") or "").strip()
        desc = (r.get("Description") or "").strip().replace("\n", " ")
        if term:
            lines.append(f"| {term} | {desc} |")
    (DOCS / "docs" / "reference" / "glossary.md").write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(f"  glossary: {len(rows)} terms")


def gen_nodes_index():
    csv_path = next(EXPORT_ROOT.rglob("All node types *.csv"), None)
    node_links = {
        "GR4J": "gr4j", "Sacramento": "sacramento", "Inflow": "inflow", "Storage": "storage",
        "Confluence": "confluence", "Splitter": "splitter", "Regulated_User": "regulated-user",
        "Unregulated_User": "unregulated-user", "Blackhole": "blackhole", "Routing": "routing",
        "Loss": "loss", "Gauge": "gauge", "Order_Control": "order-control",
    }
    lines = ["---", 'title: "[node.*]"', "---", "", "# [node.*]", "",
             "Nodes are the active elements of a Kalix model — lumped river processes that "
             "modify flow. Links pass water between them.", "", "| Node | |", "| --- | --- |"]
    names = []
    if csv_path:
        rows = list(csv.DictReader(csv_path.read_text(encoding="utf-8").splitlines()))
        names = [(r.get("Name") or "").strip() for r in rows if (r.get("Name") or "").strip()]
    if not names:
        names = list(node_links)
    for n in names:
        slug = node_links.get(n)
        label = n.replace("_", " ")
        lines.append(f"| [{label}]({slug}/) | |" if slug else f"| {label} | |")
    (DOCS / "docs" / "nodes" / "index.md").write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(f"  nodes index: {len(names)} node types")


def main() -> int:
    if EXPORT_ROOT is None or not EXPORT_ROOT.exists():
        print("Notion export not found under ignored/Notion_export/.")
        return 1
    print(f"Export root: {EXPORT_ROOT}")
    ok = 0
    for dest_rel, sources in MAP.items():
        if write_page(dest_rel, sources):
            ok += 1
    # Sacramento technical ref (second reference/technical page)
    if write_page("docs/reference/technical/sacramento.md", ["Sacramento"]):
        ok += 1
    print(f"converted {ok} pages")
    gen_glossary()
    gen_nodes_index()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
