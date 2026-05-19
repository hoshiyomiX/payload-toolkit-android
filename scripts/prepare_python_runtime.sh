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

# -- Android system libs whitelist --------------------------------------
# These are always available on Android (provided by bionic / platform).
# DO NOT bundle them — the linker resolves them from the system namespace.
# Defined once, used by DT_NEEDED patching, validation, and integrity check.
is_android_system_lib() {
    case "$1" in
        libc.so|libm.so|libdl.so|libpthread.so|librt.so|liblog.so) return 0 ;;
        *) return 1 ;;
    esac
}

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
    libandroid-posix-semaphore
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

# -- Resolve symlinks and flatten SONAME references ----------------------
# APK is a ZIP file -- ZIP cannot store symlinks.  Any symlink we cp -a'd
# above will be lost when Gradle packages jniLibs into the APK.
#
# Additionally, Android Gradle Plugin only packages files from jniLibs
# whose filename ENDS with .so.  SONAME files like libz.so.1 don't match
# and are silently dropped from the APK.
#
# V3.9 STRATEGY -- "unversioned + LD_PRELOAD":
#
#   The problem:  DT_RUNPATH=$ORIGIN does NOT work for transitive deps
#   loaded via dlopen() on Android.  When Python dlopens zlib.so, and
#   zlib.so needs libz.so.1, the linker ignores $ORIGIN and cannot find
#   libz.so.1.so (or even libz.so.1).  This was confirmed on device.
#
#   The fix has TWO parts (build-time + runtime):
#
#   BUILD-TIME (this script):
#     1. Replace symlinks with real copies of their targets.
#     2. Ensure unversioned .so exists for every versioned library:
#        libz.so.1.3.2 + libz.so.1 -> we keep libz.so (unversioned).
#        If the Termux package lacks the dev symlink, create it.
#     3. Remove DT_SONAME from ALL .so files (patchelf --remove-soname).
#        Prevents the linker from registering libs under versioned names
#        that AGP dropped from the APK.
#     4. Patch DT_NEEDED: versioned -> unversioned (libz.so.1 -> libz.so).
#        The linker finds libz.so via the default namespace search path
#        (which includes nativeLibraryDir for app processes).
#     5. (REMOVED in v3.15) DT_RUNPATH=$ORIGIN was removed — LD_PRELOAD
#        handles all transitive dep resolution on Android.  patchelf --set-rpath
#        was a corruption risk (grew dynamic section on files with minimal padding).
#
#   RUNTIME (PythonBridge.kt):
#     LD_PRELOAD with Python binary's DIRECT dependencies only (typically
#     2-4 large libs).  Transitive deps (loaded via dlopen) are resolved by
#     LD_LIBRARY_PATH.  Preloading ALL 74 libs caused persistent ELF
#     corruption crashes (did_read_ failures).
echo "    Resolving symlinks and flattening SONAME references..."

# Step 1: Replace symlinks with real copies
find "$JNI_DIR" -maxdepth 1 -type l | while read -r link; do
    target=$(readlink -f "$link")
    if [ -f "$target" ]; then
        rm -f "$link"
        cp -a "$target" "$link"
    fi
done

# Step 2: Ensure unversioned .so exists for every versioned library
# Termux packages usually provide libfoo.so -> libfoo.so.1 -> libfoo.so.1.0
# After symlink resolution above, we have real copies of all three.
# AGP drops libfoo.so.1 and libfoo.so.1.0 (don't end with .so).
# We KEEP libfoo.so (ends with .so, AGP packages it).
# If a package somehow lacks the unversioned symlink, create it:
UNVERSIONED_COUNT=0
find "$JNI_DIR" -maxdepth 1 -name '*.so.*' -type f | while read -r f; do
    # Extract base name: libfoo.so.1.0 -> libfoo.so
    base="$(echo "$f" | sed 's/\.so\..*//')"
    if [ ! -f "$base" ]; then
        cp -a "$f" "$base"
        UNVERSIONED_COUNT=$((UNVERSIONED_COUNT + 1))
    fi
done
echo "    Created $UNVERSIONED_COUNT unversioned symlinks"

# Step 2b: Delete versioned .so.* files (AGP would drop them anyway).
# Keeping them in the git repo just wastes space and causes confusion.
find "$JNI_DIR" -maxdepth 1 -name '*.so.*' -type f -delete 2>/dev/null || true
echo "    Removed versioned .so.* files (AGP drops them)"

# C extension modules from lib-dynload/ -- these have names like
# _hashlib.cpython-313-aarch64-linux-android.so (not "lib*" prefix,
# but Android still extracts all .so files from jniLibs)
# IMPORTANT: Copy BEFORE patchelf so these also get their DT_NEEDED fixed.
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

