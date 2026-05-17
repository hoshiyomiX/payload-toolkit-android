package com.hoshiyomi.payloadtoolkit

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * PayloadResult — structured result from a payload_toolkit operation.
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
 * PayloadBridge — Kotlin singleton that bridges the Android UI to payload_toolkit.pyz.
 *
 * Each method constructs CLI arguments for the .pyz and executes it via [PythonBridge].
 * The .pyz is run as a subprocess using the device's Python (Termux or system).
 *
 * All methods are suspend functions and should be called from a coroutine scope.
 */
object PayloadBridge {

    // Supported modes (dd is excluded — requires root)
    val SUPPORTED_MODES = listOf("info", "dump", "gen", "zip", "sign")

    // Compression algorithm choices
    val COMPRESSION_ALGORITHMS = listOf("none", "bzip2", "gzip", "xz", "brotli")

    /**
     * Execute payload_toolkit.pyz with the given CLI arguments.
     *
     * @param args List of CLI arguments (mode + options)
     * @return [PayloadResult] with stdout output
     */
    private suspend fun executePyz(args: List<String>): PayloadResult {
        return withContext(Dispatchers.IO) {
            // Use Termux env if Python is from Termux
            val execResult = if (PythonBridge.isTermuxInstalled()) {
                PythonBridge.executePyzWithTermuxEnv(args)
            } else {
                PythonBridge.executePyz(args)
            }

            if (execResult.success) {
                PayloadResult.success(execResult.output, execResult.durationMs)
            } else {
                val errorMsg = execResult.error ?: "Exit code ${execResult.exitCode}"
                PayloadResult.error(
                    message = errorMsg,
                    durationMs = execResult.durationMs
                ).copy(output = execResult.output)
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
        val args = mutableListOf("info", "-i", payloadPath)
        if (verbose) args.add("-v")
        return executePyz(args)
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
        val args = mutableListOf("dump", "-i", payloadPath, "-o", outputDir)
        if (partitions.isNotEmpty()) {
            args.add("-p")
            args.add(partitions.joinToString(","))
        }
        return executePyz(args)
    }

    /**
     * GEN mode — Generate a partial payload.bin from .img files.
     *
     * @param imagesDir Directory containing .img files (scanned by .pyz)
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

        // .pyz gen mode expects -i <images_dir>, not a map
        // We need to pass a directory containing the .img files
        // The images map has (name, path) — we use the parent directory of the first image
        val firstPath = images.values.first()
        val imagesDir = File(firstPath).parentFile?.absolutePath
            ?: return PayloadResult.error("Cannot determine images directory")

        val args = mutableListOf("gen", "-i", imagesDir, "-o", outputPath)
        if (compression != "none") {
            args.add("-c")
            args.add(compression)
        }
        return executePyz(args)
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

        // .pyz zip mode expects -i <images_dir>
        val firstPath = images.values.first()
        val imagesDir = File(firstPath).parentFile?.absolutePath
            ?: return PayloadResult.error("Cannot determine images directory")

        val args = mutableListOf("zip", "-i", imagesDir, "-o", outputPath)
        if (compression != "none") {
            args.add("-c")
            args.add(compression)
        }
        args.add("-n")
        args.add(device)
        return executePyz(args)
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
        // .pyz sign mode uses -k for key, but our API separates key and cert
        // The .pyz only takes one -k arg, so we pass the key path
        val args = mutableListOf("sign", "-i", inputPath, "-k", keyPath, "-o", outputPath)
        return executePyz(args)
    }

    // ═══════════════════════════════════════════════════════════════
    //  Utility methods
    // ═══════════════════════════════════════════════════════════════

    /**
     * Quick-check if a file is a valid payload.bin by running info mode.
     *
     * @param payloadPath Absolute path to the file
     * @return true if the .pyz can parse it
     */
    suspend fun validatePayload(payloadPath: String): Boolean {
        return try {
            val result = getInfo(payloadPath)
            result.success
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Get the .pyz version string.
     */
    suspend fun getPyzVersion(): String? {
        return withContext(Dispatchers.IO) {
            try {
                val result = PythonBridge.executePyz(listOf("--version"))
                if (result.success) result.output.trim() else null
            } catch (e: Exception) {
                null
            }
        }
    }
}
