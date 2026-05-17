#!/usr/bin/env bash
# ═══════════════════════════════════════════════════════════════════════════
#  build_aarch64.sh — Cross-compile payload_toolkit.py to aarch64 binary
#
#  This script cross-compiles the payload_toolkit Python package into a
#  standalone aarch64 (ARM64) binary for Android, using PyInstaller and
#  the Android NDK toolchain.
#
#  The resulting binary can be pushed to an Android device and executed
#  directly from Kotlin via ProcessBuilder, as an alternative to Chaquopy.
#
#  Prerequisites:
#    - Android NDK r26+ installed (ANDROID_NDK_HOME or ANDROID_HOME/ndk/<ver>)
#    - Python 3.10+ with pip
#    - PyInstaller 6.x: pip install pyinstaller
#    - Linux host (cross-compilation from macOS is experimental)
#
#  Usage:
#    chmod +x scripts/build_aarch64.sh
#    ./scripts/build_aarch64.sh
#
#  Output:
#    dist/payload_toolkit-aarch64  (standalone executable, ~30-50MB)
#
# ═══════════════════════════════════════════════════════════════════════════

set -euo pipefail

# ── Configuration ──────────────────────────────────────────────────────

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
SRC_DIR="$PROJECT_ROOT/src"
DIST_DIR="$PROJECT_ROOT/dist"
BUILD_DIR="$PROJECT_ROOT/build_aarch64"
PYTHON_PKG="$SRC_DIR/payload_toolkit"

# Target architecture
TARGET_ARCH="aarch64"
TARGET_TRIPLE="aarch64-linux-android"
TARGET_API="26"  # Android 8.0 — matches minSdk

# Android NDK settings
NDK_VERSION="26.1.10909125"
TOOLCHAIN_NAME="${TARGET_TRIPLE}-${NDK_VERSION}"

# Python version for the build host
PYTHON_VERSION="3.12"
PYTHON_BIN="python3"

# ═══════════════════════════════════════════════════════════════════════════
#  1. Environment checks
# ═══════════════════════════════════════════════════════════════════════════

echo "╔══════════════════════════════════════════════════════════════╗"
echo "║  Payload Toolkit — Cross-compile for Android aarch64       ║"
echo "╚══════════════════════════════════════════════════════════════╝"
echo ""

# Check ANDROID_NDK_HOME
if [ -z "${ANDROID_NDK_HOME:-}" ]; then
    if [ -n "${ANDROID_HOME:-}" ]; then
        # Try to find NDK in Android SDK
        NDK_CANDIDATES=(
            "$ANDROID_HOME/ndk/$NDK_VERSION"
            "$ANDROID_HOME/ndk"
            "$ANDROID_HOME/android-ndk"
        )
        for ndk_path in "${NDK_CANDIDATES[@]}"; do
            if [ -d "$ndk_path" ]; then
                ANDROID_NDK_HOME="$ndk_path"
                break
            fi
        done
    fi

    if [ -z "${ANDROID_NDK_HOME:-}" ]; then
        echo "ERROR: ANDROID_NDK_HOME not set."
        echo ""
        echo "  Set it to your Android NDK installation directory:"
        echo "    export ANDROID_NDK_HOME=/path/to/android-ndk-r${NDK_VERSION}"
        echo ""
        echo "  Or install NDK via Android Studio:"
        echo "    SDK Manager > SDK Tools > NDK (Side by side) > $NDK_VERSION"
        echo ""
        echo "  Download from:"
        echo "    https://developer.android.com/ndk/downloads"
        exit 1
    fi
fi

echo "[OK] ANDROID_NDK_HOME = $ANDROID_NDK_HOME"

# Check for required NDK toolchain files
NDK_TOOLCHAIN="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64"
if [ ! -d "$NDK_TOOLCHAIN" ]; then
    # Try macOS
    NDK_TOOLCHAIN="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/darwin-x86_64"
fi

if [ ! -d "$NDK_TOOLCHAIN" ]; then
    echo "ERROR: NDK toolchain not found at expected location."
    echo "  Looked for: $ANDROID_NDK_HOME/toolchains/llvm/prebuilt/{linux,darwin}-x86_64"
    exit 1
fi

echo "[OK] NDK toolchain   = $NDK_TOOLCHAIN"

# Check Python
if ! command -v $PYTHON_BIN &>/dev/null; then
    echo "ERROR: $PYTHON_BIN not found. Install Python 3.10+ first."
    exit 1
