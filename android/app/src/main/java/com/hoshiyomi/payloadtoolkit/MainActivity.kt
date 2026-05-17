package com.hoshiyomi.payloadtoolkit

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
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * MainActivity — Primary UI for Payload Toolkit Android.
 *
 * Provides a Material Design 3 interface for:
 *   - Selecting payload.bin / .img files via SAF file picker
 *   - Choosing output directory
 *   - Selecting operation mode (info / dump / gen / zip / sign)
 *   - Configuring mode-specific options (compression, partitions, device info)
 *   - Executing operations via [PayloadBridge] with progress indication
 *   - Displaying structured log output
 *
 * Python runtime: uses external Python (Termux recommended).
 * The .pyz is bundled as an asset and extracted at first launch.
 */
class MainActivity : AppCompatActivity() {

    // ═══════════════════════════════════════════════════════════════
    //  State
    // ═══════════════════════════════════════════════════════════════

    private var selectedMode: String = "info"
    private var inputFilePath: String? = null
    private var outputDirPath: String? = null
    private var selectedCompression: String = "none"
    private var imageFiles: MutableList<Pair<String, String>> = mutableListOf() // (name, path)
    private var isExecuting = false

    // App-internal directories
    private lateinit var inputDir: File
    private lateinit var outputDir: File
    private lateinit var keysDir: File

    // ═══════════════════════════════════════════════════════════════
    //  Activity Result Launchers (modern file picker API)
    // ═══════════════════════════════════════════════════════════════

