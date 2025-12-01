#!/bin/bash
set -e

echo "Building Rust CLI..."
cargo build --release

echo "Building KalixIDE..."
cd kalixide
./gradlew clean --no-daemon
./gradlew build jpackageImage --no-daemon
cd ..

echo "Preparing distribution..."
mkdir -p dist

echo "Copying standalone KalixCLI..."
cp target/release/kalixcli.exe dist/

echo "Preparing KalixIDE distribution..."
rm -rf dist/KalixIDE-Windows
mkdir -p dist/KalixIDE-Windows
cp -r kalixide/build/jpackage/KalixIDE/* dist/KalixIDE-Windows/

echo "Creating KalixIDE zip..."
cd dist
powershell -Command "Compress-Archive -Path 'KalixIDE-Windows' -DestinationPath 'KalixIDE-Windows-Portable.zip' -Force"
cd ..

echo "Done!"
echo "Standalone CLI: dist/kalixcli.exe"
echo "IDE Portable: dist/KalixIDE-Windows-Portable.zip"
ls -lh dist/kalixcli.exe dist/*.zip
