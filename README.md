# Payload Toolkit Android

A **non-root** Android APK that brings the full power of [`payload_toolkit.py`](src/payload_toolkit/) to your device — inspect, extract, generate, package, and sign AOSP OTA `payload.bin` files directly on your phone, no PC required.

> **No root. No Termux. No PC.** Just an APK and a `payload.bin`.

---

## Features

| Mode   | Command     | Description                                                        | Root Required |
|--------|-------------|--------------------------------------------------------------------|:-------------:|
| **INFO**  | `info`  | Parse and display payload.bin metadata (partitions, sizes, ops, signatures) | No |
| **DUMP**  | `dump`  | Extract partition images (`.img`) from an existing `payload.bin`   | No |
| **GEN**   | `gen`   | Generate a partial `payload.bin` from one or more `.img` files     | No |
| **ZIP**   | `zip`   | Generate a flashable OTA ZIP (AOSP format) from partition images  | No |
| **SIGN**  | `sign`  | Sign an existing `payload.bin` with RSA key (adds Signatures block) | No |
| ~~DD~~  | ~~dd~~  | ~~Generate dd-based flashable ZIP~~ *(requires block device access)* | **Yes** |

### Non-Root Limitations

- **DD/flash mode is disabled** — writing directly to block devices (`/dev/block/by-name/*`) requires root privileges
- All file I/O is constrained to app-specific storage (`/data/data/com.hoshiyomi.payloadtoolkit/`) and user-selected shared storage (via SAF/SAF picker)
- No access to `/system`, `/vendor`, or other protected partitions for reading

### Compression Support

| Algorithm | InstallOperation | Notes |
|-----------|-----------------|-------|
| `none`    | REPLACE          | No compression, fastest generation |
| `bzip2`   | REPLACE_BZ       | Standard OTA, best compatibility |
| `gzip`    | REPLACE_BZ       | Non-standard but works everywhere |
| `xz`      | REPLACE_XZ       | Smallest output, slower |
| `brotli`  | BROTLI_BZ        | Fast decompress, Android 11+ |

---

## Architecture

```
┌─────────────────────────────────────────────────────────┐
│                    Android APK                          │
│  ┌───────────────────────────────────────────────────┐  │
│  │              Kotlin UI Layer                      │  │
│  │  MainActivity (Material Design 3)                  │  │
│  │  ├─ File picker (payload.bin / .img)               │  │
│  │  ├─ Mode selector (info/dump/gen/zip/sign)         │  │
│  │  ├─ Options panel (compression, partitions, etc.)   │  │
│  │  ├─ Progress bar + log output                      │  │
│  │  └─ Output file picker                             │  │
│  └───────────────────────┬───────────────────────────┘  │
│                          │                              │
│  ┌───────────────────────▼───────────────────────────┐  │
│  │           PayloadBridge.kt                        │  │
│  │  Translates UI actions → Python function calls     │  │
│  └───────────────────────┬───────────────────────────┘  │
│                          │                              │
│  ┌───────────────────────▼───────────────────────────┐  │
│  │           Chaquopy Bridge (PythonBridge.kt)       │  │
│  │  Python.startModule() / callModuleFunc()           │  │
│  └───────────────────────┬───────────────────────────┘  │
│                          │                              │
│  ┌───────────────────────▼───────────────────────────┐  │
│  │        Python Runtime (CPython 3.12)              │  │
│  │  payload_toolkit/                                   │  │
│  │  ├─ __init__.py (CLI bridge)                       │  │
│  │  ├─ protobuf.py (minimal PB encoder/decoder)       │  │
│  │  ├─ compression.py (gzip/bz2/xz/brotli)           │  │
│  │  ├─ payload.py (read/write payload.bin)            │  │
│  │  ├─ ota_metadata.py (OTA ZIP metadata gen)         │  │
│  │  └─ modes/                                          │  │
│  │      ├─ info.py                                     │  │
│  │      ├─ dump.py                                     │  │
│  │      ├─ gen.py                                      │  │
│  │      ├─ zip.py                                      │  │
│  │      └─ sign.py                                     │  │
│  └───────────────────────────────────────────────────┘  │
│                                                         │
│  ┌───────────────────────────────────────────────────┐  │
│  │        Android Storage                             │  │
│  │  ├─ App-internal: /data/data/.../files/            │  │
│  │  └─ Shared (SAF): user-selected via picker         │  │
│  └───────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────┘
```

### Key Components

- **Chaquopy** embeds CPython 3.12 inside the APK — no external Python runtime needed
- **payload_toolkit.py** is refactored into a Python package (`src/payload_toolkit/`) for clean module imports
- **Zero external Python dependencies** — the tool implements a minimal protobuf encoder/decoder from scratch, using only Python stdlib modules (`gzip`, `bz2`, `lzma`, `zipfile`, `hashlib`)
- **PayloadBridge.kt** provides a type-safe Kotlin API that serializes arguments and return values across the Python/Kotlin boundary

---

## Build Requirements

