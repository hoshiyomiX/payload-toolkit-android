package com.hoshiyomi.payloadtoolkit

import android.content.Context
import android.util.Log
import com.hoshiyomi.payloadtoolkit.BuildConfig
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipInputStream

/**
 * PythonBridge — Manages Python runtime initialization and .pyz execution.
 *
 * Architecture (v4 — jniLibs + stdlib asset):
 *
 *   On the BUILD machine (CI):
 *     Termux Python 3.13 packages → split into two outputs:
 *       1. jniLibs/arm64-v8a/   — .so libs + python binary (renamed libpython3exec.so)
 *       2. assets/python-stdlib.zip — .py stdlib files (613 files, ~2.7 MB)
 *
 *   At INSTALL time:
 *     Android package manager extracts jniLibs → nativeLibraryDir
 *     nativeLibraryDir has SELinux "app_lib_file" context → EXECUTABLE ✓
 *
 *   At RUNTIME (first launch):
 *     1. Extract python-stdlib.zip from assets → app-internal storage (read-only)
 *     2. Execute nativeLibraryDir/libpython3exec.so with:
 *        - LD_LIBRARY_PATH = nativeLibraryDir  (finds libpython3.13.so, liblzma, etc.)
 *        - PYTHONHOME      = extracted stdlib   (finds lib/python3.13/...py)
 *        - PYTHONPATH       = nativeLibraryDir  (finds _hashlib.so, _lzma.so, etc.)
 *
 *   Fallback: if nativeLibraryDir lacks libpython3exec.so, tries system Python.
 */
object PythonBridge {

    private const val TAG = "PythonBridge"
    private const val PYZ_ASSET_NAME = "payload_toolkit.pyz"
    private const val STDLIB_ASSET_NAME = "python-stdlib.zip"
    private const val PYTHON_DIR_NAME = "python"
    private const val STDLIB_SUBDIR = "stdlib"
    private const val BUNDLED_PYTHON_LIB = "libpython3exec.so"

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
    private var sonameDirPath: String? = null
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

            // Step 1: Extract .pyz from assets
            val extractedPyz = extractPyz(ctx)
            if (extractedPyz == null) {
                return InitResult(false, null, null,
                    error = "Failed to extract $PYZ_ASSET_NAME from assets",
                    diagnostics = diagnosticLog.toString())
            }
            pyzPath = extractedPyz
            diag("[OK] .pyz extracted: $pyzPath (${File(pyzPath).length()} bytes)")

            // Step 2: Try bundled Python from nativeLibraryDir (jniLibs)
            val nativeLibDir = ctx.applicationInfo.nativeLibraryDir
            val bundledPy = File(nativeLibDir, BUNDLED_PYTHON_LIB)

            diag("nativeLibraryDir: $nativeLibDir")

            // Fix SONAME symlinks in nativeLibraryDir.
            // AGP only packages files ending with .so, so versioned files like
            // libz.so.1 are stored as libz.so.1.so in the APK.  At runtime we
            // create symlinks in an app-writable directory so the linker can
            // resolve DT_NEEDED references.
            sonameDirPath = fixSonameSymlinks(nativeLibDir, ctx)

            diag("libpython3exec.so exists: ${bundledPy.isFile}")
            diag("libpython3exec.so canExecute: ${bundledPy.isFile && bundledPy.canExecute()}")

            // List contents of nativeLibraryDir for diagnostics
            val nativeDir = File(nativeLibDir)
            if (nativeDir.isDirectory) {
                val soFiles = nativeDir.listFiles()?.filter { it.name.endsWith(".so") }
                diag("Total .so in nativeLibraryDir: ${soFiles?.size ?: 0}")
                val pythonSo = soFiles?.filter { it.name.contains("python") }
                if (pythonSo.isNullOrEmpty()) {
                    diag("WARNING: No python-related .so files found in nativeLibraryDir")
                    // List all files for full visibility
                    val allFiles = nativeDir.listFiles()?.map { it.name }?.sorted()
                    if (!allFiles.isNullOrEmpty()) {
                        diag("Files in nativeLibraryDir: ${allFiles.take(10).joinToString(", ")}")
                        if (allFiles.size > 10) diag("  ... and ${allFiles.size - 10} more")
                    } else {
                        diag("nativeLibraryDir is EMPTY")
                    }
                } else {
                    diag("Python-related .so files: ${pythonSo.map { it.name }.joinToString(", ")}")
                }
            } else {
                diag("ERROR: nativeLibraryDir does not exist or is not a directory")
            }

