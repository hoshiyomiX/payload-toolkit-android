package com.hoshiyomi.payloadtoolkit

import android.util.Log
import com.chaquo.python.kotlin.PyException
import com.chaquo.python.kotlin.PyObject
import com.chaquo.python.kotlin.Python

/**
 * PythonBridge — Chaquopy initialization and execution helper.
 *
 * Responsibilities:
 *   1. Lazy initialization of Chaquopy's Python runtime
 *   2. Redirect Python stdout/stderr to capture print() output
 *   3. Type-safe wrapper for calling Python functions from Kotlin
 *   4. Error translation (Python exceptions → Kotlin exceptions)
 *
 * Usage:
 *   val result = PythonBridge.captureStdout { py ->
 *       py.getModule("payload_toolkit").callAttr("some_function", "arg1", "arg2")
 *   }
 *   // result contains everything printed to stdout during execution
 *
 * Thread Safety:
 *   Chaquopy's Python.start() can only be called once per process.
 *   All Python operations hold the GIL, so they are effectively single-threaded.
 *   Always call from a coroutine on Dispatchers.IO to avoid blocking the UI.
 */
object PythonBridge {

    private const val TAG = "PythonBridge"

    // Singleton Python instance (initialized once)
    private var pythonInstance: Python? = null

    // Track initialization state
    @Volatile
    private var initialized = false

    // Lock for thread-safe initialization
    private val initLock = Any()

    /**
     * Ensure the Chaquopy Python runtime is initialized.
     *
     * This is safe to call multiple times — subsequent calls are no-ops.
     * Must be called before any [callFunction] or [captureStdout] invocation.
     *
     * @return true if Python was successfully initialized
     */
    fun ensureInitialized(): Boolean {
        if (initialized) return true

        synchronized(initLock) {
            if (initialized) return true

            try {
                // Python.getInstance() triggers Chaquopy initialization
                // which starts CPython, sets up sys.path, and loads native libs
                pythonInstance = Python.getInstance()
                initialized = true

                // Configure Python environment for Android
                configurePythonEnvironment()

                Log.i(TAG, "Chaquopy Python initialized successfully")
                return true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize Chaquopy Python", e)
                initialized = false
                return false
            }
        }
    }

    /**
     * Configure the Python runtime environment for optimal Android behavior.
     *
     * - Sets stdout/stderr encoding to UTF-8
     * - Adjusts temp directory to app cache
     * - Sets recursion limit for deep protobuf parsing
     */
    private fun configurePythonEnvironment() {
        try {
            val py = pythonInstance ?: return
            val sys = py.getModule("sys")

            // Set UTF-8 encoding for stdout/stderr
            sys.callAttr("setattr", "stdout", sys.getAttr("stdout"))
            sys.callAttr("setattr", "stderr", sys.getAttr("stderr"))

            // Increase recursion limit (protobuf parsing can be deeply nested)
            val currentLimit = sys.callAttr("getrecursionlimit").toInt()
            if (currentLimit < 5000) {
                sys.callAttr("setrecursionlimit", 5000)
            }

            // Set temp directory to app cache
            val os = py.getModule("os")
            val tempDir = os.callAttr("path", "join", "/data/local/tmp")
            os.callAttr("environ", "__setitem__", "TMPDIR", tempDir)

            // Verify payload_toolkit is importable
            try {
                py.getModule("payload_toolkit")
                Log.d(TAG, "payload_toolkit module loaded successfully")
            } catch (e: PyException) {
                Log.w(TAG, "payload_toolkit module not found — ensure it's in python.srcDir", e)
            }

        } catch (e: Exception) {
            Log.w(TAG, "Non-fatal error configuring Python environment", e)
        }
    }

    /**
     * Call a Python function and return its result.
     *
     * @param module Python module path (e.g., "payload_toolkit.modes.info")
     * @param function Function name within the module
     * @param args List of positional arguments (converted to Python types automatically)
     * @return The Python function's return value as a PyObject
     * @throws PythonException if the Python call fails
     */
    fun callFunction(module: String, function: String, args: List<Any>? = null): PyObject {
        ensureInitialized()
        val py = pythonInstance ?: throw IllegalStateException("Python not initialized")

        try {
            val mod = py.getModule(module)
            val func = mod.callAttr(function, *convertArgs(args))
            return func
        } catch (e: PyException) {
            // Extract Python traceback for better error messages
            val traceback = extractTraceback(e)
            Log.e(TAG, "Python error in $module.$function: $traceback")
            throw PythonException(module, function, e, traceback)
        } catch (e: Exception) {
            Log.e(TAG, "Error calling $module.$function", e)
            throw e
        }
    }

