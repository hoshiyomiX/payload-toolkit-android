# Architecture

## System Overview

```
┌──────────────────────────────────────────────────────────────────────┐
│                         Android Device                               │
│                                                                      │
│  ┌────────────────────────────────────────────────────────────────┐  │
│  │                    User Interface Layer                        │  │
│  │                    (Kotlin / Material 3)                       │  │
│  │                                                                │  │
│  │  MainActivity                                                  │  │
│  │  ├── TopAppBar (app title + theme toggle)                     │  │
│  │  ├── InputSection                                              │  │
│  │  │   ├── FilePathInput + BrowseButton (SAF file picker)       │  │
│  │  │   └── OutputPathInput + BrowseButton (SAF directory picker)│  │
│  │  ├── ModeSection                                               │  │
│  │  │   ├── ChipGroup: [INFO] [DUMP] [GEN] [ZIP] [SIGN]         │  │
│  │  │   └── OptionsPanel (context-sensitive per mode)            │  │
│  │  ├── ActionSection                                             │  │
│  │  │   ├── ExecuteButton                                         │  │
│  │  │   ├── ProgressBar (indeterminate + determinate)            │  │
│  │  │   └── CancelButton                                         │  │
│  │  └── LogSection                                                │  │
│  │      └── ScrollView > TextView (append-only log output)       │  │
│  └────────────────────────┬───────────────────────────────────────┘  │
│                           │                                          │
│  ┌────────────────────────▼───────────────────────────────────────┐  │
│  │                   Bridge Layer                                 │  │
│  │                                                                │  │
│  │  PayloadBridge.kt (Kotlin singleton)                          │  │
│  │  ├── executeMode(mode, args) → PayloadResult                   │  │
│  │  ├── getInfo(payloadPath) → PayloadResult                      │  │
│  │  ├── dump(payloadPath, outputDir, partitions) → PayloadResult  │  │
│  │  ├── gen(images, compress, output) → PayloadResult             │  │
│  │  ├── zip(images, device, fp, compress, output) → PayloadResult │  │
│  │  └── sign(input, output, key, cert) → PayloadResult            │  │
│  │                                                                │  │
│  │  PythonBridge.kt (Chaquopy helper)                            │  │
│  │  ├── ensureInitialized()                                       │  │
│  │  ├── callFunction(module, func, args...) → PyObject            │  │
│  │  └── captureStdout(func) → String                              │  │
│  └────────────────────────┬───────────────────────────────────────┘  │
│                           │                                          │
│  ┌────────────────────────▼───────────────────────────────────────┐  │
│  │                 Python Runtime Layer                           │  │
│  │                 (CPython 3.12 via Chaquopy)                    │  │
│  │                                                                │  │
│  │  payload_toolkit/                                              │  │
│  │  ├── __init__.py          Main entry point, argument parsing   │  │
│  │  ├── protobuf.py          Minimal PB encoder/decoder           │  │
│  │  ├── compression.py       gzip, bz2, xz, brotli               │  │
│  │  ├── payload.py           AOSP payload.bin read/write          │  │
│  │  ├── ota_metadata.py      OTA ZIP metadata generation          │  │
│  │  └── modes/                                                  │  │
│  │      ├── info.py          Parse payload.bin metadata           │  │
│  │      ├── dump.py          Extract partition images             │  │
│  │      ├── gen.py           Generate payload.bin                 │  │
│  │      ├── zip.py           Generate OTA ZIP                     │  │
│  │      └── sign.py          Sign payload.bin with RSA            │  │
│  └────────────────────────────────────────────────────────────────┘  │
│                                                                      │
│  ┌────────────────────────────────────────────────────────────────┐  │
│  │                    Storage Layer                               │  │
│  │                                                                │  │
│  │  App-Internal Storage (no permissions needed)                  │  │
│  │  ├── /data/data/com.hoshiyomi.payloadtoolkit/files/            │  │
│  │  │   ├── payload.bin       Input payload files                 │  │
│  │  │   ├── extracted/        Dumped partition images             │  │
│  │  │   └── output/           Generated payload.bin / OTA ZIP     │  │
│  │  └── /data/data/com.hoshiyomi.payloadtoolkit/cache/           │  │
│  │      └── tmp/              Temporary compressed chunks         │  │
│  │                                                                │  │
│  │  Shared Storage (via SAF / MediaStore)                         │  │
│  │  ├── /storage/emulated/0/Download/payload.bin                  │  │
│  │  ├── /storage/emulated/0/Documents/PayloadToolkit/             │  │
│  │  └── User-selected via ActivityResultLauncher                 │  │
│  └────────────────────────────────────────────────────────────────┘  │
└──────────────────────────────────────────────────────────────────────┘
```