# Strip UI/database extension modules not needed for payload_toolkit.
# These pull in libraries from Termux packages we don't bundle:
#   _curses       -> libncursesw.so, libtinfo.so (ok) but _curses_panel -> libpanelw.so
#                     which lives in ncurses-ui-libs (not ncurses base)
#   readline      -> libreadline.so, libncursesw.so
#   _gdbm         -> libgdbm.so (not in our PACKAGES list)
#   _dbm          -> libgdbm_compat.so (not in our PACKAGES list)
find "$JNI_DIR" -maxdepth 1 -name "_curses*.so" -delete 2>/dev/null || true
find "$JNI_DIR" -maxdepth 1 -name "readline*.so" -delete 2>/dev/null || true
find "$JNI_DIR" -maxdepth 1 -name "_gdbm*.so" -delete 2>/dev/null || true
find "$JNI_DIR" -maxdepth 1 -name "_dbm*.so" -delete 2>/dev/null || true

# Step 3: Strip DT_SONAME + rewrite DT_NEEDED ---------------------------
# patchelf modifies ELF headers so libraries use unversioned names only.
#
# IMPORTANT: This must run AFTER all .so files are in jniLibs (including
# lib-dynload extension modules) so they ALL get patched.
#
# Why strip DT_SONAME?
#   libz.so (copy of libz.so.1.3.2) has DT_SONAME: libz.so.1.3.2
#   If we don't strip it, the linker registers libz.so under the name
#   "libz.so.1.3.2" internally.  When another library's DT_NEEDED says
#   "libz.so", the linker won't find it because it's registered as
#   "libz.so.1.3.2".  Stripping SONAME makes the linker use filename.
#
# Why patch DT_NEEDED to unversioned?
#   zlib.cpython-313-*.so has DT_NEEDED: libz.so.1
#   libz.so.1 is NOT in the APK (AGP dropped it).
#   We patch to libz.so, which IS in the APK.
echo "    Patching ELF headers with patchelf..."
if ! command -v patchelf &>/dev/null; then
    echo "    Installing patchelf..."
    sudo apt-get install -y -qq patchelf 2>/dev/null || {
        echo "ERROR: patchelf is required but could not be installed"
        rm -rf "$STAGING"
        exit 1
    }
fi

SONAME_REMOVED=0
NEEDED_PATCHED=0
NEEDED_FILES=0
PATCHELF_SKIPPED=0
# Minimum file size (bytes) for patchelf safety.
# Files smaller than this typically lack ELF section padding for
# dynamic entry growth.  patchelf corrupts them silently.
# 8 KB = conservative threshold covering stubs, linker scripts,
# and tiny C extension modules.
PATCHELF_MIN_SIZE=8192

for so_file in "$JNI_DIR"/*.so; do
    [ -f "$so_file" ] || continue
    # Skip small files from patchelf — they risk ELF corruption.
    # libpython3exec.so (4 KB Python binary) and other tiny stubs
    # don't have enough section padding for patchelf's modifications.
    file_size=$(stat -c%s "$so_file" 2>/dev/null || echo 0)
    if [ "$file_size" -lt "$PATCHELF_MIN_SIZE" ]; then
        PATCHELF_SKIPPED=$((PATCHELF_SKIPPED + 1))
        continue
    fi
    file_patched=0

    # Strip DT_SONAME so linker uses filename for dedup
    if patchelf --remove-soname "$so_file" 2>/dev/null; then
        SONAME_REMOVED=$((SONAME_REMOVED + 1))
    fi

    # Patch versioned DT_NEEDED -> unversioned
    while IFS= read -r needed; do
        [ -z "$needed" ] && continue
        # Skip system libs that Android provides
        is_android_system_lib "$needed" && continue
        # Only patch versioned names (e.g. libz.so.1, libcrypto.so.3)
        # Unversioned deps are checked by the validation sweep below
        if [[ "$needed" == *.so.* ]]; then
            # Derive unversioned name: libz.so.1 -> libz.so
            unversioned="$(echo "$needed" | sed 's/\.so\..*/.so/')"
            if [ -f "$JNI_DIR/$unversioned" ]; then
                if patchelf --replace-needed "$needed" "$unversioned" "$so_file" 2>/dev/null; then
                    NEEDED_PATCHED=$((NEEDED_PATCHED + 1))
                    file_patched=1
                fi
            fi
        fi
    done < <(patchelf --print-needed "$so_file" 2>/dev/null)
    [ "$file_patched" -eq 1 ] && NEEDED_FILES=$((NEEDED_FILES + 1))
