package com.localllm.app

import android.app.DownloadManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.ui.res.stringResource
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.localllm.app.ui.AppTab
import com.localllm.app.ui.ChatTab
import com.localllm.app.ui.ConsoleTab
import com.localllm.app.ui.DashboardTab
import com.localllm.app.ui.Header
import com.localllm.app.ui.ModelsTab
import com.localllm.app.ui.SettingsTab
import com.localllm.app.ui.UiMessage
import com.localllm.app.ui.sendChatMessage
import java.io.BufferedInputStream
import java.io.File
import java.security.DigestInputStream
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Top-level Activity. All it does is:
 *
 *   1. Own the cross-tab UI state (active tab, model list, chat messages…).
 *   2. Run lifecycle effects (file picker, model directory polling, autostart).
 *   3. Compose the [Header] + tab row + the right tab's composable.
 *
 * All tab contents and presentation logic live in [com.localllm.app.ui].
 */
class MainActivity : ComponentActivity() {

    private fun getModelFile(filename: String): File =
        File(getExternalFilesDir(null), filename)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            val context = LocalContext.current
            val coroutineScope = rememberCoroutineScope()

            var existingModels by remember { mutableStateOf(setOf<String>()) }
            val activeDownloads = remember { mutableStateMapOf<String, Long>() }
            val downloadProgress = remember { mutableStateMapOf<String, Float>() }
            var activeTab by remember { mutableStateOf(AppTab.MODELS) }
            val logs by LogManager.logs.collectAsState(initial = LogEntry(0, "INFO", "Initializing..."))
            val logHistory = remember { mutableStateListOf<LogEntry>() }
            val listState = rememberLazyListState()

            val serverStatus by ServerState.status.collectAsState()
            val serverUrl by ServerState.boundUrl.collectAsState()

            val chatMessages = remember { mutableStateListOf<UiMessage>() }
            var chatInput by remember { mutableStateOf("") }
            var isChatting by remember { mutableStateOf(false) }
            val chatListState = rememberLazyListState()
            var selectedModel by remember { mutableStateOf("") }
            var chatJob by remember { mutableStateOf<Job?>(null) }
            var chatTokenRate by remember { mutableStateOf(0.0) }
            var chatTokenCount by remember { mutableStateOf(0) }
            var chatElapsedMs by remember { mutableStateOf(0L) }

            var customUrls by remember { mutableStateOf(Settings.customModelUrls(context)) }

            LaunchedEffect(existingModels) {
                if (selectedModel.isEmpty() && existingModels.isNotEmpty()) {
                    selectedModel = existingModels.first().removeSuffix(".litertlm")
                }
            }

            LaunchedEffect(logs) {
                logHistory.add(logs)
                if (logHistory.size > 200) logHistory.removeAt(0)
                if (activeTab == AppTab.CONSOLE && logHistory.isNotEmpty()) {
                    listState.animateScrollToItem(logHistory.size - 1)
                }
            }

            // Disk poll for newly imported/downloaded models — only on the tabs
            // that display the list. One immediate refresh on tab switch so the
            // user sees fresh data without waiting.
            LaunchedEffect(activeTab) {
                refreshExistingModels(context) { existingModels = it }
                if (activeTab == AppTab.MODELS || activeTab == AppTab.CHAT) {
                    while (true) {
                        delay(2_000)
                        refreshExistingModels(context) { existingModels = it }
                    }
                }
            }

            LaunchedEffect(activeDownloads.toMap()) {
                val manager = context.getSystemService(DownloadManager::class.java)
                while (activeDownloads.isNotEmpty()) {
                    val toRemove = mutableListOf<String>()
                    for ((filename, downloadId) in activeDownloads) {
                        val query = DownloadManager.Query().setFilterById(downloadId)
                        val cursor = manager.query(query)
                        if (cursor.moveToFirst()) {
                            val statusIdx = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
                            val downloadedIdx = cursor.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                            val totalIdx = cursor.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
                            if (statusIdx != -1) {
                                val status = cursor.getInt(statusIdx)
                                if (status == DownloadManager.STATUS_SUCCESSFUL || status == DownloadManager.STATUS_FAILED) {
                                    toRemove.add(filename)
                                    if (status == DownloadManager.STATUS_SUCCESSFUL) {
                                        // Hashing a multi-GB file is slow (~20s); run off the
                                        // polling loop so progress for other downloads keeps
                                        // updating. Verification is a no-op when sha256 == null.
                                        coroutineScope.launch {
                                            val ok = withContext(Dispatchers.IO) {
                                                verifyDownloadedModel(filename)
                                            }
                                            if (ok == false) {
                                                existingModels = existingModels - filename
                                                Toast.makeText(
                                                    context,
                                                    "Model verification failed: $filename",
                                                    Toast.LENGTH_LONG
                                                ).show()
                                            }
                                        }
                                    }
                                }
                            }
                            if (downloadedIdx != -1 && totalIdx != -1) {
                                val downloaded = cursor.getLong(downloadedIdx)
                                val total = cursor.getLong(totalIdx)
                                if (total > 0) downloadProgress[filename] = downloaded.toFloat() / total
                            }
                        } else {
                            toRemove.add(filename)
                        }
                        cursor.close()
                    }
                    toRemove.forEach {
                        activeDownloads.remove(it)
                        downloadProgress.remove(it)
                    }
                    delay(1_000)
                }
            }