| Tool | Version | Notes |
|------|---------|-------|
| Android Studio | Hedgehog (2023.1.1)+ | Or IntelliJ IDEA with Android plugin |
| Android SDK | API 34 | compileSdk |
| Android NDK | 26.1+ | For native build if needed |
| Gradle | 8.4+ | Via Android Gradle Plugin 8.2+ |
| Kotlin | 1.9.22+ | |
| Chaquopy | 15.0.1+ | Python integration plugin |
| JDK | 17 | |

---

## Build Instructions

### 1. Clone the repository

```bash
git clone https://github.com/hoshiyomiX/payload-toolkit-android.git
cd payload-toolkit-android
```

### 2. Install the Chaquopy plugin (local)

```bash
# Option A: Use the setup script
./scripts/setup_chaquopy.sh

# Option B: Manually download the plugin JAR
mkdir -p android/chaquopy_plugin
curl -L -o android/chaquopy_plugin/chaquopy-gradle-plugin.jar \
  https://github.com/nicholasgasior/chaquopy/releases/download/v15.0.1/chaquopy-gradle-plugin-15.0.1.jar
```

### 3. Build the APK

```bash
cd android
./gradlew assembleDebug
```

The debug APK will be at:
```
android/app/build/outputs/apk/debug/app-debug.apk
```

### 4. Build a release APK (signed)

```bash
cd android
./gradlew assembleRelease
```

Configure signing in `android/app/build.gradle.kts` under `signingConfigs`.

---

## Cross-Compilation (Alternative to Chaquopy)

If you prefer a standalone Python binary instead of Chaquopy:

```bash
# Cross-compile payload_toolkit.py to aarch64 Android binary
./scripts/build_aarch64.sh

# Output: dist/payload_toolkit-aarch64
# Copy to device and run via ProcessBuilder from Kotlin
```

See [`scripts/build_aarch64.sh`](scripts/build_aarch64.sh) for requirements (PyInstaller + Android NDK).

---

## Screenshots

<!-- TODO: Add screenshots after initial release -->

| Screen | Description |
|--------|-------------|
| Main screen | Mode selector, file picker, options |
| INFO output | Parsed payload.bin metadata with partition table |
| DUMP progress | Real-time extraction progress with SHA-256 verification |
| GEN options | Image selection, compression algorithm, output settings |

---

## Tested Devices

| Device | SoC | Android Version | Status |
|--------|-----|-----------------|--------|
| itel S666LN | UNISOC T606 | 13 (Tiramisu) | Planned |
| Samsung Galaxy A54 | Exynos 1380 | 14 (Upside Down Cake) | Planned |
| Google Pixel 7 | Tensor G2 | 14 (Upside Down Cake) | Planned |
| Xiaomi Redmi Note 12 | Snapdragon 685 | 13 (Tiramisu) | Planned |

> **minSdk 26** (Android 8.0 Oreo) — Chaquopy requires API 26+ for native library loading.

---

## Project Structure

```
payload-toolkit-android/
├── src/payload_toolkit/               # Refactored Python package
│   ├── __init__.py                    # Package init + CLI bridge
│   ├── protobuf.py                    # Minimal protobuf encoder/decoder
│   ├── compression.py                 # gzip/bz2/xz/brotli compress/decompress
│   ├── payload.py                     # Payload.bin read/write (AOSP + legacy)
│   ├── ota_metadata.py                # OTA ZIP metadata generation
│   └── modes/                         # Mode-specific logic
│       ├── __init__.py
│       ├── info.py                    # Parse & display payload.bin info
│       ├── dump.py                    # Extract partition images
│       ├── gen.py                     # Generate payload.bin from .img files
│       ├── zip.py                     # Generate flashable OTA ZIP
│       └── sign.py                    # Sign payload.bin with RSA key
├── android/                           # Android project
│   ├── build.gradle.kts               # Project-level build config
│   ├── settings.gradle.kts            # Plugin management + project includes
│   ├── gradle.properties              # Gradle properties
│   └── app/
│       ├── build.gradle.kts           # App-level build config (Chaquopy, deps)
│       └── src/main/
│           ├── AndroidManifest.xml
│           ├── java/com/hoshiyomi/payloadtoolkit/
│           │   ├── MainActivity.kt    # Main activity with Material 3 UI
│           │   ├── PayloadBridge.kt   # Python ↔ Kotlin bridge
│           │   └── PythonBridge.kt    # Chaquopy init & execution helper
│           ├── res/
│           │   ├── values/
│           │   │   ├── strings.xml
│           │   │   ├── colors.xml
│           │   │   └── themes.xml
│           │   └── layout/
│           │       └── activity_main.xml
│           └── assets/
│               └── (Python package copied here at build time)
├── scripts/
│   ├── build_aarch64.sh               # Cross-compile to standalone binary
│   └── setup_chaquopy.sh              # Chaquopy environment setup
├── docs/
│   └── ARCHITECTURE.md                # Detailed architecture documentation
├── .gitignore
└── README.md
```

---

## License

This project is provided as-is for educational and personal use. The underlying `payload_toolkit.py` implements the AOSP payload.bin v2 (Brillo) format specification.

---

## Acknowledgments

- [AOSP update_engine](https://android.googlesource.com/platform/system/update_engine/) — payload.bin format specification
- [Chaquopy](https://chaquo.com/chaquopy/) — Python integration for Android
- [payload_dumper](https://github.com/nicholasgasior/payload-dumper) — reference implementation
