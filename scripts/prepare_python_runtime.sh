#!/usr/bin/env bash
# prepare_python_runtime.sh — Prepare bundled Python runtime for Android APK
#
# Downloads Termux packages for multiple architectures and splits into:
#
#   1. jniLibs/<abi>/  — All .so files + Python binary (per architecture)
#      -> Extracted by Android package manager to nativeLibraryDir at install
#      -> nativeLibraryDir has SELinux app_lib_file context (EXECUTABLE)
#
#   2. dist/python-stdlib.zip — Python stdlib .py files + configs (shared)
#      -> Bundled as Android asset, extracted to app data at first launch
#      -> Read-only access, no exec permission needed
#
# Supported architectures:
#   aarch64   -> arm64-v8a   (64-bit ARM, modern devices)
#   arm       -> armeabi-v7a (32-bit ARM, older/budget devices)
#
# Why this split?  Android SELinux blocks execve() from app_data_file context.
# Files in nativeLibraryDir (from jniLibs) can be executed.  Pure .py files
# only need read access, so app data is fine for them.
#
# Packages: python 3.13, python-brotli, libandroid-support, liblzma,
#           libbz2, libcrypto, libsqlite3, libexpat, libsqlite, libffi, zlib, libcrypt

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
REPO="https://packages.termux.dev/apt/termux-main"
DIST_DIR="$PROJECT_ROOT/dist"

# -- Packages to download (identical for all architectures) ----------------
PACKAGES=(
    python
    python-brotli
    libandroid-posix-semaphore
    libandroid-support
    libbz2
    libcrypt
    libexpat
    libffi
    liblzma
    openssl
    sqlite
    libsqlite
    zlib
)

# -- Architecture configurations: termux_arch|android_abi|zig_target -------
ARCH_CONFIGS=(
    "aarch64|arm64-v8a|aarch64-linux-android"
    "arm|armeabi-v7a|arm-linux-androideabi"
)

mkdir -p "$DIST_DIR"

# -- Android system libs whitelist ------------------------------------------
# These are always available on Android (provided by bionic / platform).
# DO NOT bundle them — the linker resolves them from the system namespace.
is_android_system_lib() {
    # Strip version suffix: libc.so.6 -> libc.so, libdl.so.2 -> libdl.so
    local base="${1%%.so.*}"
    case "$base" in
        libc.so|libm.so|libdl.so|libpthread.so|librt.so|liblog.so) return 0 ;;
        *) return 1 ;;
    esac
}

# -- Helper functions (use dynamic scoping for STAGING/REPO) ---------------
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

# -- Pre-loop setup: install build tools -----------------------------------
echo "==> Checking build tools..."
if ! command -v patchelf &>/dev/null; then
    echo "    Installing patchelf..."
    sudo apt-get install -y -qq patchelf 2>/dev/null || {
        echo "ERROR: patchelf is required but could not be installed"
        exit 1
    }
fi

# -- Pre-loop setup: download zig cross-compiler (shared cache) ------------
ZIG_VERSION="0.13.0"
ZIG_CACHE="$PROJECT_ROOT/.zig-cache"
ZIG_BIN="$ZIG_CACHE/zig-linux-x86_64-$ZIG_VERSION/zig"
if [ ! -x "$ZIG_BIN" ]; then
    echo "    Downloading zig $ZIG_VERSION (cross-compiler for Android)..."
    mkdir -p "$ZIG_CACHE"
    ZIG_ARCHIVE="$ZIG_CACHE/zig-linux-x86_64-$ZIG_VERSION.tar.xz"
    wget -q "https://ziglang.org/download/$ZIG_VERSION/zig-linux-x86_64-$ZIG_VERSION.tar.xz" -O "$ZIG_ARCHIVE" || {
        echo "    FAIL: Could not download zig"
        exit 1
    }
    tar xf "$ZIG_ARCHIVE" -C "$ZIG_CACHE"
    rm -f "$ZIG_ARCHIVE"
fi