done
echo "    Stripped $SONAME_REMOVED DT_SONAME entries"
echo "    Patched $NEEDED_PATCHED DT_NEEDED -> unversioned in $NEEDED_FILES files"
echo "    Skipped $PATCHELF_SKIPPED files (< ${PATCHELF_MIN_SIZE} bytes, patchelf unsafe)"

# Step 4b: Post-patchelf ELF integrity validation ------------------------
# patchelf modifies ELF sections (DYNAMIC, dynstr).  On .so files with
# minimal section padding, this can produce a corrupt ELF that passes the
# patchelf exit code but fails at runtime with bionic linker CHECK errors.
#
# The critical check is PT_LOAD segment bounds: each LOAD segment's
# p_offset + p_filesz must be <= file size.  If a segment extends past EOF,
# the bionic linker's Load() fails with "Load CHECK 'did_read_' failed".
#
# Uses Python struct module for correct little-endian binary parsing.
echo "    Validating ELF integrity after patchelf..."
if ! python3 "$SCRIPT_DIR/validate_elf.py" "$JNI_DIR"; then
    echo "    ERROR: ELF validation failed! Aborting build."
    rm -rf "$STAGING"
    exit 1
fi

# Step 5: Validate DT_NEEDED — remove .so with unresolvable deps --------
# After patching, scan ALL .so files. Any that still have a DT_NEEDED
# pointing to a library NOT in jniLibs will crash at runtime (LD_PRELOAD
# loads everything, dlopen also checks deps).  Remove them now.
#
# This catches cases where:
#   - A C extension needs a library from a Termux package we don't download
#     (e.g. _curses_panel needs libpanelw.so from ncurses-ui-libs)
#   - A versioned DT_NEEDED couldn't be patched (target not in jniLibs)
#
# The loop repeats until stable to handle cascading removals.
echo "    Validating DT_NEEDED resolution..."
REMOVED_BROKEN=0
CHANGED=1
ITERATION=0
while [ "$CHANGED" -eq 1 ]; do
    CHANGED=0
    ITERATION=$((ITERATION + 1))
    for so_file in "$JNI_DIR"/*.so; do
        [ -f "$so_file" ] || continue
        # Never remove the Python executable itself
        [ "$(basename "$so_file")" = "libpython3exec.so" ] && continue
        has_broken=0
        broken_list=""
        while IFS= read -r needed; do
            [ -z "$needed" ] && continue
            # Skip system libs that Android's bionic always provides
            is_android_system_lib "$needed" && continue
            # Check BOTH versioned and unversioned deps
            # Versioned: libfoo.so.1 -> check if libfoo.so exists
            # Unversioned: libfoo.so -> check if libfoo.so exists
            if [[ "$needed" == *.so.* ]]; then
                unversioned="$(echo "$needed" | sed 's/\.so\..*/.so/')"
            else
                unversioned="$needed"
            fi
            if [ ! -f "$JNI_DIR/$unversioned" ]; then
                has_broken=1
                broken_list="$broken_list $needed"
            fi
        done < <(patchelf --print-needed "$so_file" 2>/dev/null)
        # If patchelf can't read this file at all, it may be corrupt.
        # Check the exit status of the last patchelf call in the pipeline.
        if ! patchelf --print-needed "$so_file" >/dev/null 2>&1; then
            echo "      REMOVE $(basename "$so_file"): patchelf cannot read (corrupt ELF)"
            rm -f "$so_file"
            REMOVED_BROKEN=$((REMOVED_BROKEN + 1))
            CHANGED=1
            continue
        fi
        if [ "$has_broken" -eq 1 ]; then
            echo "      REMOVE $(basename "$so_file"): unresolvable:$broken_list"
            rm -f "$so_file"
            REMOVED_BROKEN=$((REMOVED_BROKEN + 1))
            CHANGED=1
        fi
    done
done
echo "    Removed $REMOVED_BROKEN .so files with broken deps ($ITERATION passes)"

# Verify critical files exist
if [ ! -f "$JNI_DIR/libpython3exec.so" ]; then
    echo "ERROR: libpython3exec.so was not created!"
    echo "  Expected: $TERMUX_PREFIX/bin/python3.13"
    ls -la "$TERMUX_PREFIX/bin/python3.13" 2>/dev/null || echo "  File not found"
    rm -rf "$STAGING"
    exit 1
fi

