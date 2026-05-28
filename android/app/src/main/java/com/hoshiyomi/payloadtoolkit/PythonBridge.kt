package com.hoshiyomi.payloadtoolkit

import android.content.Context
import android.util.Log
import com.hoshiyomi.payloadtoolkit.BuildConfig
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.util.zip.ZipInputStream

/**
 * PythonBridge — Manages Python runtime initialization and .pyz execution.
 *
 * Architecture (v6 — JNI embedding + jniLibs + stdlib asset + build manifest):
 *
 *   On the BUILD machine (CI):
 *     Termux Python 3.13 packages → split into three outputs:
 *       1. jniLibs/arm64-v8a/         — .so libs + python binary + libpybridge.so
 *       2. assets/python-stdlib.zip   — .py stdlib files
 *       3. assets/native-libs-manifest.txt — .so dependency map
 *
 *   At INSTALL time:
 *     Android package manager extracts jniLibs → nativeLibraryDir
 *     nativeLibraryDir has SELinux "app_lib_file" context → EXECUTABLE
 *
 *   At RUNTIME (first launch):
 *     1. Extract python-stdlib.zip + manifest from assets → app-internal storage
 *     2. Cross-check nativeLibraryDir against build manifest
 *     3. Try JNI mode (dlopen libpython3.13.so → Py_Main)
 *        - No execve(), no LD_PRELOAD, no linker namespace issues
 *     4. Fallback: execve libpython3exec.so with LD_PRELOAD + LD_LIBRARY_PATH
 */
object PythonBridge {

    private const val TAG = "PythonBridge"
    private const val PYZ_ASSET_NAME = "payload_toolkit.pyz"
    private const val STDLIB_ASSET_NAME = "python-stdlib.zip"
    private const val MANIFEST_ASSET_NAME = "native-libs-manifest.txt"
    private const val PYTHON_DIR_NAME = "python"
    private const val STDLIB_SUBDIR = "stdlib"
    private const val BUNDLED_PYTHON_LIB = "libpython3exec.so"

    /** System libs that Android bionic always provides — safe to skip in dep checks. */
    private val SYSTEM_LIBS = setOf(
        "libc.so", "libm.so", "libdl.so", "libpthread.so", "librt.so"
    )

    /** Known Android ABI names used in native-libs-manifest.txt 4-col format. */
    private val KNOWN_ABI_SET = setOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")

    /**
     * Map Android's nativeLibraryDir path suffix to manifest ABI name.
     * nativeLibraryDir typically ends with .../lib/arm64 or .../lib/arm.
     */
    private val DIR_SUFFIX_TO_ABI = mapOf(
        "arm64" to "arm64-v8a",
        "arm" to "armeabi-v7a",
        "x86_64" to "x86_64",
        "x86" to "x86"
    )

    /**
     * Detect the device's ABI from nativeLibraryDir path.
     *
     * Android's nativeLibraryDir format:
     *   /data/app/.../com.hoshiyomi.payloadtoolkit-.../lib/arm64
     *
     * Returns the ABI string (e.g. "arm64-v8a") or null if detection fails.
     */
    private fun detectDeviceAbi(nativeLibraryDir: String): String? {
        // Try matching the last path component (e.g. "arm64" from "/lib/arm64")
        val lastSegment = nativeLibraryDir.substringAfterLast("/")
        DIR_SUFFIX_TO_ABI[lastSegment]?.let { return it }

        // Try matching common path patterns
        for ((suffix, abi) in DIR_SUFFIX_TO_ABI) {
            if (nativeLibraryDir.endsWith("/lib/$suffix")) return abi
        }

        return null
    }

    /**
     * System Python paths — fallback when bundled runtime is unavailable.
     */
    private val SYSTEM_PYTHON_PATHS = listOf(
        "/data/data/com.termux/files/usr/bin/python3",
        "/data/data/com.termux/files/usr/bin/python",
        "/system/bin/python3",
        "/usr/bin/python3",
        "/usr/local/bin/python3"
    )

    @Volatile private var initialized = false
    private var pythonPath: String? = null
    private var pyzPath: String? = null
    private var stdlibDir: String? = null
    private var isBundledPython = false
    private var nativeLibDir: String? = null
    private var execDirectDeps: List<String> = emptyList()

    /** JNI bridge instance (null if libpybridge.so not available). */
    private var pyBridge: PyBridge? = null

    /** Whether to use JNI mode (dlopen) vs exec mode (ProcessBuilder). */
    private var useJniMode = false

    private val initLock = Any()

    data class InitResult(
        val success: Boolean,
        val pythonPath: String?,
        val pyzPath: String?,
        val isBundled: Boolean = false,
        val error: String? = null,
        val diagnostics: String = ""
    )

    /** Detailed diagnostic information for troubleshooting. */
    private val diagnosticLog = StringBuilder()

    private fun diag(msg: String) {
        Log.d(TAG, msg)
        diagnosticLog.appendLine(msg)
    }

