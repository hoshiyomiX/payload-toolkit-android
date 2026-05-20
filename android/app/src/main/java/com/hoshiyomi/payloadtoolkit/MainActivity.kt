package com.hoshiyomi.payloadtoolkit

import android.content.ClipData
import android.provider.DocumentsContract
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
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import androidx.core.content.edit

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
    private var selectedCompressLevel: Int? = null
    private var imageFiles: MutableList<Pair<String, String>> = mutableListOf() // (name, path)
    private var isExecuting = false

    // App-internal directories
    private lateinit var inputDir: File
    private lateinit var outputDir: File

    // SharedPreferences for persisting user settings
    private val prefs by lazy { getSharedPreferences("payload_toolkit", Context.MODE_PRIVATE) }

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
        // Apply theme BEFORE setContentView
        applyTheme()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        inputDir = File(filesDir, "input").also { it.mkdirs() }
        outputDir = File(filesDir, "output").also { it.mkdirs() }

        initializePython()
        setupCompressionSelector()
        setupCompressLevelSelector()
        setupButtons()
        setupToolbar()
        setupDeviceMetaFields()
        setupOutputField()
        setupCustomFilenameField()
        setupThemeToggle()
        setupToggles()

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
            showLog("\u2500".repeat(40) + "\n")
            showLog("  Payload Toolkit v${BuildConfig.VERSION_NAME}\n")
            showLog("\u2500".repeat(40) + "\n\n")
            showLog("Initializing runtime...\n")
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
                        showLog("  \u2713 Python $pyVer ($source)\n")

                        lifecycleScope.launch {
                            val ptVer = withContext(Dispatchers.IO) {
                                PayloadBridge.getPyzVersion()
                            }
                            if (ptVer != null) showLog("  \u2713 payload_toolkit $ptVer\n")

                            // Run dependency health check
                            val depReport = withContext(Dispatchers.IO) {
                                PythonBridge.checkDependencies()
                            }
                            showLog(depReport)
                            showLog("  \u2713 Ready\n")
                            showLog("\u2500".repeat(40) + "\n\n")
                        }
                    } else {
                        showLog("  \u2717 Initialization failed\n")
                        showLog("  Error: ${result.error}\n\n")
                        if (result.diagnostics.isNotBlank()) {
                            showLog("[Diagnostics]\n")
                            showLog(result.diagnostics)
                            showLog("\u2500".repeat(40) + "\n\n")
                        }
                        showLog("Possible causes:\n")
                        showLog("  \u2022 APK from old build (before v3.0)\n")
                        showLog("  \u2022 Native libs extraction failed\n")
                        showLog("  \u2022 Fix: Uninstall \u2192 Re-download \u2192 Reinstall\n")
                        showLog("\u2500".repeat(40) + "\n\n")
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

    // ═══════════════════════════════════════════════════════════════
    //  Theme Management
    // ═══════════════════════════════════════════════════════════════

    private fun applyTheme() {
        val themeMode = prefs.getString("pref_theme_mode", "light") ?: "light"
        when (themeMode) {
            "dark" -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
            else -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
        }
    }

    override fun onCreateOptionsMenu(menu: android.view.Menu?): Boolean {
        menuInflater.inflate(R.menu.toolbar_menu, menu)
        updateThemeIcon(menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: android.view.Menu): Boolean {
        updateThemeIcon(menu)
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_toggle_theme -> {
                cycleTheme()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    /** Toggle theme: Light <-> Dark */
    private fun cycleTheme() {
        val current = prefs.getString("pref_theme_mode", "light") ?: "light"
        val next = if (current == "light") "dark" else "light"
        prefs.edit { putString("pref_theme_mode", next) }
        applyTheme()
        // Update toolbar icon after recreation
        invalidateOptionsMenu()
    }

    /** Update the theme toggle menu icon to reflect current mode. */
    private fun updateThemeIcon(menu: android.view.Menu?) {
        val item = menu?.findItem(R.id.action_toggle_theme) ?: return
        val mode = prefs.getString("pref_theme_mode", "light") ?: "light"
        item.setIcon(when (mode) {
            "dark" -> R.drawable.ic_theme_dark
            else -> R.drawable.ic_theme_light
        })
    }

    private fun setupThemeToggle() {
        // Theme toggle is handled via toolbar menu item (R.id.action_toggle_theme).
        // No extra setup needed here — onCreateOptionsMenu + onOptionsItemSelected handle it.
    }

    private fun setupToggles() {
        // Skip verification toggle
        val switchSkipVerify = findViewById<com.google.android.material.switchmaterial.SwitchMaterial>(R.id.switchSkipVerify)
        switchSkipVerify?.isChecked = prefs.getBoolean("pref_skip_verify", false)
        switchSkipVerify?.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit { putBoolean("pref_skip_verify", isChecked) }
        }
    }

    private fun setupCustomFilenameField() {
        val editFilename = findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.editTextCustomFilename)
        // Restore persisted custom filename (or keep empty for auto)
        editFilename?.setText(prefs.getString("pref_custom_filename", ""))

        // Listen for changes and update preview
        editFilename?.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) {
                val text = s?.toString()?.trim() ?: ""
                prefs.edit { putString("pref_custom_filename", text) }
                updateOutputPreview()
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })
    }

    private fun setupDeviceMetaFields() {
        val editDevice = findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.editTextDevice)

        // Restore persisted value (or keep empty for default)
        editDevice?.setText(prefs.getString("device", ""))

        // Auto-detect button
        findViewById<View>(R.id.buttonAutoDetect)?.setOnClickListener {
            val detected = Build.PRODUCT
            editDevice?.setText(detected)
            prefs.edit { putString("device", detected) }
            showLog("Auto-detected device: $detected\n")
        }
    }

    private fun setupOutputField() {
        val editOutput = findViewById<android.widget.EditText>(R.id.editTextOutput)

        // Restore persisted output directory, or default to filesDir/output
        val savedDir = prefs.getString("output_dir", null)
        if (savedDir != null) {
            outputDirPath = savedDir
            editOutput?.setText(savedDir)
        } else {
            editOutput?.setText(outputDir.absolutePath)
        }

        // Listen for manual edits in the output path field
        editOutput?.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) {
                val text = s?.toString()?.trim()
                if (!text.isNullOrEmpty() && text != outputDir.absolutePath) {
                    outputDirPath = text
                    prefs.edit { putString("output_dir", text) }
                    updateOutputPreview()
                }
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })
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
                updateCompressLevelSpinner()
                updateOutputPreview()
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }
    }

    private fun setupCompressLevelSelector() {
        val spinner = findViewById<android.widget.Spinner>(R.id.spinnerCompressLevel)
        spinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                val range = PayloadBridge.COMPRESS_LEVELS[selectedCompression]
                if (range != null && range.first != range.second) {
                    selectedCompressLevel = range.first + position
                } else {
                    selectedCompressLevel = null
                }
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }
        // Initialize based on current compression selection
        updateCompressLevelSpinner()
    }

    private fun updateCompressLevelSpinner() {
        val layout = findViewById<View>(R.id.layoutCompressLevel)
        val spinner = findViewById<android.widget.Spinner>(R.id.spinnerCompressLevel)
        val range = PayloadBridge.COMPRESS_LEVELS[selectedCompression]

        if (range == null || range.first == range.second) {
            // No level selection for 'none'
            layout?.visibility = View.GONE
            selectedCompressLevel = null
            return
        }

        layout?.visibility = View.VISIBLE
        val levels = (range.first..range.second).map { level ->
            val suffix = when {
                level == range.third -> " (default)"
                level == range.first -> " (fastest)"
                level == range.second -> " (best)"
                else -> ""
            }
            "$level$suffix"
        }
        val adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            levels
        ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        spinner.adapter = adapter

        // Default selection: use algorithm default
        val defaultIndex = range.third - range.first
        spinner.setSelection(defaultIndex.coerceIn(0, levels.size - 1))
        selectedCompressLevel = range.third
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
        // Take persistable URI permission so we can read/write after reboot
        try {
            contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (_: SecurityException) { }

        // Resolve SAF tree URI to a real filesystem path
        val resolvedPath = resolveTreeUriToPath(uri) ?: uri.toString()
        outputDirPath = resolvedPath
        prefs.edit { putString("output_dir", resolvedPath) }
        runOnUiThread {
            findViewById<android.widget.EditText>(R.id.editTextOutput)
                .setText(resolvedPath)
            showLog("Output directory: $resolvedPath\n")
            updateOutputPreview()
        }
    }

    /**
     * Resolve a SAF tree URI to a filesystem path.
     * treeDocId is typically "primary:<path>" or "XXXX-XXXX:<path>".
     */
    private fun resolveTreeUriToPath(uri: Uri): String? {
        return try {
            val treeDocId = DocumentsContract.getTreeDocumentId(uri)
            val split = treeDocId.split(":", limit = 2)
            if (split.size == 2) {
                val volume = split[0]
                val path = split[1]
                val storageRoot = when (volume) {
                    "primary" -> "/storage/emulated/0"
                    else -> "/storage/$volume"
                }
                "$storageRoot/$path"
            } else {
                uri.lastPathSegment?.let { "/storage/emulated/0/$it" }
            }
        } catch (_: Exception) {
            uri.lastPathSegment
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

        // Use custom filename if set, otherwise auto-generate
        val customName = prefs.getString("pref_custom_filename", "")?.trim()
        val outputFileName = if (!customName.isNullOrEmpty()) {
            if (customName.lowercase().endsWith(".zip")) customName else "$customName.zip"
        } else {
            PayloadBridge.buildOutputFileName(images, selectedCompression)
        }
        val outPath = File(outDir, outputFileName).absolutePath

        showLog("Partitions (${images.size}):\n")
        images.entries.sortedBy { it.key }.forEach { (name, path) ->
            val file = File(path)
            showLog("  $name (${formatFileSize(file.length())})\n")
        }
        showLog("Compression: $selectedCompression")
        if (selectedCompressLevel != null && selectedCompression != "none") {
            showLog(" (level $selectedCompressLevel)")
        }
        showLog("\n")
        showLog("Output: $outputFileName\n")

        // Read device metadata from UI field (REQUIRED)
        val deviceInput = findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.editTextDevice)
            ?.text?.toString()?.trim() ?: ""

        // Persist value for next launch
        prefs.edit { putString("device", deviceInput) }

        // Validate: device is required
        if (deviceInput.isEmpty()) {
            return PayloadResult.error("Device codename is required. Use Auto-detect or enter manually.")
        }

        val device = deviceInput

        showLog("Device: $device")

        // Read toggle states
        val skipVerify = prefs.getBoolean("pref_skip_verify", false)
        if (skipVerify) showLog(" | Verification: SKIPPED")
        showLog("\n\n")

        return PayloadBridge.dd(
            images = images,
            device = device,
            compression = selectedCompression,
            compressionLevel = selectedCompressLevel,
            skipVerify = skipVerify,
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
        // Use custom filename if set, otherwise auto-generate
        val customName = prefs.getString("pref_custom_filename", "")?.trim()
        val fileName = if (!customName.isNullOrEmpty()) {
            // Ensure .zip extension
            if (customName.lowercase().endsWith(".zip")) customName else "$customName.zip"
        } else {
            val images = imageFiles.toMap()
            PayloadBridge.buildOutputFileName(images, selectedCompression)
        }
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
