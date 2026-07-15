#!/usr/bin/env bash
set -euo pipefail

# ---------------------------------------------------------------------------
# make-icons.sh — regenerate EVERY Kalix icon artifact from the master SVG.
#
# One source of truth (graphics/icons/kalix_icon.svg) → every consumed icon:
#   • KalixIDE runtime window/taskbar PNG set   (IconManager.java)
#   • Windows launcher .ico                      (jpackage --icon, Windows)
#   • macOS launcher .icns                       (proper multi-res; see NOTE)
#   • Website favicon.png + apple-touch + PWA    (website/docs/assets)
#
# Run this after editing the master art so nothing drifts. The two .ico files
# that had already diverged (graphics/ vs kalixide/resources/) is exactly the
# failure mode this script exists to prevent: from now on, regenerate — never
# hand-edit — the outputs below.
#
# Requires: rsvg-convert (librsvg), ImageMagick (magick), and — for the .icns —
# iconutil (ships with macOS). Install the first two with:
#   brew install librsvg imagemagick
#
# Usage:  ./make-icons.sh            # regenerate everything it can on this OS
# Safe & idempotent: it only overwrites git-tracked outputs, so `git diff`
# shows precisely what changed and `git checkout` reverts it.
# ---------------------------------------------------------------------------

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# ---- Sources -------------------------------------------------------------
GLYPH_SVG="$REPO_ROOT/graphics/icons/kalix_icon_solid_white_bg.svg"  # master app glyph (white rounded tile)
FAVICON_SVG="$REPO_ROOT/website/docs/assets/favicon.svg"   # adaptive web favicon (its own art)

# ---- Output locations ----------------------------------------------------
IDE_ICONS="$REPO_ROOT/kalixide/src/main/resources/icons"   # consumed by the IDE build
WEB_ASSETS="$REPO_ROOT/website/docs/assets"                # consumed by MkDocs

# ---- Size policy ---------------------------------------------------------
# Runtime set: what IconManager loads + what jpackage takes on macOS/Linux.
RUNTIME_SIZES=(16 24 32 48 64 128 256 512 1024)
# Windows .ico frames (16/24/32/48 as bitmaps, 256 auto-stored as PNG by magick).
ICO_SIZES=(16 24 32 48 256)
# apple-touch-icon must be OPAQUE — iOS renders transparency as black. Flatten
# onto this background. Set it to the brand tile colour once that's decided.
APPLE_TOUCH_BG="#ffffff"
# Rasterised size of the PNG favicon fallback (for browsers without SVG favicons).
FAVICON_PNG_SIZE=96

# --------------------------------------------------------------------------

log()  { printf '\033[1;34m▸\033[0m %s\n' "$*"; }
ok()   { printf '  \033[1;32m✓\033[0m %s\n' "$*"; }
warn() { printf '  \033[1;33m!\033[0m %s\n' "$*" >&2; }
die()  { printf '\033[1;31m✗ %s\033[0m\n' "$*" >&2; exit 1; }

need() { command -v "$1" >/dev/null 2>&1 || die "'$1' not found on PATH. $2"; }

need rsvg-convert "Install with: brew install librsvg"
need magick       "Install with: brew install imagemagick"
[[ -f "$GLYPH_SVG" ]] || die "Master glyph not found: $GLYPH_SVG"

# iconutil is macOS-only; the .icns step is skipped elsewhere (with a warning).
HAVE_ICONUTIL=0
command -v iconutil >/dev/null 2>&1 && HAVE_ICONUTIL=1

TMP="$(mktemp -d "${TMPDIR:-/tmp}/kalix-icons.XXXXXX")"
trap 'rm -rf "$TMP"' EXIT

# render <svg> <size> <out.png>   — rasterise at native size (no upscaling).
render() { rsvg-convert -w "$2" -h "$2" -o "$3" "$1"; }

# Every unique glyph size we need across all outputs, rendered once from the
# vector so each artifact gets a crisp native raster (never a downscaled one).
GLYPH_SIZES=$(printf '%s\n' "${RUNTIME_SIZES[@]}" "${ICO_SIZES[@]}" 16 32 64 128 256 512 1024 180 192 \
              | sort -nu)

log "Rasterising master glyph from $(basename "$GLYPH_SVG")"
for s in $GLYPH_SIZES; do
    render "$GLYPH_SVG" "$s" "$TMP/glyph-$s.png"
done
ok "rendered sizes: $(echo $GLYPH_SIZES | tr '\n' ' ')"

# --------------------------------------------------------------------------
# 1. KalixIDE runtime PNG set  (IconManager loads /icons/kalix-<n>.png)
# --------------------------------------------------------------------------
log "Writing IDE runtime PNG set → ${IDE_ICONS#$REPO_ROOT/}/"
mkdir -p "$IDE_ICONS"
for s in "${RUNTIME_SIZES[@]}"; do
    cp "$TMP/glyph-$s.png" "$IDE_ICONS/kalix-$s.png"