    /**
     * Initialize: extract assets and prepare Python runtime.
     *
     * Priority:
     *   1. Bundled Python via JNI (dlopen libpython3.13.so in-process)
     *   2. Bundled Python via exec (LD_PRELOAD + LD_LIBRARY_PATH fallback)
     *   3. System Python (Termux or other)
     */
    fun ensureInitialized(context: Context?): InitResult {
        if (initialized) return InitResult(true, pythonPath, pyzPath, isBundledPython)

        synchronized(initLock) {
            if (initialized) return InitResult(true, pythonPath, pyzPath, isBundledPython)

            diagnosticLog.clear()
            val ctx = context
                ?: return InitResult(false, null, null, error = "Context required")

            diag("=== PythonBridge Init ===")
            diag("App version: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")

            // Step 1: Extract .pyz from assets
            val extractedPyz = extractPyz(ctx)
            if (extractedPyz == null) {
                return InitResult(false, null, null,
                    error = "Failed to extract $PYZ_ASSET_NAME from assets",
                    diagnostics = diagnosticLog.toString())
            }
            pyzPath = extractedPyz
            diag("[OK] .pyz: $pyzPath (${File(pyzPath).length()} bytes)")

            // Step 2: Try bundled Python from nativeLibraryDir (jniLibs)
            nativeLibDir = ctx.applicationInfo.nativeLibraryDir
            val bundledPy = File(nativeLibDir!!, BUNDLED_PYTHON_LIB)

            diag("nativeLibraryDir: $nativeLibDir")

            // -- Full .so inventory --
            val nativeDir = File(nativeLibDir!!)
            val soFiles = if (nativeDir.isDirectory) {
                nativeDir.listFiles()?.filter { it.name.endsWith(".so") }?.sortedBy { it.name }
            } else null

            if (soFiles.isNullOrEmpty()) {
                diag("ERROR: nativeLibraryDir has NO .so files!")
                diag("  Directory exists: ${nativeDir.isDirectory}")
                if (nativeDir.isDirectory) {
                    val allFiles = nativeDir.listFiles()?.map { it.name }?.sorted()
                    if (!allFiles.isNullOrEmpty()) {
                        diag("  Non-.so files: ${allFiles.joinToString(", ")}")
                    }
                }
            } else {
                diag("[OK] .so count: ${soFiles.size}")
                val totalSize = soFiles.sumOf { it.length() }
                diag("[OK] .so total size: ${formatBytes(totalSize)}")
                diag("--- .so inventory ---")
                soFiles.forEach { f ->
                    diag("  ${f.name} (${formatBytes(f.length())})")
                }
                diag("--- end inventory ---")
            }

            // -- Check for libpybridge.so (JNI mode) --
            val bridgeSo = File(nativeLibDir!!, "libpybridge.so")
            diag("libpybridge.so exists: ${bridgeSo.isFile}")

            // -- ELF header validation --
            diag("libpython3exec.so exists: ${bundledPy.isFile}")
            if (bundledPy.isFile) {
                val elfOk = validateElfHeader(bundledPy)
                diag("libpython3exec.so valid ELF: $elfOk")
                diag("libpython3exec.so canExecute: ${bundledPy.canExecute()}")
                diag("libpython3exec.so size: ${formatBytes(bundledPy.length())}")
            }

            // Check libpython3.13.so (needed for JNI mode)
            val pythonSo = File(nativeLibDir!!, "libpython3.13.so")
            diag("libpython3.13.so exists: ${pythonSo.isFile}")
            if (pythonSo.isFile) {
                diag("libpython3.13.so size: ${formatBytes(pythonSo.length())}")
            }

            // -- Detect device ABI for manifest filtering --
            val deviceAbi = nativeLibDir?.let { detectDeviceAbi(it) }
            diag("[ABI] Device ABI: ${deviceAbi ?: "unknown (path: $nativeLibDir)"}")

            // -- Manifest cross-check --
            if (soFiles != null && soFiles.isNotEmpty()) {
                val manifestIssues = crossCheckManifest(ctx, nativeLibDir!!, soFiles, deviceAbi)
                if (manifestIssues.isNotEmpty()) {
                    diag("--- MANIFEST MISMATCH ---")
                    manifestIssues.forEach { diag("  $it") }
                    diag("--- end mismatch ---")
                } else {
                    diag("[OK] Manifest cross-check: all ${soFiles.size} .so files match build")
                }
            }

            // Extract Python executable's direct dependencies for exec fallback
            execDirectDeps = parseExecDeps(ctx, deviceAbi)
            if (execDirectDeps.isNotEmpty()) {
                diag("[Manifest] $BUNDLED_PYTHON_LIB direct deps: ${execDirectDeps.joinToString(", ")}")
            } else {
                diag("[Manifest] WARNING: no DT_NEEDED found for $BUNDLED_PYTHON_LIB in manifest")
            }

            if (bundledPy.isFile || pythonSo.isFile) {
                diag("Attempting bundled Python initialization...")

                val stdlibAssets = try {
                    ctx.assets.list("")?.filter {
                        it.contains("stdlib") || it.contains("python") || it.contains("manifest")
                    }
                } catch (_: Exception) { null }
                diag("Python-related assets: ${stdlibAssets?.joinToString(", ") ?: "none found"}")

                val extractedStdlib = extractStdlib(ctx)
                if (extractedStdlib != null) {
                    stdlibDir = extractedStdlib
                    pythonPath = bundledPy.absolutePath
                    isBundledPython = true
                    diag("[OK] Bundled Python: $pythonPath")
                    diag("[OK] Stdlib dir: $stdlibDir")
                } else {
                    diag("FAILED: Stdlib extraction returned null")
                    Log.w(TAG, "Stdlib extraction failed, trying fallback")
                }
            } else {
                diag("Bundled Python not found — checking if APK was built with jniLibs")
            }

            // Step 3: Initialize JNI bridge (preferred mode)
            if (isBundledPython && stdlibDir != null) {
                pyBridge = PyBridge.getInstance()
                if (pyBridge != null) {
                    useJniMode = true
                    diag("[OK] JNI mode: will use dlopen(libpython3.13.so) + Py_Main")
                    diag("[JNI] No execve(), no LD_PRELOAD, no LD_LIBRARY_PATH needed")
                } else {
                    diag("[JNI] libpybridge.so not available, using exec fallback")
                    diag("[JNI] ${PyBridge.loadError}")
                    useJniMode = false
                }
            }

            // Step 4: Fallback to system Python
            if (pythonPath == null) {
                diag("Trying system Python fallback...")
                val found = findSystemPython()
                if (found != null) {
                    pythonPath = found
                    isBundledPython = false
                    useJniMode = false
                    diag("[OK] System Python: $pythonPath")
                } else {
                    diag("FAILED: No system Python found either")
                }
            }

            if (pythonPath == null) {
                val diagText = diagnosticLog.toString()
                Log.e(TAG, "Init failed. Diagnostics:\n$diagText")
                return InitResult(false, null, pyzPath,
                    error = "No Python runtime available",
                    diagnostics = diagText)
            }

            // Step 5: Smoke test
            val verifyError = verifySetup()
            if (verifyError != null) {
                Log.w(TAG, "Verify failed: $verifyError")
                diag("Verify failed: $verifyError")
                // If JNI mode failed, try switching to exec mode
                if (useJniMode) {
                    diag("[JNI] Smoke test failed, falling back to exec mode")
                    useJniMode = false
                    pyBridge = null
                    val retryError = verifySetup()
                    if (retryError != null) {
                        diag("Exec fallback also failed: $retryError")
                        // Parse linker error for actionable diagnostics
                        parseAndDiagnoseError(retryError)
                        return InitResult(false, pythonPath, pyzPath, isBundledPython,
                            retryError, diagnosticLog.toString())
                    }
                    diag("[OK] Exec fallback smoke test passed")
                } else {
                    parseAndDiagnoseError(verifyError)
                    return InitResult(false, pythonPath, pyzPath, isBundledPython,
                        verifyError, diagnosticLog.toString())
                }
            }

            initialized = true
            return InitResult(true, pythonPath, pyzPath, isBundledPython)
        }
    }

