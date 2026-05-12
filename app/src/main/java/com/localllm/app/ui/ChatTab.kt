package com.localllm.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.localllm.app.LogManager
import com.localllm.app.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import org.json.JSONObject
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatTab(
    existingModels: Set<String>,
    selectedModel: String,
    onModelChange: (String) -> Unit,
    chatMessages: List<UiMessage>,
    chatInput: String,
    onInputChange: (String) -> Unit,
    isChatting: Boolean,
    onSend: () -> Unit,
    chatListState: LazyListState
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
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
        ) {
            OutlinedTextField(
                value = selectedModel,
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
                        text = { Text(modelName) },
                        onClick = {
                            onModelChange(modelName.removeSuffix(".task"))
                            expanded = false
                        }
                    )
                }
            }
        }

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
                    Text(
                        stringResource(R.string.chat_empty),
                        color = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.padding(8.dp)
                    )
                }
            }
            items(chatMessages) { msg ->
                val isUser = msg.role == "user"
                val alignment = if (isUser) Alignment.End else Alignment.Start
                val bg = if (isUser) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant

                Column(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    horizontalAlignment = alignment
                ) {
                    Text(
                        text = msg.role.uppercase(),
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.padding(bottom = 2.dp, start = 4.dp, end = 4.dp)
                    )
                    Box(
                        modifier = Modifier
                            .background(bg, shape = MaterialTheme.shapes.small)
                            .padding(12.dp)
                    ) {
                        Text(text = msg.content, color = MaterialTheme.colorScheme.onSurface)
                    }
                }
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
            Button(onClick = onSend, enabled = !isChatting && chatInput.isNotBlank()) {
                Text(stringResource(R.string.chat_send))
            }
        }
    }
}

/**
 * In-app chat client. Speaks the same OpenAI-compatible API the server exposes
 * by hitting the local server. Streams via SSE and appends to [messages] in
 * place so the Chat list recomposes incrementally.
 */
fun sendChatMessage(
    messages: MutableList<UiMessage>,
    input: String,
    model: String,
    baseUrl: String,
    apiKey: String,
    coroutineScope: CoroutineScope,
    listState: LazyListState,
    onChattingChange: (Boolean) -> Unit
) {
    onChattingChange(true)
    messages.add(UiMessage("user", input))
    val assistantIndex = messages.size
    messages.add(UiMessage("assistant", ""))

    coroutineScope.launch {
        try {
            val client = OkHttpClient.Builder()
                .readTimeout(10, TimeUnit.MINUTES)
                .build()

            val reqBody = JSONObject().apply {
                put("model", model)
                put("stream", true)
                put("session_id", "local_chat_test")

                val msgArray = org.json.JSONArray()
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
            factory.newEventSource(request, object : EventSourceListener() {
                override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                    if (data == "[DONE]") {
                        onChattingChange(false)
                        return
                    }
                    try {
                        val obj = JSONObject(data)
                        val choices = obj.optJSONArray("choices")
                        if (choices != null && choices.length() > 0) {
                            val delta = choices.getJSONObject(0).optJSONObject("delta")
                            val content = delta?.optString("content")
                            if (!content.isNullOrEmpty()) {
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
                    LogManager.e("Chat", "SSE Failure: $errorMsg", t)
                    onChattingChange(false)
                }

                override fun onClosed(eventSource: EventSource) {
                    onChattingChange(false)
                }
            })
        } catch (e: Exception) {
            LogManager.e("Chat", "Request failed", e)
            onChattingChange(false)
        }
    }
}