            if (bundledPy.isFile) {
                diag("Attempting bundled Python initialization...")

                // Check if stdlib asset exists before extraction
                val stdlibAssets = try {
                    ctx.assets.list("")?.filter { it.contains("stdlib") || it.contains("python") }
                } catch (_: Exception) { null }
                diag("Python-related assets: ${stdlibAssets?.joinToString(", ") ?: "none found"}")

                // Extract Python stdlib (.py files) from assets
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
                return InitResult(false, pythonPath, pyzPath, isBundledPython,
                    verifyError, diagnosticLog.toString())
            }

            initialized = true
            return InitResult(true, pythonPath, pyzPath, isBundledPython)
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  SONAME symlink repair
    // ═══════════════════════════════════════════════════════════════

    /**
     * AGP only packages files ending with ".so" from jniLibs.
     * Versioned SONAME files like libz.so.1 were renamed to libz.so.1.so
     * by prepare_python_runtime.sh so AGP would include them in the APK.
     *
     * At runtime, the linker resolves DT_NEEDED (e.g. "libz.so.1") by
     * searching LD_LIBRARY_PATH directories.  We COPY the .so.so files
     * into an app-writable directory (filesDir/python/soname/) with their
     * correct SONAME names, and prepend this directory to LD_LIBRARY_PATH.
     *
     * We use copies instead of symlinks because:
     *   1. SELinux may block cross-context symlink traversal
     *      (app_data_file -> app_lib_file)
     *   2. LD_LIBRARY_PATH is unreliable on modern Android for dlopen()
     *      transitive dependencies; the linker namespace may not include
     *      paths added at runtime via LD_LIBRARY_PATH.
     *   3. Copies are always accessible regardless of linker config.
     *
     * Example:
     *   filesDir/python/soname/libz.so.1  (copy of nativeLibraryDir/libz.so.1.so)
     *
     * Returns the soname directory path (to be prepended to LD_LIBRARY_PATH).
     */
    private fun fixSonameSymlinks(nativeLibDir: String, context: Context): String {
        val sonameDir = File(context.filesDir, "python/soname")
        if (!sonameDir.exists()) sonameDir.mkdirs()

        var created = 0
        val nativeDir = File(nativeLibDir)
        val soSoFiles = nativeDir.listFiles()?.filter {
            it.isFile && it.name.endsWith(".so.so")
        }
        diag("SONAME .so.so files in nativeLibraryDir: ${soSoFiles?.size ?: 0}")

        soSoFiles?.forEach { file ->
            // libz.so.1.so -> remove trailing .so -> libz.so.1
            val soname = file.name.removeSuffix(".so")
            val target = File(sonameDir, soname)
            if (!target.exists() || target.length() != file.length()) {
                try {
                    file.copyTo(target, overwrite = true)
                    // Make readable/executable for the linker
                    target.setReadable(true, false)
                    target.setExecutable(true, false)
                    created++
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to copy SONAME file $soname: ${e.message}")
                    diag("WARN: Failed to copy SONAME $soname: ${e.message}")
                }
            }
        }
        if (created > 0) {
            val sonameList = sonameDir.listFiles()?.map { it.name }?.sorted()
            Log.d(TAG, "Created $created SONAME copies in ${sonameDir.absolutePath}")
            diag("Created $created SONAME copies: ${sonameList?.joinToString(", ")}")
        } else if (soSoFiles.isNullOrEmpty()) {
            diag("WARNING: No .so.so files found — SONAME resolution disabled")
        }
        return sonameDir.absolutePath
    }