    private fun parseAndDiagnoseError(error: String) {
        val parsedError = parseLinkerError(error)
        if (parsedError != null) {
            diag("--- LINKER ERROR ANALYSIS ---")
            diag("  Problem .so:  ${parsedError.first}")
            diag("  Missing dep:  ${parsedError.second}")
            diag("  Fix: add the missing .so to prepare_python_runtime.sh PACKAGES,")
            diag("       or exclude the problem .so if not needed.")
            diag("--- end analysis ---")
        } else if (error.contains("did_read_") || error.contains("linker_phdr")
            || error.contains("CHECK") || error.contains("SIGABRT")) {
            diag("--- LINKER CRASH ANALYSIS ---")
            diag("  Error type: bionic linker CHECK failure (ELF corruption)")
            diag("--- end analysis ---")
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  Asset extraction
    // ═══════════════════════════════════════════════════════════════

    private fun extractPyz(context: Context): String? {
        val dir = File(context.filesDir, PYTHON_DIR_NAME).also { it.mkdirs() }
        val file = File(dir, PYZ_ASSET_NAME)

        if (file.exists()) {
            try {
                val assetSize = context.assets.open(PYZ_ASSET_NAME).use { it.available().toLong() }
                if (assetSize > 0 && assetSize == file.length()) return file.absolutePath
            } catch (_: Exception) {}
        }

        return try {
            context.assets.open(PYZ_ASSET_NAME).use { input ->
                FileOutputStream(file).use { output -> input.copyTo(output) }
            }
            file.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "Failed to extract $PYZ_ASSET_NAME", e)
            null
        }
    }

    private fun extractStdlib(context: Context): String? {
        val pythonDir = File(context.filesDir, PYTHON_DIR_NAME)
        val stdlibDirFile = File(pythonDir, STDLIB_SUBDIR)
        val marker = File(pythonDir, ".stdlib_v${BuildConfig.VERSION_CODE}")

        if (marker.exists() && stdlibDirFile.isDirectory) {
            val children = stdlibDirFile.listFiles()?.size ?: 0
            if (children > 0) {
                Log.d(TAG, "Stdlib already extracted ($children items)")
                return stdlibDirFile.absolutePath
            }
        }

        stdlibDirFile.deleteRecursively()

        return try {
            Log.d(TAG, "Extracting $STDLIB_ASSET_NAME ...")
            stdlibDirFile.mkdirs()
            extractZipAsset(context, STDLIB_ASSET_NAME, stdlibDirFile)
            marker.createNewFile()
            pruneEmptyDirs(stdlibDirFile)
            val count = countFiles(stdlibDirFile)
            Log.d(TAG, "Stdlib extracted: $count files")
            stdlibDirFile.absolutePath
        } catch (e: Exception) {
            Log.w(TAG, "Failed to extract stdlib", e)
            null
        }
    }

    private fun extractZipAsset(context: Context, assetName: String, targetDir: File) {
        context.assets.open(assetName).use { input ->
            ZipInputStream(input).use { zipIn ->
                var entry = zipIn.nextEntry
                while (entry != null) {
                    val outFile = File(targetDir, entry.name)
                    if (entry.isDirectory) {
                        outFile.mkdirs()
                    } else {
                        outFile.parentFile?.mkdirs()
                        FileOutputStream(outFile).use { out -> zipIn.copyTo(out) }
                    }
                    zipIn.closeEntry()
                    entry = zipIn.nextEntry
                }
            }
        }
    }

    private fun pruneEmptyDirs(dir: File) {
        dir.walkBottomUp().forEach { f ->
            if (f.isDirectory && f.listFiles()?.isEmpty() == true) f.delete()
        }
    }

    private fun countFiles(dir: File): Int {
        var count = 0
        dir.walkTopDown().forEach { if (it.isFile) count++ }
        return count
    }

    // ═══════════════════════════════════════════════════════════════
    //  Diagnostics helpers
    // ═══════════════════════════════════════════════════════════════

    private fun resolveLibName(needed: String): String? {
        if (SYSTEM_LIBS.contains(needed)) return null
        return if (needed.contains(".so.")) {
            needed.substringBefore(".so.") + ".so"
        } else {
            needed
        }
    }

    private fun parseExecDeps(context: Context, deviceAbi: String? = null): List<String> {
        return try {
            val content = context.assets.open(MANIFEST_ASSET_NAME)
                .bufferedReader().readText()
            var result = emptyList<String>()
            for (line in content.lines()) {
                if (line.startsWith("#") || line.isBlank()) continue
                val parts = line.split(" | ")
                /*
                 * 4-col: abi | filename | size | deps  →  filename at [1], deps at [3]
                 * 3-col: filename | size | deps          →  filename at [0], deps at [2]
                 */
                val is4col = parts.size >= 4 && parts[0].trim() in KNOWN_ABI_SET
                // Skip entries from other ABIs when device ABI is known
                if (is4col && deviceAbi != null && parts[0].trim() != deviceAbi) continue
                val nameIdx = if (is4col) 1 else 0
                val depsIdx = if (is4col) 3 else 2
                if (parts.size > nameIdx && parts[nameIdx].trim() == BUNDLED_PYTHON_LIB) {
                    if (parts.size > depsIdx && parts[depsIdx].trim().isNotEmpty()) {
                        result = parts[depsIdx].trim().split(",")
                            .map { it.trim() }
                            .filter { it.isNotEmpty() }
                    }
                    break
                }
            }
            result
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun validateElfHeader(file: File): Boolean {
        return try {
            val stream = file.inputStream()
            val magic = ByteArray(4)
            val read = stream.read(magic)
            stream.close()
            read == 4 && magic[0] == 0x7f.toByte() && magic[1] == 'E'.code.toByte()
                    && magic[2] == 'L'.code.toByte() && magic[3] == 'F'.code.toByte()
        } catch (e: Exception) {
            Log.w(TAG, "ELF header check failed for ${file.name}: ${e.message}")
            false
        }
    }

    private fun readLeLong(raf: RandomAccessFile, bytes: Int): Long {
        val buf = ByteArray(bytes)
        raf.readFully(buf)
        var result = 0L
        for (i in buf.indices) {
            result = result or ((buf[i].toLong() and 0xFFL) shl (8 * i))
        }
        return result
    }

    private fun readLeUInt(raf: RandomAccessFile): Int {
        val buf = ByteArray(4)
        raf.readFully(buf)
        return (buf[0].toInt() and 0xFF) or
               ((buf[1].toInt() and 0xFF) shl 8) or
               ((buf[2].toInt() and 0xFF) shl 16) or
               ((buf[3].toInt() and 0xFF) shl 24)
    }

    private fun readLeUShort(raf: RandomAccessFile): Int {
        val buf = ByteArray(2)
        raf.readFully(buf)
        return (buf[0].toInt() and 0xFF) or ((buf[1].toInt() and 0xFF) shl 8)
    }

    private fun validateElfProgramHeaders(file: File): String? {
        return try {
            val raf = RandomAccessFile(file, "r")
            try {
                val size = raf.length()
                if (size < 64) return "file too small for ELF header (${size} bytes)"
                raf.seek(32)
                val ePhoff = readLeLong(raf, 8)
                raf.seek(54)
                val ePhentsize = readLeUShort(raf).toLong()
                raf.seek(56)
                val ePhnum = readLeUShort(raf).toLong()
                val phTableEnd = ePhoff + ePhnum * ePhentsize
                if (phTableEnd > size) {
                    return "phdr table overflows: offset=$ePhoff + ${ePhnum}x${ePhentsize} = $phTableEnd > fileSize=$size"
                }
                for (i in 0 until ePhnum.toInt()) {
                    val entryOff = ePhoff + i * ePhentsize
                    raf.seek(entryOff)
                    val pType = readLeUInt(raf)
                    if (pType == 1 || pType == 2) {
                        raf.seek(entryOff + 8)
                        val pOffset = readLeLong(raf, 8)
                        raf.seek(entryOff + 32)
                        val pFilesz = readLeLong(raf, 8)
                        val segEnd = pOffset + pFilesz
                        if (segEnd > size) {
                            val typeName = if (pType == 1) "PT_LOAD" else "PT_DYNAMIC"
                            return "$typeName[$i] segment overflows: offset=$pOffset + filesz=$pFilesz = $segEnd > fileSize=$size"
                        }
                    }
                }
                null
            } finally {
                raf.close()
            }
        } catch (e: Exception) {
            "read error: ${e.message}"
        }
    }

    private fun crossCheckManifest(
        context: Context,
        nativeLibDir: String,
        deviceSoFiles: List<File>,
        deviceAbi: String? = null
    ): List<String> {
        val issues = mutableListOf<String>()
        val manifestMap = parseManifest(context, deviceAbi) ?: run {
            issues.add("WARNING: manifest asset not found — cannot cross-check")
            return issues
        }
        val deviceNames = deviceSoFiles.associate { it.name to it.length() }
        for ((name, expectedSize) in manifestMap) {
            val deviceFile = deviceNames[name]
            if (deviceFile == null) {
                issues.add("MISSING on device: $name (expected ${formatBytes(expectedSize)})")
            } else if (deviceFile != expectedSize) {
                issues.add("SIZE MISMATCH: $name (device=${formatBytes(deviceFile)}, build=${formatBytes(expectedSize)})")
            }
        }
        for (name in deviceNames.keys) {
            if (name !in manifestMap) {
                issues.add("EXTRA on device: $name (not in build manifest — stale from old APK?)")
            }
        }
        return issues
    }

    /**
     * Parse native-libs-manifest.txt, optionally filtered by device ABI.
     *
     * In multi-arch manifests (4-col format), entries from other ABIs are
     * skipped. This prevents false SIZE MISMATCH (arm32 size overwriting
     * arm64 size in flat Map) and false MISSING (arm32 extension modules
     * not present on arm64 device).
     *
     * @param deviceAbi Device ABI (e.g. "arm64-v8a"), or null to include all.
     */
    private fun parseManifest(context: Context, deviceAbi: String? = null): Map<String, Long>? {
        return try {
            val content = context.assets.open(MANIFEST_ASSET_NAME)
                .bufferedReader().readText()
            val map = mutableMapOf<String, Long>()
            var skippedOtherAbi = 0
            for (line in content.lines()) {
                if (line.startsWith("#") || line.isBlank()) continue
                val parts = line.split(" | ")
                /*
                 * Manifest format (since multi-arch refactor):
                 *   abi | filename | size_bytes | DT_NEEDED
                 * Legacy format (single-arch):
                 *   filename | size_bytes | DT_NEEDED
                 *
                 * Detect format: if parts[0] is a known ABI name, use 4-col;
                 * otherwise fall back to 3-col (legacy).
                 */
                val is4col = parts.size >= 4 && parts[0].trim() in KNOWN_ABI_SET
                // Skip entries from other ABIs when device ABI is known
                if (is4col && deviceAbi != null && parts[0].trim() != deviceAbi) {
                    skippedOtherAbi++
                    continue
                }
                val (name, sizeStr) = if (is4col) {
                    Pair(parts[1].trim(), parts[2].trim())
                } else if (parts.size >= 2) {
                    Pair(parts[0].trim(), parts[1].trim())
                } else continue
                val size = sizeStr.toLongOrNull() ?: continue
                map[name] = size
            }
            diag("[Manifest] Parsed ${map.size} entries" +
                (if (skippedOtherAbi > 0) " (skipped $skippedOtherAbi from other ABI)" else "") +
                (if (deviceAbi != null) " for ABI: $deviceAbi" else ""))
            map
        } catch (e: Exception) {
            diag("[Manifest] Could not read: ${e.message}")
            null
        }
    }

    private fun parseLinkerError(error: String): Pair<String, String>? {
        val neededByPattern = Regex(
            """library\s+"([^"]+)"\s+not\s+found.*?needed\s+by\s+\S*/(\S+)"""
        )
        val m1 = neededByPattern.find(error)
        if (m1 != null) return Pair(m1.groupValues[2], m1.groupValues[1])

        val dlopenPattern = Regex("""dlopen failed:\s+library\s+"([^"]+)"\s+not\s+found""")
        val m2 = dlopenPattern.find(error)
        if (m2 != null) return Pair("(unknown requester)", m2.groupValues[1])

        val verneedPattern = Regex(
            """cannot find\s+"([^"]+)"\s+from\s+verneed\[\d+\].*?for\s+(\S+)"""
        )
        val m3 = verneedPattern.find(error)
        if (m3 != null) return Pair(m3.groupValues[2], m3.groupValues[1])

        return null
    }

    private fun formatBytes(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "${bytes / 1024} KB"
            else -> "${"%.1f".format(bytes / (1024.0 * 1024.0))} MB"
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  Python binary discovery
    // ═══════════════════════════════════════════════════════════════

    private fun findSystemPython(): String? {
        for (path in SYSTEM_PYTHON_PATHS) {
            if (File(path).exists() && File(path).canExecute()) return path
        }
        return try {
            val pb = ProcessBuilder("which", "python3")
            val process = pb.start()
            val output = process.inputStream.bufferedReader().readText().trim()
            process.waitFor()
            if (process.exitValue() == 0 && output.isNotEmpty()) output else null
        } catch (_: Exception) { null }
    }

    // ═══════════════════════════════════════════════════════════════
    //  Execution
    // ═══════════════════════════════════════════════════════════════

    /**
     * Smoke test: run Python with --version to verify it works.
     * Uses JNI mode if available, falls back to exec.
     */
    private fun verifySetup(): String? {
        val pyz = pyzPath ?: return "No .pyz path"

        if (isBundledPython) {
            if (stdlibDir == null) return "No stdlib dir"
            val libDir = File(stdlibDir!!, "lib/python3.13")
            if (!libDir.isDirectory) return "Stdlib lib dir missing: ${libDir.absolutePath}"
            val pyCount = libDir.listFiles()?.count { it.name.endsWith(".py") } ?: 0
            if (pyCount < 10) return "Stdlib too small: $pyCount .py files in $libDir"
        }

        // Try JNI mode first
        if (useJniMode && pyBridge != null && nativeLibDir != null) {
            return verifyViaJni(pyz)
        }

        // Fallback: exec mode
        val py = pythonPath ?: return "No Python path"
        return verifyViaExec(py, pyz)
    }

    private fun verifyViaJni(pyz: String): String? {
        Log.d(TAG, "Verify (JNI): $pyz --version")
        val result = pyBridge!!.runPython(
            libDir = nativeLibDir!!,
            pyzPath = pyz,
            stdlibDir = stdlibDir!!,
            args = listOf("--version")
        )
        diag("[JNI verify] exit=${result.exitCode} output=${result.output.take(200)}")
        if (result.success && result.output.isNotEmpty()) {
            Log.d(TAG, "JNI verify OK: ${result.output}")
            return null
        }
        return "JNI verify exit ${result.exitCode}: ${result.output}${result.error?.let { " ($it)" } ?: ""}"
    }

    private fun verifyViaExec(py: String, pyz: String): String? {
        if (isBundledPython) {
            val pyFile = File(py)
            if (!pyFile.exists()) return "Bundled Python not found: $py"
            if (!pyFile.canExecute()) {
                Log.w(TAG, "Bundled Python exists but may not be executable: $py")
            }
        }

        return try {
            /*
             * Smoke test: run Python directly to verify the interpreter works.
             * Tests Python itself (not the .pyz app) so we can see real errors.
             * Uses -c "import sys; print(sys.version)" instead of passing the .pyz.
             */
            val pb = ProcessBuilder(py, "-c", "import sys; print(sys.version)")
                .redirectErrorStream(true)
            configureEnvironment(pb)
            Log.d(TAG, "Verify (exec): $py -c 'import sys; print(sys.version)'")
            val process = pb.start()
            val rawOutput = process.inputStream.bufferedReader().readText().trim()
            val exitCode = process.waitFor()
            /*
             * Log raw output BEFORE filtering for full diagnostics.
             * filterLinkerWarnings removes CANNOT LINK lines which may be
             * the only output when exec fails.
             */
            if (rawOutput.isNotEmpty()) {
                diag("[exec raw] ${rawOutput.take(1000)}")
            } else {
                diag("[exec raw] (empty — process produced no output)")
            }
            val output = filterLinkerWarnings(rawOutput)
            if (exitCode == 0 && output.isNotEmpty()) {
                Log.d(TAG, "Exec verify OK: $output")
                null
            } else {
                "Python exit $exitCode: $output"
            }
        } catch (e: Exception) {
            Log.e(TAG, "Verify exception", e)
            "Failed to run Python: ${e.message}"
        }
    }

    /**
     * Execute the .pyz file with the given arguments.
     *
     * JNI mode (preferred): dlopen + Py_Main — no subprocess, no linker issues.
     * Exec mode (fallback): ProcessBuilder + LD_PRELOAD — may show linker warnings.
     *
     * @param onProgress Optional callback invoked when a __PROGRESS__ marker is
     *                   parsed from stdout. Only effective in exec mode (JNI returns
     *                   output after completion, progress is parsed retroactively).
     */
    fun executePyz(
        args: List<String>,
        onProgress: ((ProgressUpdate) -> Unit)? = null,
        onOutputLine: ((String) -> Unit)? = null
    ): ExecResult {
        val pyz = pyzPath ?: return ExecResult("", ".pyz not found", -1, 0)

        // JNI mode: run Python in-process via dlopen
        if (useJniMode && pyBridge != null && nativeLibDir != null && stdlibDir != null) {
            return executeViaJni(pyz, args, onProgress, onOutputLine)
        }

        // Exec mode: run Python as subprocess (streaming for progress)
        val py = pythonPath ?: return ExecResult("", "Python not initialized", -1, 0)
        return executeViaExec(py, pyz, args, onProgress, onOutputLine)
    }

    private fun executeViaJni(
        pyz: String, args: List<String>,
        onProgress: ((ProgressUpdate) -> Unit)?,
        onOutputLine: ((String) -> Unit)?
    ): ExecResult {
        val startTime = System.currentTimeMillis()
        Log.d(TAG, "Exec (JNI): $pyz ${args.joinToString(" ")}")
        val result = pyBridge!!.runPython(
            libDir = nativeLibDir!!,
            pyzPath = pyz,
            stdlibDir = stdlibDir!!,
            args = args
        )
        val duration = System.currentTimeMillis() - startTime

        if (!result.success) {
            Log.w(TAG, "JNI exec exit ${result.exitCode}: ${result.output.take(500)}")
        }

        // JNI mode returns all output after completion — stream retroactively
        if (onProgress != null) {
            parseProgressFromOutput(result.output, onProgress)
        }
        if (onOutputLine != null) {
            for (line in result.output.lines()) {
                onOutputLine(line)
            }
        }

        return ExecResult(result.output, result.error, result.exitCode, duration)
    }

    private fun executeViaExec(
        py: String, pyz: String, args: List<String>,
        onProgress: ((ProgressUpdate) -> Unit)?,
        onOutputLine: ((String) -> Unit)?
    ): ExecResult {
        val startTime = System.currentTimeMillis()
        return try {
            val command = mutableListOf(py, pyz)
            command.addAll(args)
            val pb = ProcessBuilder(command).redirectErrorStream(true)
            configureEnvironment(pb)
            Log.d(TAG, "Exec (exec): ${command.joinToString(" ")}")
            val process = pb.start()

            // Read stdout line-by-line — stream in real-time + buffer for result
            val outputLines = mutableListOf<String>()
            val reader = process.inputStream.bufferedReader()
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                val raw = line!!
                if (parseProgressLine(raw, onProgress)) continue
                outputLines.add(raw)
                // Stream each line to caller in real-time
                onOutputLine?.invoke(raw)
            }

            val exitCode = process.waitFor()
            val duration = System.currentTimeMillis() - startTime
            val rawOutput = outputLines.joinToString("\n")
            val output = filterLinkerWarnings(rawOutput)
            if (exitCode != 0) {
                Log.w(TAG, "Exec exit $exitCode, output: ${output.take(500)}")
            }
            ExecResult(output, null, exitCode, duration)
        } catch (e: Exception) {
            val duration = System.currentTimeMillis() - startTime
            Log.e(TAG, "executePyz failed", e)
            ExecResult("", "Execution failed: ${e.message}", -1, duration)
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  Progress parsing
    // ═══════════════════════════════════════════════════════════════

    private val PROGRESS_MARKER = "__PROGRESS__"

    /**
     * Parse a single stdout line for __PROGRESS__ marker.
     * @return true if the line was a progress marker (stripped from output), false otherwise.
     *
     * Marker format: __PROGRESS__<current>/<total>/<message>[/<percent>]
     * When the optional 4th field (percent) is present, it is used directly
     * instead of computing from current/total, giving accurate sub-step
     * compression percentage.
     */
    private fun parseProgressLine(line: String, onProgress: ((ProgressUpdate) -> Unit)?): Boolean {
        val idx = line.indexOf(PROGRESS_MARKER)
        if (idx < 0) return false

        val payload = line.substring(idx + PROGRESS_MARKER.length)
        val parts = payload.split("/", limit = 4)
        if (parts.size >= 2) {
            // Python sends float current during compression sub-progress (e.g. "1.33/5/...").
            // toIntOrNull() drops these — use toDoubleOrNull() first, then truncate.
            val current = parts[0].toDoubleOrNull()?.toInt() ?: parts[0].toIntOrNull() ?: return false
            val total = parts[1].toIntOrNull() ?: return false
            val message = if (parts.size >= 3) parts[2].replace("_", " ") else ""
            // Use explicit percent from 4th field if available, otherwise compute from current/total
            val percent = if (parts.size >= 4) {
                parts[3].toIntOrNull() ?: if (total > 0) current * 100 / total else 0
            } else {
                if (total > 0) current * 100 / total else 0
            }
            onProgress?.invoke(ProgressUpdate(current, total, message, percent.coerceIn(0, 100)))
            return true  // Strip this line from output
        }
        return false
    }

    /** Parse all progress markers from completed output (for JNI mode retroactive parsing). */
    private fun parseProgressFromOutput(output: String, onProgress: (ProgressUpdate) -> Unit) {
        var lastProgress: ProgressUpdate? = null
        for (line in output.lineSequence()) {
            val idx = line.indexOf(PROGRESS_MARKER)
            if (idx < 0) continue
            val payload = line.substring(idx + PROGRESS_MARKER.length)
            val parts = payload.split("/", limit = 4)
            if (parts.size >= 2) {
                val current = parts[0].toDoubleOrNull()?.toInt() ?: parts[0].toIntOrNull() ?: continue
                val total = parts[1].toIntOrNull() ?: continue
                val message = if (parts.size >= 3) parts[2].replace("_", " ") else ""
                val percent = if (parts.size >= 4) {
                    parts[3].toIntOrNull() ?: if (total > 0) current * 100 / total else 0
                } else {
                    if (total > 0) current * 100 / total else 0
                }
                lastProgress = ProgressUpdate(current, total, message, percent.coerceIn(0, 100))
            }
        }
        // Emit the last known progress as final state
        lastProgress?.let { onProgress(it) }
    }

    // ═══════════════════════════════════════════════════════════════
    //  Environment configuration (exec mode only)
    // ═══════════════════════════════════════════════════════════════

    private fun configureEnvironment(pb: ProcessBuilder) {
        val env = pb.environment()

        if (isBundledPython && stdlibDir != null) {
            val libDir = nativeLibDir ?: pythonPath?.let { File(it).parent } ?: return
            env["LD_LIBRARY_PATH"] = libDir
            env["PYTHONHOME"] = stdlibDir!!
            env["PYTHONPATH"] = libDir
            env["TMPDIR"] = File(stdlibDir!!, "../tmp").absolutePath.also {
                File(it).mkdirs()  // Ensure tmp dir exists for Python tempfile
            }
            env["PYTHONUNBUFFERED"] = "1"

            /*
             * Preload ALL .so files from nativeLibraryDir via LD_PRELOAD.
             * This ensures dependency libraries (libcrypto.so.3, libbz2.so.1.0.8,
             * libz.so.1.3.2, etc.) are available when Python's C extensions
             * (hashlib, bz2, zlib, lzma) load at import time.
             *
             * Previous approach used manifest-based direct deps only, but
             * that missed indirect deps (e.g. libbz2 is needed by _bz2.so,
             * not by libpython3exec.so directly).  Also, resolveLibName()
             * stripped version numbers causing mismatch (libcrypto.so.3
             * was resolved to libcrypto.so which doesn't exist).
             */
            val preloadLibs = File(nativeLibDir!!).listFiles()
                ?.filter { it.name.endsWith(".so") || it.name.contains(".so.") }
                /*
                 * Exclude:
                 *   - libpybridge.so  (JNI bridge, not needed for exec mode)
                 *   - libpython3exec.so (the binary being executed — preloading it
                 *     causes a linker conflict on Android bionic)
                 */
                ?.filter { !it.name.contains("pybridge") && it.name != BUNDLED_PYTHON_LIB }
                ?.sortedBy { it.name }
                ?: emptyList()
            if (preloadLibs.isNotEmpty()) {
                val preloadString = preloadLibs.joinToString(":", transform = { it.absolutePath })
                env["LD_PRELOAD"] = preloadString
                val preloadBytes = preloadLibs.sumOf { it.length() }
                diag("[LD_PRELOAD] ${preloadLibs.size} libs ($preloadBytes bytes)")
            } else {
                diag("[LD_PRELOAD] no libs to preload")
            }
        } else {
            val py = pythonPath ?: return
            if (py.contains("termux")) {
                env["TERMUX_PREFIX"] = "/data/data/com.termux/files/usr"
                env["LD_LIBRARY_PATH"] = "/data/data/com.termux/files/usr/lib"
                env["PATH"] = "/data/data/com.termux/files/usr/bin:${env.getOrDefault("PATH", "")}"
                env["HOME"] = "/data/data/com.termux/files/home"
                env["TMPDIR"] = "/data/data/com.termux/files/usr/tmp"
            }
        }
    }

    /** Filter non-fatal CANNOT LINK EXECUTABLE warnings from exec mode output. */
    private fun filterLinkerWarnings(rawOutput: String): String {
        if (!isBundledPython) return rawOutput
        return rawOutput.lineSequence()
            .filterNot { it.contains("CANNOT LINK EXECUTABLE") }
            .joinToString("\n")
    }

    // ═══════════════════════════════════════════════════════════════
    //  Public accessors
    // ═══════════════════════════════════════════════════════════════

    fun isReady(): Boolean = initialized && pythonPath != null && pyzPath != null
    fun getPythonPath(): String? = pythonPath
    fun getPyzPath(): String? = pyzPath
    fun isBundled(): Boolean = isBundledPython

    /** Whether JNI mode (dlopen) is active vs exec mode (ProcessBuilder). */
    fun isJniMode(): Boolean = useJniMode

    /** Get the last initialization diagnostic log (for UI display). */
    fun getDiagnostics(): String = diagnosticLog.toString()

    fun checkDependencies(): String {
        if (!initialized) return "ERROR: Python not initialized"

        // Use JNI or exec based on current mode
        val result = executePyz(listOf("--check-deps"))
        return if (result.error != null) {
            "Failed: ${result.error}"
        } else {
            result.output.ifEmpty { "No output (exit ${result.exitCode})" }
        }
    }

    /**
     * Parsed dependency check result for pre-repack validation.
     */
    data class DepCheckResult(
        val allOk: Boolean,
        val missing: List<String>,
        val availableCompression: List<String>
    )

    /**
     * Run --check-deps and parse the output into a structured result.
     * Used by UI to validate before allowing repack.
     *
     * Expected format:
     *   OK:     "v3.1.0 | Python 3.13.13 | none, gzip, bzip2, xz, brotli"
     *   Error:  "v3.1.0 | Python 3.13.13 | none, gzip, bzip2, xz, brotli | missing: hashlib"
     */
    fun checkDependenciesParsed(): DepCheckResult {
        if (!initialized) return DepCheckResult(false, listOf("python"), emptyList())

        val output = checkDependencies().trim()

        // If output is multi-line (old format fallback), parse the legacy way
        if (output.lines().size > 1) {
            return parseLegacyDepOutput(output)
        }

        // Parse new single-line pipe-delimited format
        val parts = output.split("|").map { it.trim() }
        val compression = if (parts.size >= 3) {
            parts[2].split(",").map { it.trim() }.filter { it.isNotBlank() }
        } else {
            emptyList()
        }
        val missingPart = parts.find { it.startsWith("missing:") }
        val missing = if (missingPart != null) {
            missingPart.removePrefix("missing:").split(",").map { it.trim() }.filter { it.isNotBlank() }
        } else {
            emptyList()
        }
        val allOk = missing.isEmpty()

        return DepCheckResult(allOk, missing, compression)
    }

    /** Parse legacy multi-line dependency output (pre-refactor format). */
    private fun parseLegacyDepOutput(output: String): DepCheckResult {
        var allOk = false
        val missing = mutableListOf<String>()
        val availableCompression = mutableListOf<String>()

        for (line in output.lines()) {
            val trimmed = line.trim()
            if (trimmed == "Status: OK - all required dependencies available") {
                allOk = true
            }
            if (trimmed.startsWith("[!]")) {
                missing.add(trimmed.removePrefix("[!] ").trim())
            }
        }

        val compLine = output.lines().find { it.trim().startsWith("Compression:") }
        if (compLine != null) {
            val comps = compLine.substringAfter(":").split(",").map { it.trim() }
            availableCompression.addAll(comps)
        }

        return DepCheckResult(allOk, missing, availableCompression)
    }

    fun isTermuxInstalled(): Boolean {
        return SYSTEM_PYTHON_PATHS.any { it.contains("termux") && File(it).exists() }
    }
}

/** Progress data parsed from Python __PROGRESS__ stdout markers. */
data class ProgressUpdate(
    val current: Int,
    val total: Int,
    val message: String,
    val percent: Int
)

data class ExecResult(
    val output: String,
    val error: String?,
    val exitCode: Int,
    val durationMs: Long
) {
    val success: Boolean get() = exitCode == 0 && error == null
}