## Data Flow Per Mode

### INFO Mode

```
User taps [INFO] → selects payload.bin
    │
    ▼
PayloadBridge.getInfo(path)
    │
    ▼
PythonBridge.callFunction("payload_toolkit.modes.info", "run", path)
    │
    ▼
payload_toolkit/modes/info.py:run()
    ├── read_payload(path) → parse header + manifest
    └── Format output text (partition table, sizes, hashes)
    │
    ▼
PayloadResult(success=true, output=text)
    │
    ▼
UI displays formatted output in log TextView
```

### DUMP Mode

```
User taps [DUMP] → selects payload.bin → selects output directory
    ├── Optionally filters partitions (e.g., "product, vendor")
    │
    ▼
PayloadBridge.dump(payloadPath, outputDir, partitions)
    │
    ▼
payload_toolkit/modes/dump.py:run()
    ├── read_payload(path) → parse manifest
    ├── For each partition:
    │   ├── Seek to data_offset + op.data_offset
    │   ├── Detect compression (REPLACE/REPLACE_BZ/REPLACE_XZ/BROTLI_BZ)
    │   ├── Decompress if needed
    │   ├── Write to output/{partition_name}.img
    │   └── Verify SHA-256 against manifest
    └── Return summary text
    │
    ▼
UI: ProgressBar updates + log output + SHA-256 verification results
```

### GEN Mode

```
User taps [GEN] → adds image files (.img) → sets partition names
    → selects compression → selects output path
    │
    ▼
PayloadBridge.gen(images, compress, output)
    │
    ▼
payload_toolkit/modes/gen.py:run()
    ├── SHA-256 hash each image
    ├── Compress images (if compression selected)
    ├── Build DeltaArchiveManifest protobuf
    ├── Build PayloadHeader protobuf (convergence loop)
    ├── Write payload.bin:
    │   "CrAU" | header_len | PayloadHeader | Manifest | data blobs
    └── Self-verify by re-parsing output
    │
    ▼
UI: Progress per image + final summary with sizes
```

### ZIP Mode

```
User taps [ZIP] → adds image files → sets device info
    → selects compression → selects output path
    │
    ▼
PayloadBridge.zip(images, device, fingerprint, compress, output)
    │
    ▼
payload_toolkit/modes/zip.py:run()
    ├── [Pass 1] Build payload.bin (via gen.py logic)
    ├── Generate payload_properties.txt
    │   ├── FILE_HASH = base64(SHA-256(payload.bin))
    │   ├── FILE_SIZE = payload.bin size
    │   ├── METADATA_HASH = base64(SHA-256(manifest))
    │   └── METADATA_SIZE = manifest size
    ├── Generate metadata.pb (protobuf format)
    ├── [Pass 2] Build OTA ZIP with correct offsets:
    │   ├── payload.bin
    │   ├── payload_properties.txt
    │   ├── payload_metadata.bin
    │   ├── apex_info.pb (stub)
    │   ├── care_map.pb (stub)
    │   ├── metadata (text)
    │   ├── metadata.pb (protobuf)
    │   └── otacert
    └── Return summary
    │
    ▼
UI: Two-phase progress + final ZIP summary
```

### SIGN Mode

```
User taps [SIGN] → selects payload.bin → selects RSA private key + cert
    → selects output path
    │
    ▼
PayloadBridge.sign(inputPath, outputPath, keyPath, certPath)
    │
    ▼
payload_toolkit/modes/sign.py:run()
    ├── Read payload.bin (header + manifest + data)
    ├── Sign manifest with RSA private key (PKCS#1 v1.5)
    ├── Build Signatures protobuf message
    ├── Append signatures block to payload.bin
    │   Layout: ... | padding | signatures_length | signatures | ...
    └── Update header: signatures_size, signatures_offset
    │
    ▼
UI: Signing progress + final file info
```

## Python → Chaquopy → Kotlin → UI Call Chain

