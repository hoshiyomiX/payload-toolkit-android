package com.hoshiyomi.payloadtoolkit

import android.util.Log
import java.io.FileInputStream
import java.io.InputStreamReader

/**
 * PyBridge — JNI wrapper for embedded Python execution.
 *
 * Uses dlopen() to load libpython3.13.so directly in-process,
 * then calls Py_Main() via dlsym().  This avoids execve() and
 * all Android linker namespace issues (no LD_PRELOAD, no
 * LD_LIBRARY_PATH, no CANNOT LINK warnings).
 *
 * Architecture:
 *   Java → JNI (pybridge.c) → dlopen(libpython3.13.so) → Py_Main()
 *
 * Output capture:
 *   Python runs in-process (not a subprocess), so its stdout/stderr
 *   go to the app's fd 1/2.  We redirect fd 1/2 → pipe via dup2(),
 *   and Java reads from the pipe's read end in a background thread.
 */
class PyBridge {

    companion object {
        private const val TAG = "PyBridge"

        @Volatile private var instance: PyBridge? = null
        private var loadError: String? = null

        /**
         * Get singleton instance.  Returns null if libpybridge.so is not
         * available (e.g., older APK without JNI bridge compiled).
         */
        fun getInstance(): PyBridge? {
            if (instance != null) return instance
            if (loadError != null) return null
            return try {
                val bridge = PyBridge()
                instance = bridge
                Log.d(TAG, "libpybridge.so loaded successfully (JNI mode)")
                bridge
            } catch (e: UnsatisfiedLinkError) {
                loadError = e.message
                Log.w(TAG, "libpybridge.so not available: ${e.message}")
                null
            } catch (e: Exception) {
                loadError = e.message
                Log.w(TAG, "PyBridge init failed: ${e.message}")
                null
            }
        }

        /** Check if JNI bridge is available. */
        fun isAvailable(): Boolean = getInstance() != null
    }

    private var lastReadFd: Int = -1

    private external fun nativeSetupOutput(): Int
    private external fun nativeRedirectOutput()
    private external fun nativeFlushOutput()
    private external fun nativeRestoreOutput()
    private external fun nativeCloseReadFd()
    private external fun nativeRunPython(
        libDir: String,
        pyzPath: String,
        stdlibDir: String,
        args: Array<String>
    ): Int

    init {
        System.loadLibrary("pybridge")
    }

    /**
     * Run Python with the given .pyz file and arguments.
     *
     * @param libDir    nativeLibraryDir (contains all .so files including libpython3.13.so)
     * @param pyzPath   absolute path to payload_toolkit.pyz
     * @param stdlibDir PYTHONHOME directory (extracted stdlib)
     * @param args      command-line arguments for the .pyz
     * @return PyResult with captured output, exit code, and duration
     */
    fun runPython(
        libDir: String,
        pyzPath: String,
        stdlibDir: String,
        args: List<String>
    ): PyResult {
        val startTime = System.currentTimeMillis()

        // Set up output capture pipe
        lastReadFd = nativeSetupOutput()
        if (lastReadFd < 0) {
            return PyResult(
                output = "",
                exitCode = -1,
                durationMs = 0,
                error = "Failed to create output pipe"
            )
        }

        // Start background thread to read Python's output
        val outputBuilder = StringBuilder()
        val readerThread = Thread({
            try {
                val reader = InputStreamReader(FileInputStream(lastReadFd), "UTF-8")
                val buffer = CharArray(8192)
                var charsRead: Int
                while (reader.read(buffer).also { charsRead = it } != -1) {
                    synchronized(outputBuilder) {
                        outputBuilder.append(buffer, 0, charsRead)
                    }
                }
                reader.close()
            } catch (_: Exception) {
                // Pipe closed — expected when Python finishes
            }
        }, "pybridge-output-reader")
        readerThread.isDaemon = true
        readerThread.start()

        // Redirect stdout/stderr to pipe, then call Python
        nativeRedirectOutput()

        val exitCode = try {
            nativeRunPython(libDir, pyzPath, stdlibDir, args.toTypedArray())
        } catch (e: Exception) {
            Log.e(TAG, "nativeRunPython threw: ${e.message}")
            -1
        }

        // Flush any buffered Python output before restoring
        nativeFlushOutput()
        nativeRestoreOutput()

        // Wait for reader thread to finish (with timeout)
        try {
            readerThread.join(5000)
        } catch (_: InterruptedException) {}

        val output = synchronized(outputBuilder) { outputBuilder.toString().trim() }
        val duration = System.currentTimeMillis() - startTime

        // Close read fd
        try { nativeCloseReadFd() } catch (_: Exception) {}

        return PyResult(
            output = output,
            exitCode = exitCode,
            durationMs = duration
        )
    }

    data class PyResult(
        val output: String,
        val exitCode: Int,
        val durationMs: Long,
        val error: String? = null
    ) {
        val success: Boolean get() = exitCode == 0 && error == null
    }
}