# Verify critical shared libraries
CRITICAL_LIBS=(
    "libandroid-support.so"
    "libpython3.13.so"
    "libz.so"
)
for lib in "${CRITICAL_LIBS[@]}"; do
    if [ ! -f "$JNI_DIR/$lib" ]; then
        echo "WARNING: Critical library $lib not found in jniLibs"
    fi
done

# Final verification: scan ALL remaining .so files for ANY unresolvable
# DT_NEEDED.  If Step 5 worked correctly, this should find ZERO issues.
echo "    Final DT_NEEDED integrity check..."
FINAL_ISSUES=0
for so_file in "$JNI_DIR"/*.so; do
    [ -f "$so_file" ] || continue
    while IFS= read -r needed; do
        [ -z "$needed" ] && continue
        is_android_system_lib "$needed" && continue
        # Check BOTH versioned and unversioned deps
        if [[ "$needed" == *.so.* ]]; then
            unversioned="$(echo "$needed" | sed 's/\.so\..*/.so/')"
        else
            unversioned="$needed"
        fi
        if [ ! -f "$JNI_DIR/$unversioned" ]; then
            echo "    FAIL: $(basename "$so_file") needs $needed (not in jniLibs)"
            FINAL_ISSUES=$((FINAL_ISSUES + 1))
        fi
    done < <(patchelf --print-needed "$so_file" 2>/dev/null || true)