            var hasNotificationPermission by remember {
                mutableStateOf(
                    if (Build.VERSION.SDK_INT >= 33) {
                        ContextCompat.checkSelfPermission(context, "android.permission.POST_NOTIFICATIONS") == PackageManager.PERMISSION_GRANTED
                    } else true
                )
            }

            val permLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.RequestPermission(),
                onResult = { hasNotificationPermission = it }
            )

            val filePickerLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.OpenDocument()
            ) { uri: Uri? ->
                if (uri != null) {
                    coroutineScope.launch {
                        try {
                            val cursor = context.contentResolver.query(uri, null, null, null, null)
                            val nameIndex = cursor?.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                            cursor?.moveToFirst()
                            var name = if (nameIndex != null && nameIndex >= 0)
                                cursor?.getString(nameIndex) ?: "imported_model.litertlm"
                            else "imported_model.litertlm"
                            cursor?.close()
                            if (!name.endsWith(".litertlm")) name = "$name.litertlm"

                            val destFile = getModelFile(name)
                            context.contentResolver.openInputStream(uri)?.use { input ->
                                destFile.outputStream().use { output -> input.copyTo(output) }
                            }
                            existingModels = existingModels + name
                            LogManager.i("FilePicker", "Imported $name")
                        } catch (e: Exception) {
                            LogManager.e("FilePicker", "Failed to import model", e)
                        }
                    }
                }
            }

            // Autostart sequence: request notification permission first, then
            // (when granted and a model is present) start the server.
            LaunchedEffect(existingModels.isNotEmpty(), hasNotificationPermission) {
                if (!hasNotificationPermission && Build.VERSION.SDK_INT >= 33) {
                    permLauncher.launch("android.permission.POST_NOTIFICATIONS")
                } else if (Settings.autostart(context) &&
                    existingModels.isNotEmpty() &&
                    serverStatus == ServerState.Status.STOPPED
                ) {
                    startServer()
                }
            }

            MaterialTheme(colorScheme = DarkColors) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        Header(
                            status = serverStatus,
                            url = serverUrl,
                            onCopyUrl = {
                                val url = serverUrl ?: return@Header
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.app_name), url))
                                Toast.makeText(context, getString(R.string.toast_url_copied, url), Toast.LENGTH_SHORT).show()
                            }
                        )

                        ScrollableTabRow(
                            selectedTabIndex = activeTab.ordinal,
                            containerColor = MaterialTheme.colorScheme.surface,
                            contentColor = MaterialTheme.colorScheme.primary,
                            edgePadding = 0.dp
                        ) {
                            AppTab.values().forEach { t ->
                                Tab(
                                    selected = activeTab == t,
                                    onClick = { activeTab = t },
                                    text = {
                                        Text(
                                            stringResource(t.labelRes),
                                            maxLines = 1,
                                            softWrap = false
                                        )
                                    }
                                )
                            }
                        }

                        Box(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                            when (activeTab) {
                                AppTab.MODELS -> ModelsTab(
                                    builtIn = AVAILABLE_MODELS,
                                    customUrls = customUrls,
                                    existingModels = existingModels,
                                    activeDownloads = activeDownloads,
                                    downloadProgress = downloadProgress,
                                    onDownload = { model ->
                                        activeDownloads[model.filename] = startDownload(context, model)
                                    },
                                    onDelete = { model ->
                                        getModelFile(model.filename).delete()
                                        existingModels = existingModels - model.filename
                                    },
                                    onImport = { filePickerLauncher.launch(arrayOf("*/*")) }
                                )
                                AppTab.DASHBOARD -> DashboardTab(coroutineScope = coroutineScope)
                                AppTab.CONSOLE -> ConsoleTab(logHistory = logHistory, listState = listState)
                                AppTab.CHAT -> ChatTab(
                                    existingModels = existingModels,
                                    selectedModel = selectedModel,
                                    onModelChange = { selectedModel = it },
                                    chatMessages = chatMessages,
                                    chatInput = chatInput,
                                    onInputChange = { chatInput = it },
                                    isChatting = isChatting,
                                    onSend = { systemPrompt ->
                                        val toSend = chatInput.trim()
                                        val url = ServerState.boundUrl.value
                                        if (toSend.isNotEmpty() && !isChatting && url != null) {
                                            chatInput = ""
                                            chatTokenRate = 0.0
                                            chatTokenCount = 0
                                            chatElapsedMs = 0L
                                            chatJob = sendChatMessage(
                                                messages = chatMessages,
                                                input = toSend,
                                                model = selectedModel,
                                                baseUrl = url,
                                                apiKey = Settings.apiKey(context),
                                                coroutineScope = coroutineScope,
                                                listState = chatListState,
                                                onChattingChange = { isChatting = it },
                                                systemPrompt = systemPrompt,
                                                onTokenRate = { rate, count, elapsed ->
                                                    chatTokenRate = rate
                                                    chatTokenCount = count
                                                    chatElapsedMs = elapsed
                                                }
                                            )
                                        }
                                    },
                                    onStop = {
                                        chatJob?.cancel()
                                    },
                                    chatListState = chatListState,
                                    tokenRate = chatTokenRate,
                                    tokenCount = chatTokenCount,
                                    streamElapsedMs = chatElapsedMs
                                )
                                AppTab.SETTINGS -> SettingsTab(
                                    context = context,
                                    serverStatus = serverStatus,
                                    customUrls = customUrls,
                                    onCustomUrlsChange = {
                                        customUrls = it
                                        Settings.setCustomModelUrls(context, it)
                                    },
                                    onStartServer = { startServer() },
                                    onStopServer = { stopServer() }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    private fun startDownload(context: Context, model: ModelInfo): Long {
        val file = getModelFile(model.filename)
        file.parentFile?.mkdirs()

        val request = DownloadManager.Request(Uri.parse(model.url))
            .setTitle("Downloading ${model.name}")
            .setDescription("Required for LocalLLM Service")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(context, null, model.filename)
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(true)

        val manager = context.getSystemService(DownloadManager::class.java)
        return manager.enqueue(request)
    }

    private fun startServer() {
        startForegroundService(Intent(this, LLMServerService::class.java))
    }

    private fun stopServer() {
        stopService(Intent(this, LLMServerService::class.java))
    }

    /**
     * Returns `true` on hash match, `false` on mismatch (file deleted as a
     * side effect), `null` when no expected hash is recorded (custom URLs).
     * Caller is responsible for surfacing the mismatch to the user and
     * removing the entry from in-memory `existingModels`.
     */
    private fun verifyDownloadedModel(filename: String): Boolean? {
        val expected = AVAILABLE_MODELS.firstOrNull { it.filename == filename }?.sha256
        if (expected == null) {
            LogManager.w("DownloadVerify", "$filename has no expected hash; skipping verification")
            return null
        }
        val file = getModelFile(filename)
        if (!file.exists()) {
            LogManager.e("DownloadVerify", "$filename missing on disk; cannot verify")
            return false
        }
        val digest = MessageDigest.getInstance("SHA-256")
        DigestInputStream(BufferedInputStream(file.inputStream(), 64 * 1024), digest).use { input ->
            val buf = ByteArray(64 * 1024)
            while (input.read(buf) != -1) { /* digest updated as a side effect */ }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        return if (actual == expected.lowercase()) {
            LogManager.i("DownloadVerify", "Verified $filename (sha256 match)")
            true
        } else {
            LogManager.e(
                "DownloadVerify",
                "Hash mismatch for $filename: expected=$expected actual=$actual"
            )
            file.delete()
            false
        }
    }
}

private inline fun refreshExistingModels(context: Context, update: (Set<String>) -> Unit) {
    val dir = context.getExternalFilesDir(null)
    val files = dir?.listFiles { file -> file.name.endsWith(".litertlm") } ?: emptyArray()
    update(files.map { it.name }.toSet())
}
