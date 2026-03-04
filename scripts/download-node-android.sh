#!/usr/bin/env bash
# Downloads a Termux-compatible Node.js binary and its shared library
# dependencies for Android. These binaries use the Bionic libc linker
# (/system/bin/linker64) and are the only way to run Node on Android
# without a full Termux installation.
#
# Usage: ./scripts/download-node-android.sh [arch]
#   arch: x86_64 (for emulators) or aarch64 (for real devices, default)
#
# Output directory: runtime/bin/android-<arch>/
#                   runtime/lib/android-<arch>/

set -euo pipefail

ARCH="${1:-aarch64}"
REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"

BIN_OUT="${REPO_ROOT}/runtime/bin/android-${ARCH}"
LIB_OUT="${REPO_ROOT}/runtime/lib/android-${ARCH}"

mkdir -p "$BIN_OUT" "$LIB_OUT"

TERMUX_REPO="https://packages.termux.dev/apt/termux-main"

# Map our arch names to Termux's deb arch names
case "$ARCH" in
  aarch64|arm64) DEB_ARCH="aarch64" ;;
  x86_64)        DEB_ARCH="x86_64" ;;
  *)             echo "ERROR: Unsupported arch: $ARCH"; exit 1 ;;
esac

TMPDIR=$(mktemp -d)
trap "rm -rf $TMPDIR" EXIT

echo "==> Downloading Termux Packages index for ${DEB_ARCH}..."
PACKAGES_URL="${TERMUX_REPO}/dists/stable/main/binary-${DEB_ARCH}/Packages"
curl -fsSL "$PACKAGES_URL" -o "$TMPDIR/Packages" || {
    echo "ERROR: Failed to download Packages index from $PACKAGES_URL"
    exit 1
}

# Function to find a package filename in the Packages index
find_pkg_filename() {
    local pkg_name="$1"
    # Use string equality instead of regex to avoid issues with '+' characters
    awk -v pkg="$pkg_name" '$1=="Package:" && $2==pkg { found=1 } found && $1=="Filename:" { print $2; found=0 }' "$TMPDIR/Packages" | tail -1
}

# Function to download and extract a .deb file
extract_deb() {
    local url="$1"
    local dest="$2"
    local name=$(basename "$url")
    echo "  Downloading $name..."
    curl -fsSL "$url" -o "$dest/$name" || { echo "  FAILED: $url"; return 1; }
    cd "$dest"
    ar x "$name"
    if [ -f data.tar.xz ]; then
        tar -xJf data.tar.xz
        rm -f data.tar.xz
    elif [ -f data.tar.gz ]; then
        tar -xzf data.tar.gz
        rm -f data.tar.gz
    fi
    rm -f "$name" control.tar.* debian-binary
    cd - > /dev/null
}

# Packages we need: nodejs + all its runtime dependencies
PACKAGES=(nodejs c-ares libc++ libicu libsqlite openssl zlib ripgrep pcre2 bash ncurses readline)
EXTRACT_DIR="$TMPDIR/extracted"
mkdir -p "$EXTRACT_DIR"

echo "==> Downloading packages for ${DEB_ARCH}..."
for pkg in "${PACKAGES[@]}"; do
    filename=$(find_pkg_filename "$pkg")
    if [ -z "$filename" ]; then
        echo "  WARNING: Package '$pkg' not found in index, skipping"
        continue
    fi
    extract_deb "${TERMUX_REPO}/${filename}" "$EXTRACT_DIR"
done

# Find the extracted Termux prefix
TERMUX_PREFIX="$EXTRACT_DIR/data/data/com.termux/files/usr"

if [ ! -d "$TERMUX_PREFIX" ]; then
    echo "ERROR: Termux prefix not found at $TERMUX_PREFIX"
    exit 1
fi

# Copy node binary
echo "==> Copying node binary..."
if [ -f "$TERMUX_PREFIX/bin/node" ]; then
    cp "$TERMUX_PREFIX/bin/node" "$BIN_OUT/node"
    chmod +x "$BIN_OUT/node"
else
    echo "ERROR: node binary not found"
    exit 1
fi

if [ -f "$TERMUX_PREFIX/bin/rg" ]; then
    cp "$TERMUX_PREFIX/bin/rg" "$BIN_OUT/rg"
    chmod +x "$BIN_OUT/rg"
fi

if [ -f "$TERMUX_PREFIX/bin/bash" ]; then
    cp "$TERMUX_PREFIX/bin/bash" "$BIN_OUT/bash.real"
    cat > "$BIN_OUT/bash" << 'EOF'
#!/system/bin/sh
# Prevent bash from loading hardcoded Termux profiles/rc files that don't exist
export PS1='\[\033[01;32m\]android@theia\[\033[00m\]:\[\033[01;34m\]\w\[\033[00m\]\$ '
exec "$(dirname "$0")/bash.real" --noprofile --norc "$@"
EOF
    chmod +x "$BIN_OUT/bash.real" "$BIN_OUT/bash"
fi

# Copy npm/npx scripts
for script in npm npx; do
    if [ -f "$TERMUX_PREFIX/bin/$script" ]; then
        cp "$TERMUX_PREFIX/bin/$script" "$BIN_OUT/$script"
        chmod +x "$BIN_OUT/$script"
    fi
done

# Copy npm lib directory (needed for npm to work)
if [ -d "$TERMUX_PREFIX/lib/node_modules/npm" ]; then
    echo "==> Copying npm modules..."
    mkdir -p "$BIN_OUT/lib/node_modules"
    cp -r "$TERMUX_PREFIX/lib/node_modules/npm" "$BIN_OUT/lib/node_modules/npm"

    # Create npm/npx shims that use the bundled node
    cat > "$BIN_OUT/npm" << 'SHIMEOF'
#!/system/bin/sh
DIR="$(cd "$(dirname "$0")" && pwd)"
"$DIR/node" "$DIR/lib/node_modules/npm/bin/npm-cli.js" "$@"
SHIMEOF

    cat > "$BIN_OUT/npx" << 'SHIMEOF'
#!/system/bin/sh
DIR="$(cd "$(dirname "$0")" && pwd)"
"$DIR/node" "$DIR/lib/node_modules/npm/bin/npx-cli.js" "$@"
SHIMEOF
    chmod +x "$BIN_OUT/npm" "$BIN_OUT/npx"
fi

# Copy shared libraries (only .so files, resolve symlinks)
echo "==> Copying shared libraries..."
if [ -d "$TERMUX_PREFIX/lib" ]; then
    # Copy real .so files
    find "$TERMUX_PREFIX/lib" -maxdepth 1 -name "*.so*" -type f -exec cp {} "$LIB_OUT/" \;
    # Copy symlinks
    find "$TERMUX_PREFIX/lib" -maxdepth 1 -name "*.so*" -type l -exec cp -P {} "$LIB_OUT/" \;
    chmod +x "$LIB_OUT"/*.so* 2>/dev/null || true
fi

echo ""
echo "==> Done."
echo "    Node binary: ${BIN_OUT}/node"
echo "    npm shim:    ${BIN_OUT}/npm"
echo "    npx shim:    ${BIN_OUT}/npx"
echo "    Shared libs: ${LIB_OUT}/"
echo ""
echo "    Files in bin:"
ls -la "$BIN_OUT/" | head -10
echo ""
echo "    Shared libs:"
ls -la "$LIB_OUT/" | head -20
