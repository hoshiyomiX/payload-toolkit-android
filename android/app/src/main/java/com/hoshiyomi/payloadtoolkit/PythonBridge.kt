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
 * Architecture (v5 — jniLibs + stdlib asset + build manifest):
 *
 *   On the BUILD machine (CI):
 *     Termux Python 3.13 packages → split into three outputs:
 *       1. jniLibs/arm64-v8a/         — .so libs + python binary
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
 *     3. Execute nativeLibraryDir/libpython3exec.so with:
 *        - LD_PRELOAD      = all .so files (transitive dep resolution)
 *        - LD_LIBRARY_PATH = nativeLibraryDir
 *        - PYTHONHOME      = extracted stdlib
 *        - PYTHONPATH       = nativeLibraryDir
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
     *   1. Bundled Python (nativeLibraryDir from jniLibs + stdlib from assets)
     *   2. System Python (Termux or other)
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
            val nativeLibDir = ctx.applicationInfo.nativeLibraryDir
            val bundledPy = File(nativeLibDir, BUNDLED_PYTHON_LIB)

            diag("nativeLibraryDir: $nativeLibDir")

            // -- Full .so inventory --
            val nativeDir = File(nativeLibDir)
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
                // Log every .so with size — this makes finding missing/extra files instant
                val totalSize = soFiles.sumOf { it.length() }
                diag("[OK] .so total size: ${formatBytes(totalSize)}")
                diag("--- .so inventory ---")
                soFiles.forEach { f ->
                    diag("  ${f.name} (${formatBytes(f.length())})")
                }
                diag("--- end inventory ---")
            }

            // -- ELF header validation --
            diag("libpython3exec.so exists: ${bundledPy.isFile}")
            if (bundledPy.isFile) {
                val elfOk = validateElfHeader(bundledPy)
                diag("libpython3exec.so valid ELF: $elfOk")
                diag("libpython3exec.so canExecute: ${bundledPy.canExecute()}")
                diag("libpython3exec.so size: ${formatBytes(bundledPy.length())}")
            }

            // -- Manifest cross-check --
            if (soFiles != null && soFiles.isNotEmpty()) {
                val manifestIssues = crossCheckManifest(ctx, nativeLibDir, soFiles)
                if (manifestIssues.isNotEmpty()) {
                    diag("--- MANIFEST MISMATCH ---")
                    manifestIssues.forEach { diag("  $it") }
                    diag("--- end mismatch ---")
                } else {
                    diag("[OK] Manifest cross-check: all ${soFiles.size} .so files match build")
                }
            }

            if (bundledPy.isFile) {
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

            // Step 3: Fallback to system Python
            if (pythonPath == null) {
                diag("Trying system Python fallback...")
                val found = findSystemPython()
                if (found != null) {
                    pythonPath = found
                    isBundledPython = false
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

            // Step 4: Smoke test
            val verifyError = verifySetup()
            if (verifyError != null) {
                Log.w(TAG, "Verify failed: $verifyError")
                diag("Verify failed: $verifyError")
                // Parse linker error for actionable diagnostics
                val parsedError = parseLinkerError(verifyError)
                if (parsedError != null) {
                    diag("--- LINKER ERROR ANALYSIS ---")
                    diag("  Problem .so:  ${parsedError.first}")
                    diag("  Missing dep:  ${parsedError.second}")
                    diag("  Fix: add the missing .so to prepare_python_runtime.sh PACKAGES,")
                    diag("       or exclude the problem .so if not needed.")
                    diag("--- end analysis ---")
                } else if (verifyError.contains("did_read_") || verifyError.contains("linker_phdr")
                    || verifyError.contains("CHECK") || verifyError.contains("SIGABRT")) {
                    diag("--- LINKER CRASH ANALYSIS ---")
                    diag("  Error type: bionic linker CHECK failure (ELF corruption)")
                    diag("  This means a .so file has invalid program headers.")
                    diag("  The linker crashes during LD_PRELOAD before it can report which file.")
                    diag("  Check [ELF VALIDATION] section above for detected corrupt files.")
                    diag("  v3.17: LD_PRELOAD removed. If this error persists from an old")
                    diag("  APK, uninstall and reinstall from the latest build.")
                    diag("--- end analysis ---")
                }
                return InitResult(false, pythonPath, pyzPath, isBundledPython,
                    verifyError, diagnosticLog.toString())
            }

            initialized = true
            return InitResult(true, pythonPath, pyzPath, isBundledPython)
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

    /**
     * Validate that a file starts with the ELF magic bytes (\x7fELF).
     * Catches cases where the file is corrupt or not actually an ELF binary.
     */
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

    /**
     * Read a little-endian unsigned 64-bit integer from the current file position.
     * RandomAccessFile.readInt()/readLong() use BIG-endian, but ELF64 on ARM64
     * is LITTLE-endian, so we must read bytes manually and reconstruct.
     */
    private fun readLeLong(raf: RandomAccessFile, bytes: Int): Long {
        val buf = ByteArray(bytes)
        raf.readFully(buf)
        var result = 0L
        for (i in buf.indices) {
            result = result or ((buf[i].toLong() and 0xFFL) shl (8 * i))
        }
        return result
    }

    /**
     * Read a little-endian unsigned 32-bit integer from the current file position.
     */
    private fun readLeUInt(raf: RandomAccessFile): Int {
        val buf = ByteArray(4)
        raf.readFully(buf)
        return (buf[0].toInt() and 0xFF) or
               ((buf[1].toInt() and 0xFF) shl 8) or
               ((buf[2].toInt() and 0xFF) shl 16) or
               ((buf[3].toInt() and 0xFF) shl 24)
    }

    /**
     * Read a little-endian unsigned 16-bit integer from the current file position.
     */
    private fun readLeUShort(raf: RandomAccessFile): Int {
        val buf = ByteArray(2)
        raf.readFully(buf)
        return (buf[0].toInt() and 0xFF) or ((buf[1].toInt() and 0xFF) shl 8)
    }

    /**
     * Deep ELF validation — reads program headers and validates all PT_LOAD
     * and PT_DYNAMIC segments.  Catches files that will crash the bionic
     * linker with "Load CHECK 'did_read_' failed".
     *
     * The bionic linker's Load() function mmaps the file and reads each
     * PT_LOAD segment's data (p_offset .. p_offset+p_filesz).  If a segment
     * extends past the file, the read fails and CHECK('did_read_') aborts.
     *
     * patchelf can corrupt segments by growing the dynamic section past
     * the end of the file on binaries with minimal section padding.
     *
     * ELF64 Phdr layout (56 bytes each):
     *   0: p_type(4)  p_flags(4)
     *   8: p_offset(8)  p_vaddr(8)  p_paddr(8)
     *  32: p_filesz(8)  p_memsz(8)  p_align(8)
     *
     * Returns null if valid, or a description of the problem.
     */
    private fun validateElfProgramHeaders(file: File): String? {
        return try {
            val raf = RandomAccessFile(file, "r")
            try {
                val size = raf.length()
                if (size < 64) return "file too small for ELF header (${size} bytes)"

                // ELF64 header fields (all LITTLE-endian on ARM64):
                //   e_phoff:     offset 32, 8 bytes
                //   e_phentsize: offset 54, 2 bytes
                //   e_phnum:     offset 56, 2 bytes
                raf.seek(32)
                val ePhoff = readLeLong(raf, 8)
                raf.seek(54)
                val ePhentsize = readLeUShort(raf).toLong()
                raf.seek(56)
                val ePhnum = readLeUShort(raf).toLong()

                // Check 1: phdr table itself fits in file
                val phTableEnd = ePhoff + ePhnum * ePhentsize
                if (phTableEnd > size) {
                    return "phdr table overflows: offset=$ePhoff + ${ePhnum}x${ePhentsize} = $phTableEnd > fileSize=$size"
                }

                // Check 2: each PT_LOAD and PT_DYNAMIC segment fits in file
                for (i in 0 until ePhnum.toInt()) {
                    val entryOff = ePhoff + i * ePhentsize

                    // Read p_type (Elf64_Word, 4 bytes at offset 0 within Phdr)
                    raf.seek(entryOff)
                    val pType = readLeUInt(raf)

                    // For PT_LOAD (1) and PT_DYNAMIC (2): validate segment bounds
                    if (pType == 1 || pType == 2) {
                        // p_offset at offset 8, p_filesz at offset 32 (Elf64, 8 bytes each)
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

                null // valid
            } finally {
                raf.close()
            }
        } catch (e: Exception) {
            "read error: ${e.message}"
        }
    }

    /**
     * Cross-check nativeLibraryDir .so files against the build-time manifest.
     * Detects: missing files (expected by build, not on device),
     *          extra files (on device but not in build),
     *          size mismatches (file corrupted or wrong version).
     *
     * Returns a list of issue descriptions (empty = all OK).
     */
    private fun crossCheckManifest(
        context: Context,
        nativeLibDir: String,
        deviceSoFiles: List<File>
    ): List<String> {
        val issues = mutableListOf<String>()
        val manifestMap = parseManifest(context) ?: run {
            issues.add("WARNING: manifest asset not found — cannot cross-check")
            return issues
        }

        val deviceNames = deviceSoFiles.associate { it.name to it.length() }

        // Check for files in manifest but missing on device
        for ((name, expectedSize) in manifestMap) {
            val deviceFile = deviceNames[name]
            if (deviceFile == null) {
                issues.add("MISSING on device: $name (expected ${formatBytes(expectedSize)})")
            } else if (deviceFile != expectedSize) {
                issues.add("SIZE MISMATCH: $name (device=${formatBytes(deviceFile)}, build=${formatBytes(expectedSize)})")
            }
        }

        // Check for files on device but not in manifest (stale from old APK?)
        for (name in deviceNames.keys) {
            if (name !in manifestMap) {
                issues.add("EXTRA on device: $name (not in build manifest — stale from old APK?)")
            }
        }

        return issues
    }

    /**
     * Parse the build-time manifest from assets.
     * Returns a map of filename -> expected size, or null if manifest not found.
     */
    private fun parseManifest(context: Context): Map<String, Long>? {
        return try {
            val content = context.assets.open(MANIFEST_ASSET_NAME)
                .bufferedReader().readText()
            val map = mutableMapOf<String, Long>()
            // Parse lines like: libfoo.so | 12345 | libz.so,libpython3.13.so
            for (line in content.lines()) {
                if (line.startsWith("#") || line.isBlank()) continue
                val parts = line.split(" | ")
                if (parts.size >= 2) {
                    val name = parts[0].trim()
                    val size = parts[1].trim().toLongOrNull() ?: continue
                    map[name] = size
                }
            }
            diag("[Manifest] Parsed ${map.size} entries")
            map
        } catch (e: Exception) {
            diag("[Manifest] Could not read: ${e.message}")
            null
        }
    }

    /**
     * Parse Android linker error messages to extract actionable information.
     *
     * Recognized patterns:
     *   - "CANNOT LINK EXECUTABLE ...: library \"libfoo.so\" not found: needed by libbar.so"
     *   - "dlopen failed: library \"libfoo.so\" not found"
     *   - "dlopen failed: cannot find \"libfoo.so\" from verneed[N] in DT_NEEDED list"
     *
     * Returns Pair(problemSo, missingDep) or null if not a recognizable linker error.
     */
    private fun parseLinkerError(error: String): Pair<String, String>? {
        // Pattern 1: "library "X" not found: needed by Y" or "needed by .../Y"
        val neededByPattern = Regex(
            """library\s+"([^"]+)"\s+not\s+found.*?needed\s+by\s+\S*/(\S+)"""
        )
        val m1 = neededByPattern.find(error)
        if (m1 != null) {
            return Pair(m1.groupValues[2], m1.groupValues[1])
        }

        // Pattern 2: "dlopen failed: library "X" not found"
        val dlopenPattern = Regex("""dlopen failed:\s+library\s+"([^"]+)"\s+not\s+found""")
        val m2 = dlopenPattern.find(error)
        if (m2 != null) {
            // We know what's missing but not who needs it
            return Pair("(unknown requester)", m2.groupValues[1])
        }

        // Pattern 3: "cannot find "X" from verneed" (Android-specific)
        val verneedPattern = Regex(
            """cannot find\s+"([^"]+)"\s+from\s+verneed\[\d+\].*?for\s+(\S+)"""
        )
        val m3 = verneedPattern.find(error)
        if (m3 != null) {
            return Pair(m3.groupValues[2], m3.groupValues[1])
        }

        return null
    }

    /** Format bytes in human-readable form. */
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

    private fun verifySetup(): String? {
        val py = pythonPath ?: return "No Python path"
        val pyz = pyzPath ?: return "No .pyz path"

        if (isBundledPython) {
            val pyFile = File(py)
            if (!pyFile.exists()) return "Bundled Python not found: $py"
            if (!pyFile.canExecute()) {
                Log.w(TAG, "Bundled Python exists but may not be executable: $py")
                Log.w(TAG, "nativeLibraryDir: ${pyFile.parent}")
            }
            if (stdlibDir != null) {
                val libDir = File(stdlibDir!!, "lib/python3.13")
                if (!libDir.isDirectory) return "Stdlib lib dir missing: ${libDir.absolutePath}"
                val pyCount = libDir.listFiles()?.count { it.name.endsWith(".py") } ?: 0
                if (pyCount < 10) return "Stdlib too small: $pyCount .py files in $libDir"
            }
        }

        return try {
            val pb = ProcessBuilder(py, pyz, "--version")
                .redirectErrorStream(true)
            configureEnvironment(pb)
            Log.d(TAG, "Running: $py $pyz --version")
            val process = pb.start()
            val output = process.inputStream.bufferedReader().readText().trim()
            val exitCode = process.waitFor()
            if (exitCode == 0 && output.isNotEmpty()) {
                Log.d(TAG, "Verify OK: $output")
                null
            } else {
                "Python exit $exitCode: $output"
            }
        } catch (e: Exception) {
            Log.e(TAG, "Verify exception", e)
            "Failed to run Python: ${e.message}"
        }
    }

    fun executePyz(args: List<String>): ExecResult {
        val py = pythonPath ?: return ExecResult("", "Python not initialized", -1, 0)
        val pyz = pyzPath ?: return ExecResult("", ".pyz not found", -1, 0)

        val startTime = System.currentTimeMillis()
        return try {
            val command = mutableListOf(py, pyz)
            command.addAll(args)

            val pb = ProcessBuilder(command).redirectErrorStream(true)
            configureEnvironment(pb)

            Log.d(TAG, "Exec: ${command.joinToString(" ")}")
            val process = pb.start()
            val output = process.inputStream.bufferedReader().readText()
            val exitCode = process.waitFor()
            val duration = System.currentTimeMillis() - startTime

            if (exitCode != 0) {
                Log.w(TAG, "Python exit $exitCode, output: ${output.take(500)}")
            }
            ExecResult(output, null, exitCode, duration)
        } catch (e: Exception) {
            val duration = System.currentTimeMillis() - startTime
            Log.e(TAG, "executePyz failed", e)
            ExecResult("", "Execution failed: ${e.message}", -1, duration)
        }
    }

    /**
     * @deprecated Use [executePyz] instead. Kept for backward compatibility.
     */
    fun executePyzWithTermuxEnv(args: List<String>): ExecResult = executePyz(args)

    // ═══════════════════════════════════════════════════════════════
    //  Environment configuration
    // ═══════════════════════════════════════════════════════════════

    /**
     * Configure ProcessBuilder environment for the current Python source.
     *
     * Bundled Python (from jniLibs):
     *   LD_PRELOAD = absolute paths of ALL .so files in nativeLibraryDir
     *       -> Preloads every shared lib at process start, so transitive
     *          deps of dlopen'd C extensions are already in the loaded map.
     *   LD_LIBRARY_PATH = nativeLibraryDir
     *   PYTHONHOME = stdlibDir
     *   PYTHONPATH = nativeLibraryDir
     *
     * System Python (Termux fallback):
     *   Standard Termux environment variables.
     */
    private fun configureEnvironment(pb: ProcessBuilder) {
        val env = pb.environment()

        if (isBundledPython && stdlibDir != null) {
            val nativeLibDir = pythonPath?.let { File(it).parent } ?: return
            env["LD_LIBRARY_PATH"] = nativeLibDir
            env["PYTHONHOME"] = stdlibDir!!
            env["PYTHONPATH"] = nativeLibDir
            env["TMPDIR"] = File(stdlibDir!!, "../tmp").absolutePath

            // v3.17: NO LD_PRELOAD.  Previous approach preloaded all 74 .so files
            // via LD_PRELOAD to handle transitive deps.  This caused persistent
            // "Load CHECK 'did_read_' failed" crashes — some files have subtle
            // ELF corruption not detectable from header validation.
            //
            // New approach: rely on LD_LIBRARY_PATH + build-time patching:
            //   - DT_SONAME stripped → linker registers libs by filename
            //   - DT_NEEDED unversioned → libz.so.1 → libz.so (matches APK files)
            //   - LD_LIBRARY_PATH → linker searches nativeLibraryDir for all deps
            //
            // This gives SPECIFIC error messages ("library X not found") instead
            // of generic crashes, making debugging immediate.
            diag("[LIBRARY RESOLUTION] LD_LIBRARY_PATH=$nativeLibDir (no LD_PRELOAD)")
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

    // ═══════════════════════════════════════════════════════════════
    //  Public accessors
    // ═══════════════════════════════════════════════════════════════

    fun isReady(): Boolean = initialized && pythonPath != null && pyzPath != null
    fun getPythonPath(): String? = pythonPath
    fun getPyzPath(): String? = pyzPath
    fun isBundled(): Boolean = isBundledPython

    /** Get the last initialization diagnostic log (for UI display). */
    fun getDiagnostics(): String = diagnosticLog.toString()

    fun checkDependencies(): String {
        val py = pythonPath ?: return "ERROR: Python not initialized"
        val pyz = pyzPath ?: return "ERROR: .pyz not extracted from assets"

        return try {
            val pb = ProcessBuilder(py, pyz, "--check-deps")
                .redirectErrorStream(true)
            configureEnvironment(pb)
            val process = pb.start()
            val output = process.inputStream.bufferedReader().readText().trim()
            process.waitFor()
            output.ifEmpty { "No output (exit ${process.exitValue()})" }
        } catch (e: Exception) {
            "Failed: ${e.message}"
        }
    }

    fun isTermuxInstalled(): Boolean {
        return SYSTEM_PYTHON_PATHS.any { it.contains("termux") && File(it).exists() }
    }
}

data class ExecResult(
    val output: String,
    val error: String?,
    val exitCode: Int,
    val durationMs: Long
) {
    val success: Boolean get() = exitCode == 0 && error == null
}
