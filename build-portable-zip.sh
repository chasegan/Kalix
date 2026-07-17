#!/bin/bash
set -e

# Preflight: python3 is required (used by bump-version.py)
if ! command -v python3 &> /dev/null; then
    echo "ERROR: python3 is required but was not found on PATH"
    exit 1
fi

# Read version from VERSION file
VERSION=$(cat VERSION | tr -d '[:space:]')
echo "========================================"
echo "Building Kalix v${VERSION}"
echo "========================================"

# Sync VERSION into Cargo.toml + python package files
python3 bump-version.py

echo "Building Rust CLI..."
cargo build --release

echo "Building KalixIDE..."
cd kalixide
./gradlew clean --no-daemon
./gradlew assemble jpackageImage --no-daemon
cd ..

echo "Preparing distribution..."
mkdir -p dist

# Detect platform and set appropriate binary name and paths.
# CLI_DEST_SUBDIR is where the CLI binary must live relative to the dist root,
# so it ends up alongside the KalixIDE binary (which expects to find kalix
# in its own folder). jpackage emits platform-conventional layouts, hence
# the per-OS difference.
case "$(uname -s)" in
    MINGW*|MSYS*|CYGWIN*)
        CLI_BINARY="kalix.exe"
        PLATFORM="Windows"
        JPACKAGE_DIR="kalixide/build/jpackage/KalixIDE"
        CLI_DEST_SUBDIR=""
        ;;
    Darwin*)
        CLI_BINARY="kalix"
        PLATFORM="macOS"
        JPACKAGE_DIR="kalixide/build/jpackage/KalixIDE.app"
        CLI_DEST_SUBDIR="Contents/MacOS"
        ;;
    Linux*)
        CLI_BINARY="kalix"
        PLATFORM="Linux"
        JPACKAGE_DIR="kalixide/build/jpackage/KalixIDE"
        CLI_DEST_SUBDIR="bin"
        ;;
    *)
        echo "Unknown platform: $(uname -s)"
        exit 1
        ;;
esac

DIST_FOLDER="dist/KalixIDE-${PLATFORM}-${VERSION}"
# macOS bundles must end in .app for Finder/LaunchServices to recognize them.
if [ "$PLATFORM" = "macOS" ]; then
    DIST_FOLDER="${DIST_FOLDER}.app"
fi
ZIP_NAME="KalixIDE-${PLATFORM}-${VERSION}-Portable.zip"

echo "Preparing KalixIDE distribution..."
rm -rf "${DIST_FOLDER}"
mkdir -p "${DIST_FOLDER}"
# Copy the *contents* of the jpackage output into DIST_FOLDER so the dist folder
# itself is the app image / .app. No `2>/dev/null || cp <dir>` fallback: that would
# silently nest the whole bundle one level down (a broken layout) whenever the glob
# fails. Under `set -e` a failed copy must stop the build loudly instead.
cp -R "${JPACKAGE_DIR}/." "${DIST_FOLDER}/"

# Fail loudly if the jpackage launcher is missing (mirrors the Windows .bat's .exe
# check) so a broken/empty jpackage output never ships as if it succeeded.
case "$PLATFORM" in
    macOS)   LAUNCHER="${DIST_FOLDER}/Contents/MacOS/KalixIDE" ;;
    Linux)   LAUNCHER="${DIST_FOLDER}/bin/KalixIDE" ;;
    Windows) LAUNCHER="${DIST_FOLDER}/KalixIDE.exe" ;;
    *)       LAUNCHER="" ;;
esac
if [ -n "$LAUNCHER" ] && [ ! -e "$LAUNCHER" ]; then
    echo "ERROR: expected launcher missing at ${LAUNCHER}; jpackage output looks broken"
    exit 1
fi

echo "Copying Kalix CLI into distribution..."
CLI_DEST="${DIST_FOLDER}${CLI_DEST_SUBDIR:+/${CLI_DEST_SUBDIR}}"
mkdir -p "${CLI_DEST}"
cp "target/release/${CLI_BINARY}" "${CLI_DEST}/"

# Re-sign the macOS bundle: jpackage signs ad-hoc, but copying kalix into
# Contents/MacOS/ invalidates that seal. Re-sign ad-hoc (-) over the whole
# tree so spctl/Gatekeeper sees a consistent signature.
if [ "$PLATFORM" = "macOS" ]; then
    echo "Re-signing macOS bundle (ad-hoc)..."
    codesign --force --deep --sign - "${DIST_FOLDER}"
fi

echo "Creating KalixIDE zip..."
cd dist
DIST_BASENAME="$(basename "${DIST_FOLDER}")"
if [ "$PLATFORM" = "macOS" ]; then
    # ditto is Apple's archiver for a .app: it preserves symlinks (the jlink
    # runtime ships ~24 under runtime/.../legal), extended attributes, and the
    # ad-hoc code signature -- all of which `zip -r` corrupts by dereferencing
    # symlinks and dropping xattrs. --keepParent keeps the .app as the top entry.
    ditto -c -k --keepParent "${DIST_BASENAME}" "${ZIP_NAME}"
elif command -v zip &> /dev/null; then
    zip -r "${ZIP_NAME}" "${DIST_BASENAME}"
elif command -v powershell &> /dev/null; then
    powershell -Command "Compress-Archive -Path '${DIST_BASENAME}' -DestinationPath '${ZIP_NAME}' -Force"
else
    echo "Warning: No zip utility found, skipping zip creation"
fi
cd ..

echo "========================================"
echo "Build Complete - Kalix v${VERSION}"
echo "========================================"
echo "Portable zip: dist/${ZIP_NAME}"
echo "  Contains: KalixIDE + ${CLI_BINARY}"
ls -lh "dist/${ZIP_NAME}" 2>/dev/null || ls -lh dist/
