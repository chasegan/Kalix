#!/usr/bin/env bash
# Full local build of the Kalix website: regenerate the version-dependent data
# (project-health + speed plot from the committed regression data), then build.
# All content is committed Markdown; there is no content-migration step.
set -euo pipefail

cd "$(dirname "$0")/.."   # -> website/
PY=".venv/bin/python"
[ -x "$PY" ] || PY="python3"
MKDOCS=".venv/bin/mkdocs"
[ -x "$MKDOCS" ] || MKDOCS="mkdocs"

echo "==> Generating version-dependent data"
"$PY" scripts/gen_health.py
"$PY" scripts/gen_speed_plot.py

echo "==> Building site"
"$MKDOCS" build --clean --strict

echo "==> Checking internal links"
"$PY" scripts/check_links.py
echo "==> Done: website/site/"