```kotlin
// 1. User taps Execute button in MainActivity.kt
fun onExecuteClicked() {
    val mode = getSelectedMode()          // e.g., "info"
    val args = buildArguments()           // Map<String, String>
    val result = PayloadBridge.execute(mode, args)  // blocking call on IO thread
    updateUI(result)
}

// 2. PayloadBridge.kt serializes arguments and calls Python
object PayloadBridge {
    fun execute(mode: String, args: Map<String, Any>): PayloadResult {
        val stdout = StringBuilder()
        PythonBridge.captureStdout { py ->
            val module = py.getModule("payload_toolkit.modes.$mode")
            val func = module.callAttr("run", *args.toPyObjectArray())
            // func returns status dict
        }
        return PayloadResult(success = true, output = stdout.toString())
    }
}

// 3. PythonBridge.kt wraps Chaquopy's Python API
object PythonBridge {
    private var python: Python? = null

    fun ensureInitialized() {
        if (python == null) {
            python = Python.getInstance()
        }
    }

    fun captureStdout(block: (Python) -> Unit): String {
        ensureInitialized()
        val sys = python!!.getModule("sys")
        val io = python!!.getModule("io")
        val buffer = io.callAttr("StringIO")
        sys.setAttr("stdout", buffer)
        try {
            block(python!!)
        } finally {
            sys.setAttr("stdout", sys.getAttr("__stdout__"))
        }
        return buffer.callAttr("getvalue").toString()
    }
}

// 4. Python module receives call
# payload_toolkit/modes/info.py
def run(payload_path: str, verbose: bool = False) -> dict:
    """Called from Kotlin via Chaquopy."""
    payload = read_payload(payload_path)
    # ... format output ...
    print(formatted_text)  # captured by StringIO in PythonBridge
    return {"success": True, "partitions": len(payload["manifest"]["partitions"])}
```

## File I/O Paths on Android

### App-Specific Storage (Primary)

All Python file operations use app-internal storage by default:

```
/data/data/com.hoshiyomi.payloadtoolkit/files/
├── input/                    # User-provided input files (copied from SAF)
│   └── payload.bin
├── extracted/                # Dump mode output
│   ├── boot.img
│   ├── system.img
│   ├── vendor.img
│   └── product.img
├── generated/                # GEN/ZIP mode output
│   ├── payload.bin
│   └── partial_ota.zip
└── keys/                     # RSA keys for signing
    ├── private_key.pem
    └── public_cert.pem
```

### Shared Storage (Secondary)

Files selected via Android's Storage Access Framework (SAF):

```kotlin
// Reading a file from shared storage
val launcher = registerForActivityResult(OpenDocument()) { uri ->
    contentResolver.openInputStream(uri)?.use { input ->
        // Copy to app-internal storage for Python access
        File(filesDir, "input/payload.bin").outputStream().use { output ->
            input.copyTo(output)
        }
    }
}
```

### Temporary Files

```kotlin
// Temp files for compression intermediaries
// /data/data/com.hoshiyomi.payloadtoolkit/cache/tmp/
// Cleaned up automatically by Python's tempfile or on app exit
```

## Non-Root Limitations

### What Works Without Root

| Operation | Mechanism | Storage Location |
|-----------|-----------|-----------------|
| Read payload.bin | Standard file I/O | App storage / SAF |
| Parse metadata | In-memory protobuf decode | N/A |
| Write extracted .img | Standard file I/O | App storage / SAF |
| Generate payload.bin | Standard file I/O | App storage / SAF |
| Create OTA ZIP | zipfile module | App storage / SAF |
| RSA signing | hashlib + stdlib | App storage / SAF |

### What Does NOT Work Without Root

| Operation | Reason | Workaround |
|-----------|--------|------------|
| Direct dd to block device | `/dev/block/by-name/*` requires root | Generate flashable ZIP instead |
| Read current partition | `/dev/block/by-name/*` requires root | Use extracted OTA images |
| Write to /system or /vendor | Read-only mounted partitions | Generate OTA ZIP, flash via recovery |
| Fastboot operations | Requires USB + bootloader | N/A |

## Thread Model

```
Main Thread (UI)
├── Button clicks → dispatch to IO thread
├── ProgressBar updates → posted to Main thread
└── TextView log append → posted to Main thread

IO Thread (Coroutines / ExecutorService)
├── PythonBridge.callFunction()  ← Chaquopy blocks this thread
├── File copy operations (SAF → app storage)
└── Progress callback → Main thread

Python Thread (CPython GIL)
├── Python functions execute sequentially
├── stdout redirected to StringIO
└── Returns to IO thread when complete
```

Important notes:
- Chaquopy function calls are **blocking** — always run on a background thread
- The CPython GIL means only one Python thread runs at a time
- Progress updates from Python are implemented via callback functions registered before execution
- The Python `print()` output is captured via `sys.stdout` redirection (StringIO)
