package com.hoshiyomi.payloadtoolkit

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * MainActivity — Payload Toolkit Android.
 *
 * Single-purpose: Repack partition images (.img) into a flashable OTA ZIP.
 *
 * Flow:
 *   1. Select partition images (dd.img, odm.img, dlkm.img, etc.)
 *   2. Choose compression algorithm
 *   3. Select output directory
 *   4. Tap "Repack" to generate flashable OTA ZIP
 *
 * Python runtime: external Python (Termux recommended).
 * payload_toolkit.pyz is bundled as an asset and extracted at first launch.
 */
class MainActivity : AppCompatActivity() {

    // ═══════════════════════════════════════════════════════════════
    //  State
    // ═══════════════════════════════════════════════════════════════

    private var selectedCompression: String = "gzip"
    private var imageFiles: MutableList<Pair<String, String>> = mutableListOf() // (name, path)
    private var isExecuting = false

    // App-internal directories
    private lateinit var inputDir: File
    private lateinit var outputDir: File

    // ═══════════════════════════════════════════════════════════════
    //  Activity Result Launchers
    // ═══════════════════════════════════════════════════════════════

    private val outputDirChooser = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        uri?.let { handleOutputDirSelected(it) }
    }

    private val imageFileChooser = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri>? ->
        uris?.let { handleImageFilesSelected(it) }
    }

    private val removeImageConfirm = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { /* Not used — placeholder for future file save */ }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.entries.all { it.value }
        if (!allGranted) {
            showLog("WARNING: Some permissions were denied. File access may be limited.\n")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                promptManageStorage()
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  Lifecycle
    // ═══════════════════════════════════════════════════════════════

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        inputDir = File(filesDir, "input").also { it.mkdirs() }
        outputDir = File(filesDir, "output").also { it.mkdirs() }

        initializePython()
        setupCompressionSelector()
        setupButtons()
        setupToolbar()

        requestStoragePermissions()
        handleIncomingIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncomingIntent(intent)
    }

    // ═══════════════════════════════════════════════════════════════
    //  Initialization
    // ═══════════════════════════════════════════════════════════════

    private fun initializePython() {
        lifecycleScope.launch {
            showLog("Initializing Payload Toolkit...\n")
            withContext(Dispatchers.IO) {
                val result = PythonBridge.ensureInitialized(this@MainActivity)
                withContext(Dispatchers.Main) {
                    if (result.success) {
                        val pyVer = result.pythonPath?.let {
                            try {
                                val pb = ProcessBuilder(it, "--version")
                                    .redirectErrorStream(true)
                                    .start()
                                val raw = pb.inputStream.bufferedReader().readText().trim()
                                pb.waitFor()
                                if (result.isBundled) raw.lineSequence()
                                    .filterNot { it.contains("CANNOT LINK EXECUTABLE") }
                                    .joinToString("\n") else raw
                            } catch (_: Exception) { "unknown" }
                        }
                        val source = if (result.isBundled) "bundled" else "system"
                        showLog("Python runtime: $pyVer ($source)\n")

                        lifecycleScope.launch {
                            val ptVer = withContext(Dispatchers.IO) {
                                PayloadBridge.getPyzVersion()
                            }
                            if (ptVer != null) showLog("payload_toolkit $ptVer loaded\n")

                            // Run dependency health check
                            val depReport = withContext(Dispatchers.IO) {
                                PythonBridge.checkDependencies()
                            }
                            showLog(depReport + "\n")
                        }

                        showLog("\u2550".repeat(50) + "\n\n")
                    } else {
                        showLog("WARNING: ${result.error}\n\n")
                        // Show detailed diagnostics so the user can report them
                        if (result.diagnostics.isNotBlank()) {
                            showLog("[Diagnostics]\n")
                            showLog(result.diagnostics)
                            showLog("\u2550".repeat(50) + "\n\n")
                        }
                        showLog("Possible causes:\n")
                        showLog("  - APK installed from an old build (before v3.0)\n")
                        showLog("  - App installed but native libs extraction failed\n")
                        showLog("  - Try: Uninstall -> Re-download latest APK -> Install\n")
                        showLog("\u2550".repeat(50) + "\n\n")
                    }
                }
            }
        }
    }

    private fun setupToolbar() {
        val toolbar = findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.title = getString(R.string.app_name)
        supportActionBar?.subtitle = "v${BuildConfig.VERSION_NAME}"
    }

    private fun setupCompressionSelector() {
        val spinner = findViewById<android.widget.Spinner>(R.id.spinnerCompression)
        val adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            PayloadBridge.COMPRESSION_ALGORITHMS
        ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        spinner.adapter = adapter

        spinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                selectedCompression = PayloadBridge.COMPRESSION_ALGORITHMS[position]
                updateOutputPreview()
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }
    }

    private fun setupButtons() {
        findViewById<View>(R.id.buttonAddImages).setOnClickListener {
            imageFileChooser.launch(arrayOf("application/octet-stream", "image/*"))
        }

        findViewById<View>(R.id.buttonBrowseOutput).setOnClickListener {
            outputDirChooser.launch(null)
        }

        findViewById<View>(R.id.buttonRemoveAll)?.setOnClickListener {
            imageFiles.clear()
            copyPendingRemovals()
            updateImageListUI()
            updateOutputPreview()
            showLog("All images removed.\n")
        }

        findViewById<View>(R.id.buttonExecute).setOnClickListener {
            onRepackClicked()
        }

        findViewById<View>(R.id.buttonCopyLog).setOnClickListener {
            copyLogToClipboard()
        }

        findViewById<View>(R.id.buttonClearLog).setOnClickListener {
            findViewById<android.widget.TextView>(R.id.textViewLog).text = ""
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  Permission handling
    // ═══════════════════════════════════════════════════════════════

    private fun requestStoragePermissions() {
        val permissionsToRequest = mutableListOf<String>()

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED
            ) permissionsToRequest.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED
            ) permissionsToRequest.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        if (permissionsToRequest.isNotEmpty()) {
            permissionLauncher.launch(permissionsToRequest.toTypedArray())
        }
    }

    private fun promptManageStorage() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                MaterialAlertDialogBuilder(this)
                    .setTitle("Storage Permission Required")
                    .setMessage(
                        "Payload Toolkit needs access to all files to read/write " +
                        "partition images and generate OTA ZIPs.\n\n" +
                        "Please grant 'All files access' in the next screen."
                    )
                    .setPositiveButton("Grant Access") { _, _ ->
                        val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                            data = Uri.parse("package:$packageName")
                        }
                        startActivity(intent)
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  File handling
    // ═══════════════════════════════════════════════════════════════

    private fun handleOutputDirSelected(uri: Uri) {
        outputDirPath = outputDir.absolutePath
        runOnUiThread {
            findViewById<android.widget.EditText>(R.id.editTextOutput)
                .setText(outputDirPath)
            showLog("Output directory: ${outputDir.absolutePath}\n")
        }
    }

    private var outputDirPath: String? = null

    private fun handleImageFilesSelected(uris: List<Uri>) {
        lifecycleScope.launch {
            for (uri in uris) {
                val fileName = getFileName(uri) ?: "image.img"
                // Partition name = filename without .img extension
                val partitionName = if (fileName.lowercase().endsWith(".img"))
                    fileName.removeSuffix(".img")
                else fileName.removeSuffix(".IMG")

                val destFile = File(inputDir, fileName)

                // Skip if already added
                if (imageFiles.any { it.first == partitionName }) {
                    showLog("SKIP: $partitionName already added\n")
                    continue
                }

                copyUriToFile(uri, destFile)
                imageFiles.add(partitionName to destFile.absolutePath)
            }

            runOnUiThread {
                updateImageListUI()
                updateOutputPreview()
            }
        }
    }

    private fun handleIncomingIntent(intent: Intent) {
        // Accept .img files shared/opened from another app
        when (intent.action) {
            Intent.ACTION_VIEW -> {
                intent.data?.let { uri ->
                    handleImageFilesSelected(listOf(uri))
                }
            }
            Intent.ACTION_SEND -> {
                (intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))?.let { uri ->
                    handleImageFilesSelected(listOf(uri))
                }
            }
        }
    }

    private suspend fun copyUriToFile(uri: Uri, destFile: File) {
        withContext(Dispatchers.IO) {
            contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(destFile).use { output ->
                    input.copyTo(output)
                }
            }
        }
    }

    private fun getFileName(uri: Uri): String? {
        var fileName: String? = null
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (cursor.moveToFirst() && nameIndex >= 0) {
                fileName = cursor.getString(nameIndex)
            }
        }
        return fileName ?: uri.lastPathSegment
    }

    // ═══════════════════════════════════════════════════════════════
    //  Execution — Repack to OTA ZIP
    // ═══════════════════════════════════════════════════════════════

    private fun onRepackClicked() {
        if (isExecuting) {
            showLog("WARNING: Operation already in progress. Please wait.\n")
            return
        }

        if (!PythonBridge.isReady()) {
            showLog("ERROR: Python runtime not available.\n")
            showLog("Restart the app to retry initialization.\n\n")
            return
        }

        lifecycleScope.launch {
            isExecuting = true
            setUIExecuting(true)

            showLog("\n${"\u2550".repeat(50)}\n")
            showLog("REPACK: Generate flashable OTA ZIP\n")
            showLog("Time: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date())}\n")
            showLog("\u2500".repeat(50) + "\n\n")

            val result = executeRepack()

            showLog("\n" + "\u2550".repeat(50) + "\n")
            if (result.success) {
                showLog("COMPLETED in ${result.durationMs}ms\n")
                if (result.output.isNotBlank()) showLog(result.output)
            } else {
                showLog("FAILED in ${result.durationMs}ms\n")
                showLog("Error: ${result.error}\n")
                if (result.output.isNotBlank()) showLog(result.output)
            }
            showLog("\u2550".repeat(50) + "\n\n")

            isExecuting = false
            setUIExecuting(false)
        }
    }

    private suspend fun executeRepack(): PayloadResult {
        if (imageFiles.isEmpty()) {
            return PayloadResult.error("No partition images added. Use 'Add Images' button.")
        }

        val outDir = outputDirPath ?: outputDir.absolutePath
        File(outDir).mkdirs()

        val images = imageFiles.toMap()
        val outputFileName = PayloadBridge.buildOutputFileName(images, selectedCompression)
        val outPath = File(outDir, outputFileName).absolutePath

        showLog("Partitions (${images.size}):\n")
        images.entries.sortedBy { it.key }.forEach { (name, path) ->
            val file = File(path)
            showLog("  $name (${formatFileSize(file.length())})\n")
        }
        showLog("Compression: $selectedCompression\n")
        showLog("Output: $outputFileName\n\n")

        return PayloadBridge.zip(
            images = images,
            compression = selectedCompression,
            outputPath = outPath
        )
    }

    // ═══════════════════════════════════════════════════════════════
    //  UI Updates
    // ═══════════════════════════════════════════════════════════════

    private fun updateImageListUI() {
        val textView = findViewById<android.widget.TextView>(R.id.textViewImageList)
        val removeButton = findViewById<View>(R.id.buttonRemoveAll)

        if (imageFiles.isEmpty()) {
            textView?.text = getString(R.string.hint_no_images)
            removeButton?.visibility = View.GONE
        } else {
            val lines = imageFiles.sortedBy { it.first }.mapIndexed { idx, (name, path) ->
                val file = File(path)
                "  ${idx + 1}. $name  (${formatFileSize(file.length())})"
            }
            textView?.text = lines.joinToString("\n")
            removeButton?.visibility = View.VISIBLE
        }

        // Show/hide empty state hint
        val emptyHint = findViewById<View>(R.id.textEmptyHint)
        emptyHint?.visibility = if (imageFiles.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun updateOutputPreview() {
        val textView = findViewById<android.widget.TextView>(R.id.textViewOutputPreview)
        if (imageFiles.isEmpty()) {
            textView?.text = ""
            return
        }
        val images = imageFiles.toMap()
        val fileName = PayloadBridge.buildOutputFileName(images, selectedCompression)
        textView?.text = fileName
    }

    private fun copyPendingRemovals() {
        // Cleanup input dir for removed images
        inputDir.listFiles()?.forEach { file ->
            if (file.name.endsWith(".img") && !imageFiles.any { it.second == file.absolutePath }) {
                file.delete()
            }
        }
    }

    private fun setUIExecuting(executing: Boolean) {
        runOnUiThread {
            findViewById<View>(R.id.buttonExecute)?.isEnabled = !executing
            findViewById<View>(R.id.progressBar)?.visibility =
                if (executing) View.VISIBLE else View.GONE
            findViewById<View>(R.id.buttonAddImages)?.isEnabled = !executing
            findViewById<View>(R.id.buttonRemoveAll)?.isEnabled = !executing
        }
    }

    private fun showLog(text: String) {
        runOnUiThread {
            val textView = findViewById<android.widget.TextView>(R.id.textViewLog)
            textView?.append(text)

            val scrollView = findViewById<android.widget.ScrollView>(R.id.scrollViewLog)
            scrollView?.post { scrollView.fullScroll(View.FOCUS_DOWN) }
        }
    }

    private fun copyLogToClipboard() {
        val logText = findViewById<android.widget.TextView>(R.id.textViewLog)?.text?.toString()
        if (logText.isNullOrBlank()) {
            Toast.makeText(this, "Nothing to copy", Toast.LENGTH_SHORT).show()
            return
        }
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("PayloadToolkit Log", logText)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(this, getString(R.string.log_copied), Toast.LENGTH_SHORT).show()
    }

    // ═══════════════════════════════════════════════════════════════
    //  Utilities
    // ═══════════════════════════════════════════════════════════════

    private fun formatFileSize(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> String.format("%.1f KB", bytes / 1024.0)
            bytes < 1024 * 1024 * 1024 -> String.format("%.1f MB", bytes / (1024.0 * 1024))
            else -> String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024))
        }
    }
}
