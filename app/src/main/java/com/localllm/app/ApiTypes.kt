package com.localllm.app

import com.google.gson.annotations.SerializedName

/**
 * OpenAI-compatible wire types. Kept in one place so the server and any
 * future API clients can share them.
 *
 * Field names follow the OpenAI Chat Completions contract; @SerializedName
 * maps the snake_case JSON keys to camelCase Kotlin properties where they
 * differ.
 */

data class Message(
    val role: String,
    val content: String
)

data class ChatRequest(
    val model: String,
    val messages: List<Message>,
    val stream: Boolean = false,
    /**
     * Opaque conversation ID for KV-cache reuse across turns. When empty
     * (the default) every request runs in a fresh session. When non-empty
     * the server caches the `LlmInferenceSession` for this ID and only
     * `addQueryChunk`s the new turns on follow-up requests.
     */
    @SerializedName("session_id") val sessionId: String? = null,
    val temperature: Float? = null,
    @SerializedName("top_k") val topK: Int? = null,
    @SerializedName("max_tokens") val maxTokens: Int? = null
)

data class ChatResponse(
    val id: String,
    val `object`: String,
    val created: Long,
    val model: String,
    val choices: List<Choice>
)

data class Choice(
    val index: Int,
    val message: Message,
    @SerializedName("finish_reason") val finishReason: String
)

data class StreamResponse(
    val id: String,
    val `object`: String,
    val created: Long,
    val model: String,
    val choices: List<StreamChoice>
)

data class StreamChoice(
    val index: Int,
    val delta: StreamDelta,
    @SerializedName("finish_reason") val finishReason: String?
)

data class StreamDelta(
    val role: String? = null,
    val content: String? = null
)

data class ErrorResponse(val error: ErrorDetails)

data class ErrorDetails(
    val message: String,
    val type: String,
    val code: Int
)

data class ModelListResponse(
    val `object`: String = "list",
    val data: List<ModelData>
)

data class ModelData(
    val id: String,
    val `object`: String = "model",
    val created: Long = 0,
    @SerializedName("owned_by") val ownedBy: String = "local"
)
