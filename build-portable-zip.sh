#!/bin/bash
set -e

# Read version from VERSION file
VERSION=$(cat VERSION | tr -d '[:space:]')
echo "========================================"
echo "Building Kalix v${VERSION}"
echo "========================================"

echo "Building Rust CLI..."
cargo build --release

echo "Building KalixIDE..."
cd kalixide
./gradlew clean --no-daemon
./gradlew build jpackageImage --no-daemon
cd ..

echo "Preparing distribution..."
mkdir -p dist

# Detect platform and set appropriate binary name and paths
case "$(uname -s)" in
    MINGW*|MSYS*|CYGWIN*)
        CLI_BINARY="kalix.exe"
        PLATFORM="Windows"
        JPACKAGE_DIR="kalixide/build/jpackage/KalixIDE"
        ;;
    Darwin*)
        CLI_BINARY="kalix"
        PLATFORM="macOS"
        JPACKAGE_DIR="kalixide/build/jpackage/KalixIDE.app"
        ;;
    Linux*)
        CLI_BINARY="kalix"
        PLATFORM="Linux"
        JPACKAGE_DIR="kalixide/build/jpackage/KalixIDE"
        ;;
    *)
        echo "Unknown platform: $(uname -s)"
        exit 1
        ;;
esac

DIST_FOLDER="dist/KalixIDE-${PLATFORM}-${VERSION}"
ZIP_NAME="KalixIDE-${PLATFORM}-${VERSION}-Portable.zip"

echo "Preparing KalixIDE distribution..."
rm -rf "${DIST_FOLDER}"
mkdir -p "${DIST_FOLDER}"
cp -r "${JPACKAGE_DIR}"/* "${DIST_FOLDER}/" 2>/dev/null || cp -r "${JPACKAGE_DIR}" "${DIST_FOLDER}/"

echo "Copying Kalix CLI into distribution..."
cp "target/release/${CLI_BINARY}" "${DIST_FOLDER}/"

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