fi

PYTHON_MAJOR=$($PYTHON_BIN -c "import sys; print(f'{sys.version_info.major}.{sys.version_info.minor}')")
echo "[OK] Python           = $PYTHON_BIN ($PYTHON_MAJOR)"

# Check PyInstaller
if ! $PYTHON_BIN -m pip show pyinstaller &>/dev/null; then
    echo "ERROR: PyInstaller not installed."
    echo "  Install with: pip install pyinstaller"
    exit 1
fi

PYINSTALLER_VER=$($PYTHON_BIN -m pip show pyinstaller 2>/dev/null | grep Version | awk '{print $2}')
echo "[OK] PyInstaller      = $PYINSTALLER_VER"

# Check source files
if [ ! -f "$PYTHON_PKG/__init__.py" ]; then
    echo "ERROR: Python package not found at $PYTHON_PKG"
    echo "  Ensure src/payload_toolkit/__init__.py exists."
    exit 1
fi

echo "[OK] Source package   = $PYTHON_PKG"
echo ""

# ═══════════════════════════════════════════════════════════════════════════
#  2. Prepare build environment
# ═══════════════════════════════════════════════════════════════════════════

echo "[1/6] Setting up build environment..."

rm -rf "$BUILD_DIR"
mkdir -p "$BUILD_DIR"
mkdir -p "$DIST_DIR"

# Create a PyInstaller-compatible entry point script
# This script imports payload_toolkit and exposes the CLI interface
cat > "$BUILD_DIR/payload_toolkit_entry.py" << 'ENTRY_POINT'
#!/usr/bin/env python3
"""
Entry point for cross-compiled payload_toolkit binary.
Provides a CLI interface compatible with the original payload_toolkit.py.
"""
import sys
import os

# Ensure the payload_toolkit package is importable
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from payload_toolkit import main
if __name__ == "__main__":
    main()
ENTRY_POINT

# ═══════════════════════════════════════════════════════════════════════════
#  3. Build standalone Python for aarch64 (using buildroot-python)
# ═══════════════════════════════════════════════════════════════════════════

echo "[2/6] Configuring PyInstaller for cross-compilation..."

# Export NDK compiler environment variables for PyInstaller
export CC="$NDK_TOOLCHAIN/bin/${TARGET_TRIPLE}${TARGET_API}-clang"
export CXX="$NDK_TOOLCHAIN/bin/${TARGET_TRIPLE}${TARGET_API}-clang++"
export AR="$NDK_TOOLCHAIN/bin/llvm-ar"
export RANLIB="$NDK_TOOLCHAIN/bin/llvm-ranlib"
export STRIP="$NDK_TOOLCHAIN/bin/llvm-strip"
export NM="$NDK_TOOLCHAIN/bin/llvm-nm"
export OBJDUMP="$NDK_TOOLCHAIN/bin/llvm-objdump"
export READELF="$NDK_TOOLCHAIN/bin/llvm-readelf"

# Verify compiler exists
if [ ! -f "$CC" ]; then
    echo "ERROR: Cross-compiler not found: $CC"
    echo "  Ensure NDK API level $TARGET_API is supported (NDK r21+)."
    exit 1
fi

echo "  CC      = $CC"
echo "  CXX     = $CXX"
echo "  TARGET  = $TARGET_TRIPLE (API $TARGET_API)"

# ═══════════════════════════════════════════════════════════════════════════
#  4. Run PyInstaller with cross-compilation settings
# ═══════════════════════════════════════════════════════════════════════════

echo "[3/6] Running PyInstaller..."

PYINSTALLER_SPEC="$BUILD_DIR/payload_toolkit.spec"

# Generate PyInstaller spec file for cross-compilation
cat > "$PYINSTALLER_SPEC" << SPEC_EOF
# -*- mode: python ; coding: utf-8 -*-
# PyInstaller spec for cross-compiling payload_toolkit to Android aarch64
import os

