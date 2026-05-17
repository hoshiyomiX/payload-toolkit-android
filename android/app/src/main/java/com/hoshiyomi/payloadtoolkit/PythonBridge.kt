package com.hoshiyomi.payloadtoolkit

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream

/**
 * PythonBridge — Manages .pyz extraction and external Python discovery.
 *
 * Architecture:
 *   1. payload_toolkit.pyz is bundled as an Android asset
 *   2. On first init, .pyz is extracted to app internal storage
 *   3. Device Python binary is discovered (Termux, system, etc.)
 *   4. Execution is done via ProcessBuilder subprocess
 *
 * No Chaquopy dependency — uses whatever Python is available on the device.
 */
object PythonBridge {

    private const val TAG = "PythonBridge"
    private const val PYZ_ASSET_NAME = "payload_toolkit.pyz"
    private const val PYZ_DIR_NAME = "python"

    /**
     * Ordered list of Python binary paths to try.
     * Termux is most common for power users, then system paths.
     */
    private val PYTHON_PATHS = listOf(
        "/data/data/com.termux/files/usr/bin/python3",
        "/data/data/com.termux/files/usr/bin/python",
        "/system/bin/python3",
        "/usr/bin/python3",
        "/usr/local/bin/python3"
    )

    @Volatile private var initialized = false
    private var pythonPath: String? = null
    private var pyzPath: String? = null
    private val initLock = Any()

    /**
     * Result of Python initialization attempt.
     */
    data class InitResult(
        val success: Boolean,
        val pythonPath: String?,
        val pyzPath: String?,
        val error: String? = null
    )

    /**
     * Initialize: extract .pyz from assets and discover Python binary.
     *
     * @param context Android context (for asset extraction)
     * @return [InitResult] with paths or error description
     */
    fun ensureInitialized(context: Context?): InitResult {
        if (initialized) return InitResult(true, pythonPath, pyzPath)

        synchronized(initLock) {
            if (initialized) return InitResult(true, pythonPath, pyzPath)

            val ctx = context
                ?: return InitResult(false, null, null, "Context required for initialization")

            // Step 1: Extract .pyz from assets
            val extractedPyz = extractPyz(ctx)
            if (extractedPyz == null) {
                return InitResult(false, null, null, "Failed to extract $PYZ_ASSET_NAME from assets")
            }
            pyzPath = extractedPyz
            Log.d(TAG, "Extracted $PYZ_ASSET_NAME to $pyzPath")

            // Step 2: Find Python binary
            val foundPython = findPythonBinary()
            if (foundPython == null) {
                val hint = "Python not found. Install Termux and run: pkg install python"
                Log.w(TAG, hint)
                return InitResult(false, null, pyzPath, hint)
            }
            pythonPath = foundPython
            Log.d(TAG, "Found Python at: $pythonPath")

            // Step 3: Verify Python can run the .pyz
            val verifyResult = verifySetup()
            if (verifyResult != null) {
                Log.w(TAG, "Verification failed: $verifyResult")
                return InitResult(false, pythonPath, pyzPath, verifyResult)
            }

            initialized = true
            return InitResult(true, pythonPath, pyzPath)
        }
    }

