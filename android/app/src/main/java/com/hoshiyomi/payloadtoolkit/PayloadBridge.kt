package com.hoshiyomi.payloadtoolkit

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * PayloadResult — structured result from a payload_toolkit operation.
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
            success = false, output = "", error = message, exitCode = -1, durationMs = durationMs
        )
        fun success(output: String, durationMs: Long = 0) = PayloadResult(
            success = true, output = output, error = null, exitCode = 0, durationMs = durationMs
        )
    }
}

/**
 * PayloadBridge — Kotlin singleton that bridges the Android UI to payload_toolkit.pyz.
 *
 * Primary use case: Repack partition images (.img) into a flashable OTA ZIP.
 *
 * The .pyz is run as a subprocess using the device's Python (Termux or system).
 */
object PayloadBridge {

    // Compression algorithm choices
    val COMPRESSION_ALGORITHMS = listOf("gzip", "bzip2", "xz", "brotli", "none")

    /**
     * Execute payload_toolkit.pyz with the given CLI arguments.
     * PythonBridge.executePyz auto-configures the environment based on
     * whether Python is bundled or system (Termux).
     */
    private suspend fun executePyz(args: List<String>): PayloadResult {
        return withContext(Dispatchers.IO) {
            val execResult = PythonBridge.executePyz(args)

            if (execResult.success) {
                PayloadResult.success(execResult.output, execResult.durationMs)
            } else {
                PayloadResult.error(
                    message = execResult.error ?: "Exit code ${execResult.exitCode}",
                    durationMs = execResult.durationMs
                ).copy(output = execResult.output)
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  Core operations
    // ═══════════════════════════════════════════════════════════════

    /**
     * INFO mode — Parse and display payload.bin metadata.
     */
    suspend fun getInfo(payloadPath: String, verbose: Boolean = false): PayloadResult {
        val args = mutableListOf("info", "-i", payloadPath)
        if (verbose) args.add("-v")
        return executePyz(args)
    }

    /**
     * DUMP mode — Extract partition images from payload.bin.
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
     */
    suspend fun gen(
        images: Map<String, String>,
        compression: String = "none",
        outputPath: String
    ): PayloadResult {
        if (images.isEmpty()) return PayloadResult.error("No images specified for generation")
        if (compression !in COMPRESSION_ALGORITHMS)
            return PayloadResult.error("Invalid compression: '$compression'")

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
     * Device and fingerprint use sensible defaults if not provided.
     * Output filename follows: flashable_<partitions>_<compress>.zip
     *
     * @param images Map of partition name -> absolute path to .img file
     * @param device Device identifier (default: generic AOSP device)
     * @param fingerprint Build fingerprint (default: generic AOSP fingerprint)
     * @param compression Compression algorithm (default: "gzip")
     * @param outputPath Absolute path to output .zip file
     * @param certPath Optional path to OTA certificate
     */
    suspend fun zip(
        images: Map<String, String>,
        device: String = "aosp_crosshatch,Generic AOSP",
        fingerprint: String = "AOSP/crosshatch/crosshatch:14/AP2A.240805.005/11572411:user/release-keys",
        compression: String = "gzip",
        outputPath: String,
        certPath: String? = null
    ): PayloadResult {
        if (images.isEmpty()) return PayloadResult.error("No images specified for OTA ZIP")

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
        args.add("-f")
        args.add(fingerprint)
        return executePyz(args)
    }

    /**
     * Build a smart output filename based on selected partitions and compression.
     *
     * Examples:
     *   - flashable_dd_odm_dlkm_gzip.zip
     *   - flashable_boot_vendor_bzip2.zip
     */
    fun buildOutputFileName(images: Map<String, String>, compression: String, version: Int = 16): String {
        val partitionNames = images.keys.sorted().joinToString("_")
        val compressSuffix = if (compression == "none") "raw" else compression
        return "flashable_${partitionNames}_v${version}_${compressSuffix}.zip"
    }

    /**
     * SIGN mode — Sign an existing payload.bin with RSA key.
     */
    suspend fun sign(
        inputPath: String,
        outputPath: String,
        keyPath: String,
        certPath: String
    ): PayloadResult {
        val args = mutableListOf("sign", "-i", inputPath, "-k", keyPath, "-o", outputPath)
        return executePyz(args)
    }

    // ═══════════════════════════════════════════════════════════════
    //  Utility methods
    // ═══════════════════════════════════════════════════════════════

    suspend fun validatePayload(payloadPath: String): Boolean {
        return try { getInfo(payloadPath).success } catch (_: Exception) { false }
    }

    suspend fun getPyzVersion(): String? {
        return withContext(Dispatchers.IO) {
            try {
                val result = PythonBridge.executePyz(listOf("--version"))
                if (result.success) result.output.trim() else null
            } catch (_: Exception) { null }
        }
    }
}
