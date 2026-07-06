# kalixproject.org — website

Static site for Kalix: a **bespoke HTML landing/contact** plus **MkDocs Material**
documentation, deployed to GitHub Pages. Light mode only. Built from the design
assets in [`../docs/web/style-guide/demo-pages/`](../docs/web/style-guide/demo-pages/)
and the Notion content export.

## Layout

```
website/
  mkdocs.yml            site config, nav (the information architecture)
  main.py               mkdocs-macros: syncs tokens.css, exposes release/health data,
                          substitutes %%LATEST_VERSION%% into bespoke pages
  overrides/main.html   custom light top-nav (matches the landing) on Material pages
  docs/
    index.html          bespoke landing page (pass-through, bypasses the theme)
    contact/index.html  bespoke contact page (pass-through)
    docs_home.md, tutorials/index.md, downloads.md, code.md   design-layout pages
    <sections>/*.md     migrated documentation
    stylesheets/        tokens.css (copied from the design at build) + extra.css
    assets/             images (landing + migrated screenshots)
    CNAME               custom domain
  data/
    releases.json       release data (committed fallback; refreshed from GitHub API)
    health.json         regression-model health (generated)
  scripts/
    fetch_releases.py   GitHub Releases API -> data/releases.json
    gen_health.py       regression_tests/simulations/verify_all_models_log.txt -> health.json
    gen_speed_plot.py   first figure of speed_results_analysis.ipynb -> assets/speed-plot.png
    migrate_notion.py   Notion HTML export -> docs/*.md (one-off, local only)
    build.sh            full local build (generators + migration + mkdocs build)
```

## Build & preview

```bash
python3 -m venv .venv && .venv/bin/pip install -r requirements.txt
.venv/bin/mkdocs serve            # preview at http://localhost:8000
scripts/build.sh                  # full build into site/
```

## Key decisions (why it's built this way)

- **`tokens.css` is the single styling source of truth.** The canonical file is
  the design one under `docs/web/style-guide/demo-pages/`; `main.py` copies it into
  `docs/stylesheets/` on every build. `extra.css` only consumes `--kx-*` tokens.
- **Two fidelity tiers.** Landing + Contact are bespoke pass-through HTML (near
  pixel-faithful). Docs/Tutorials are Material Markdown carrying the design via
  custom token-styled HTML where the design implies a custom layout (Docs spine,
  Tutorials timeline, Downloads/Code). Raw-HTML links use directory URLs
  (`page/`) — MkDocs only rewrites `.md` links in Markdown syntax.
- **Version-dependent content is generated, never hand-edited.** A release just
  needs a GitHub release cut: the Downloads list, `latest_version` (landing hero),
  and — from committed regression data — the Code page's project-health and speed
  plot all regenerate at build. See the scripts above.
- **The Notion migration is a one-off local step.** Its source is gitignored, so
  the migrated Markdown is committed and CI never re-migrates.

## Deploy

`.github/workflows/deploy-website.yml` builds and deploys to GitHub Pages on
push to `main` (or via *Run workflow*). To go live, the repo owner must:

1. **Settings → Pages → Source: GitHub Actions.**
2. Add the DNS records for `kalixproject.org` (see the handover / commit notes).
3. Merge this branch to `main` (or run the workflow manually).

CI uses the Actions-provided `GITHUB_TOKEN` for the Releases API (authenticated
rate limit). Nothing here pushes, changes settings, or registers a domain.
