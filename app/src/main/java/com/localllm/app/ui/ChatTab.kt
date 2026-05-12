package com.localllm.app.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.localllm.app.AVAILABLE_MODELS
import com.localllm.app.LogManager
import com.localllm.app.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Translate a bare model filename (or id) into a friendly label using the
 * built-in [AVAILABLE_MODELS] catalog. Custom / unknown models fall back to
 * the bare filename so the user still sees a stable identifier.
 */
fun displayLabelFor(filename: String): String {
    val bare = filename.removeSuffix(".litertlm").removeSuffix(".task")
    val known = AVAILABLE_MODELS.firstOrNull { it.id == bare }
    return known?.name ?: bare
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ChatTab(
    existingModels: Set<String>,
    selectedModel: String,
    onModelChange: (String) -> Unit,
    chatMessages: List<UiMessage>,
    chatInput: String,
    onInputChange: (String) -> Unit,
    isChatting: Boolean,
    onSend: (systemPrompt: String) -> Unit,
    onStop: () -> Unit,
    chatListState: LazyListState,
    tokenRate: Double = 0.0,
    tokenCount: Int = 0,
    streamElapsedMs: Long = 0L
) {
    Column(modifier = Modifier.fillMaxSize()) {
        if (existingModels.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.chat_needs_model), color = MaterialTheme.colorScheme.error)
            }
            return@Column
        }

        var expanded by remember { mutableStateOf(false) }
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = !expanded },
            modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp)
        ) {
            OutlinedTextField(
                value = displayLabelFor(selectedModel),
                onValueChange = {},
                readOnly = true,
                label = { Text(stringResource(R.string.chat_active_model)) },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                modifier = Modifier.menuAnchor().fillMaxWidth(),
                colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors()
            )
            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                existingModels.forEach { modelName ->
                    DropdownMenuItem(
                        text = { Text(displayLabelFor(modelName)) },
                        onClick = {
                            onModelChange(modelName.removeSuffix(".litertlm").removeSuffix(".task"))
                            expanded = false
                        }
                    )
                }
            }
        }

        // Live streaming subtitle: only visible while a request is in flight.
        if (isChatting) {
            val subtitle = if (streamElapsedMs > 200L && tokenCount > 0) {
                "streaming — %.1f tok/s · %d tokens".format(tokenRate, tokenCount)
            } else {
                "streaming…"
            }
            Text(
                text = subtitle,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.secondary,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 4.dp, bottom = 4.dp)
                    .semantics { contentDescription = subtitle }
            )
        }

        // Collapsible system prompt section, with rotating chevron-ish icon
        // (we rotate the Tune icon 90° when expanded for a tactile feel).
        var systemPromptVisible by remember { mutableStateOf(false) }
        var systemPrompt by remember { mutableStateOf("") }
        val rotation by animateFloatAsState(
            targetValue = if (systemPromptVisible) 90f else 0f,
            animationSpec = tween(durationMillis = 200),
            label = "system-prompt-toggle"
        )
        val systemToggleLabel = if (systemPromptVisible) {
            stringResource(R.string.chat_hide_system_prompt)
        } else {
            stringResource(R.string.chat_show_system_prompt)
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(
                onClick = { systemPromptVisible = !systemPromptVisible },
                modifier = Modifier
                    .defaultMinSize(minHeight = 48.dp)
                    .semantics { contentDescription = systemToggleLabel }
            ) {
                Icon(
                    imageVector = Icons.Outlined.Tune,
                    contentDescription = null,
                    modifier = Modifier
                        .size(18.dp)
                        .rotate(rotation)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(systemToggleLabel)
            }
        }
        if (systemPromptVisible) {
            OutlinedTextField(
                value = systemPrompt,
                onValueChange = { systemPrompt = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                label = { Text(stringResource(R.string.chat_system_prompt_label)) },
                placeholder = { Text(stringResource(R.string.chat_system_prompt_placeholder)) },
                maxLines = 4
            )
        }

        // Track the size of the last appended chunk on the streaming assistant
        // message so ChatBubble can fade the trailing delta in. Uses a
        // SideEffect to mutate after composition completes, avoiding the
        // "writing state during composition" footgun.
        val lastAssistantLengthState = remember { androidx.compose.runtime.mutableStateOf(0) }
        val lastDeltaState = remember { androidx.compose.runtime.mutableStateOf(0) }
        val streamingAssistantIndex = chatMessages.indexOfLast { it.role == "assistant" }
        val currentAssistantLength =
            if (isChatting && streamingAssistantIndex >= 0) chatMessages[streamingAssistantIndex].content.length
            else 0
        androidx.compose.runtime.SideEffect {
            if (isChatting && currentAssistantLength > lastAssistantLengthState.value) {
                lastDeltaState.value = currentAssistantLength - lastAssistantLengthState.value
                lastAssistantLengthState.value = currentAssistantLength
            } else if (!isChatting) {
                lastAssistantLengthState.value = 0
                lastDeltaState.value = 0
            }
        }
        val lastDelta = lastDeltaState.value

        LazyColumn(
            state = chatListState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface, shape = MaterialTheme.shapes.medium)
                .padding(8.dp)
        ) {
            if (chatMessages.isEmpty()) {
                item {
                    ChatEmptyState(onPromptSelected = onInputChange)
                }
            }
            itemsIndexed(chatMessages) { index, msg ->
                val isStreaming = isChatting && index == streamingAssistantIndex && msg.role == "assistant"
                ChatBubble(
                    msg = msg,
                    isStreaming = isStreaming,
                    lastDeltaLength = if (isStreaming) lastDelta else 0
                )
            }
            if (isChatting) {
                item {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp).padding(8.dp),
                        strokeWidth = 2.dp
                    )
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = chatInput,
                onValueChange = onInputChange,
                modifier = Modifier.weight(1f),
                placeholder = { Text(stringResource(R.string.chat_placeholder)) },
                enabled = !isChatting,
                maxLines = 3
            )
            Spacer(modifier = Modifier.width(8.dp))
            if (isChatting) {
                Button(
                    onClick = onStop,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer
                    ),
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .semantics { contentDescription = "Stop streaming" }
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Stop,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(stringResource(R.string.chat_stop))
                }
            } else {
                Button(
                    onClick = { onSend(systemPrompt) },
                    enabled = chatInput.isNotBlank(),
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .semantics { contentDescription = "Send message" }
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Outlined.Send,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(stringResource(R.string.chat_send))
                }
            }
        }
    }
}

