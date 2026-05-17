package com.hoshiyomi.payloadtoolkit

import com.chaquo.python.kotlin.PyObject
import com.chaquo.python.kotlin.Python
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.StringWriter

/**
 * PayloadResult — structured result from a Python payload_toolkit operation.
 *
 * @property success Whether the operation completed without errors.
 * @property output Combined stdout/stderr text from the Python execution.
 * @property error Optional error message if [success] is false.
 * @property exitCode Process exit code (0 = success, non-zero = failure).
 * @property durationMs Execution time in milliseconds.
 */
data class PayloadResult(
    val success: Boolean,
    val output: String,
    val error: String? = null,
    val exitCode: Int = 0,
    val durationMs: Long = 0
) {
    val hasError: Boolean get() = !success || !error.isNullOrBlank()

    companion object {
        fun error(message: String, durationMs: Long = 0) = PayloadResult(
            success = false,
            output = "",
            error = message,
            exitCode = -1,
            durationMs = durationMs
        )

        fun success(output: String, durationMs: Long = 0) = PayloadResult(
            success = true,
            output = output,
            error = null,
            exitCode = 0,
            durationMs = durationMs
        )
    }
}

/**
 * PayloadBridge — Kotlin singleton that bridges the Android UI to the Python payload_toolkit.
 *
 * This object provides type-safe methods for each payload_toolkit mode (info, dump, gen, zip, sign).
 * Each method:
 *   1. Validates input arguments
 *   2. Calls the corresponding Python function via [PythonBridge]
 *   3. Captures stdout output and returns a [PayloadResult]
 *
 * All methods are suspend functions and should be called from a coroutine scope (e.g., viewModelScope).
 * They execute on Dispatchers.IO to avoid blocking the main thread, since Chaquopy calls are blocking.
 */
object PayloadBridge {

    // Module path constants
    private const val PKG = "payload_toolkit"
    private const val MODES_PKG = "payload_toolkit.modes"

    // Supported modes (dd is excluded — requires root)
    val SUPPORTED_MODES = listOf("info", "dump", "gen", "zip", "sign")

    // Compression algorithm choices
    val COMPRESSION_ALGORITHMS = listOf("none", "bzip2", "gzip", "xz", "brotli")