done
ok "kalix-{$(IFS=,; echo "${RUNTIME_SIZES[*]}")}.png"

# --------------------------------------------------------------------------
# 2. Windows launcher .ico  (build.gradle.kts passes this to jpackage)
# --------------------------------------------------------------------------
log "Assembling Windows .ico"
ico_inputs=()
for s in "${ICO_SIZES[@]}"; do ico_inputs+=("$TMP/glyph-$s.png"); done
magick "${ico_inputs[@]}" "$IDE_ICONS/kalix.ico"
ok "kalix.ico (frames: $(IFS=,; echo "${ICO_SIZES[*]}"))"

# --------------------------------------------------------------------------
# 3. macOS launcher .icns  (proper multi-res, incl. @2x retina tiers)
# --------------------------------------------------------------------------
if [[ $HAVE_ICONUTIL -eq 1 ]]; then
    log "Assembling macOS .icns via iconutil"
    ISET="$TMP/kalix.iconset"; mkdir -p "$ISET"
    #        iconset name              source px
    cp "$TMP/glyph-16.png"   "$ISET/icon_16x16.png"
    cp "$TMP/glyph-32.png"   "$ISET/icon_16x16@2x.png"
    cp "$TMP/glyph-32.png"   "$ISET/icon_32x32.png"
    cp "$TMP/glyph-64.png"   "$ISET/icon_32x32@2x.png"
    cp "$TMP/glyph-128.png"  "$ISET/icon_128x128.png"
    cp "$TMP/glyph-256.png"  "$ISET/icon_128x128@2x.png"
    cp "$TMP/glyph-256.png"  "$ISET/icon_256x256.png"
    cp "$TMP/glyph-512.png"  "$ISET/icon_256x256@2x.png"
    cp "$TMP/glyph-512.png"  "$ISET/icon_512x512.png"
    cp "$TMP/glyph-1024.png" "$ISET/icon_512x512@2x.png"
    iconutil -c icns "$ISET" -o "$IDE_ICONS/kalix.icns"
    ok "kalix.icns (16→1024 incl. retina)"
else
    warn "iconutil not found (macOS only) — skipped kalix.icns"
fi

# --------------------------------------------------------------------------
# 4. Website icons  → website/docs/assets/
# --------------------------------------------------------------------------
if [[ -d "$WEB_ASSETS" ]]; then
    log "Writing website icons → ${WEB_ASSETS#$REPO_ROOT/}/"

    # favicon.png fallback — derived from the adaptive favicon.svg so it MATCHES
    # what the SVG favicon shows (light-scheme rendering), not the app glyph.
    if [[ -f "$FAVICON_SVG" ]]; then
        render "$FAVICON_SVG" "$FAVICON_PNG_SIZE" "$WEB_ASSETS/favicon.png"
        ok "favicon.png (${FAVICON_PNG_SIZE}px, from favicon.svg)"
    else
        warn "favicon.svg missing — skipped favicon.png"
    fi

    # apple-touch-icon (180) — opaque, from the app glyph.
    magick "$TMP/glyph-180.png" -background "$APPLE_TOUCH_BG" -flatten \
        "$WEB_ASSETS/apple-touch-icon.png"
    ok "apple-touch-icon.png (180px, opaque $APPLE_TOUCH_BG)"

    # PWA / maskable icons (192, 512) — from the app glyph.
    cp "$TMP/glyph-192.png" "$WEB_ASSETS/icon-192.png"
    cp "$TMP/glyph-512.png" "$WEB_ASSETS/icon-512.png"
    ok "icon-192.png, icon-512.png"
else
    warn "website assets dir missing — skipped web icons"
fi

# --------------------------------------------------------------------------
# Optional lossless squeeze if a PNG optimiser is installed (purely a size win).
# --------------------------------------------------------------------------
if command -v oxipng >/dev/null 2>&1; then
    log "Optimising PNGs with oxipng"
    oxipng -q -o 4 --strip safe "$IDE_ICONS"/kalix-*.png "$WEB_ASSETS"/*.png 2>/dev/null || true
    ok "oxipng pass complete"
fi

echo
log "Done. Review with:  git diff --stat"
cat <<'NOTE'

  Follow-ups (one-time wiring, not handled by this script):
    • macOS build still points jpackage at kalix-256.png. To use the richer
      .icns, change the non-Windows branch in kalixide/build.gradle.kts to
      file("src/main/resources/icons/kalix.icns").
    • The web extras (apple-touch-icon / icon-192 / icon-512) are generated but
      not yet referenced. Add the <link> tags + a manifest.webmanifest in
      website/overrides/main.html to activate them.
    • graphics/icons/exported-{transparent,white}/ are now redundant reference
      exports — retire them or regenerate from the same master to avoid drift.
NOTE
