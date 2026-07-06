# kalixproject.org — website

Static site for Kalix: a **bespoke HTML landing/contact** plus **MkDocs Material**
documentation, deployed to GitHub Pages. Light mode only. Built from the design
assets in [`../docs/web/style-guide/demo-pages/`](../docs/web/style-guide/demo-pages/).
The documentation Markdown (originally migrated from Notion) is now committed and
maintained directly.

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
    docs/index.md, tutorials/index.md, downloads.md, code/index.md   design-layout pages
    docs/**/*.md        documentation (Markdown)
    stylesheets/        tokens.css (copied from the design at build) + extra.css
    assets/             images (landing + doc screenshots)
    CNAME               custom domain
  data/
    releases/*.md       one Markdown file per release (aggregated by the Downloads page)
    health.json         regression-model health (generated)
  scripts/
    gen_health.py       regression_tests/simulations/verify_all_models_log.txt -> health.json
    gen_speed_plot.py   first figure of speed_results_analysis.ipynb -> assets/speed-plot.png
    gen_release_stub.py writes data/releases/<version>.md for a new release
    build.sh            full local build (generators + mkdocs build)
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
- **Version comes from the repo `VERSION` file.** `latest_version` (landing hero,
  Downloads "Latest") reads it directly, and a build auto-stubs a matching
  `data/releases/<version>.md` if one doesn't exist yet.
- **Releases are per-file Markdown.** Each `data/releases/<version>.md` carries
  front-matter (asset URLs, pip) and a Markdown notes body; the Downloads page
  aggregates them. Old releases (pre-GitHub) just hold their own URLs.
- **The Code page's project-health and speed plot regenerate at build** from the
  committed regression data (see the scripts above).

## Deploy

`.github/workflows/deploy-website.yml` builds and deploys to GitHub Pages on
push to `main` (or via *Run workflow*). To go live, the repo owner must:

1. **Settings → Pages → Source: GitHub Actions.**
2. Add the DNS records for `kalixproject.org` (see the handover / commit notes).
3. Merge this branch to `main` (or run the workflow manually).

Nothing here pushes, changes settings, or registers a domain.
