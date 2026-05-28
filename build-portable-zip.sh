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
cp -r "${JPACKAGE_DIR}"/* "${DIST_FOLDER}/" 2>/dev/null || cp -r "${JPACKAGE_DIR}" "${DIST_FOLDER}/"

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
if command -v zip &> /dev/null; then
    zip -r "${ZIP_NAME}" "$(basename "${DIST_FOLDER}")"
elif command -v powershell &> /dev/null; then
    powershell -Command "Compress-Archive -Path '$(basename "${DIST_FOLDER}")' -DestinationPath '${ZIP_NAME}' -Force"
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
