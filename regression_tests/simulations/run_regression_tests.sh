#!/usr/bin/env bash
set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$SCRIPT_DIR/../.."

echo "Building kalix..."
cargo build --release --manifest-path "$REPO_ROOT/Cargo.toml"

echo ""
echo "Running regression tests..."
PATH="$REPO_ROOT/target/release:$PATH" python3 "$SCRIPT_DIR/verify_all_models.py"