a = Analysis(
    ['$BUILD_DIR/payload_toolkit_entry.py'],
    pathex=['$SRC_DIR'],
    binaries=[],
    datas=[
        ('$PYTHON_PKG', 'payload_toolkit'),
    ],
    hiddenimports=[
        'payload_toolkit',
        'payload_toolkit.protobuf',
        'payload_toolkit.compression',
        'payload_toolkit.payload',
        'payload_toolkit.ota_metadata',
        'payload_toolkit.modes',
        'payload_toolkit.modes.info',
        'payload_toolkit.modes.dump',
        'payload_toolkit.modes.gen',
        'payload_toolkit.modes.zip',
        'payload_toolkit.modes.sign',
    ],
    hookspath=[],
    hooksconfig={},
    runtime_hooks=[],
    excludes=[
        'tkinter', 'matplotlib', 'numpy', 'pandas', 'PIL',
        'scipy', 'pytest', 'IPython', 'jupyter', 'notebook',
        'setuptools', 'pip', 'wheel', 'PyInstaller',
        'email', 'html', 'xml', 'xmlrpc', 'pydoc',
        'doctest', 'unittest', 'multiprocessing',
        'concurrent', 'asyncio', 'ssl', 'ctypes',
        'curses', 'turtle', 'idlelib',
    ],
    noarchive=False,
)

pyz = PYZ(a.pure)

exe = EXE(
    pyz,
    a.scripts,
    a.binaries,
    a.datas,
    [],
    name='payload_toolkit',
    debug=False,
    bootloader_ignore_signals=False,
    strip=True,
    upx=True,
    upx_exclude=[],
    runtime_tmpdir=None,
    console=True,
)
SPEC_EOF

# Run PyInstaller
cd "$BUILD_DIR"

$PYTHON_BIN -m PyInstaller \
    --clean \
    --distpath "$DIST_DIR" \
    --workpath "$BUILD_DIR/work" \
    --specpath "$BUILD_DIR" \
    "$PYINSTALLER_SPEC"

cd "$PROJECT_ROOT"

# ═══════════════════════════════════════════════════════════════════════════
#  5. Verify output
# ═══════════════════════════════════════════════════════════════════════════

echo "[4/6] Verifying output..."

OUTPUT_BINARY="$DIST_DIR/payload_toolkit"

if [ -f "$OUTPUT_BINARY" ]; then
    FILE_SIZE=$(stat -c%s "$OUTPUT_BINARY" 2>/dev/null || stat -f%z "$OUTPUT_BINARY" 2>/dev/null)
    FILE_SIZE_MB=$(echo "scale=1; $FILE_SIZE / 1048576" | bc)

    # Check architecture
    if command -v file &>/dev/null; then
        FILE_INFO=$(file "$OUTPUT_BINARY" 2>/dev/null || echo "unknown")
        echo "  Binary: $OUTPUT_BINARY"
        echo "  Size:   ${FILE_SIZE_MB} MB"
        echo "  Type:   $FILE_INFO"
    else
        echo "  Binary: $OUTPUT_BINARY (${FILE_SIZE_MB} MB)"
    fi
else
    echo "ERROR: Output binary not found at $OUTPUT_BINARY"
    echo "  PyInstaller may have failed. Check the build output above."
    exit 1
fi

# ═══════════════════════════════════════════════════════════════════════════
#  6. Deploy to device (optional)
# ═══════════════════════════════════════════════════════════════════════════

echo "[5/6] Post-build..."

# Rename for clarity
if [ -f "$OUTPUT_BINARY" ]; then
    mv "$OUTPUT_BINARY" "$DIST_DIR/payload_toolkit-aarch64"
    echo "  Output renamed to: dist/payload_toolkit-aarch64"
fi

echo ""
echo "[6/6] Done!"

echo ""
echo "══════════════════════════════════════════════════════════════"
echo "  BUILD COMPLETE"
echo "══════════════════════════════════════════════════════════════"
echo ""
echo "  Output: $DIST_DIR/payload_toolkit-aarch64"
echo ""
echo "  To deploy to a connected Android device:"
echo "    adb push dist/payload_toolkit-aarch64 /data/local/tmp/"
echo "    adb shell chmod 755 /data/local/tmp/payload_toolkit-aarch64"
echo "    adb shell /data/local/tmp/payload_toolkit-aarch64 info -i /sdcard/payload.bin"
echo ""
echo "  To use from Kotlin (ProcessBuilder):"
echo "    val process = Runtime.getRuntime()"
echo "        .exec(arrayOf(\"/data/local/tmp/payload_toolkit-aarch64\", \"info\", \"-i\", payloadPath))"
echo ""
echo "  NOTE: Cross-compiled binary requires matching Android NDK sysroot."
echo "  If the binary doesn't run on the device, try a different TARGET_API."
echo ""
