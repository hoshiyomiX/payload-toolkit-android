#!/usr/bin/env bash
# ═══════════════════════════════════════════════════════════════════════════
#  setup_chaquopy.sh — Set up Chaquopy environment for local APK builds
#
#  This script downloads the Chaquopy Gradle plugin and configures the
#  Android project to use it. Run this once before building the APK.
#
#  Prerequisites:
#    - Android Studio 2022.3+ (or Gradle 8.4+ with AGP 8.2+)
#    - JDK 17
#    - Internet connection (to download Chaquopy plugin JAR)
#
#  Usage:
#    chmod +x scripts/setup_chaquopy.sh
#    ./scripts/setup_chaquopy.sh
#
# ═══════════════════════════════════════════════════════════════════════════

set -euo pipefail

# ── Configuration ──────────────────────────────────────────────────────

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
ANDROID_DIR="$PROJECT_ROOT/android"
CHAQUOPY_DIR="$ANDROID_DIR/chaquopy_plugin"

# Chaquopy version
CHAQUOPY_VERSION="15.0.1"

# Chaquopy plugin JAR URL (from GitHub releases)
CHAQUOPY_PLUGIN_JAR="chaquopy-gradle-plugin-${CHAQUOPY_VERSION}.jar"
CHAQUOPY_RELEASE_URL="https://github.com/nicholasgasior/chaquopy/releases/download/v${CHAQUOPY_VERSION}/${CHAQUOPY_PLUGIN_JAR}"

# Fallback: Chaquopy Maven URL
CHAQUOPY_MAVEN_URL="https://chaquo.com/maven/com/chaquo/python/gradle/${CHAQUOPY_VERSION}/${CHAQUOPY_PLUGIN_JAR}"

# ═══════════════════════════════════════════════════════════════════════════
#  1. Check prerequisites
# ═══════════════════════════════════════════════════════════════════════════

echo "╔══════════════════════════════════════════════════════════════╗"
echo "║  Chaquopy Setup — Python on Android                        ║"
echo "║  Version: ${CHAQUOPY_VERSION}                                       ║"
echo "╚══════════════════════════════════════════════════════════════╝"
echo ""

# Check for Android project
if [ ! -f "$ANDROID_DIR/build.gradle.kts" ]; then
    echo "ERROR: Android project not found at $ANDROID_DIR"
    echo "  Expected: android/build.gradle.kts"
    exit 1
fi

echo "[OK] Android project found at $ANDROID_DIR"

# Check for curl or wget
if command -v curl &>/dev/null; then
    DOWNLOAD_CMD="curl -L -o"
elif command -v wget &>/dev/null; then
    DOWNLOAD_CMD="wget -O"
else
    echo "ERROR: Neither curl nor wget is available."
    echo "  Install one of them to download the Chaquopy plugin."
    exit 1
fi

echo "[OK] Download tool available"

# Check for Java (needed for Gradle)
if command -v java &>/dev/null; then
    JAVA_VERSION=$(java -version 2>&1 | head -1 | cut -d'"' -f2)
    echo "[OK] Java $JAVA_VERSION"
else
    echo "WARNING: Java not found. Gradle will not work without JDK 17."
fi

echo ""

# ═══════════════════════════════════════════════════════════════════════════
#  2. Create plugin directory
# ═══════════════════════════════════════════════════════════════════════════

echo "[1/4] Creating Chaquopy plugin directory..."

mkdir -p "$CHAQUOPY_DIR"

echo "  Directory: $CHAQUOPY_DIR"

# ═══════════════════════════════════════════════════════════════════════════
#  3. Download Chaquopy plugin JAR
# ═══════════════════════════════════════════════════════════════════════════

PLUGIN_JAR_PATH="$CHAQUOPY_DIR/$CHAQUOPY_PLUGIN_JAR"

if [ -f "$PLUGIN_JAR_PATH" ]; then
    JAR_SIZE=$(stat -c%s "$PLUGIN_JAR_PATH" 2>/dev/null || stat -f%z "$PLUGIN_JAR_PATH" 2>/dev/null)
    JAR_SIZE_MB=$(echo "scale=1; $JAR_SIZE / 1048576" | bc 2>/dev/null || echo "unknown")
    echo "[2/4] Chaquopy plugin JAR already exists ($JAR_SIZE_MB MB)"
    echo "  Path: $PLUGIN_JAR_PATH"
    echo "  To re-download, delete the file and re-run this script."