# -- Pre-loop setup: find JDK include path for jni.h ----------------------
JAVA_INCLUDE="${JAVA_HOME:-/usr/lib/jvm/default-java}/include"
JAVA_INCLUDE_LINUX="$JAVA_INCLUDE/linux"
if [ ! -f "$JAVA_INCLUDE/jni.h" ]; then
    for jdk in "${JAVA_HOME:-}" /usr/lib/jvm/java-17-openjdk-amd64 /usr/lib/jvm/default-java; do
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

# -- Manifest header (appended to per-architecture) -----------------------
MANIFEST="$DIST_DIR/native-libs-manifest.txt"
{
    echo "# payload-toolkit native libs manifest"
    echo "# Generated: $(date -u +%Y-%m-%dT%H:%M:%SZ)"
    echo "# Architectures: $(printf '%s, ' "${ARCH_CONFIGS[@]}" | sed 's/, $//')"
    echo "# FORMAT: abi | filename | size_bytes | DT_NEEDED (comma-separated)"
    echo "#"
} > "$MANIFEST"

# =========================================================================
#  Per-architecture processing loop
# =========================================================================
STDLIB_CREATED=0
TOTAL_JNI_COUNT=0

for arch_config in "${ARCH_CONFIGS[@]}"; do
    IFS='|' read -r ARCH JNI_ABI ZIG_TARGET <<< "$arch_config"
    STAGING=$(mktemp -d)
    JNI_DIR="$PROJECT_ROOT/android/app/src/main/jniLibs/$JNI_ABI"
    TERMUX_PREFIX="$STAGING/installed/data/data/com.termux/files/usr"
    STDLIB_STAGING="$STAGING/stdlib/lib/python3.13"

    mkdir -p "$JNI_DIR" "$STDLIB_STAGING"

    echo ""
    echo "========================================================"
    echo "  Architecture: $ARCH -> $JNI_ABI (zig: $ZIG_TARGET)"
    echo "========================================================"

    # -- Fetch package index -----------------------------------------------
    echo "==> Fetching Termux package index for ${ARCH}..."
    curl -sL "$REPO/dists/stable/main/binary-${ARCH}/Packages.gz" | gzip -d > "$STAGING/Packages"

    # -- Download packages -------------------------------------------------
    echo ""
    echo "==> Downloading packages for ${ARCH}..."
    for pkg in "${PACKAGES[@]}"; do
        download_and_extract "$pkg"
    done

    if [ ! -d "$TERMUX_PREFIX/lib" ]; then
        echo "ERROR: Termux prefix not found at ${TERMUX_PREFIX}"
        rm -rf "$STAGING"
        exit 1
    fi

    # =====================================================================
    #  1. Populate jniLibs/$JNI_ABI/
    # =====================================================================
    echo ""
    echo "==> Populating jniLibs/$JNI_ABI/..."

    # Clean ALL previous output (including versioned .so.X files from git)
    find "$JNI_DIR" -maxdepth 1 \( -name "*.so" -o -name "*.so.*" \) -delete 2>/dev/null || true

    # Python binary -> renamed to .so so Android packages it into lib/$JNI_ABI/
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
    # STRATEGY -- "unversioned + LD_PRELOAD":
    #
    #   BUILD-TIME (this script):
    #     1. Replace symlinks with real copies of their targets.
    #     2. Ensure unversioned .so exists for every versioned library.
    #     3. Set DT_SONAME to filename so linker matches DT_NEEDED lookups.
    #     4. Patch DT_NEEDED: versioned -> unversioned (libz.so.1 -> libz.so).
    #
    #   RUNTIME (PythonBridge.kt):
    #     LD_PRELOAD scans nativeLibraryDir for all .so files.
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
    UNVERSIONED_COUNT=0
    find "$JNI_DIR" -maxdepth 1 -name '*.so.*' -type f | while read -r f; do
        # Extract base name: libfoo.so.1.0 -> libfoo.so
        base="$(echo "$f" | sed 's/\.so\..*//')"
        if [ ! -f "$base" ]; then
            cp -a "$f" "$base"
            UNVERSIONED_COUNT=$((UNVERSIONED_COUNT + 1))
        fi
    done
    echo "    Created unversioned copies for versioned libraries"

    # Step 2b: Delete versioned .so.* files (AGP would drop them anyway).
    find "$JNI_DIR" -maxdepth 1 -name '*.so.*' -type f -delete 2>/dev/null || true
    echo "    Removed versioned .so.* files (AGP drops them)"

    # C extension modules from lib-dynload/
    # IMPORTANT: Copy BEFORE patchelf so these also get their DT_NEEDED fixed.
    if [ -d "$TERMUX_PREFIX/lib/python3.13/lib-dynload" ]; then
        find "$TERMUX_PREFIX/lib/python3.13/lib-dynload" -name "*.so" \( -type f -o -type l \) | while read -r f; do
            cp -a "$f" "$JNI_DIR/"
        done
    fi

    # Third-party C extension modules from site-packages/.
    # stdlib extensions live in lib-dynload/, but pip-style packages install their
    # .so files in site-packages/ instead.  Example: python-brotli installs
    # _brotli.cpython-313-aarch64-linux-android.so in site-packages/.
    # IMPORTANT: Copy BEFORE patchelf so these also get their DT_NEEDED fixed.
    SITE_PACKAGES="$TERMUX_PREFIX/lib/python3.13/site-packages"
    if [ -d "$SITE_PACKAGES" ]; then
        find "$SITE_PACKAGES" -maxdepth 2 -name "*.so" \( -type f -o -type l \) | while read -r f; do
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
    find "$JNI_DIR" -maxdepth 1 -name "_curses*.so" -delete 2>/dev/null || true
    find "$JNI_DIR" -maxdepth 1 -name "readline*.so" -delete 2>/dev/null || true
    find "$JNI_DIR" -maxdepth 1 -name "_gdbm*.so" -delete 2>/dev/null || true
    find "$JNI_DIR" -maxdepth 1 -name "_dbm*.so" -delete 2>/dev/null || true

    # Strip terminal/UI shared libraries not needed for payload_toolkit.
    # These are transitive deps of the stripped extension modules above.
    # No remaining .so (libpython3.13.so, extension modules) depends on them.
    # Keeping them causes DT_NEEDED resolution failures at runtime:
    #   libreadline.so needs libncursesw.so.6 (versioned) -> not found -> crash
    # Removing them saves ~2.8 MB and eliminates the broken DT_NEEDED chain.
    TERMINAL_LIBS=(
        "libreadline.so"
        "libhistory.so"
        "libncursesw.so"
        "libncurses.so"
        "libcurses.so"
        "libtermcap.so"
        "libtic.so"
        "libtinfo.so"
    )
    REMOVED_TERMINAL=0
    for lib in "${TERMINAL_LIBS[@]}"; do
        if [ -f "$JNI_DIR/$lib" ]; then
            rm -f "$JNI_DIR/$lib"
            REMOVED_TERMINAL=$((REMOVED_TERMINAL + 1))
        fi
    done
    if [ "$REMOVED_TERMINAL" -gt 0 ]; then
        echo "    Removed $REMOVED_TERMINAL terminal/UI shared libs (not needed)"
    fi

    # =====================================================================
    #  2. Patch ELF headers (DT_SONAME + DT_NEEDED)
    # =====================================================================
    #
    # IMPORTANT: This must run AFTER all .so files are in jniLibs (including
    # lib-dynload extension modules) so they ALL get patched.

    echo "    Patching ELF headers..."

    SONAME_REMOVED=0
    PATCHELF_SKIPPED=0
    PATCHELF_MIN_SIZE=8192

    for so_file in "$JNI_DIR"/*.so; do
        [ -f "$so_file" ] || continue
        file_size=$(stat -c%s "$so_file" 2>/dev/null || echo 0)
        if [ "$file_size" -lt "$PATCHELF_MIN_SIZE" ]; then
            PATCHELF_SKIPPED=$((PATCHELF_SKIPPED + 1))
            continue
        fi

        FILENAME=$(basename "$so_file")
        SONAME_CURRENT=$(patchelf --print-soname "$so_file" 2>/dev/null || true)
        SONAME_OK=0

        # Only patch DT_SONAME if the file ALREADY has one.
        # Extension modules typically do NOT have DT_SONAME.
        if [ -z "$SONAME_CURRENT" ]; then
            continue
        fi

        if [ "$SONAME_CURRENT" = "$FILENAME" ]; then
            SONAME_OK=1
        elif patchelf --set-soname "$FILENAME" "$so_file" 2>/dev/null && \
             [ "$(patchelf --print-soname "$so_file" 2>/dev/null)" = "$FILENAME" ]; then
            SONAME_OK=1
        fi
        if [ "$SONAME_OK" -eq 0 ]; then
            if python3 -c "
import sys; sys.path.insert(0, '$SCRIPT_DIR')
from validate_elf import fix_soname
sys.exit(0 if fix_soname('$so_file', '$FILENAME') else 1)" 2>/dev/null; then
                SONAME_OK=1
            fi
        fi
        if [ "$SONAME_OK" -eq 1 ]; then
            SONAME_REMOVED=$((SONAME_REMOVED + 1))
        fi
    done
    echo "    Renamed $SONAME_REMOVED DT_SONAME -> filename"
    echo "    Skipped $PATCHELF_SKIPPED files (< ${PATCHELF_MIN_SIZE} bytes, patchelf unsafe)"

    # Step 3b: Python in-place .dynstr patching for ALL .so files.
    echo "    Patching DT_NEEDED (versioned -> unversioned) in all files..."
    PYTHON_NEEDED=0
    PYTHON_NEEDED_FILES=0
    for so_file in "$JNI_DIR"/*.so; do
        [ -f "$so_file" ] || continue
        FILENAME=$(basename "$so_file")
        PATCHED=$(python3 -c "
import sys; sys.path.insert(0, '$SCRIPT_DIR')
from validate_elf import fix_needed_all
print(fix_needed_all('$so_file', '$JNI_DIR'))")
        PATCHED=${PATCHED:-0}
        if [ "$PATCHED" -gt 0 ]; then
            echo "      $FILENAME: patched $PATCHED DT_NEEDED"
            PYTHON_NEEDED=$((PYTHON_NEEDED + PATCHED))
            PYTHON_NEEDED_FILES=$((PYTHON_NEEDED_FILES + 1))
        fi
    done
    echo "    Python patcher: patched $PYTHON_NEEDED DT_NEEDED in $PYTHON_NEEDED_FILES files"

    # Step 3b-2: Strip RPATH/RUNPATH from all .so files.
    # Termux extension modules embed RPATH pointing to
    # /data/data/com.termux/files/usr/lib which does not exist on
    # non-Termux devices.  On older ARM32 bionic linkers, this causes
    # DT_NEEDED resolution to fail instead of falling back.
    echo "    Stripping RPATH/RUNPATH (Termux paths)..."
    PYTHON_RPATH=0
    for so_file in "$JNI_DIR"/*.so; do
        [ -f "$so_file" ] || continue
        FILENAME=$(basename "$so_file")
        STRIPPED=$(python3 -c "
import sys; sys.path.insert(0, '$SCRIPT_DIR')
from validate_elf import strip_rpath
print('1' if strip_rpath('$so_file') else '0')")
        STRIPPED=${STRIPPED:-0}
        if [ "$STRIPPED" -gt 0 ]; then
            echo "      $FILENAME: RPATH/RUNPATH stripped"
            PYTHON_RPATH=$((PYTHON_RPATH + 1))
        fi
    done
    echo "    Stripped RPATH/RUNPATH from $PYTHON_RPATH files"

    # Step 3c: DT_NEEDED audit
    echo "    Auditing DT_NEEDED for remaining versioned entries..."
    if ! python3 "$SCRIPT_DIR/validate_elf.py" --audit-needed "$JNI_DIR"; then
        echo "    WARNING: Versioned DT_NEEDED entries remain after patching!"
        echo "    These may cause runtime dlopen failures on Android."
    fi

    # =====================================================================
    #  3. ELF integrity validation
    # =====================================================================
    echo "    Validating ELF integrity after patching..."
    if ! python3 "$SCRIPT_DIR/validate_elf.py" "$JNI_DIR"; then
        echo "    ERROR: ELF validation failed for $JNI_ABI! Aborting build."
        rm -rf "$STAGING"
        exit 1
    fi

    # =====================================================================
    #  4. Validate DT_NEEDED resolution — remove broken .so files
    # =====================================================================
    # Extension modules (from lib-dynload/) that start with "_" are
    # Python C extensions.  Their DT_NEEDED may reference versioned libs
    # that the linker resolves at runtime via LD_PRELOAD.  On arm64-v8a,
    # the SONAME patching sometimes fails to create the correct unversioned
    # symlink, causing these extensions to be falsely removed.
    # Skip removal for these protected extension modules.
    # Protected base names — matched with wildcard suffix.
    # Actual on-device filenames include platform tags:
    #   _hashlib.cpython-313-arm-linux-androideabi.so
    #   _hashlib.cpython-313-aarch64-linux-android.so
    # So we use prefix matching (*=suffix glob).
    PROTECTED_EXTENSIONS=(
        "_hashlib.so"
        "_bz2.so"
        "_lzma.so"
        "_sha256.so"
        "_sha1.so"
        "_sha512.so"
        "_md5.so"
        "_socket.so"
        "_struct.so"
        "_array.so"
        "_codecs_cn.so"
        "_codecs_hk.so"
        "_codecs_iso2022.so"
        "_codecs_jp.so"
        "_codecs_kr.so"
        "_codecs_tw.so"
        "_multibytecodec.so"
        "_ssl.so"
        "_elementtree.so"
        "_pickle.so"
        "_json.so"
        "_csv.so"
        "_decimal.so"
        "_datetime.so"
        "unicodedata.so"
        "_ctypes.so"
        "_asyncio.so"
        "_blake2.so"
        "_sha3.so"
        "_random.so"
        "math.so"
        "cmath.so"
        "_bisect.so"
        "_heapq.so"
        "_queue.so"
        "_opcode.so"
        "_statistics.so"
        "_contextvars.so"
        "_interpchannels.so"
        "_interpreters.so"
        "_interpqueues.so"
        "_posixsubprocess.so"
        "select.so"
        "mmap.so"
        "_lzma.so"
        "termios.so"
        "fcntl.so"
        "resource.so"
        "grp.so"
        "syslog.so"
        "binascii.so"
        "array.so"
        "_lsprof.so"
        "_multiprocessing.so"
        "_posixshmem.so"
        "_zoneinfo.so"
        "_sqlite3.so"
        "_brotli.so"
    )
    _is_protected() {
        local name="$1"
        # Match all Python C extension modules: *.cpython-*-*.so
        # These are loaded by Python's import machinery, not the linker.
        [[ "$name" == *.cpython-*-*.so ]] && return 0
        # Also match by base-name prefix for completeness
        for p in "${PROTECTED_EXTENSIONS[@]}"; do
            [[ "$name" == "$p"* ]] && return 0
        done
        return 1
    }

    echo "    Validating DT_NEEDED resolution..."
    REMOVED_BROKEN=0
    PROTECTED_SKIPPED=0
    CHANGED=1
    ITERATION=0
    while [ "$CHANGED" -eq 1 ]; do
        CHANGED=0
        ITERATION=$((ITERATION + 1))
        for so_file in "$JNI_DIR"/*.so; do
            [ -f "$so_file" ] || continue
            [ "$(basename "$so_file")" = "libpython3exec.so" ] && continue
            has_broken=0
            broken_list=""
            while IFS= read -r needed; do
                [ -z "$needed" ] && continue
                is_android_system_lib "$needed" && continue
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
            if ! patchelf --print-needed "$so_file" >/dev/null 2>&1; then
                echo "      REMOVE $(basename "$so_file"): patchelf cannot read (corrupt ELF)"
                rm -f "$so_file"
                REMOVED_BROKEN=$((REMOVED_BROKEN + 1))
                CHANGED=1
                continue
            fi
            if [ "$has_broken" -eq 1 ]; then
                if _is_protected "$(basename "$so_file")"; then
                    echo "      SKIP $(basename "$so_file"): protected extension (deps may resolve at runtime)$broken_list"
                    PROTECTED_SKIPPED=$((PROTECTED_SKIPPED + 1))
                else
                    echo "      REMOVE $(basename "$so_file"): unresolvable:$broken_list"
                    rm -f "$so_file"
                    REMOVED_BROKEN=$((REMOVED_BROKEN + 1))
                    CHANGED=1
                fi
            fi
        done
    done
    echo "    Removed $REMOVED_BROKEN .so files with broken deps ($ITERATION passes)"
    [ "$PROTECTED_SKIPPED" -gt 0 ] && echo "    Protected $PROTECTED_SKIPPED extension modules from removal"

    # -- Verify critical files exist ----------------------------------------
    if [ ! -f "$JNI_DIR/libpython3exec.so" ]; then
        echo "ERROR: libpython3exec.so was not created for $JNI_ABI!"
        echo "  Expected: $TERMUX_PREFIX/bin/python3.13"
        ls -la "$TERMUX_PREFIX/bin/python3.13" 2>/dev/null || echo "  File not found"
        rm -rf "$STAGING"
        exit 1
    fi

    CRITICAL_LIBS=(
        "libandroid-support.so"
        "libpython3.13.so"
        "libz.so"
        "libcrypto.so"
        "libssl.so"
        "libbz2.so"
        "liblzma.so"
    )
    for lib in "${CRITICAL_LIBS[@]}"; do
        if [ ! -f "$JNI_DIR/$lib" ]; then
            echo "WARNING: Critical library $lib not found in jniLibs/$JNI_ABI"
        fi
    done

    # -- Final DT_NEEDED integrity check -----------------------------------
    # Protected extension modules are skipped — Python's import machinery
    # handles missing deps at import time (ImportError, not crash).
    # Only non-extension .so files with broken deps are hard failures.
    echo "    Final DT_NEEDED integrity check..."
    FINAL_ISSUES=0
    PROTECTED_WARNINGS=0
    for so_file in "$JNI_DIR"/*.so; do
        [ -f "$so_file" ] || continue
        FILENAME=$(basename "$so_file")
        while IFS= read -r needed; do
            [ -z "$needed" ] && continue
            is_android_system_lib "$needed" && continue
            if [[ "$needed" == *.so.* ]]; then
                unversioned="$(echo "$needed" | sed 's/\.so\..*/.so/')"
            else
                unversioned="$needed"
            fi
            if [ ! -f "$JNI_DIR/$unversioned" ] && [ ! -f "$JNI_DIR/$needed" ]; then
                if _is_protected "$FILENAME"; then
                    echo "    WARN: $FILENAME needs $needed (will fail at import, not crash)"
                    PROTECTED_WARNINGS=$((PROTECTED_WARNINGS + 1))
                else
                    echo "    FAIL: $FILENAME needs $needed (not in jniLibs/$JNI_ABI)"
                    FINAL_ISSUES=$((FINAL_ISSUES + 1))
                fi
            fi
        done < <(patchelf --print-needed "$so_file" 2>/dev/null || true)
    done
    for so_file in "$JNI_DIR"/*.so; do
        [ -f "$so_file" ] || continue
        if ! patchelf --print-needed "$so_file" >/dev/null 2>&1; then
            echo "    FAIL: $(basename "$so_file") — patchelf cannot read (corrupt ELF)"
            FINAL_ISSUES=$((FINAL_ISSUES + 1))
        fi
    done
    [ "$PROTECTED_WARNINGS" -gt 0 ] && echo "    $PROTECTED_WARNINGS protected extension warnings (non-fatal)"
    if [ "$FINAL_ISSUES" -eq 0 ]; then
        echo "    [OK] All DT_NEEDED entries resolvable"
    else
        echo "    ERROR: $FINAL_ISSUES unresolvable DT_NEEDED entries remain for $JNI_ABI!"
        echo "    These will cause runtime crashes.  Aborting."
        rm -rf "$STAGING"
        exit 1
    fi

    # Verify DT_SONAME matches filename (spot check)
    if [ -f "$JNI_DIR/libz.so" ]; then
        SONAME_CHECK=$(patchelf --print-soname "$JNI_DIR/libz.so" 2>/dev/null || true)
        if [ "$SONAME_CHECK" != "libz.so" ]; then
            echo "    WARNING: libz.so has DT_SONAME: $SONAME_CHECK (should be libz.so)"
        else
            echo "    [OK] libz.so: DT_SONAME = libz.so"
        fi
    fi

    JNI_COUNT=$(find "$JNI_DIR" -maxdepth 1 -name "*.so" | wc -l)
    JNI_SIZE=$(du -sh "$JNI_DIR" | cut -f1)
    TOTAL_JNI_COUNT=$((TOTAL_JNI_COUNT + JNI_COUNT))
    echo "    $JNI_ABI: $JNI_COUNT native libraries ($JNI_SIZE)"

    # =====================================================================
    #  5. Create python-stdlib.zip (first architecture only)
    # =====================================================================
    # Stlib .py files are identical across architectures — create once.
    if [ "$STDLIB_CREATED" -eq 0 ]; then
        echo ""
        echo "==> Creating python-stdlib.zip..."

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
        STDLIB_CREATED=1
        echo "    $STDLIB_COUNT files ($STDLIB_SIZE)"
    fi

    # =====================================================================
    #  6. Compile JNI bridge (libpybridge.so) for this architecture
    # =====================================================================
    echo ""
    echo "==> Compiling JNI bridge for $JNI_ABI..."
    BRIDGE_OUT="$JNI_DIR/libpybridge.so"

    if [ -f "$BRIDGE_SRC" ]; then
        echo "    Compiling: $BRIDGE_SRC -> $BRIDGE_OUT (target: $ZIG_TARGET)"
        set +e  # Allow zig to fail gracefully (use exec fallback)
        "$ZIG_BIN" cc \
            -target "$ZIG_TARGET" \
            -shared -fPIC \
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
            echo "    WARNING: JNI bridge compilation failed for $JNI_ABI"
            echo "    Python will use exec fallback (LD_PRELOAD + LD_LIBRARY_PATH)"
            rm -f "$BRIDGE_OUT"
        fi
    else
        echo "    WARNING: $BRIDGE_SRC not found — skipping JNI bridge"
    fi

    # Recount after bridge compilation
    JNI_COUNT=$(find "$JNI_DIR" -maxdepth 1 -name "*.so" | wc -l)
    JNI_SIZE=$(du -sh "$JNI_DIR" | cut -f1)

    # -- Append to manifest ------------------------------------------------
    for so_file in $(ls -1S "$JNI_DIR"/*.so); do
        [ -f "$so_file" ] || continue
        name="$(basename "$so_file")"
        size="$(stat -c%s "$so_file" 2>/dev/null || echo 0)"
        # Collect DT_NEEDED entries (excluding system libs)
        needed_list=$(patchelf --print-needed "$so_file" 2>/dev/null || true \
            | grep -v -E '^(libc|libm|libdl|libpthread|librt)\.so$' \
            | tr '\n' ',' | sed 's/,$//')
        echo "$JNI_ABI | $name | $size | $needed_list" >> "$MANIFEST"
    done

    # -- Cleanup per-arch staging ------------------------------------------
    rm -rf "$STAGING"
done

# =========================================================================
#  Final summary
# =========================================================================
MANIFEST_SIZE=$(wc -c < "$MANIFEST")
echo ""
echo "==========================================="
echo "  jniLibs (native libs):"
echo "    Total:   $TOTAL_JNI_COUNT files across all architectures"
for arch_config in "${ARCH_CONFIGS[@]}"; do
    IFS='|' read -r ARCH JNI_ABI ZIG_TARGET <<< "$arch_config"
    JNI_DIR="$PROJECT_ROOT/android/app/src/main/jniLibs/$JNI_ABI"
    if [ -d "$JNI_DIR" ]; then
        COUNT=$(find "$JNI_DIR" -maxdepth 1 -name "*.so" | wc -l)
        SIZE=$(du -sh "$JNI_DIR" | cut -f1)
        BRIDGE_STATUS=$([ -f "$JNI_DIR/libpybridge.so" ] && echo 'YES' || echo 'NO (exec fallback)')
        echo "    $JNI_ABI: $COUNT files ($SIZE), bridge: $BRIDGE_STATUS"
    fi
done
echo "  python-stdlib.zip (.py only):"
echo "    Files:  $STDLIB_COUNT"
echo "    Size:   $STDLIB_SIZE"
echo "  native-libs-manifest.txt:"
echo "    Size:   $MANIFEST_SIZE"
echo "==========================================="
echo "Done!"