    private val inputFileChooser = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let { handleInputFileSelected(it) }
    }

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

    private val keyFileChooser = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let { handleKeyFileSelected(it) }
    }

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

        // Initialize directories
        inputDir = File(filesDir, "input").also { it.mkdirs() }
        outputDir = File(filesDir, "output").also { it.mkdirs() }
        keysDir = File(filesDir, "keys").also { it.mkdirs() }

        // Initialize Python bridge
        initializePython()

        // Setup UI
        setupModeChips()
        setupCompressionSelector()
        setupButtons()
        setupToolbar()

        // Request permissions
        requestStoragePermissions()

        // Handle incoming intents (file opened from another app)
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
                                pb.inputStream.bufferedReader().readText().trim()
                            } catch (e: Exception) {
                                "unknown"
                            }
                        }
                        showLog("Python runtime: $pyVer\n")
                        showLog("Path: ${result.pythonPath}\n")

                        // Get .pyz version
                        lifecycleScope.launch {
                            val ptVer = PayloadBridge.getPyzVersion()
                            if (ptVer != null) {
                                showLog("payload_toolkit $ptVer loaded from .pyz\n")
                            }
                        }

                        showLog("Supported modes: ${PayloadBridge.SUPPORTED_MODES.joinToString(", ")}\n")
                        showLog("\u2550".repeat(60) + "\n\n")
                    } else {
                        showLog("WARNING: ${result.error}\n\n")
                        showLog("This app requires Python to be installed.\n")
                        showLog("Recommended: Install Termux, then run:\n")
                        showLog("  pkg install python\n\n")
                        showLog("After installing Python, restart this app.\n")
                        showLog("\u2550".repeat(60) + "\n\n")
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

    private fun setupModeChips() {
        val chipGroup = findViewById<com.google.android.material.chip.ChipGroup>(R.id.chipGroupModes)
        val modes = PayloadBridge.SUPPORTED_MODES

        modes.forEach { mode ->
            val chip = Chip(this).apply {
                text = mode.uppercase()
                isCheckable = true
                tag = mode
                setChipBackgroundColorResource(
                    if (mode == "info") R.color.chip_selected_bg
                    else R.color.chip_default_bg
                )
            }
            chip.setOnClickListener {
                selectedMode = mode
                updateModeUI(mode)
            }
            chipGroup.addView(chip)
        }

        // Select "info" by default
        chipGroup.check(chipGroup.getChildAt(0)?.id ?: return)
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
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }
    }

    private fun setupButtons() {
        findViewById<View>(R.id.buttonBrowseInput).setOnClickListener {
            val mimeType = when (selectedMode) {
                "sign" -> "*/*"
                "gen", "zip" -> "application/octet-stream"
                else -> "application/octet-stream"
            }
            inputFileChooser.launch(arrayOf(mimeType))
        }

        findViewById<View>(R.id.buttonBrowseOutput).setOnClickListener {
            outputDirChooser.launch(null)
        }

        findViewById<View>(R.id.buttonAddImages)?.setOnClickListener {
            imageFileChooser.launch(arrayOf("application/octet-stream", "image/*"))
        }

        findViewById<View>(R.id.buttonExecute).setOnClickListener {
            onExecuteClicked()
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
            ) {
                permissionsToRequest.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED
            ) {
                permissionsToRequest.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
            }
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
                        "payload.bin and partition images.\n\n" +
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

    private fun handleInputFileSelected(uri: Uri) {
        lifecycleScope.launch {
            val fileName = getFileName(uri) ?: "input.bin"
            val destFile = File(inputDir, fileName)
            copyUriToFile(uri, destFile)
            inputFilePath = destFile.absolutePath

            runOnUiThread {
                findViewById<android.widget.EditText>(R.id.editTextInput)
                    .setText(inputFilePath)
                showLog("Input file: $fileName (${formatFileSize(destFile.length())})\n")
            }
        }
    }

    private fun handleOutputDirSelected(uri: Uri) {
        outputDirPath = outputDir.absolutePath
        runOnUiThread {
            findViewById<android.widget.EditText>(R.id.editTextOutput)
                .setText(outputDirPath)
            showLog("Output directory: ${outputDir.absolutePath}\n")
        }
    }

    private fun handleImageFilesSelected(uris: List<Uri>) {
        lifecycleScope.launch {
            for (uri in uris) {
                val fileName = getFileName(uri) ?: "image.img"
                val partitionName = fileName.removeSuffix(".img")
                val destFile = File(inputDir, fileName)
                copyUriToFile(uri, destFile)
                imageFiles.add(partitionName to destFile.absolutePath)
            }

            runOnUiThread {
                val summary = imageFiles.joinToString("\n") { (name, path) ->
                    "  $name -> ${File(path).name}"
                }
                showLog("Image files added:\n$summary\n")
            }
        }
    }

    private fun handleKeyFileSelected(uri: Uri) {
        lifecycleScope.launch {
            val fileName = getFileName(uri) ?: "key.pem"
            val destFile = File(keysDir, fileName)
            copyUriToFile(uri, destFile)
            showLog("Key file: $fileName (${formatFileSize(destFile.length())})\n")
        }
    }

    private fun handleIncomingIntent(intent: Intent) {
        when (intent.action) {
            Intent.ACTION_VIEW -> {
                intent.data?.let { uri ->
                    handleInputFileSelected(uri)
                    selectedMode = "info"
                    updateModeUI("info")
                }
            }
            Intent.ACTION_SEND -> {
                (intent.getParcelableExtra<Intent.EXTRA_STREAM>(Intent.EXTRA_STREAM) as? Uri)?.let { uri ->
                    handleInputFileSelected(uri)
                    selectedMode = "info"
                    updateModeUI("info")
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
    //  Execution
    // ═══════════════════════════════════════════════════════════════

    private fun onExecuteClicked() {
        if (isExecuting) {
            showLog("WARNING: Operation already in progress. Please wait.\n")
            return
        }

        if (!PythonBridge.isReady()) {
            showLog("ERROR: Python runtime not available. Cannot execute.\n")
            showLog("Install Python via Termux: pkg install python\n")
            return
        }

        lifecycleScope.launch {
            isExecuting = true
            setUIExecuting(true)

            showLog("\n${"\u2550".repeat(60)}\n")
            showLog("MODE: ${selectedMode.uppercase()}\n")
            showLog("Time: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date())}\n")
            showLog("\u2500".repeat(60) + "\n\n")

            val result = when (selectedMode) {
                "info" -> executeInfo()
                "dump" -> executeDump()
                "gen" -> executeGen()
                "zip" -> executeZip()
                "sign" -> executeSign()
                else -> PayloadResult.error("Unknown mode: $selectedMode")
            }

            showLog("\n" + "\u2550".repeat(60) + "\n")
            if (result.success) {
                showLog("COMPLETED in ${result.durationMs}ms\n")
                if (result.output.isNotBlank()) {
                    showLog(result.output)
                }
            } else {
                showLog("FAILED in ${result.durationMs}ms\n")
                showLog("Error: ${result.error}\n")
                if (result.output.isNotBlank()) {
                    showLog(result.output)
                }
            }
            showLog("\u2550".repeat(60) + "\n\n")

            isExecuting = false
            setUIExecuting(false)
        }
    }

    private suspend fun executeInfo(): PayloadResult {
        val path = inputFilePath
        if (path == null || !File(path).exists()) {
            return PayloadResult.error("No input file selected or file not found.")
        }
        showLog("Reading: ${File(path).name}...\n")
        return PayloadBridge.getInfo(path, verbose = true)
    }

    private suspend fun executeDump(): PayloadResult {
        val path = inputFilePath
        if (path == null || !File(path).exists()) {
            return PayloadResult.error("No payload.bin selected or file not found.")
        }
        val outDir = outputDirPath ?: outputDir.absolutePath
        File(outDir).mkdirs()
        showLog("Extracting to: $outDir\n")
        return PayloadBridge.dump(path, outDir)
    }

    private suspend fun executeGen(): PayloadResult {
        if (imageFiles.isEmpty()) {
            return PayloadResult.error("No image files added. Use 'Add Images' button.")
        }
        val outPath = File(outputDirPath ?: outputDir.absolutePath, "payload.bin").absolutePath
        val images = imageFiles.toMap()
        showLog("Generating payload.bin with ${images.size} partition(s)...\n")
        showLog("Compression: $selectedCompression\n")
        return PayloadBridge.gen(images, selectedCompression, outPath)
    }

    private suspend fun executeZip(): PayloadResult {
        if (imageFiles.isEmpty()) {
            return PayloadResult.error("No image files added. Use 'Add Images' button.")
        }
        val outPath = File(outputDirPath ?: outputDir.absolutePath, "partial_ota.zip").absolutePath
        val images = imageFiles.toMap()

        val device = "S666LN,itel-S666LN"
        val fingerprint = "Itel/S666LN-OP/itel-S666LN:13/TP1A.220624.014/251212V1661:user/release-keys"

        showLog("Generating OTA ZIP with ${images.size} partition(s)...\n")
        showLog("Compression: $selectedCompression\n")
        return PayloadBridge.zip(images, device, fingerprint, selectedCompression, outPath)
    }

    private suspend fun executeSign(): PayloadResult {
        val path = inputFilePath
        if (path == null || !File(path).exists()) {
            return PayloadResult.error("No payload.bin selected or file not found.")
        }
        val outPath = File(outputDirPath ?: outputDir.absolutePath, "payload_signed.bin").absolutePath

        val keyFiles = keysDir.listFiles() ?: emptyArray()
        val keyFile = keyFiles.find { it.name.contains("private") || it.name.contains("key") }
        val certFile = keyFiles.find { it.name.contains("cert") || it.name.contains("public") }

        if (keyFile == null || certFile == null) {
            return PayloadResult.error(
                "RSA key pair not found. Place private_key.pem and public_cert.pem " +
                "in the keys directory, or use the key picker.\n" +
                "Keys directory: ${keysDir.absolutePath}"
            )
        }

        showLog("Signing payload.bin...\n")
        showLog("Key: ${keyFile.name}\n")
        showLog("Cert: ${certFile.name}\n")
        return PayloadBridge.sign(path, outPath, keyFile.absolutePath, certFile.absolutePath)
    }

    // ═══════════════════════════════════════════════════════════════
    //  UI Updates
    // ═══════════════════════════════════════════════════════════════

    private fun updateModeUI(mode: String) {
        val addImagesButton = findViewById<View>(R.id.buttonAddImages)
        val imageListCard = findViewById<View>(R.id.cardImageList)
        val compressionCard = findViewById<View>(R.id.cardCompression)
        val signOptionsCard = findViewById<View>(R.id.cardSignOptions)

        addImagesButton?.visibility = if (mode in listOf("gen", "zip")) View.VISIBLE else View.GONE
        imageListCard?.visibility = if (mode in listOf("gen", "zip")) View.VISIBLE else View.GONE
        compressionCard?.visibility = if (mode in listOf("gen", "zip")) View.VISIBLE else View.GONE
        signOptionsCard?.visibility = if (mode == "sign") View.VISIBLE else View.GONE

        val inputHint = when (mode) {
            "info", "dump" -> "payload.bin path"
            "gen", "zip" -> "Partition images added below"
            "sign" -> "payload.bin to sign"
            else -> "Input file path"
        }
        findViewById<android.widget.EditText>(R.id.editTextInput)?.hint = inputHint
    }

    private fun setUIExecuting(executing: Boolean) {
        runOnUiThread {
            findViewById<View>(R.id.buttonExecute)?.isEnabled = !executing
            findViewById<View>(R.id.progressBar)?.visibility =
                if (executing) View.VISIBLE else View.GONE
            findViewById<android.widget.EditText>(R.id.editTextInput)?.isEnabled = !executing

            val chipGroup = findViewById<com.google.android.material.chip.ChipGroup>(R.id.chipGroupModes)
            for (i in 0 until chipGroup.childCount) {
                chipGroup.getChildAt(i)?.isEnabled = !executing
            }
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
