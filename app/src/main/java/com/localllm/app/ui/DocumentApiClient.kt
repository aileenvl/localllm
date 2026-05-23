package com.localllm.app.ui

import com.google.gson.Gson
import com.localllm.app.DocumentDeleteResponse
import com.localllm.app.DocumentListResponse
import com.localllm.app.DocumentRequest
import com.localllm.app.DocumentSummaryResponse
import com.localllm.app.ErrorResponse
import com.localllm.app.SearchRequest
import com.localllm.app.SearchResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Shared tenant bucket for in-app RAG corpus operations. */
const val RAG_CLIENT_ID = "localllm-app"

const val DEFAULT_EMBEDDING_MODEL = "bge-small-en-v1.5"

private val gson = Gson()
private val jsonType = "application/json; charset=utf-8".toMediaType()

private val http = OkHttpClient.Builder()
    .connectTimeout(30, TimeUnit.SECONDS)
    .readTimeout(5, TimeUnit.MINUTES)
    .writeTimeout(5, TimeUnit.MINUTES)
    .build()

private fun Request.Builder.ragHeaders(apiKey: String): Request.Builder {
    header("X-Client-Id", RAG_CLIENT_ID)
    header("User-Agent", "LocalLLM/${RAG_CLIENT_ID}")
    if (apiKey.isNotEmpty()) {
        header("Authorization", "Bearer $apiKey")
    }
    return this
}

private fun parseError(body: String?, code: Int): String {
    if (!body.isNullOrBlank()) {
        runCatching { gson.fromJson(body, ErrorResponse::class.java) }.getOrNull()?.error?.message
            ?.let { return it }
    }
    return "HTTP $code"
}

suspend fun fetchDocuments(baseUrl: String, apiKey: String): DocumentListResponse =
    withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("${baseUrl.removeSuffix("/")}/v1/documents")
            .get()
            .ragHeaders(apiKey)
            .build()
        http.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw IOException(parseError(body, resp.code))
            gson.fromJson(body, DocumentListResponse::class.java)
        }
    }

suspend fun ingestDocument(
    baseUrl: String,
    apiKey: String,
    id: String,
    text: String,
    model: String = DEFAULT_EMBEDDING_MODEL,
): DocumentSummaryResponse = withContext(Dispatchers.IO) {
    val payload = gson.toJson(DocumentRequest(id = id, text = text, model = model))
    val req = Request.Builder()
        .url("${baseUrl.removeSuffix("/")}/v1/documents")
        .post(payload.toRequestBody(jsonType))
        .ragHeaders(apiKey)
        .build()
    http.newCall(req).execute().use { resp ->
        val body = resp.body?.string().orEmpty()
        if (!resp.isSuccessful) throw IOException(parseError(body, resp.code))
        gson.fromJson(body, DocumentSummaryResponse::class.java)
    }
}

suspend fun deleteDocument(
    baseUrl: String,
    apiKey: String,
    documentId: String,
): DocumentDeleteResponse = withContext(Dispatchers.IO) {
    val req = Request.Builder()
        .url("${baseUrl.removeSuffix("/")}/v1/documents/${java.net.URLEncoder.encode(documentId, "UTF-8")}")
        .delete()
        .ragHeaders(apiKey)
        .build()
    http.newCall(req).execute().use { resp ->
        val body = resp.body?.string().orEmpty()
        if (!resp.isSuccessful) throw IOException(parseError(body, resp.code))
        gson.fromJson(body, DocumentDeleteResponse::class.java)
    }
}

suspend fun searchDocuments(
    baseUrl: String,
    apiKey: String,
    query: String,
    model: String = DEFAULT_EMBEDDING_MODEL,
    k: Int = 5,
): SearchResponse = withContext(Dispatchers.IO) {
    val payload = gson.toJson(SearchRequest(query = query, model = model, k = k))
    val req = Request.Builder()
        .url("${baseUrl.removeSuffix("/")}/v1/search")
        .post(payload.toRequestBody(jsonType))
        .ragHeaders(apiKey)
        .build()
    http.newCall(req).execute().use { resp ->
        val body = resp.body?.string().orEmpty()
        if (!resp.isSuccessful) throw IOException(parseError(body, resp.code))
        gson.fromJson(body, SearchResponse::class.java)
    }
}