/**
 * In-app chat client. Speaks the same OpenAI-compatible API the server exposes
 * by hitting the local server. Streams via SSE and appends to [messages] in
 * place so the Chat list recomposes incrementally.
 *
 * Returns the launched [Job] so the caller can `.cancel()` it (used by the
 * Stop button to abort an in-flight request).
 */
fun sendChatMessage(
    messages: MutableList<UiMessage>,
    input: String,
    model: String,
    baseUrl: String,
    apiKey: String,
    coroutineScope: CoroutineScope,
    listState: LazyListState,
    onChattingChange: (Boolean) -> Unit,
    systemPrompt: String = "",
    onTokenRate: (rate: Double, count: Int, elapsedMs: Long) -> Unit = { _, _, _ -> }
): Job {
    onChattingChange(true)
    messages.add(UiMessage("user", input))
    val assistantIndex = messages.size
    messages.add(UiMessage("assistant", ""))

    val job = coroutineScope.launch {
        var startTimeMs = 0L
        var tokenCount = 0
        var eventSource: EventSource? = null
        try {
            val client = OkHttpClient.Builder()
                .readTimeout(10, TimeUnit.MINUTES)
                .build()

            val reqBody = JSONObject().apply {
                put("model", model)
                put("stream", true)
                put("session_id", "local_chat_test")

                val msgArray = org.json.JSONArray()
                if (systemPrompt.isNotBlank()) {
                    val sys = JSONObject()
                    sys.put("role", "system")
                    sys.put("content", systemPrompt)
                    msgArray.put(sys)
                }
                for (i in 0 until messages.size - 1) {
                    val m = JSONObject()
                    m.put("role", messages[i].role)
                    m.put("content", messages[i].content)
                    msgArray.put(m)
                }
                put("messages", msgArray)
            }.toString().toRequestBody("application/json".toMediaType())

            val request = Request.Builder()
                .url("${baseUrl.removeSuffix("/")}/v1/chat/completions")
                .post(reqBody).apply {
                    if (apiKey.isNotEmpty()) {
                        header("Authorization", "Bearer $apiKey")
                    }
                }
                .build()

            val factory = EventSources.createFactory(client)

            // Suspend until the SSE stream terminates (DONE / failure / close)
            // so that this coroutine's lifetime maps to the request lifetime.
            // Cancelling the Job closes the underlying OkHttp call.
            suspendCancellableCoroutine<Unit> { cont ->
                val source = factory.newEventSource(request, object : EventSourceListener() {
                    @Volatile private var done = false
                    private fun finishOnce() {
                        if (done) return
                        done = true
                        if (cont.isActive) cont.resume(Unit)
                    }

                    override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                        if (data == "[DONE]") {
                            finishOnce()
                            return
                        }
                        try {
                            val obj = JSONObject(data)
                            val choices = obj.optJSONArray("choices")
                            if (choices != null && choices.length() > 0) {
                                val delta = choices.getJSONObject(0).optJSONObject("delta")
                                val content = delta?.optString("content")
                                if (!content.isNullOrEmpty()) {
                                    if (startTimeMs == 0L) startTimeMs = System.currentTimeMillis()
                                    tokenCount += 1
                                    val elapsed = System.currentTimeMillis() - startTimeMs
                                    val rate = if (elapsed > 0) tokenCount * 1000.0 / elapsed else 0.0
                                    onTokenRate(rate, tokenCount, elapsed)

                                    val current = messages[assistantIndex]
                                    messages[assistantIndex] = current.copy(content = current.content + content)
                                    coroutineScope.launch { listState.animateScrollToItem(messages.size - 1) }
                                }
                            }
                        } catch (e: Exception) {
                            LogManager.e("Chat", "Parse error", e)
                        }
                    }

                    override fun onFailure(eventSource: EventSource, t: Throwable?, response: okhttp3.Response?) {
                        val errorMsg = response?.body?.string() ?: t?.message ?: "Unknown SSE failure"
                        // A cancelled call shows up here too — don't spam logs with our own cancellation.
                        if (cont.isActive) LogManager.e("Chat", "SSE Failure: $errorMsg", t)
                        finishOnce()
                    }

                    override fun onClosed(eventSource: EventSource) {
                        finishOnce()
                    }
                })
                eventSource = source
                cont.invokeOnCancellation {
                    // Close the OkHttp call so the server sees the disconnect.
                    try { source.cancel() } catch (_: Throwable) {}
                }
            }
        } catch (e: CancellationException) {
            LogManager.i("Chat", "Request cancelled")
            try { eventSource?.cancel() } catch (_: Throwable) {}
            throw e
        } catch (e: Exception) {
            LogManager.e("Chat", "Request failed", e)
        } finally {
            onChattingChange(false)
        }
    }

    return job
}
