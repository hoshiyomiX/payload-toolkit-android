#!/usr/bin/env bash
# prepare_python_runtime.sh — Prepare bundled Python runtime for Android APK
#
# Downloads Termux aarch64 packages and splits them into two outputs:
#
#   1. jniLibs/arm64-v8a/  — All .so files + Python binary
#      -> Extracted by Android package manager to nativeLibraryDir at install
#      -> nativeLibraryDir has SELinux app_lib_file context (EXECUTABLE)
#
#   2. dist/python-stdlib.zip — Python stdlib .py files + configs
#      -> Bundled as Android asset, extracted to app data at first launch
#      -> Read-only access, no exec permission needed
#
# Why this split?  Android SELinux blocks execve() from app_data_file context.
# Files in nativeLibraryDir (from jniLibs) can be executed.  Pure .py files
# only need read access, so app data is fine for them.
#
# Packages: python 3.13, libandroid-support, liblzma, libbz2, libcrypto,
#           libsqlite3, ncurses, readline, libffi, zlib, libcrypt

set -euo pipefail

ARCH=aarch64
REPO="https://packages.termux.dev/apt/termux-main"
STAGING=$(mktemp -d)
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
JNI_DIR="$PROJECT_ROOT/android/app/src/main/jniLibs/arm64-v8a"
STDLIB_STAGING="$STAGING/stdlib/lib/python3.13"
DIST_DIR="$PROJECT_ROOT/dist"

mkdir -p "$JNI_DIR" "$DIST_DIR" "$STDLIB_STAGING"

# -- Fetch package index -----------------------------------------------
echo "==> Fetching Termux package index for ${ARCH}..."
curl -sL "$REPO/dists/stable/main/binary-${ARCH}/Packages.gz" | gzip -d > "$STAGING/Packages"

# -- Helpers -----------------------------------------------------------
pkg_filename() {
    local pkg="$1"
    awk -v p="^Package: ${pkg}$" '
        $0 ~ p { found=1 }
        found && /^Filename:/ { sub(/^Filename: */, ""); print; exit }
    ' "$STAGING/Packages"
}

download_and_extract() {
    local pkg="$1"
    local filename
    filename=$(pkg_filename "$pkg")
    [ -z "$filename" ] && { echo "    SKIP: ${pkg} (not found)"; return 0; }
    echo "    $pkg"
    wget -q "${REPO}/${filename}" -O "$STAGING/${pkg}.deb" || { echo "    FAIL: ${pkg}"; return 0; }
    dpkg-deb -x "$STAGING/${pkg}.deb" "$STAGING/installed/" 2>/dev/null
    rm -f "$STAGING/${pkg}.deb"
}

# -- Download packages -------------------------------------------------
echo ""
echo "==> Downloading packages..."
PACKAGES=(
    python
    libandroid-support
    libbz2
    libcrypt
    libffi
    liblzma
    ncurses
    openssl
    readline
    sqlite
    zlib
)
for pkg in "${PACKAGES[@]}"; do
    download_and_extract "$pkg"
done

TERMUX_PREFIX="$STAGING/installed/data/data/com.termux/files/usr"
if [ ! -d "$TERMUX_PREFIX/lib" ]; then
    echo "ERROR: Termux prefix not found at ${TERMUX_PREFIX}"
    rm -rf "$STAGING"
    exit 1
fi

# -- 1. Populate jniLibs/arm64-v8a/ ------------------------------------
# All .so files + Python binary go here.
# Android extracts them to nativeLibraryDir (executable SELinux context).
echo ""
echo "==> Populating jniLibs/arm64-v8a/..."

# Clean ALL previous output (including versioned .so.X files from git)
find "$JNI_DIR" -maxdepth 1 \( -name "*.so" -o -name "*.so.*" \) -delete 2>/dev/null || true

# Python binary -> renamed to .so so Android packages it into lib/arm64-v8a/
if [ -f "$TERMUX_PREFIX/bin/python3.13" ]; then
    cp -a "$TERMUX_PREFIX/bin/python3.13" "$JNI_DIR/libpython3exec.so"
    echo "    python3.13 -> libpython3exec.so"
fi

# Shared libraries from lib/ -- include symlinks so the linker can
# resolve SONAMEs (e.g. libpython3.13.so -> libpython3.13.so.1.0)
find "$TERMUX_PREFIX/lib" -maxdepth 1 -name "*.so*" \( -type f -o -type l \) | while read -r f; do
    cp -a "$f" "$JNI_DIR/"
done

# -- Resolve symlinks and fix SONAME extensions -------------------------
# APK is a ZIP file -- ZIP cannot store symlinks.  Any symlink we cp -a'd
# above will be lost when Gradle packages jniLibs into the APK.
#
# Additionally, Android Gradle Plugin only packages files from jniLibs
# whose filename ENDS with .so.  SONAME files like libz.so.1 don't match
# and are silently dropped from the APK.
#
# Strategy:
#   1. Replace symlinks with real copies of their targets.
#   2. For every file matching *.so.* (e.g. libz.so.1.3.2 or libz.so.1),
#      create a copy with .so appended (e.g. libz.so.1.3.2.so) so AGP
#      packages it.
#   3. At runtime, PythonBridge.kt creates symlinks from the SONAME name
#      to the .so.so file so the linker can find them by DT_NEEDED.
echo "    Resolving symlinks and fixing SONAME extensions..."