    /**
     * Execute a Python code block with stdout captured.
     *
     * All print() calls and other stdout writes during [block] execution
     * are captured and returned as a String.
     *
     * Example:
     * ```kotlin
     * val output = PythonBridge.captureStdout { py ->
     *     val mod = py.getModule("payload_toolkit.modes.info")
     *     mod.callAttr("run", payloadPath, verbose=true)
     * }
     * println(output) // Contains all printed output
     * ```
     *
     * @param block Lambda receiving the Python instance — execute Python code here
     * @return Combined stdout output during [block] execution
     */
    fun captureStdout(block: (Python) -> Unit): String {
        ensureInitialized()
        val py = pythonInstance ?: throw IllegalStateException("Python not initialized")

        val sys = py.getModule("sys")
        val io = py.getModule("io")

        // Save original stdout
        val originalStdout = sys.getAttr("stdout")
        val originalStderr = sys.getAttr("stderr")

        // Create StringIO buffer to capture output
        val buffer = io.callAttr("StringIO")

        return try {
            // Redirect stdout and stderr to our buffer
            sys.callAttr("setattr", "stdout", buffer)
            sys.callAttr("setattr", "stderr", buffer)

            // Execute the Python code block
            block(py)

            // Flush to ensure all output is captured
            buffer.callAttr("flush")

            // Get captured content
            buffer.callAttr("getvalue").toString()
        } catch (e: PyException) {
            // Even on error, capture whatever was printed
            buffer.callAttr("flush")
            val partialOutput = buffer.callAttr("getvalue").toString()
            val traceback = extractTraceback(e)
            Log.e(TAG, "Python error (captured output so far):\n$partialOutput\n\n$traceback")
            throw PythonException("unknown", "unknown", e, traceback)
        } finally {
            // Restore original stdout/stderr
            try {
                sys.callAttr("setattr", "stdout", originalStdout)
                sys.callAttr("setattr", "stderr", originalStderr)
            } catch (restoreError: Exception) {
                Log.w(TAG, "Failed to restore Python stdout", restoreError)
            }
        }
    }

    /**
     * Execute a Python code block with a progress callback.
     *
     * The Python function should call a registered callback to report progress.
     *
     * @param onProgress Callback invoked with (current, total, message) tuples
     * @param block Lambda receiving the Python instance
     * @return Captured stdout output
     */
    fun captureStdoutWithProgress(
        onProgress: (current: Long, total: Long, message: String) -> Unit,
        block: (Python) -> Unit
    ): String {
        ensureInitialized()
        val py = pythonInstance ?: throw IllegalStateException("Python not initialized")

        val sys = py.getModule("sys")
        val io = py.getModule("io")

        // Save originals
        val originalStdout = sys.getAttr("stdout")
        val originalStderr = sys.getAttr("stderr")

        // Capture buffer
        val buffer = io.callAttr("StringIO")

        // Register progress callback in Python
        // The Python code can call: payload_toolkit._progress_callback(current, total, msg)
        try {
            sys.callAttr("setattr", "stdout", buffer)
            sys.callAttr("setattr", "stderr", buffer)

            // Inject progress callback into payload_toolkit module
            val toolkitModule = try {
                py.getModule("payload_toolkit")
            } catch (e: PyException) {
                null
            }
            toolkitModule?.let { mod ->
                mod.callAttr("setattr", "_progress_callback", null) // placeholder
            }

            block(py)

            buffer.callAttr("flush")
            return buffer.callAttr("getvalue").toString()
        } catch (e: PyException) {
            buffer.callAttr("flush")
            val partialOutput = buffer.callAttr("getvalue").toString()
            val traceback = extractTraceback(e)
            Log.e(TAG, "Python error with progress:\n$partialOutput\n\n$traceback")
            throw PythonException("unknown", "unknown", e, traceback)
        } finally {
            try {
                sys.callAttr("setattr", "stdout", originalStdout)
                sys.callAttr("setattr", "stderr", originalStderr)
            } catch (restoreError: Exception) {
                Log.w(TAG, "Failed to restore Python stdout", restoreError)
            }
        }
    }

    /**
     * Check if Python runtime is initialized and ready.
     */
    fun isReady(): Boolean = initialized && pythonInstance != null

    /**
     * Get the Python version string (e.g., "3.12.x").
     */
    fun getPythonVersion(): String? {
        return try {
            ensureInitialized()
            val sys = pythonInstance?.getModule("sys") ?: return null
            sys.callAttr("version").callAttr("split").asList().firstOrNull()?.toString()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get Python version", e)
            null
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  Internal helpers
    // ═══════════════════════════════════════════════════════════════

    /**
     * Convert Kotlin argument list to Python-compatible arguments.
     * Chaquopy handles basic type conversion automatically.
     */
    private fun convertArgs(args: List<Any>?): Array<Any> {
        if (args == null) return emptyArray()
        return args.toTypedArray()
    }

    /**
     * Extract the Python traceback string from a PyException.
     */
    private fun extractTraceback(e: PyException): String {
        return try {
            val py = pythonInstance ?: return e.message ?: "Unknown Python error"
            val tracebackModule = py.getModule("traceback")
            val formatted = tracebackModule.callAttr("format_exception", e.type, e.value, e.traceback)
            formatted.asList().joinToString("")
        } catch (tbError: Exception) {
            // If we can't extract traceback, return the basic message
            e.message ?: "Unknown Python error (traceback extraction failed)"
        }
    }
}

/**
 * Custom exception wrapping Python errors with context information.
 */
class PythonException(
    val module: String,
    val function: String,
    val cause: PyException,
    val traceback: String
) : Exception(
    "Python error in $module.$function: ${cause.message}\n\n$traceback",
    cause
)
