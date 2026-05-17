package com.hoshiyomi.payloadtoolkit

import android.content.Context
import android.util.Log
import com.chaquo.python.kotlin.PyException
import com.chaquo.python.kotlin.PyObject
import com.chaquo.python.kotlin.Python
import java.io.File

/**
 * PythonBridge — Chaquopy initialization + .pyz loader.
 *
 * Architecture:
 *   1. Chaquopy provides CPython 3.11 runtime (bundled in APK)
 *   2. payload_toolkit.pyz is embedded as an Android asset
 *   3. On first init, .pyz is extracted to app internal storage
 *   4. _bootstrap.py adds .pyz to sys.path → import payload_toolkit works
 *
 * Thread Safety:
 *   Chaquopy's Python.start() can only be called once per process.
 *   All Python operations hold the GIL.
 */
object PythonBridge {

    private const val TAG = "PythonBridge"
    private const val PYZ_ASSET_NAME = "payload_toolkit.pyz"
    private const val PYZ_DIR_NAME = "python"

    private var pythonInstance: Python? = null
    @Volatile private var initialized = false
    private val initLock = Any()

    /**
     * Initialize Chaquopy Python runtime and load payload_toolkit from .pyz.
     *
     * @param context Android context (for asset extraction)
     * @return true if Python + payload_toolkit loaded successfully
     */
    fun ensureInitialized(context: Context? = null): Boolean {
        if (initialized) return true

        synchronized(initLock) {
            if (initialized) return true

            try {
                // Step 1: Start Chaquopy Python
                pythonInstance = Python.getInstance()

                // Step 2: Extract .pyz from assets and load into sys.path
                val ctx = context
                    ?: throw IllegalStateException("Context required for first initialization")
                initPyz(ctx)

                initialized = true
                Log.i(TAG, "Python initialized with payload_toolkit.pyz")
                return true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize Python", e)
                initialized = false
                return false
            }
        }
    }

    /**
     * Extract payload_toolkit.pyz from assets and add to sys.path.
     *
     * The .pyz is extracted to: {context.filesDir}/python/payload_toolkit.pyz
     * Then _bootstrap.setup() adds it to Python's sys.path.
     */
    private fun initPyz(context: Context) {
        val py = pythonInstance ?: return
        val pythonDir = File(context.filesDir, PYZ_DIR_NAME).also { it.mkdirs() }
        val pyzFile = File(pythonDir, PYZ_ASSET_NAME)

        // Extract .pyz from assets if not already present (or if asset is newer)
        val needsCopy = !pyzFile.exists() || assetIsNewer(context, pyzFile)
        if (needsCopy) {
            try {
                context.assets.open(PYZ_ASSET_NAME).use { input ->
                    pyzFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                Log.d(TAG, "Extracted $PYZ_ASSET_NAME to ${pyzFile.absolutePath}")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to extract $PYZ_ASSET_NAME from assets", e)
                throw e
            }
        }

        // Call _bootstrap.setup(pyz_path) to add .pyz to sys.path
        val bootstrapResult = py.getModule("_bootstrap").callAttr("setup", pyzFile.absolutePath)
        val success = bootstrapResult.callAttr("get", "success")?.toBoolean() ?: false

        if (success) {
            val version = bootstrapResult.callAttr("get", "version")?.toString() ?: "unknown"
            Log.d(TAG, "payload_toolkit v$version loaded from .pyz")
        } else {
            val error = bootstrapResult.callAttr("get", "error")?.toString() ?: "unknown error"
            throw RuntimeException("Failed to load payload_toolkit: $error")
        }

        // Increase recursion limit for deep protobuf parsing
        val sys = py.getModule("sys")
        val currentLimit = sys.callAttr("getrecursionlimit").toInt()
        if (currentLimit < 5000) {
            sys.callAttr("setrecursionlimit", 5000)
        }
    }

    /**
     * Check if the asset version is newer than the extracted file.
     */
    private fun assetIsNewer(context: Context, extractedFile: File): Boolean {
        return try {
            val assetModified = context.assets.open(PYZ_ASSET_NAME).use { it.available().toLong() }
            // Simple heuristic: if sizes differ, re-extract
            assetModified != extractedFile.length()
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Call a Python function and return its result.
     */
    fun callFunction(module: String, function: String, args: List<Any>? = null): PyObject {
        val py = pythonInstance
            ?: throw IllegalStateException("Python not initialized. Call ensureInitialized(context) first.")

        try {
            val mod = py.getModule(module)
            return mod.callAttr(function, *(args?.toTypedArray() ?: emptyArray()))
        } catch (e: PyException) {
            val traceback = extractTraceback(e)
            Log.e(TAG, "Python error in $module.$function: $traceback")
            throw PythonException(module, function, e, traceback)
        }
    }

    /**
     * Execute Python code with stdout captured.
     */
    fun captureStdout(block: (Python) -> Unit): String {
        val py = pythonInstance
            ?: throw IllegalStateException("Python not initialized. Call ensureInitialized(context) first.")

        val sys = py.getModule("sys")
        val io = py.getModule("io")

        val originalStdout = sys.getAttr("stdout")
        val originalStderr = sys.getAttr("stderr")
        val buffer = io.callAttr("StringIO")

        return try {
            sys.callAttr("setattr", "stdout", buffer)
            sys.callAttr("setattr", "stderr", buffer)
            block(py)
            buffer.callAttr("flush")
            buffer.callAttr("getvalue").toString()
        } catch (e: PyException) {
            buffer.callAttr("flush")
            val partialOutput = buffer.callAttr("getvalue").toString()
            val traceback = extractTraceback(e)
            Log.e(TAG, "Python error:\n$partialOutput\n\n$traceback")
            throw PythonException("unknown", "unknown", e, traceback)
        } finally {
            try {
                sys.callAttr("setattr", "stdout", originalStdout)
                sys.callAttr("setattr", "stderr", originalStderr)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to restore stdout", e)
            }
        }
    }

    fun isReady(): Boolean = initialized && pythonInstance != null

    fun getPythonVersion(): String? {
        return try {
            val py = pythonInstance ?: return null
            val sys = py.getModule("sys")
            sys.callAttr("version").callAttr("split").asList().firstOrNull()?.toString()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get Python version", e)
            null
        }
    }

    fun getPayloadToolkitVersion(): String? {
        return try {
            val py = pythonInstance ?: return null
            val mod = py.getModule("payload_toolkit")
            mod.getAttr("__version__").toString()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get payload_toolkit version", e)
            null
        }
    }

    private fun extractTraceback(e: PyException): String {
        return try {
            val py = pythonInstance ?: return e.message ?: "Unknown Python error"
            val tracebackModule = py.getModule("traceback")
            val formatted = tracebackModule.callAttr("format_exception", e.type, e.value, e.traceback)
            formatted.asList().joinToString("")
        } catch (e: Exception) {
            e.message ?: "Unknown Python error"
        }
    }
}

class PythonException(
    val module: String,
    val function: String,
    val cause: PyException,
    val traceback: String
) : Exception("Python error in $module.$function: ${cause.message}\n\n$traceback", cause)
