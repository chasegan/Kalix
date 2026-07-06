#!/usr/bin/env bash
# Full local build of the Kalix website.
#
# Runs the version-dependent generators, then (only if the Notion export is
# present locally — it is gitignored) the content migration, then mkdocs build.
# CI does NOT use this script: it runs the generators + mkdocs build directly and
# never migrates (the Notion source is absent in CI). See
# .github/workflows/deploy-website.yml.
set -euo pipefail

cd "$(dirname "$0")/.."   # -> website/
PY=".venv/bin/python"
[ -x "$PY" ] || PY="python3"
MKDOCS=".venv/bin/mkdocs"
[ -x "$MKDOCS" ] || MKDOCS="mkdocs"

echo "==> Generating version-dependent data"
"$PY" scripts/fetch_releases.py || echo "  (releases fetch failed; using committed fallback)"
"$PY" scripts/gen_health.py
"$PY" scripts/gen_speed_plot.py

if ls ../ignored/Notion_export/ExportBlock-* >/dev/null 2>&1; then
  echo "==> Migrating Notion content (local export found)"
  "$PY" scripts/migrate_notion.py
else
  echo "==> Skipping Notion migration (export not present — using committed Markdown)"
fi

echo "==> Building site"
"$MKDOCS" build --clean
echo "==> Done: website/site/"
