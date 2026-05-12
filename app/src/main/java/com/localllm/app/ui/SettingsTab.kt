package com.localllm.app.ui

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.localllm.app.R
import com.localllm.app.ServerState
import com.localllm.app.Settings
import com.localllm.app.SettingsRepository

@Composable
fun SettingsTab(
    context: Context,
    serverStatus: ServerState.Status,
    customUrls: List<String>,
    onCustomUrlsChange: (List<String>) -> Unit,
    onStartServer: () -> Unit,
    onStopServer: () -> Unit
) {
    val scroll = rememberScrollState()
    val repo = remember(context) { SettingsRepository.get(context) }

    // Observe each preference as a StateFlow — no more disk reads on recompose.
    val portValue by repo.port.collectAsState()
    val maxTokensValue by repo.maxTokens.collectAsState()
    val temperatureValue by repo.temperature.collectAsState()
    val topKValue by repo.topK.collectAsState()
    val bindLan by repo.bindLan.collectAsState()
    val startOnBoot by repo.startOnBoot.collectAsState()
    val autostart by repo.autostart.collectAsState()

    val requestTimeoutMs by repo.requestTimeoutMs.collectAsState()
    val maxQueueDepthValue by repo.maxQueueDepth.collectAsState()
    val maxPromptCharsValue by repo.maxPromptChars.collectAsState()
    val apiKey by repo.apiKey.collectAsState()
    val keepAwake by repo.keepAwake.collectAsState()
    val idleEvictMs by repo.idleEvictMs.collectAsState()
    val idleStopMs by repo.idleStopMs.collectAsState()
    val backend by repo.backend.collectAsState()
    val allowCors by repo.allowCors.collectAsState()

    // For text fields whose user-facing value is a string buffer separate from
    // the stored numeric value (mid-typing the field may hold something like
    // "" or "12" that doesn't yet parse to a valid clamped int), keep a local
    // editable mirror seeded from the flow. We deliberately do NOT re-sync
    // the buffer back from the flow on every emission — otherwise typing "9"
    // (port 9) would immediately get clamped to "1024" mid-keystroke.
    var port by remember { mutableStateOf(portValue.toString()) }
    var maxTokens by remember { mutableStateOf(maxTokensValue.toString()) }
    var urlsText by remember { mutableStateOf(customUrls.joinToString("\n")) }
    var requestTimeoutSec by remember { mutableStateOf((requestTimeoutMs / 1000).toString()) }
    var maxQueueDepth by remember { mutableStateOf(maxQueueDepthValue.toString()) }
    var maxPromptChars by remember { mutableStateOf(maxPromptCharsValue.toString()) }
    var idleEvictMin by remember { mutableStateOf((idleEvictMs / 60_000L).toString()) }
    var idleStopMin by remember { mutableStateOf((idleStopMs / 60_000L).toString()) }

    // Slider drags need a local mirror so the thumb tracks the finger without
    // every position change forcing a disk write. We push to the repo only on
    // release (onValueChangeFinished), matching the original semantics.
    var temperature by remember { mutableStateOf(temperatureValue) }
    var topK by remember { mutableStateOf(topKValue) }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(scroll),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        SectionCard(stringResource(R.string.settings_section_server)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onStartServer,
                    enabled = serverStatus != ServerState.Status.RUNNING && serverStatus != ServerState.Status.STARTING
                ) {
                    Text(stringResource(
                        if (serverStatus == ServerState.Status.ERROR) R.string.settings_retry
                        else R.string.settings_start
                    ))
                }
                Button(
                    onClick = onStopServer,
                    enabled = serverStatus == ServerState.Status.RUNNING,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) { Text(stringResource(R.string.settings_stop)) }
            }
            HintText(stringResource(R.string.settings_restart_hint))
        }

        SectionCard(stringResource(R.string.settings_section_network)) {
            NumberField(
                value = port,
                onValueChange = {
                    val sanitized = it.filter { c -> c.isDigit() }.take(5)
                    port = sanitized
                    sanitized.toIntOrNull()?.let { v -> repo.setPort(v) }
                },
                label = stringResource(R.string.settings_port)
            )
            SettingRow(
                label = stringResource(R.string.settings_bind_lan),
                subtitle = stringResource(R.string.settings_bind_lan_subtitle),
                checked = bindLan,
                onCheckedChange = { repo.setBindLan(it) }
            )
            SettingRow(
                label = stringResource(R.string.settings_cors),
                subtitle = stringResource(R.string.settings_cors_subtitle),
                checked = allowCors,
                onCheckedChange = { repo.setAllowCors(it) }
            )
        }

        SectionCard(stringResource(R.string.settings_section_inference)) {
            HintText(stringResource(R.string.settings_inference_hint))

            Text(stringResource(R.string.settings_backend), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp))
            BackendSelector(
                selected = backend,
                onSelect = { repo.setBackend(it) }
            )
            HintText(stringResource(R.string.settings_backend_hint))

            NumberField(
                value = maxTokens,
                onValueChange = {
                    val sanitized = it.filter { c -> c.isDigit() }.take(5)
                    maxTokens = sanitized
                    sanitized.toIntOrNull()?.let { v -> repo.setMaxTokens(v) }
                },
                label = stringResource(R.string.settings_max_tokens)
            )
            Text(stringResource(R.string.settings_temperature, "%.2f".format(temperature)), style = MaterialTheme.typography.bodyMedium)
            Slider(
                value = temperature,
                onValueChange = { temperature = it },
                onValueChangeFinished = { repo.setTemperature(temperature) },
                valueRange = 0f..2f,
                steps = 39
            )
            Text(stringResource(R.string.settings_top_k, topK), style = MaterialTheme.typography.bodyMedium)
            Slider(
                value = topK.toFloat(),
                onValueChange = { topK = it.toInt() },
                onValueChangeFinished = { repo.setTopK(topK) },
                valueRange = 1f..100f,
                steps = 98
            )
        }

        SectionCard(stringResource(R.string.settings_section_startup)) {
            SettingRow(
                label = stringResource(R.string.settings_boot),
                subtitle = stringResource(R.string.settings_boot_subtitle),
                checked = startOnBoot,
                onCheckedChange = { repo.setStartOnBoot(it) }
            )
            SettingRow(
                label = stringResource(R.string.settings_autostart),
                subtitle = stringResource(R.string.settings_autostart_subtitle),
                checked = autostart,
                onCheckedChange = { repo.setAutostart(it) }
            )
        }

        SectionCard(stringResource(R.string.settings_section_limits)) {
            HintText(stringResource(R.string.settings_limits_hint))
            NumberField(
                value = requestTimeoutSec,
                onValueChange = {
                    val sanitized = it.filter { c -> c.isDigit() }.take(4)
                    requestTimeoutSec = sanitized
                    sanitized.toLongOrNull()?.let { v -> repo.setRequestTimeoutMs(v * 1000L) }
                },
                label = stringResource(R.string.settings_timeout)
            )
            NumberField(
                value = maxQueueDepth,
                onValueChange = {
                    val sanitized = it.filter { c -> c.isDigit() }.take(3)
                    maxQueueDepth = sanitized
                    sanitized.toIntOrNull()?.let { v -> repo.setMaxQueueDepth(v) }
                },
                label = stringResource(R.string.settings_queue_depth)
            )
            NumberField(
                value = maxPromptChars,
                onValueChange = {
                    val sanitized = it.filter { c -> c.isDigit() }.take(8)
                    maxPromptChars = sanitized
                    sanitized.toIntOrNull()?.let { v -> repo.setMaxPromptChars(v) }
                },
                label = stringResource(R.string.settings_prompt_chars)
            )
        }

        SectionCard(stringResource(R.string.settings_section_background)) {
            HintText(stringResource(R.string.settings_background_hint))
            NumberField(
                value = idleEvictMin,
                onValueChange = {
                    val sanitized = it.filter { c -> c.isDigit() }.take(4)
                    idleEvictMin = sanitized
                    sanitized.toLongOrNull()?.let { v -> repo.setIdleEvictMs(v * 60_000L) }
                },
                label = stringResource(R.string.settings_idle_evict)
            )
            NumberField(
                value = idleStopMin,
                onValueChange = {
                    val sanitized = it.filter { c -> c.isDigit() }.take(4)
                    idleStopMin = sanitized
                    sanitized.toLongOrNull()?.let { v -> repo.setIdleStopMs(v * 60_000L) }
                },
                label = stringResource(R.string.settings_idle_stop)
            )
            SettingRow(
                label = stringResource(R.string.settings_keep_awake),
                subtitle = stringResource(R.string.settings_keep_awake_subtitle),
                checked = keepAwake,
                onCheckedChange = { repo.setKeepAwake(it) }
            )
        }

        SectionCard(stringResource(R.string.settings_section_security)) {
            HintText(stringResource(R.string.settings_security_hint))
            OutlinedTextField(
                value = apiKey,
                onValueChange = { repo.setApiKey(it) },
                label = { Text(stringResource(R.string.settings_api_key)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text(stringResource(R.string.settings_api_key_placeholder)) }
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    val random = java.security.SecureRandom()
                    val bytes = ByteArray(16).also { random.nextBytes(it) }
                    val key = bytes.joinToString("") { "%02x".format(it) }
                    repo.setApiKey(key)
                }) { Text(stringResource(R.string.settings_generate)) }
                OutlinedButton(
                    enabled = apiKey.isNotEmpty(),
                    onClick = { repo.setApiKey("") }
                ) { Text(stringResource(R.string.settings_clear)) }
            }
        }

        SectionCard(stringResource(R.string.settings_section_custom_urls)) {
            HintText(stringResource(R.string.settings_urls_hint))
            OutlinedTextField(
                value = urlsText,
                onValueChange = {
                    urlsText = it
                    val parsed = it.split("\n").map { s -> s.trim() }.filter { s -> s.isNotEmpty() }
                    onCustomUrlsChange(parsed)
                },
                label = { Text(stringResource(R.string.settings_urls_label)) },
                modifier = Modifier.fillMaxWidth().height(120.dp),
                placeholder = { Text(stringResource(R.string.settings_urls_placeholder)) }
            )
        }

        Spacer(modifier = Modifier.height(8.dp))
    }
}

/* ------------------------------------------------------------------ */
/* Reusable building blocks                                            */
/* ------------------------------------------------------------------ */

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            content()
        }
    }
}

@Composable
private fun HintText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.secondary
    )
}

@Composable
private fun NumberField(value: String, onValueChange: (String) -> Unit, label: String) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun BackendSelector(selected: String, onSelect: (String) -> Unit) {
    val options = listOf(
        Settings.BACKEND_AUTO to stringResource(R.string.settings_backend_auto),
        Settings.BACKEND_CPU to stringResource(R.string.settings_backend_cpu),
        Settings.BACKEND_GPU to stringResource(R.string.settings_backend_gpu)
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        options.forEach { (value, label) ->
            val isSel = selected == value
            Button(
                onClick = { onSelect(value) },
                modifier = Modifier.weight(1f),
                colors = if (isSel) ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                ) else ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = MaterialTheme.colorScheme.onSurface
                )
            ) { Text(label) }
        }
    }
}

@Composable
private fun SettingRow(
    label: String,
    subtitle: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary
                )
            }
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
