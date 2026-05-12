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

    var port by remember { mutableStateOf(Settings.port(context).toString()) }
    var maxTokens by remember { mutableStateOf(Settings.maxTokens(context).toString()) }
    var temperature by remember { mutableStateOf(Settings.temperature(context)) }
    var topK by remember { mutableStateOf(Settings.topK(context)) }
    var bindLan by remember { mutableStateOf(Settings.bindLan(context)) }
    var startOnBoot by remember { mutableStateOf(Settings.startOnBoot(context)) }
    var autostart by remember { mutableStateOf(Settings.autostart(context)) }
    var urlsText by remember { mutableStateOf(customUrls.joinToString("\n")) }

    var requestTimeoutSec by remember { mutableStateOf((Settings.requestTimeoutMs(context) / 1000).toString()) }
    var maxQueueDepth by remember { mutableStateOf(Settings.maxQueueDepth(context).toString()) }
    var maxPromptChars by remember { mutableStateOf(Settings.maxPromptChars(context).toString()) }
    var apiKey by remember { mutableStateOf(Settings.apiKey(context)) }
    var keepAwake by remember { mutableStateOf(Settings.keepAwake(context)) }
    var idleEvictMin by remember { mutableStateOf((Settings.idleEvictMs(context) / 60_000L).toString()) }
    var idleStopMin by remember { mutableStateOf((Settings.idleStopMs(context) / 60_000L).toString()) }
    var backend by remember { mutableStateOf(Settings.backend(context)) }
    var allowCors by remember { mutableStateOf(Settings.allowCors(context)) }

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
                    sanitized.toIntOrNull()?.let { v -> Settings.setPort(context, v) }
                },
                label = stringResource(R.string.settings_port)
            )
            SettingRow(
                label = stringResource(R.string.settings_bind_lan),
                subtitle = stringResource(R.string.settings_bind_lan_subtitle),
                checked = bindLan,
                onCheckedChange = {
                    bindLan = it
                    Settings.setBindLan(context, it)
                }
            )
            SettingRow(
                label = stringResource(R.string.settings_cors),
                subtitle = stringResource(R.string.settings_cors_subtitle),
                checked = allowCors,
                onCheckedChange = {
                    allowCors = it
                    Settings.setAllowCors(context, it)
                }
            )
        }

        SectionCard(stringResource(R.string.settings_section_inference)) {
            HintText(stringResource(R.string.settings_inference_hint))

            Text(stringResource(R.string.settings_backend), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp))
            BackendSelector(
                selected = backend,
                onSelect = {
                    backend = it
                    Settings.setBackend(context, it)
                }
            )
            HintText(stringResource(R.string.settings_backend_hint))

            NumberField(
                value = maxTokens,
                onValueChange = {
                    val sanitized = it.filter { c -> c.isDigit() }.take(5)
                    maxTokens = sanitized
                    sanitized.toIntOrNull()?.let { v -> Settings.setMaxTokens(context, v) }
                },
                label = stringResource(R.string.settings_max_tokens)
            )
            Text(stringResource(R.string.settings_temperature, "%.2f".format(temperature)), style = MaterialTheme.typography.bodyMedium)
            Slider(
                value = temperature,
                onValueChange = { temperature = it },
                onValueChangeFinished = { Settings.setTemperature(context, temperature) },
                valueRange = 0f..2f,
                steps = 39
            )
            Text(stringResource(R.string.settings_top_k, topK), style = MaterialTheme.typography.bodyMedium)
            Slider(
                value = topK.toFloat(),
                onValueChange = { topK = it.toInt() },
                onValueChangeFinished = { Settings.setTopK(context, topK) },
                valueRange = 1f..100f,
                steps = 98
            )
        }

        SectionCard(stringResource(R.string.settings_section_startup)) {
            SettingRow(
                label = stringResource(R.string.settings_boot),
                subtitle = stringResource(R.string.settings_boot_subtitle),
                checked = startOnBoot,
                onCheckedChange = {
                    startOnBoot = it
                    Settings.setStartOnBoot(context, it)
                }
            )
            SettingRow(
                label = stringResource(R.string.settings_autostart),
                subtitle = stringResource(R.string.settings_autostart_subtitle),
                checked = autostart,
                onCheckedChange = {
                    autostart = it
                    Settings.setAutostart(context, it)
                }
            )
        }

        SectionCard(stringResource(R.string.settings_section_limits)) {
            HintText(stringResource(R.string.settings_limits_hint))
            NumberField(
                value = requestTimeoutSec,
                onValueChange = {
                    val sanitized = it.filter { c -> c.isDigit() }.take(4)
                    requestTimeoutSec = sanitized
                    sanitized.toLongOrNull()?.let { v -> Settings.setRequestTimeoutMs(context, v * 1000L) }
                },
                label = stringResource(R.string.settings_timeout)
            )
            NumberField(
                value = maxQueueDepth,
                onValueChange = {
                    val sanitized = it.filter { c -> c.isDigit() }.take(3)
                    maxQueueDepth = sanitized
                    sanitized.toIntOrNull()?.let { v -> Settings.setMaxQueueDepth(context, v) }
                },
                label = stringResource(R.string.settings_queue_depth)
            )
            NumberField(
                value = maxPromptChars,
                onValueChange = {
                    val sanitized = it.filter { c -> c.isDigit() }.take(8)
                    maxPromptChars = sanitized
                    sanitized.toIntOrNull()?.let { v -> Settings.setMaxPromptChars(context, v) }
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
                    sanitized.toLongOrNull()?.let { v -> Settings.setIdleEvictMs(context, v * 60_000L) }
                },
                label = stringResource(R.string.settings_idle_evict)
            )
            NumberField(
                value = idleStopMin,
                onValueChange = {
                    val sanitized = it.filter { c -> c.isDigit() }.take(4)
                    idleStopMin = sanitized
                    sanitized.toLongOrNull()?.let { v -> Settings.setIdleStopMs(context, v * 60_000L) }
                },
                label = stringResource(R.string.settings_idle_stop)
            )
            SettingRow(
                label = stringResource(R.string.settings_keep_awake),
                subtitle = stringResource(R.string.settings_keep_awake_subtitle),
                checked = keepAwake,
                onCheckedChange = {
                    keepAwake = it
                    Settings.setKeepAwake(context, it)
                }
            )
        }

        SectionCard(stringResource(R.string.settings_section_security)) {
            HintText(stringResource(R.string.settings_security_hint))
            OutlinedTextField(
                value = apiKey,
                onValueChange = {
                    apiKey = it
                    Settings.setApiKey(context, it)
                },
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
                    apiKey = key
                    Settings.setApiKey(context, key)
                }) { Text(stringResource(R.string.settings_generate)) }
                OutlinedButton(
                    enabled = apiKey.isNotEmpty(),
                    onClick = {
                        apiKey = ""
                        Settings.setApiKey(context, "")
                    }
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