done
# Also check that patchelf can read every file (catches silent corruption)
for so_file in "$JNI_DIR"/*.so; do
    [ -f "$so_file" ] || continue
    if ! patchelf --print-needed "$so_file" >/dev/null 2>&1; then
        echo "    FAIL: $(basename "$so_file") — patchelf cannot read (corrupt ELF)"
        FINAL_ISSUES=$((FINAL_ISSUES + 1))
    fi
done
if [ "$FINAL_ISSUES" -eq 0 ]; then
    echo "    [OK] All DT_NEEDED entries resolvable"
else
    echo "    ERROR: $FINAL_ISSUES unresolvable DT_NEEDED entries remain!"
    echo "    These will cause runtime crashes.  Aborting."
    rm -rf "$STAGING"
    exit 1
fi

# Verify DT_SONAME stripped (spot check)
SONAME_CHECK=$(patchelf --print-soname "$JNI_DIR/libz.so" 2>/dev/null || true)
if [ -n "$SONAME_CHECK" ]; then
    echo "    WARNING: libz.so still has DT_SONAME: $SONAME_CHECK (should be empty)"
else
    echo "    [OK] libz.so: DT_SONAME stripped"
fi

JNI_COUNT=$(find "$JNI_DIR" -maxdepth 1 -name "*.so" | wc -l)
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

# -- 3. Generate native-libs-manifest.txt ------------------------------
# Build-time dependency manifest: lists every .so, its size, and its
# DT_NEEDED entries.  Bundled as an Android asset so the runtime can
# cross-check device state vs build expectations.
#
# This makes debugging IMMEDIATE: if a .so is missing or has wrong deps,
# the runtime logs the exact mismatch instead of a vague linker error.
echo ""
echo "==> Generating native-libs-manifest.txt..."
MANIFEST="$DIST_DIR/native-libs-manifest.txt"
{
    echo "# payload-toolkit native libs manifest"
    echo "# Generated: $(date -u +%Y-%m-%dT%H:%M:%SZ)"
    echo "# Total: $JNI_COUNT libs, $JNI_SIZE"
    echo "# FORMAT: filename | size_bytes | DT_NEEDED (comma-separated)"
    echo "#"
    for so_file in $(ls -1S "$JNI_DIR"/*.so); do
        [ -f "$so_file" ] || continue
        name="$(basename "$so_file")"
        size="$(stat -c%s "$so_file" 2>/dev/null || echo 0)"
        # Collect DT_NEEDED entries (excluding system libs)
        needed_list=$(patchelf --print-needed "$so_file" 2>/dev/null || true \
            | grep -v -E '^(libc|libm|libdl|libpthread|librt)\.so$' \
            | tr '\n' ',' | sed 's/,$//')
        echo "$name | $size | $needed_list"
    done
} > "$MANIFEST"
MANIFEST_SIZE=$(wc -c < "$MANIFEST")
echo "    $MANIFEST ($MANIFEST_SIZE bytes, $JNI_COUNT entries)"

# -- 4. Compile JNI bridge (libpybridge.so) --------------------------
# Compiles pybridge.c into a shared library that uses dlopen() to load
# libpython3.13.so and call Py_Main() directly — no execve(), no LD_PRELOAD,
# no linker namespace issues.
#
# Uses zig cc as cross-compiler (supports Android target, no NDK needed).
# zig produces a thin .so (~15 KB) with no dependencies beyond libc/libdl.
echo ""
echo "==> Compiling JNI bridge (libpybridge.so)..."

ZIG_VERSION="0.13.0"
ZIG_CACHE="$PROJECT_ROOT/.zig-cache"
ZIG_BIN="$ZIG_CACHE/zig-linux-x86_64-$ZIG_VERSION/zig"

if [ ! -x "$ZIG_BIN" ]; then
    echo "    Downloading zig $ZIG_VERSION (cross-compiler for Android)..."
    mkdir -p "$ZIG_CACHE"
    ZIG_ARCHIVE="$ZIG_CACHE/zig-linux-x86_64-$ZIG_VERSION.tar.xz"
    wget -q "https://ziglang.org/download/$ZIG_VERSION/zig-linux-x86_64-$ZIG_VERSION.tar.xz" -O "$ZIG_ARCHIVE" || {
        echo "    FAIL: Could not download zig"
        rm -rf "$STAGING"
        exit 1
    }
    tar xf "$ZIG_ARCHIVE" -C "$ZIG_CACHE"
    rm -f "$ZIG_ARCHIVE"
fi

# Find JDK include path for jni.h
JAVA_INCLUDE="${JAVA_HOME:-/usr/lib/jvm/default-java}/include"
JAVA_INCLUDE_LINUX="$JAVA_INCLUDE/linux"
if [ ! -f "$JAVA_INCLUDE/jni.h" ]; then
    # Try common JDK paths
    for jdk in "$JAVA_HOME" /usr/lib/jvm/java-17-openjdk-amd64 /usr/lib/jvm/default-java; do
        if [ -f "$jdk/include/jni.h" ]; then
            JAVA_INCLUDE="$jdk/include"
            JAVA_INCLUDE_LINUX="$jdk/include/linux"
            break
        fi
    done
fi
if [ ! -f "$JAVA_INCLUDE/jni.h" ]; then
    echo "    WARNING: jni.h not found at $JAVA_INCLUDE/jni.h"
    echo "    JNI bridge compilation may fail."
fi

BRIDGE_SRC="$SCRIPT_DIR/jni/pybridge.c"
BRIDGE_OUT="$JNI_DIR/libpybridge.so"

if [ -f "$BRIDGE_SRC" ]; then
    echo "    Compiling: $BRIDGE_SRC -> $BRIDGE_OUT"
    set +e  # Allow zig to fail gracefully (use exec fallback)
    "$ZIG_BIN" cc \
        -target aarch64-linux-musl \
        -shared -fPIC \
        -Wl,--no-undefined \
        -I"$JAVA_INCLUDE" \
        -I"$JAVA_INCLUDE_LINUX" \
        -O2 \
        -o "$BRIDGE_OUT" \
        "$BRIDGE_SRC" 2>&1
    ZIG_EXIT=$?
    set -e
    if [ $ZIG_EXIT -eq 0 ] && [ -f "$BRIDGE_OUT" ]; then
        BRIDGE_SIZE=$(stat -c%s "$BRIDGE_OUT" 2>/dev/null || echo 0)
        echo "    [OK] libpybridge.so compiled ($BRIDGE_SIZE bytes)"
    else
        echo "    WARNING: JNI bridge compilation failed"
        echo "    Python will use exec fallback (LD_PRELOAD + LD_LIBRARY_PATH)"
        # Remove any partial output
        rm -f "$BRIDGE_OUT"
    fi
else
    echo "    WARNING: $BRIDGE_SRC not found — skipping JNI bridge"
fi

# Recount after bridge compilation
JNI_COUNT=$(find "$JNI_DIR" -maxdepth 1 -name "*.so" | wc -l)
JNI_SIZE=$(du -sh "$JNI_DIR" | cut -f1)

# -- Summary ------------------------------------------------------------
echo ""
echo "==========================================="
echo "  jniLibs (native libs):"
echo "    Files:  $JNI_COUNT (includes libpybridge.so)"
echo "    Size:   $JNI_SIZE"
echo "    JNI bridge: $([ -f "$BRIDGE_OUT" ] && echo 'YES' || echo 'NO (exec fallback)')"
echo "  python-stdlib.zip (.py only):"
echo "    Files:  $STDLIB_COUNT"
echo "    Size:   $STDLIB_SIZE"
echo "  native-libs-manifest.txt:"
echo "    Size:   $MANIFEST_SIZE"
echo "  Output:  $DIST_DIR/python-stdlib.zip"
echo "==========================================="

# -- Cleanup ------------------------------------------------------------
rm -rf "$STAGING"
echo "Done!"