    // ═══════════════════════════════════════════════════════════════
    //  Asset extraction
    // ═══════════════════════════════════════════════════════════════

    /**
     * Extract payload_toolkit.pyz from assets to app-internal storage.
     */
    private fun extractPyz(context: Context): String? {
        val dir = File(context.filesDir, PYTHON_DIR_NAME).also { it.mkdirs() }
        val file = File(dir, PYZ_ASSET_NAME)

        // Skip if already extracted and same size
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

    /**
     * Extract python-stdlib.zip from assets to app-internal storage.
     * Uses a version marker to skip re-extraction on subsequent launches.
     *
     * Output: filesDir/python/stdlib/lib/python3.13/...py
     */
    private fun extractStdlib(context: Context): String? {
        val pythonDir = File(context.filesDir, PYTHON_DIR_NAME)
        val stdlibDirFile = File(pythonDir, STDLIB_SUBDIR)
        val marker = File(pythonDir, ".stdlib_v${BuildConfig.VERSION_CODE}")

        // Already extracted for this version?
        if (marker.exists() && stdlibDirFile.isDirectory) {
            val children = stdlibDirFile.listFiles()?.size ?: 0
            if (children > 0) {
                Log.d(TAG, "Stdlib already extracted ($children items)")
                return stdlibDirFile.absolutePath
            }
        }

        // Clean previous extraction
        stdlibDirFile.deleteRecursively()

        return try {
            Log.d(TAG, "Extracting $STDLIB_ASSET_NAME ...")
            stdlibDirFile.mkdirs()
            extractZipAsset(context, STDLIB_ASSET_NAME, stdlibDirFile)
            marker.createNewFile()

            // Clean empty directories left after stripping
            pruneEmptyDirs(stdlibDirFile)

            val count = countFiles(stdlibDirFile)
            Log.d(TAG, "Stdlib extracted: $count files")
            stdlibDirFile.absolutePath
        } catch (e: Exception) {
            Log.w(TAG, "Failed to extract stdlib", e)
            null
        }
    }

    /**
     * Extract a ZIP asset to a target directory.
     */
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

        // Pre-flight checks for bundled Python
        if (isBundledPython) {
            val pyFile = File(py)
            if (!pyFile.exists()) return "Bundled Python not found: $py"
            if (!pyFile.canExecute()) {
                // Log SELinux context for debugging
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
            Log.d(TAG, "PYTHONHOME=${pb.environment()["PYTHONHOME"]}")
            Log.d(TAG, "LD_LIBRARY_PATH=${pb.environment()["LD_LIBRARY_PATH"]}")
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

    /**
     * Execute payload_toolkit.pyz with given arguments.
     * Automatically configures the environment for bundled or system Python.
     */
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
     *   LD_LIBRARY_PATH = nativeLibraryDir
     *       → Linker finds libpython3.13.so, liblzma.so, libcrypto.so, etc.
     *   PYTHONHOME = stdlibDir
     *       → Python finds lib/python3.13/...py (stdlib modules)
     *   PYTHONPATH = nativeLibraryDir
     *       → Python finds _hashlib.so, _lzma.so, _bz2.so (C extensions)
     *
     * System Python (Termux fallback):
     *   Standard Termux environment variables.
     */
    private fun configureEnvironment(pb: ProcessBuilder) {
        val env = pb.environment()

        if (isBundledPython && stdlibDir != null) {
            val nativeLibDir = pythonPath?.let { File(it).parent } ?: return
            // LD_LIBRARY_PATH: soname dir first (for SONAME resolution),
            // then nativeLibraryDir (for base libs and C extensions)
            val ldPath = buildString {
                sonameDirPath?.let { append("$it:") }
                append(nativeLibDir)
            }
            env["LD_LIBRARY_PATH"] = ldPath
            env["PYTHONHOME"] = stdlibDir!!
            env["PYTHONPATH"] = nativeLibDir
            env["TMPDIR"] = File(stdlibDir!!, "../tmp").absolutePath
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
