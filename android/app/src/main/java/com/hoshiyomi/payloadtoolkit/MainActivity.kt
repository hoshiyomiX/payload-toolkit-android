package com.hoshiyomi.payloadtoolkit

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.PowerManager
import android.provider.DocumentsContract
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import java.lang.ref.WeakReference
import java.io.File
import java.io.FileOutputStream
import androidx.core.content.edit
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.app.NotificationManager
import android.app.PendingIntent
import androidx.core.app.NotificationCompat

/**
 * MainActivity — OTAku Android.
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
    private var selectedCompressionLevel: Int = 0  // 0 = default (best)
    private var imageFiles: MutableList<Pair<String, String>> = mutableListOf() // (name, path)
    private var isExecuting = false
    companion object {
        // Application-scoped coroutine scope for long-running repack operations.
        // Survives Activity destruction when the user minimizes the app.
        private val repackScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

        // Whether a repack is currently running (survives Activity recreation)
        @Volatile var isRepacking = false
            private set

        // Weak reference to the current Activity for safe UI updates from coroutine
        @Volatile private var activityRef: WeakReference<MainActivity>? = null

        // WakeLock (survives Activity recreation)
        @Volatile private var wakeLock: PowerManager.WakeLock? = null

        // Latest output path (survives Activity recreation)
        @Volatile private var lastOutputPath: String = ""

        // Track last progress message to avoid spamming the log with duplicates
        @Volatile private var lastProgressMessage: String = ""

        // Track last progress percent to avoid logging on every chunk
        @Volatile private var lastProgressPercent: Int = -1

        // Persisted log text (survives Activity recreation)
        @Volatile private var savedLogText: StringBuilder = StringBuilder()

        // Heartbeat: last time a progress update was received (epoch millis)
        @Volatile private var lastProgressTime: Long = 0L
        // Threshold: if no progress for this long (ms), process is assumed dead
        private const val DEAD_PROCESS_THRESHOLD_MS = 120_000L  // 2 minutes

        // Per-partition split progress bar state
        @Volatile private var partitionCount: Int = 0
        @Volatile private var partitionProgress: IntArray = IntArray(0)
        @Volatile private var currentPartitionIndex: Int = -1
        @Volatile private var partitionNames: List<String> = emptyList()

        // Notification management (survives Activity recreation)
        private const val NOTIFICATION_ID = 1001
        @Volatile private var appContext: Context? = null

        // Cached dependency check result (updated at init, used for pre-repack validation)
        @Volatile var cachedDepCheck: PythonBridge.DepCheckResult? = null
            private set

        /** Show ongoing progress notification with determinate progress bar. */
        fun showProgressNotification(message: String, percent: Int) {
            val ctx = appContext ?: return
            try {
                val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                val intent = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)?.apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                } ?: return
                val pi = PendingIntent.getActivity(
                    ctx, 0, intent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
                val notification = NotificationCompat.Builder(ctx, PayloadToolkitApp.CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.ic_media_play)
                    .setContentTitle("OTAku")
                    .setContentText(message)
                    .setProgress(100, percent.coerceIn(0, 100), percent == 0)
                    .setOngoing(true)
                    .setSilent(true)
                    .setContentIntent(pi)
                    .setPriority(NotificationCompat.PRIORITY_LOW)
                    .build()
                nm.notify(NOTIFICATION_ID, notification)
            } catch (_: Exception) { /* notification is non-critical */ }
        }

        /** Show completion/failure notification (auto-dismissable). */
        fun showCompletionNotification(success: Boolean, message: String) {
            val ctx = appContext ?: return
            try {
                val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                val intent = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)?.apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                } ?: return
                val pi = PendingIntent.getActivity(
                    ctx, 0, intent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
                val notification = NotificationCompat.Builder(ctx, PayloadToolkitApp.CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.ic_media_play)
                    .setContentTitle(if (success) "Repack Completed" else "Repack Failed")
                    .setContentText(message)
                    .setOngoing(false)
                    .setAutoCancel(true)
                    .setContentIntent(pi)
                    .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                    .build()
                nm.notify(NOTIFICATION_ID, notification)
            } catch (_: Exception) { /* notification is non-critical */ }
        }

        /** Cancel the repack notification. */
        fun cancelRepackNotification() {
            try {
                appContext?.let {
                    (it.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                        .cancel(NOTIFICATION_ID)
                }
            } catch (_: Exception) { /* notification is non-critical */ }
            appContext = null
        }
    }

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
            showLog("Some permissions were denied. File access may be limited.\n", LogLevel.WARN)
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
        setupButtons()
        setupToolbar()
        setupDeviceMetaFields()
        setupOutputField()
        setupCustomFilenameField()
        setupThemeToggle()
        updateOutputPreview()  // Show default filename preview immediately

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
            showLog("Initializing OTAku...\n", LogLevel.INFO)
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
                        showLog("Python runtime: $pyVer ($source)\n", LogLevel.INFO)

                        lifecycleScope.launch {
                            val ptVer = withContext(Dispatchers.IO) {
                                PayloadBridge.getPyzVersion()
                            }
                            if (ptVer != null) showLog("payload_toolkit $ptVer loaded\n", LogLevel.INFO)

                            // Run dependency health check
                            val depReport = withContext(Dispatchers.IO) {
                                PythonBridge.checkDependencies()
                            }
                            if (depReport.isNotBlank()) {
                                showLog(depReport + "\n")
                            }
                            // Cache parsed result for pre-repack validation
                            cachedDepCheck = withContext(Dispatchers.IO) {
                                PythonBridge.checkDependenciesParsed()
                            }
                        }

                        showLog("\u2550".repeat(50) + "\n\n")
                    } else {
                        showLog("Initialization failed: ${result.error}\n", LogLevel.ERROR)
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

    // ═══════════════════════════════════════════════════════════════
    //  Theme Management
    // ═══════════════════════════════════════════════════════════════

    /** Apply theme. Default: follow system; user can override to Light/Dark. */
    private fun applyTheme() {
        val themeMode = prefs.getString("pref_theme_mode", "system") ?: "system"
        when (themeMode) {
            "light" -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
            "dark" -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
            else -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
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

    /** Cycle theme: System -> Light -> Dark -> System */
    private fun cycleTheme() {
        val current = prefs.getString("pref_theme_mode", "system") ?: "system"
        val next = when (current) {
            "system" -> "light"
            "light" -> "dark"
            else -> "system"
        }
        prefs.edit { putString("pref_theme_mode", next) }
        applyTheme()
        invalidateOptionsMenu()
    }

    /** Update the theme toggle menu icon to reflect current mode. */
    private fun updateThemeIcon(menu: android.view.Menu?) {
        val item = menu?.findItem(R.id.action_toggle_theme) ?: return
        val mode = prefs.getString("pref_theme_mode", "system") ?: "system"
        item.setIcon(when (mode) {
            "light" -> R.drawable.ic_theme_light
            "dark" -> R.drawable.ic_theme_dark
            else -> R.drawable.ic_theme_auto
        })
    }

    private fun setupThemeToggle() {
        // Theme toggle is handled via toolbar menu item (R.id.action_toggle_theme).
        // No extra setup needed here — onCreateOptionsMenu + onOptionsItemSelected handle it.
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

        // Auto-detect button: fill device field with Build.PRODUCT
        findViewById<View>(R.id.buttonAutoDetect)?.setOnClickListener {
            val deviceName = android.os.Build.PRODUCT
            editDevice?.setText(deviceName)
            prefs.edit { putString("device", deviceName) }
            updateOutputPreview()
            showLog("Auto-detected device: $deviceName\n", LogLevel.INFO)
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
                updateCompressionLevelSpinner()
                updateOutputPreview()
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }

        // Initialize compression level spinner
        setupCompressionLevelSpinner()
    }

    // Compression level ranges per algorithm (matches Python LEVEL_RANGES)
    // Default level per algorithm (matches Python DEFAULT_LEVELS):
    //   gzip=6, bzip2=9, xz=6, brotli=6
    private val COMPRESSION_LEVELS: Map<String, Pair<Int, Int>> = mapOf(
        "none" to Pair(0, 0),     // no compression
        "gzip" to Pair(1, 9),     // stdlib gzip: levels 1-9, default 6
        "bzip2" to Pair(1, 9),    // stdlib bzip2: levels 1-9, default 9
        "xz" to Pair(0, 9),       // stdlib lzma: levels 0-9, default 6
        "brotli" to Pair(0, 11)   // brotli: quality 0-11, default 6
    )

    // Default compression level per algorithm (single source of truth for UI labels)
    private val DEFAULT_COMPRESSION_LEVELS: Map<String, Int> = mapOf(
        "none" to 0,
        "gzip" to 6,
        "bzip2" to 9,
        "xz" to 6,
        "brotli" to 6
    )

    private fun setupCompressionLevelSpinner() {
        val spinner = findViewById<android.widget.Spinner>(R.id.spinnerCompressionLevel)
        updateCompressionLevelSpinner()

        spinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                val items = getCurrentLevelItems()
                selectedCompressionLevel = if (position < items.size) items[position] else 0
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }
    }

    private fun getCurrentLevelItems(): List<Int> {
        val range = COMPRESSION_LEVELS[selectedCompression] ?: (0 to 0)
        val (min, max) = range
        return if (min == 0 && max == 0) {
            listOf(0)  // "none" → just show "Default"
        } else {
            listOf(0) + (min..max).toList()  // 0 (default) + 1..9
        }
    }

    private fun updateCompressionLevelSpinner() {
        val spinner = findViewById<android.widget.Spinner>(R.id.spinnerCompressionLevel) ?: return
        val items = getCurrentLevelItems()
        val defaultLevel = DEFAULT_COMPRESSION_LEVELS[selectedCompression] ?: 0
        val labels = items.map { if (it == 0) "Default ($defaultLevel)" else "$it" }
        val adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            labels
        ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        spinner.adapter = adapter
        // Reset selection to "Default"
        spinner.setSelection(0)
        selectedCompressionLevel = 0
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
            showLog("All images removed.\n", LogLevel.INFO)
        }

        findViewById<View>(R.id.buttonExecute).setOnClickListener {
            onRepackClicked()
        }

        findViewById<View>(R.id.buttonCopyLog).setOnClickListener {
            copyLogToClipboard()
        }

        findViewById<View>(R.id.buttonClearLog).setOnClickListener {
            findViewById<android.widget.TextView>(R.id.textViewLog).text = ""
            savedLogText.clear()
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
                        "OTAku needs access to all files to read/write " +
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
            showLog("Output directory: $resolvedPath\n", LogLevel.INFO)
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
                    showLog("$partitionName already added, skipping.\n", LogLevel.WARN)
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

    override fun onBackPressed() {
        if (isRepacking) {
            MaterialAlertDialogBuilder(this)
                .setTitle("Repack in progress")
                .setMessage("The repack operation is running in the background " +
                    "and will continue even if you leave the app.")
                .setPositiveButton("Stay", null)
                .show()
        } else {
            super.onBackPressed()
        }
    }

    private fun onRepackClicked() {
        if (isRepacking) {
            showLog("Operation already in progress. Please wait.\n", LogLevel.WARN)
            return
        }

        if (!PythonBridge.isReady()) {
            showLog("Python runtime not available.\n", LogLevel.ERROR)
            showLog("Restart the app to retry initialization.\n\n", LogLevel.WARN)
            return
        }

        // Pre-repack dependency check: validate selected compression is available.
        // Uses cached result from initialization to avoid blocking the UI.
        // Note: hashlib and bz2 are non-blocking — compression.py provides a
        // pure-Python SHA-256 fallback, and bz2 is optional (use gzip/xz/brotli).
        val depCheck = cachedDepCheck
        if (depCheck == null) {
            showLog("Python runtime not ready. Restart the app.\n\n", LogLevel.ERROR)
            return
        }
        // Show informational warnings for missing modules (don't block repack).
        if (depCheck.missing.contains("hashlib")) {
            showLog("[!] hashlib C extension unavailable — using pure-Python SHA-256 fallback.\n", LogLevel.WARN)
        }
        if (depCheck.missing.contains("bz2")) {
            showLog("[!] bz2 unavailable — bzip2 compression disabled.\n", LogLevel.WARN)
        }
        if (selectedCompression !in depCheck.availableCompression) {
            showLog("Cannot start repack: compression '$selectedCompression' is not available.\n", LogLevel.ERROR)
            showLog("  Available: ${depCheck.availableCompression.joinToString(", ")}\n\n", LogLevel.INFO)
            return
        }

        // Collect parameters for repack
        val images = imageFiles.toMap()
        val device = findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.editTextDevice)
            ?.text?.toString()?.trim() ?: ""
        prefs.edit { putString("device", device) }
        val deviceValue = device.ifEmpty { "generic" }

        val outDir = outputDirPath ?: outputDir.absolutePath
        File(outDir).mkdirs()

        val customName = prefs.getString("pref_custom_filename", "")?.trim()
        val outputFileName = if (!customName.isNullOrEmpty()) {
            if (customName.lowercase().endsWith(".zip")) customName else "$customName.zip"
        } else {
            PayloadBridge.buildOutputFileName(images, selectedCompression)
        }
        val outPath = File(outDir, outputFileName).absolutePath

        // Log header
        showLog("\n${"\u2550".repeat(50)}\n")
        showLog("Generating flashable OTA ZIP\n", LogLevel.INFO)
        showLog("${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date())}\n")
        showLog("\u2500".repeat(50) + "\n\n")

        showLog("Partitions (${images.size}):\n", LogLevel.INFO)
        images.entries.sortedBy { it.key }.forEach { (name, path) ->
            val file = File(path)
            showLog("  $name (${formatFileSize(file.length())})\n")
        }
        showLog("Compression: $selectedCompression (level $selectedCompressionLevel)\n", LogLevel.INFO)
        showLog("Device: $deviceValue\n", LogLevel.INFO)
        showLog("Output file: $outputFileName\n", LogLevel.INFO)
        showLog("Output path: $outPath\n\n", LogLevel.INFO)

        // Store state in companion object (survives Activity recreation)
        lastOutputPath = outPath
        lastProgressMessage = ""
        lastProgressPercent = -1
        lastProgressTime = System.currentTimeMillis()  // Start heartbeat
        isRepacking = true
        isExecuting = true
        appContext = applicationContext
        setUIExecuting(true)
        val sortedNames = images.keys.sorted()
        partitionNames = sortedNames
        setupSplitProgressBar(sortedNames)
        showLog("[INFO] Starting repack operation...\n", LogLevel.INFO)
        showProgressNotification("Preparing repack...", 0)

        // Execute in application-scoped scope (survives Activity destruction)
        repackScope.launch {
            try {
                // Acquire WakeLock with application context
                val act = activityRef?.get()
                if (act != null) {
                    val pm = act.applicationContext.getSystemService(Context.POWER_SERVICE) as PowerManager
                    wakeLock = pm.newWakeLock(
                        PowerManager.PARTIAL_WAKE_LOCK,
                        "PayloadToolkit::RepackWakeLock"
                    ).apply {
                        setReferenceCounted(false)
                        acquire(3 * 60 * 60 * 1000L)  // 3 hours — enough for any compression job
                    }
                }

                val result = PayloadBridge.dd(
                    images = images,
                    device = deviceValue,
                    compression = selectedCompression,
                    level = selectedCompressionLevel,
                    outputPath = outPath,
                    onProgress = { progress ->
                        // Update heartbeat timestamp (survives Activity recreation)
                        lastProgressTime = System.currentTimeMillis()

                        // Update notification (works even when Activity is destroyed)
                        val msg = "${progress.message} — ${progress.percent}%"
                        if (msg != lastProgressMessage) {
                            lastProgressMessage = msg
                            showProgressNotification(msg, progress.percent)
                        }

                        // Update split progress bars (per-partition) using message-based mapping.
                        // progress.current is 1-based across ALL steps (Step 1 + N partitions + Step 2 + Step 3),
                        // so numeric index mapping is unreliable. Map by partition name from message instead.
                        if (partitionCount > 0) {
                            val msg = progress.message
                            val pIdx = when {
                                msg.startsWith("Compressing ") -> {
                                    val name = msg.removePrefix("Compressing ").trim()
                                    partitionNames.indexOf(name)
                                }
                                msg.contains("Building ddbundle") -> {
                                    // Pre-partition step: show bar 0 as indeterminate (process started)
                                    currentPartitionIndex = 0
                                    -2  // special sentinel
                                }
                                msg.contains("Building flasher") || msg.contains("Writing output") -> {
                                    // Post-partition steps: mark all bars complete
                                    for (j in 0 until partitionCount) {
                                        partitionProgress[j] = 100
                                    }
                                    currentPartitionIndex = partitionCount - 1
                                    -1  // skip bar update below
                                }
                                else -> -1
                            }
                            when {
                                pIdx == -2 -> { /* indeterminate handled in UI update block */ }
                                pIdx >= 0 && pIdx < partitionCount -> {
                                    partitionProgress[pIdx] = progress.percent
                                    currentPartitionIndex = pIdx
                                    // Relay: mark all previous partitions as complete
                                    for (j in 0 until pIdx) {
                                        if (partitionProgress[j] < 100) partitionProgress[j] = 100
                                    }
                                }
                            }
                        }

                        // Only log when percent changes (not every chunk)
                        val current = activityRef?.get()
                        if (current != null && !current.isFinishing && !current.isDestroyed) {
                            current.runOnUiThread {
                                // Update split progress bars — bars live inside a tagged horizontal child
                                val container = current.findViewById<android.widget.LinearLayout>(R.id.progressBarContainer)
                                val barRow = container?.findViewWithTag<android.widget.LinearLayout>("bar_row")
                                if (barRow != null && barRow.childCount == partitionCount) {
                                    for (i in 0 until partitionCount) {
                                        val bar = barRow.getChildAt(i) as? com.google.android.material.progressindicator.LinearProgressIndicator
                                        if (bar != null) {
                                            // During pre-partition step (sentinel -2), show bar 0 as indeterminate
                                            if (currentPartitionIndex == 0 && partitionProgress[0] == 0 && i == 0
                                                && progress.message.contains("Building ddbundle")) {
                                                bar.isIndeterminate = true
                                            } else {
                                                bar.isIndeterminate = false
                                                bar.progress = partitionProgress[i]
                                            }
                                        }
                                    }
                                }
                            }
                            // Log only when percent changes
                            if (progress.percent != lastProgressPercent) {
                                lastProgressPercent = progress.percent
                                current.showLog("[PROGRESS] ${progress.message} — ${progress.percent}%\n", LogLevel.PLAIN)
                            }
                        }
                    },
                    onOutputLine = { line ->
                        // Stream Python stdout to log in real-time
                        val current = activityRef?.get()
                        if (current != null && !current.isFinishing && !current.isDestroyed) {
                            current.showLog("$line\n")
                        }
                    }
                )

                // Handle result on current Activity instance
                val current = activityRef?.get()
                if (current != null && !current.isFinishing && !current.isDestroyed) {
                    current.handleRepackResult(
                        success = result.success,
                        output = result.output,
                        error = result.error,
                        durationMs = result.durationMs
                    )
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                showCompletionNotification(false, "Repack cancelled")
                throw e  // Don't swallow coroutine cancellation
            } catch (e: Exception) {
                val current = activityRef?.get()
                if (current != null && !current.isFinishing && !current.isDestroyed) {
                    current.showLog("[ERROR] Repack failed: ${e.message}\n", LogLevel.ERROR)
                    current.showLog("[INFO] Check logcat for details.\n", LogLevel.WARN)
                }
                showCompletionNotification(false, "${e.message}")
            } finally {
                // Release WakeLock
                try { wakeLock?.release() } catch (_: Exception) {}
                wakeLock = null
                isRepacking = false

                val current = activityRef?.get()
                if (current != null && !current.isFinishing && !current.isDestroyed) {
                    current.isExecuting = false
                    current.setUIExecuting(false)
                }
            }
        }
    }

    /**
     * Handle repack result — updates UI with success/failure status.
     */
    private fun handleRepackResult(success: Boolean, output: String, error: String?, durationMs: Long) {
        isExecuting = false
        setUIExecuting(false)

        showLog("\n" + "\u2550".repeat(50) + "\n")
        if (success) {
            val duration = if (durationMs < 60000) "${durationMs / 1000}s"
                else "${durationMs / 60000}m ${durationMs % 60000}"
            showLog("Completed in ${durationMs}ms\n", LogLevel.SUCCESS)
            showLog("Output: $lastOutputPath\n", LogLevel.INFO)
            showCompletionNotification(true, "Completed in $duration")
        } else {
            showLog("Failed in ${durationMs}ms\n", LogLevel.ERROR)
            showLog("Error: $error\n", LogLevel.ERROR)
            showCompletionNotification(false, error ?: "Unknown error")
        }
        showLog("\u2550".repeat(50) + "\n\n")
        showLog("[INFO] Repack finished\n", LogLevel.INFO)
    }

    // ═══════════════════════════════════════════════════
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

        // Use custom filename if set, otherwise auto-generate
        val customName = prefs.getString("pref_custom_filename", "")?.trim()
        val fileName = if (!customName.isNullOrEmpty()) {
            // Ensure .zip extension
            if (customName.lowercase().endsWith(".zip")) customName else "$customName.zip"
        } else if (imageFiles.isNotEmpty()) {
            val images = imageFiles.toMap()
            PayloadBridge.buildOutputFileName(images, selectedCompression)
        } else {
            // Show default preview even when no images are added yet
            val ts = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US).format(java.util.Date())
            "flashable_dd_v1_${ts}_${selectedCompression}.zip"
        }

        // Show preview as helper text below the custom filename input field
        val layout = findViewById<com.google.android.material.textfield.TextInputLayout>(R.id.layoutCustomFilename)
        layout?.helperText = fileName
    }

    private fun copyPendingRemovals() {
        // Cleanup input dir for removed images
        inputDir.listFiles()?.forEach { file ->
            if (file.name.endsWith(".img") && !imageFiles.any { it.second == file.absolutePath }) {
                file.delete()
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  Lifecycle
    // ═══════════════════════════════════════════════════════════════

    override fun onResume() {
        super.onResume()
        activityRef = WeakReference(this)
        // Restore persisted log text on Activity recreation
        if (savedLogText.isNotEmpty()) {
            val textView = findViewById<android.widget.TextView>(R.id.textViewLog)
            if (textView != null && textView.text.isEmpty()) {
                textView.text = savedLogText.toString()
            }
        }
        // Check if repack process is actually alive
        if (isRepacking) {
            val elapsed = System.currentTimeMillis() - lastProgressTime
            if (lastProgressTime > 0 && elapsed > DEAD_PROCESS_THRESHOLD_MS) {
                // No progress for > 2 minutes — process was killed by OS
                isRepacking = false
                isExecuting = false
                cancelRepackNotification()
                showLog("\n[ERROR] Repack was interrupted — process killed (idle timeout).\n", LogLevel.ERROR)
                showLog("The device may have entered Doze mode and killed the background process.\n", LogLevel.WARN)
                showLog("Tip: go to Settings → Apps → OTAku → Battery → Unrestricted.\n", LogLevel.INFO)
                setUIExecuting(false)
            } else {
                // Process still alive — reconnect UI
                isExecuting = true
                setUIExecuting(true)
                showLog("[INFO] Repack in progress (returned from background)\n", LogLevel.INFO)
                // Re-create split progress bars with current state
                if (partitionCount > 0) {
                    val savedProgress = partitionProgress.copyOf()
                    val savedIndex = currentPartitionIndex
                    setupSplitProgressBar(partitionNames)
                    savedProgress.copyInto(partitionProgress)
                    currentPartitionIndex = savedIndex
                    val barRow = findViewById<android.widget.LinearLayout>(R.id.progressBarContainer)
                        ?.findViewWithTag<android.widget.LinearLayout>("bar_row")
                    if (barRow != null) {
                        for (i in 0 until partitionCount) {
                            val bar = barRow.getChildAt(i) as? com.google.android.material.progressindicator.LinearProgressIndicator
                            if (bar != null) {
                                bar.isIndeterminate = false
                                bar.progress = partitionProgress[i]
                            }
                        }
                    }
                }
            }
        } else {
            // Repack finished while app was in background — cancel notification
            cancelRepackNotification()
        }
    }

    override fun onPause() {
        super.onPause()
        activityRef = null
    }

    override fun onDestroy() {
        super.onDestroy()
    }

    private fun setupSplitProgressBar(names: List<String>) {
        val count = names.size
        partitionCount = count
        partitionProgress = IntArray(count)
        currentPartitionIndex = -1
        val container = findViewById<android.widget.LinearLayout>(R.id.progressBarContainer) ?: return
        container.removeAllViews()
        container.orientation = android.widget.LinearLayout.VERTICAL
        container.visibility = View.VISIBLE

        // Horizontal row for progress bars
        val barRow = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            tag = "bar_row"
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        // Horizontal row for partition name labels
        val labelRow = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dpToPx(4)
            }
        }

        for (i in 0 until count) {
            val name = names.getOrElse(i) { "" }
            val isLast = (i == count - 1)
            val gap = if (!isLast) dpToPx(4) else 0

            // Progress bar for this partition
            val bar = com.google.android.material.progressindicator.LinearProgressIndicator(this).apply {
                layoutParams = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginEnd = gap
                }
                isIndeterminate = false
                progress = 0
                trackColor = ContextCompat.getColor(this@MainActivity, R.color.md_theme_light_surfaceVariant)
            }
            barRow.addView(bar)

            // Partition name label — use theme attribute for dark mode support
            val tv = android.util.TypedValue()
            theme.resolveAttribute(android.R.attr.textColorSecondary, tv, true)
            val labelColor = ContextCompat.getColor(this@MainActivity, tv.resourceId)

            val label = android.widget.TextView(this).apply {
                text = name
                textSize = 10f
                setTextColor(labelColor)
                gravity = android.view.Gravity.CENTER
                layoutParams = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginEnd = gap
                }
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
            labelRow.addView(label)
        }

        container.addView(barRow)
        container.addView(labelRow)
    }

    private fun dpToPx(dp: Int): Int = (dp * resources.displayMetrics.density).toInt()

    private fun setUIExecuting(executing: Boolean) {
        runOnUiThread {
            findViewById<View>(R.id.buttonExecute)?.isEnabled = !executing
            val container = findViewById<android.widget.LinearLayout>(R.id.progressBarContainer)
            if (executing) {
                container?.visibility = View.VISIBLE
            } else {
                container?.visibility = View.GONE
                container?.removeAllViews()
                partitionCount = 0
                partitionProgress = IntArray(0)
                currentPartitionIndex = -1
                partitionNames = emptyList()
            }
            findViewById<View>(R.id.buttonAddImages)?.isEnabled = !executing
            findViewById<View>(R.id.buttonRemoveAll)?.isEnabled = !executing
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  Log Level System
    // ═══════════════════════════════════════════════════════════════

    private enum class LogLevel(val tag: String, val colorRes: Int) {
        INFO("INFO", R.color.log_info),
        WARN("WARN", R.color.log_warning),
        ERROR("ERR ", R.color.log_error),
        SUCCESS("OK  ", R.color.log_success),
        PLAIN("", 0),
    }

    private fun showLog(text: String, level: LogLevel = LogLevel.PLAIN) {
        // Persist to companion object (survives Activity recreation)
        savedLogText.append(text)

        runOnUiThread {
            val textView = findViewById<android.widget.TextView>(R.id.textViewLog) ?: return@runOnUiThread

            if (level == LogLevel.PLAIN) {
                textView.append(text)
            } else {
                val prefix = "[${level.tag}] "
                val colored = SpannableString("$prefix$text")
                try {
                    colored.setSpan(
                        ForegroundColorSpan(ContextCompat.getColor(this, level.colorRes)),
                        0, prefix.length, SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                } catch (_: Exception) { /* fallback to plain */ }
                textView.append(colored)
            }

            // Scroll to bottom WITHOUT triggering parent NestedScrollView
            val scrollView = findViewById<android.widget.ScrollView>(R.id.scrollViewLog)
            scrollView?.post {
                val child = scrollView.getChildAt(0)
                if (child != null) {
                    val target = child.bottom - scrollView.height
                    scrollView.smoothScrollTo(0, if (target > 0) target else 0)
                }
            }
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