    /**
     * Extract payload_toolkit.pyz from assets to app-internal storage.
     */
    private fun extractPyz(context: Context): String? {
        val pythonDir = File(context.filesDir, PYZ_DIR_NAME).also { it.mkdirs() }
        val pyzFile = File(pythonDir, PYZ_ASSET_NAME)

        // Check if extraction is needed
        if (pyzFile.exists()) {
            try {
                val assetSize = context.assets.open(PYZ_ASSET_NAME).use { it.available().toLong() }
                if (assetSize == pyzFile.length()) {
                    return pyzFile.absolutePath // Already up to date
                }
            } catch (_: Exception) {}
        }

        return try {
            context.assets.open(PYZ_ASSET_NAME).use { input ->
                FileOutputStream(pyzFile).use { output ->
                    input.copyTo(output)
                }
            }
            pyzFile.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "Failed to extract $PYZ_ASSET_NAME", e)
            null
        }
    }

    /**
     * Discover a usable Python binary on the device.
     */
    private fun findPythonBinary(): String? {
        for (path in PYTHON_PATHS) {
            val file = File(path)
            if (file.exists() && file.canExecute()) {
                return path
            }
        }

        // Try 'python3' via PATH (works if Termux is in PATH)
        return try {
            val pb = ProcessBuilder("which", "python3")
            val process = pb.start()
            val output = process.inputStream.bufferedReader().readText().trim()
            process.waitFor()
            if (process.exitValue() == 0 && output.isNotEmpty()) {
                output
            } else null
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Quick smoke test: run .pyz --version.
     */
    private fun verifySetup(): String? {
        val py = pythonPath ?: return "No Python path"
        val pyz = pyzPath ?: return "No .pyz path"

        return try {
            val process = ProcessBuilder(py, pyz, "--version")
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().readText().trim()
            val exitCode = process.waitFor()
            if (exitCode == 0 && output.isNotEmpty()) {
                Log.d(TAG, "Verify OK: $output")
                null // Success
            } else {
                "Python returned exit code $exitCode: $output"
            }
        } catch (e: Exception) {
            "Failed to run Python: ${e.message}"
        }
    }

    /**
     * Execute payload_toolkit.pyz with given arguments.
     *
     * @param args CLI arguments (e.g., ["info", "-i", "/path/to/payload.bin"])
     * @return [ExecResult] with stdout, stderr, exit code, and duration
     */
    fun executePyz(args: List<String>): ExecResult {
        val py = pythonPath
            ?: return ExecResult("", "Python not initialized", -1, 0)
        val pyz = pyzPath
            ?: return ExecResult("", ".pyz not found", -1, 0)

        val startTime = System.currentTimeMillis()
        return try {
            val command = mutableListOf(py, pyz)
            command.addAll(args)

            val process = ProcessBuilder(command)
                .redirectErrorStream(true)
                .start()

            val output = process.inputStream.bufferedReader().readText()
            val exitCode = process.waitFor()
            val duration = System.currentTimeMillis() - startTime

            ExecResult(output, null, exitCode, duration)
        } catch (e: Exception) {
            val duration = System.currentTimeMillis() - startTime
            ExecResult("", "Execution failed: ${e.message}", -1, duration)
        }
    }

    /**
     * Execute payload_toolkit.pyz with Termux environment variables set.
     * This ensures Termux's Python can find its stdlib and native libs.
     */
    fun executePyzWithTermuxEnv(args: List<String>): ExecResult {
        val py = pythonPath
            ?: return ExecResult("", "Python not initialized", -1, 0)
        val pyz = pyzPath
            ?: return ExecResult("", ".pyz not found", -1, 0)

        // Detect Termux Python and set appropriate environment
        val env = mutableMapOf<String, String>()
        if (py.contains("termux")) {
            env["TERMUX_PREFIX"] = "/data/data/com.termux/files/usr"
            env["LD_LIBRARY_PATH"] = "/data/data/com.termux/files/usr/lib"
            env["PATH"] = "/data/data/com.termux/files/usr/bin:${System.getenv("PATH")}"
            env["HOME"] = "/data/data/com.termux/files/home"
            env["TMPDIR"] = "/data/data/com.termux/files/usr/tmp"
        }

        val startTime = System.currentTimeMillis()
        return try {
            val command = mutableListOf(py, pyz)
            command.addAll(args)

            val pb = ProcessBuilder(command)
                .redirectErrorStream(true)

            // Merge environment variables
            val envBlock = pb.environment()
            for ((key, value) in env) {
                envBlock[key] = value
            }

            val process = pb.start()
            val output = process.inputStream.bufferedReader().readText()
            val exitCode = process.waitFor()
            val duration = System.currentTimeMillis() - startTime

            ExecResult(output, null, exitCode, duration)
        } catch (e: Exception) {
            val duration = System.currentTimeMillis() - startTime
            ExecResult("", "Execution failed: ${e.message}", -1, duration)
        }
    }

    fun isReady(): Boolean = initialized && pythonPath != null && pyzPath != null

    fun getPythonPath(): String? = pythonPath

    fun getPyzPath(): String? = pyzPath

    /**
     * Run dependency health check via .pyz --check-deps.
     * Returns a human-readable report string.
     */
    fun checkDependencies(): String {
        val py = pythonPath
            ?: return "ERROR: Python not initialized. Install Termux, then: pkg install python"
        val pyz = pyzPath
            ?: return "ERROR: .pyz not extracted from assets"

        return try {
            val process = ProcessBuilder(py, pyz, "--check-deps")
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().readText().trim()
            process.waitFor()
            output.ifEmpty { "Dependency check returned no output (exit code ${process.exitValue()})" }
        } catch (e: Exception) {
            "Failed to check dependencies: ${e.message}"
        }
    }

    /**
     * Check if Termux is installed on the device.
     */
    fun isTermuxInstalled(): Boolean {
        return PYTHON_PATHS.any { it.contains("termux") && File(it).exists() }
    }
}

/**
 * Result of executing a Python subprocess.
 */
data class ExecResult(
    val output: String,
    val error: String?,
    val exitCode: Int,
    val durationMs: Long
) {
    val success: Boolean get() = exitCode == 0 && error == null
}