    /**
     * Execute a generic payload_toolkit mode with arbitrary arguments.
     *
     * @param mode One of: "info", "dump", "gen", "zip", "sign"
     * @param kwargs Map of keyword arguments to pass to the Python function
     * @return [PayloadResult] with stdout output
     */
    suspend fun executeMode(mode: String, kwargs: Map<String, Any>): PayloadResult {
        if (mode !in SUPPORTED_MODES) {
            return PayloadResult.error("Unsupported mode: '$mode'. Supported: ${SUPPORTED_MODES.joinToString()}")
        }

        return withContext(Dispatchers.IO) {
            val startTime = System.currentTimeMillis()
            try {
                val output = PythonBridge.captureStdout { py ->
                    val module = py.getModule("$MODES_PKG.$mode")
                    val func = module.callAttr("run", kwargs.toPyDict(py))
                    // The function may return a status dict, but we primarily use stdout
                }
                val duration = System.currentTimeMillis() - startTime
                PayloadResult.success(output, duration)
            } catch (e: Exception) {
                val duration = System.currentTimeMillis() - startTime
                PayloadResult.error(
                    message = "Error in $mode mode: ${e.message}",
                    durationMs = duration
                )
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  Mode-specific convenience methods
    // ═══════════════════════════════════════════════════════════════

    /**
     * INFO mode — Parse and display payload.bin metadata.
     *
     * @param payloadPath Absolute path to payload.bin
     * @param verbose Whether to show detailed operation info
     * @return Formatted payload info as [PayloadResult.output]
     */
    suspend fun getInfo(payloadPath: String, verbose: Boolean = false): PayloadResult {
        return executeMode("info", mapOf(
            "payload_path" to payloadPath,
            "verbose" to verbose
        ))
    }

    /**
     * DUMP mode — Extract partition images from payload.bin.
     *
     * @param payloadPath Absolute path to payload.bin
     * @param outputDir Absolute path to output directory
     * @param partitions Optional list of partition names to extract (empty = all)
     * @return Extraction log with SHA-256 verification results
     */
    suspend fun dump(
        payloadPath: String,
        outputDir: String,
        partitions: List<String> = emptyList()
    ): PayloadResult {
        val kwargs = mutableMapOf<String, Any>(
            "payload_path" to payloadPath,
            "output_dir" to outputDir
        )
        if (partitions.isNotEmpty()) {
            kwargs["partitions"] = partitions
        }
        return executeMode("dump", kwargs)
    }

    /**
     * GEN mode — Generate a partial payload.bin from .img files.
     *
     * @param images Map of partition name → absolute path to .img file
     * @param compression Compression algorithm ("none", "bzip2", "gzip", "xz", "brotli")
     * @param outputPath Absolute path to output payload.bin
     * @return Generation log with sizes and verification
     */
    suspend fun gen(
        images: Map<String, String>,
        compression: String = "none",
        outputPath: String
    ): PayloadResult {
        if (images.isEmpty()) {
            return PayloadResult.error("No images specified for generation")
        }
        if (compression !in COMPRESSION_ALGORITHMS) {
            return PayloadResult.error("Invalid compression: '$compression'. Choices: $COMPRESSION_ALGORITHMS")
        }
        return executeMode("gen", mapOf(
            "images" to images,
            "compress" to compression,
            "output_path" to outputPath
        ))
    }

    /**
     * ZIP mode — Generate a flashable OTA ZIP from partition images.
     *
     * @param images Map of partition name → absolute path to .img file
     * @param device Device identifier (e.g., "S666LN,itel-S666LN")
     * @param fingerprint Build fingerprint string
     * @param compression Compression algorithm
     * @param outputPath Absolute path to output .zip file
     * @param certPath Optional path to OTA certificate
     * @return ZIP generation log with file offsets and metadata
     */
    suspend fun zip(
        images: Map<String, String>,
        device: String,
        fingerprint: String,
        compression: String = "bzip2",
        outputPath: String,
        certPath: String? = null
    ): PayloadResult {
        if (images.isEmpty()) {
            return PayloadResult.error("No images specified for OTA ZIP")
        }
        val kwargs = mutableMapOf<String, Any>(
            "images" to images,
            "device" to device,
            "fingerprint" to fingerprint,
            "compress" to compression,
            "output_path" to outputPath
        )
        certPath?.let { kwargs["cert_path"] = it }
        return executeMode("zip", kwargs)
    }

    /**
     * SIGN mode — Sign an existing payload.bin with RSA key.
     *
     * @param inputPath Absolute path to unsigned payload.bin
     * @param outputPath Absolute path to output signed payload.bin
     * @param keyPath Absolute path to RSA private key (PEM)
     * @param certPath Absolute path to public certificate (PEM)
     * @return Signing log with signature details
     */
    suspend fun sign(
        inputPath: String,
        outputPath: String,
        keyPath: String,
        certPath: String
    ): PayloadResult {
        return executeMode("sign", mapOf(
            "input_path" to inputPath,
            "output_path" to outputPath,
            "key_path" to keyPath,
            "cert_path" to certPath
        ))
    }

    // ═══════════════════════════════════════════════════════════════
    //  Utility methods
    // ═══════════════════════════════════════════════════════════════

    /**
     * Quick-check if a file is a valid payload.bin by reading the magic bytes.
     * Uses Python's read_payload for a full validation (throws on invalid).
     *
     * @param payloadPath Absolute path to the file
     * @return true if the file has the "CrAU" magic and can be parsed
     */
    suspend fun validatePayload(payloadPath: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                PythonBridge.callFunction(
                    module = "$PKG.payload",
                    function = "read_payload",
                    args = listOf(payloadPath)
                )
                true
            } catch (e: Exception) {
                false
            }
        }
    }

    /**
     * Get a list of partition names from a payload.bin without extracting.
     *
     * @param payloadPath Absolute path to payload.bin
     * @return List of partition name strings, or null on error
     */
    suspend fun getPartitionNames(payloadPath: String): List<String>? {
        return withContext(Dispatchers.IO) {
            try {
                val result = PythonBridge.callFunction(
                    module = "$PKG.modes.info",
                    function = "get_partition_names",
                    args = listOf(payloadPath)
                )
                // Convert Python list to Kotlin list
                result.asList().map { it.toString() }
            } catch (e: Exception) {
                null
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  Internal helpers
    // ═══════════════════════════════════════════════════════════════

    /**
     * Convert a Kotlin Map<String, Any> to a Python dict PyObject.
     */
    private fun Map<String, Any>.toPyDict(py: Python): PyObject {
        val builtins = py.getModule("builtins")
        val dict = builtins.callAttr("dict")
        for ((key, value) in this) {
            val pyValue = when (value) {
                is String -> py.getModule("builtins").callAttr("str", value)
                is Int -> py.getModule("builtins").callAttr("int", value)
                is Long -> py.getModule("builtins").callAttr("int", value)
                is Boolean -> py.getModule("builtins").callAttr("bool", value)
                is List<*> -> {
                    val pyList = builtins.callAttr("list")
                    for (item in value) {
                        pyList.callAttr("append", item?.toString() ?: "")
                    }
                    pyList
                }
                is Map<*, *> -> {
                    val innerDict = builtins.callAttr("dict")
                    @Suppress("UNCHECKED_CAST")
                    for ((k, v) in value as Map<String, Any>) {
                        innerDict.callAttr("__setitem__", k, v.toString())
                    }
                    innerDict
                }
                else -> py.getModule("builtins").callAttr("str", value.toString())
            }
            dict.callAttr("__setitem__", key, pyValue)
        }
        return dict
    }
}