else
    echo "[2/4] Downloading Chaquopy Gradle plugin v${CHAQUOPY_VERSION}..."

    # Try GitHub release first, fallback to Maven
    echo "  Trying: $CHAQUOPY_RELEASE_URL"
    if $DOWNLOAD_CMD "$PLUGIN_JAR_PATH" "$CHAQUOPY_RELEASE_URL" 2>/dev/null; then
        # Verify download
        if [ -f "$PLUGIN_JAR_PATH" ] && [ "$(stat -c%s "$PLUGIN_JAR_PATH" 2>/dev/null || stat -f%z "$PLUGIN_JAR_PATH" 2>/dev/null)" -gt 1000 ]; then
            echo "  [OK] Downloaded from GitHub releases"
        else
            echo "  [WARN] GitHub download may have failed, trying Maven..."
            rm -f "$PLUGIN_JAR_PATH"
        fi
    fi

    # Fallback to Maven
    if [ ! -f "$PLUGIN_JAR_PATH" ]; then
        echo "  Trying: $CHAQUOPY_MAVEN_URL"
        $DOWNLOAD_CMD "$PLUGIN_JAR_PATH" "$CHAQUOPY_MAVEN_URL"
        echo "  [OK] Downloaded from Chaquopy Maven"
    fi

    if [ ! -f "$PLUGIN_JAR_PATH" ]; then
        echo ""
        echo "ERROR: Failed to download Chaquopy plugin JAR."
        echo ""
        echo "  Manual download required:"
        echo "  1. Visit: https://chaquo.com/chaquopy/doc/current/download.html"
        echo "  2. Download: chaquopy-gradle-plugin-${CHAQUOPY_VERSION}.jar"
        echo "  3. Place it in: $CHAQUOPY_DIR/"
        exit 1
    fi
fi

JAR_SIZE=$(stat -c%s "$PLUGIN_JAR_PATH" 2>/dev/null || stat -f%z "$PLUGIN_JAR_PATH" 2>/dev/null)
JAR_SIZE_MB=$(echo "scale=1; $JAR_SIZE / 1048576" | bc 2>/dev/null || echo "unknown")
echo "  Plugin JAR: $PLUGIN_JAR_PATH ($JAR_SIZE_MB MB)"

# ═══════════════════════════════════════════════════════════════════════════
#  4. Configure settings.gradle.kts for local plugin
# ═══════════════════════════════════════════════════════════════════════════

echo "[3/4] Configuring Gradle settings..."

SETTINGS_FILE="$ANDROID_DIR/settings.gradle.kts"

if [ ! -f "$SETTINGS_FILE" ]; then
    echo "ERROR: settings.gradle.kts not found at $SETTINGS_FILE"
    exit 1
fi

echo "  settings.gradle.kts already configured (see project setup)"
echo "  The Chaquopy plugin is declared in app/build.gradle.kts"

# ═══════════════════════════════════════════════════════════════════════════
#  5. Verify Python source package
# ═══════════════════════════════════════════════════════════════════════════

echo "[4/4] Verifying Python source package..."

PYTHON_PKG="$PROJECT_ROOT/src/payload_toolkit"

if [ -f "$PYTHON_PKG/__init__.py" ]; then
    echo "  [OK] payload_toolkit package found at $PYTHON_PKG"

    # List Python modules
    MODULE_COUNT=$(find "$PYTHON_PKG" -name "*.py" -not -name "__pycache__" | wc -l)
    echo "  [OK] $MODULE_COUNT Python module(s) found"

    # Verify critical modules
    REQUIRED_MODULES=(
        "__init__.py"
    )

    MISSING=()
    for mod in "${REQUIRED_MODULES[@]}"; do
        if [ ! -f "$PYTHON_PKG/$mod" ]; then
            MISSING+=("$mod")
        fi
    done

    if [ ${#MISSING[@]} -gt 0 ]; then
        echo "  [WARN] Missing required modules: ${MISSING[*]}"
    fi
else
    echo "  [WARN] payload_toolkit package not found at $PYTHON_PKG"
    echo "  The Python source will need to be created before building."
fi

# ═══════════════════════════════════════════════════════════════════════════
#  Done
# ═══════════════════════════════════════════════════════════════════════════

echo ""
echo "══════════════════════════════════════════════════════════════"
echo "  SETUP COMPLETE"
echo "══════════════════════════════════════════════════════════════"
echo ""
echo "  Chaquopy plugin: v${CHAQUOPY_VERSION}"
echo "  Plugin JAR:      $PLUGIN_JAR_PATH"
echo "  Python source:   $PYTHON_PKG"
echo ""
echo "  Next steps:"
echo "    cd android"
echo "    ./gradlew assembleDebug"
echo ""
echo "  The debug APK will be at:"
echo "    android/app/build/outputs/apk/debug/app-debug.apk"
echo ""
echo "  Troubleshooting:"
echo "  - If Gradle can't find the Chaquopy plugin, check:"
echo "    1. The JAR exists at $PLUGIN_JAR_PATH"
echo "    2. settings.gradle.kts includes the Chaquopy Maven repo"
echo "  - If Python modules aren't found during build, ensure:"
echo "    1. src/payload_toolkit/ exists with __init__.py"
echo "    2. app/build.gradle.kts has: python.srcDir '../../src/payload_toolkit'"
echo ""