# Step 1: Replace symlinks with real copies
find "$JNI_DIR" -maxdepth 1 -type l | while read -r link; do
    target=$(readlink -f "$link")
    if [ -f "$target" ]; then
        rm -f "$link"
        cp -a "$target" "$link"
    fi
done

# Step 2: Copy *.so.* files to *.so.*.so (append .so so AGP packages them)
SONAME_COUNT=0
find "$JNI_DIR" -maxdepth 1 -name '*.so.*' -type f | while read -r f; do
    cp -a "$f" "${f}.so"
    SONAME_COUNT=$((SONAME_COUNT + 1))
done
echo "    SONAME extensions fixed ($SONAME_COUNT files)"

# C extension modules from lib-dynload/ -- these have names like
# _hashlib.cpython-313-aarch64-linux-android.so (not "lib*" prefix,
# but Android still extracts all .so files from jniLibs)
if [ -d "$TERMUX_PREFIX/lib/python3.13/lib-dynload" ]; then
    find "$TERMUX_PREFIX/lib/python3.13/lib-dynload" -name "*.so" \( -type f -o -type l \) | while read -r f; do
        cp -a "$f" "$JNI_DIR/"
    done
fi

# Strip test extension modules (saves ~1 MB, not needed for payload_toolkit)
find "$JNI_DIR" -maxdepth 1 -name "_test*.so" -delete 2>/dev/null || true
find "$JNI_DIR" -maxdepth 1 -name "_xxtestfuzz*.so" -delete 2>/dev/null || true
find "$JNI_DIR" -maxdepth 1 -name "xxlimited*.so" -delete 2>/dev/null || true
find "$JNI_DIR" -maxdepth 1 -name "xxsubtype*.so" -delete 2>/dev/null || true
find "$JNI_DIR" -maxdepth 1 -name "_ctypes_test*.so" -delete 2>/dev/null || true

# Verify critical files exist
if [ ! -f "$JNI_DIR/libpython3exec.so" ]; then
    echo "ERROR: libpython3exec.so was not created!"
    echo "  Expected: $TERMUX_PREFIX/bin/python3.13"
    ls -la "$TERMUX_PREFIX/bin/python3.13" 2>/dev/null || echo "  File not found"
    rm -rf "$STAGING"
    exit 1
fi

JNI_COUNT=$(find "$JNI_DIR" -maxdepth 1 -name "*.so" -o -name "*.so.*" | wc -l)
JNI_SIZE=$(du -sh "$JNI_DIR" | cut -f1)
echo "    $JNI_COUNT native libraries ($JNI_SIZE)"

# -- 2. Create python-stdlib.zip ----------------------------------------
# Only .py files and configs -- no .so files (those are in jniLibs).
echo ""
echo "==> Creating python-stdlib.zip..."

# Copy Python stdlib to staging
if [ -d "$TERMUX_PREFIX/lib/python3.13" ]; then
    cp -a "$TERMUX_PREFIX/lib/python3.13/." "$STDLIB_STAGING/"
fi

# Remove ALL .so files from stdlib (they're in jniLibs now)
find "$STDLIB_STAGING" -name "*.so" -delete 2>/dev/null || true

# Strip unnecessary content to reduce size
find "$STDLIB_STAGING" -type d -name "__pycache__" -exec rm -rf {} + 2>/dev/null || true
find "$STDLIB_STAGING" -type d \( -name "test" -o -name "tests" \) \
    -path "*/python*/test*" -exec rm -rf {} + 2>/dev/null || true
find "$STDLIB_STAGING" -type d -name "idlelib" -exec rm -rf {} + 2>/dev/null || true
find "$STDLIB_STAGING" -type d -name "tkinter" -exec rm -rf {} + 2>/dev/null || true
find "$STDLIB_STAGING" -type d -name "turtledemo" -exec rm -rf {} + 2>/dev/null || true
find "$STDLIB_STAGING" -type d -name "ensurepip" -exec rm -rf {} + 2>/dev/null || true
find "$STDLIB_STAGING" -name "*.pyc" -delete 2>/dev/null || true
find "$STDLIB_STAGING" -name "*.pyo" -delete 2>/dev/null || true
find "$STDLIB_STAGING" -name "*.a" -delete 2>/dev/null || true
find "$STDLIB_STAGING" -name "*.la" -delete 2>/dev/null || true

# Create zip preserving lib/python3.13/ structure
(cd "$STAGING/stdlib" && zip -qr "$DIST_DIR/python-stdlib.zip" lib/)

if [ ! -f "$DIST_DIR/python-stdlib.zip" ]; then
    echo "ERROR: python-stdlib.zip was not created!"
    rm -rf "$STAGING"
    exit 1
fi

STDLIB_SIZE=$(du -h "$DIST_DIR/python-stdlib.zip" | cut -f1)
STDLIB_COUNT=$(zipinfo -1 "$DIST_DIR/python-stdlib.zip" | wc -l)

# -- Summary ------------------------------------------------------------
echo ""
echo "==========================================="
echo "  jniLibs (native libs):"
echo "    Files:  $JNI_COUNT"
echo "    Size:   $JNI_SIZE"
echo "  python-stdlib.zip (.py only):"
echo "    Files:  $STDLIB_COUNT"
echo "    Size:   $STDLIB_SIZE"
echo "  Output:  $DIST_DIR/python-stdlib.zip"
echo "==========================================="

# -- Cleanup ------------------------------------------------------------
rm -rf "$STAGING"
echo "Done!"
