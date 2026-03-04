#!/usr/bin/env bash
# Master build script for Theia Android APK.
# Usage: ./scripts/build-android.sh [debug|release]
#
# Prerequisites:
#   - Node.js 18+ and npm
#   - Android SDK with platform 36 and build-tools
#   - JDK 17
#   - Node binary at runtime/bin/android-arm64/node
#     (run scripts/download-node-android.sh first)

set -euo pipefail

BUILD_TYPE="${1:-debug}"
REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"

echo "============================================"
echo " Theia Android APK — Build Script"
echo " Build type: $BUILD_TYPE"
echo "============================================"
echo ""

# Step 1: Check prerequisites
echo "[1/5] Checking prerequisites..."
if ! command -v node &>/dev/null; then
    echo "ERROR: node not found. Install Node.js 18+."
    exit 1
fi
if ! command -v npm &>/dev/null; then
    echo "ERROR: npm not found."
    exit 1
fi

NODE_BIN="${REPO_ROOT}/runtime/bin/android-arm64/node"
if [ ! -f "$NODE_BIN" ]; then
    echo "WARNING: Node binary for Android not found at $NODE_BIN"
    echo "         Run: ./scripts/download-node-android.sh"
    echo "         Continuing build anyway (APK will not work without it)."
fi

# Step 2: Install dependencies (if needed)
echo ""
echo "[2/5] Installing/verifying npm dependencies..."
if [ ! -d "${REPO_ROOT}/node_modules" ]; then
    (cd "$REPO_ROOT" && npm install)
else
    echo "  node_modules exists, skipping install."
fi

# Step 3: Build the command console package
echo ""
echo "[3/5] Building theia-command-console package..."
(cd "${REPO_ROOT}/packages/theia-command-console" && npm run build)

# Step 4: Build Theia product (production bundle)
echo ""
echo "[4/5] Building theia-android-lite product..."
(cd "${REPO_ROOT}/products/theia-android-lite" && npm run build:production)

# Step 4b: Prepare runtime assets
echo ""
echo "[4b] Preparing runtime assets for Android..."
(cd "${REPO_ROOT}/products/theia-android-lite" && npm run prepare:android-assets)

# Step 5: Build Android APK
echo ""
echo "[5/5] Building Android APK ($BUILD_TYPE)..."
ANDROID_DIR="${REPO_ROOT}/android-app/android"

if [ ! -f "$ANDROID_DIR/gradlew" ]; then
    echo "ERROR: gradlew not found at $ANDROID_DIR"
    exit 1
fi

if [ "$BUILD_TYPE" = "release" ]; then
    (cd "$ANDROID_DIR" && ./gradlew assembleRelease)
    APK_PATH="$ANDROID_DIR/app/build/outputs/apk/release/app-release-unsigned.apk"
else
    (cd "$ANDROID_DIR" && ./gradlew assembleDebug)
    APK_PATH="$ANDROID_DIR/app/build/outputs/apk/debug/app-debug.apk"
fi

echo ""
echo "============================================"
echo " Build Complete!"
echo "============================================"
echo ""
if [ -f "$APK_PATH" ]; then
    echo "APK: $APK_PATH"
    echo "Size: $(du -h "$APK_PATH" | cut -f1)"
    echo ""
    echo "Install: adb install $APK_PATH"
else
    echo "WARNING: Expected APK not found at $APK_PATH"
    echo "Check build output above for errors."
fi
