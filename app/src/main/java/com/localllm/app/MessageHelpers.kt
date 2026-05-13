package com.localllm.app

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * Pure, dependency-free helpers extracted from [LLMServerService] so that the
 * non-trivial logic around request hashing, SSRF protection, and tool-argument
 * parsing is independently testable on the JVM (no Android, no LiteRT-LM).
 *
 * Everything in this file is a top-level function so the unit tests in
 * `app/src/test/java/com/localllm/app/MessageHelpersTest.kt` can call them
 * directly. The service delegates to these instead of inlining the same logic.
 */

/**
 * Hash of `messages[0 until count]`. Used by [LLMServerService.resolveSession]
 * to detect when a client is replaying a different prefix than what was
 * recorded on the cached conversation — in which case the cache entry must be
 * rebuilt.
 *
 * Mixes in role, full content body (JSON-serialized so string and array
 * shapes hash to the same value when the underlying content is equivalent),
 * tool_call_id, and tool_calls so a follow-up tool turn or a swapped
 * function-call argument invalidates the cache correctly.
 */
fun messagesPrefixHash(messages: List<Message>, count: Int): Long {
    var h = 1L
    val n = minOf(count, messages.size)
    for (i in 0 until n) {
        val m = messages[i]
        h = h * 31L + m.role.hashCode()
        h = h * 31L + (m.content?.toString()?.hashCode() ?: 0)
        h = h * 31L + (m.toolCallId?.hashCode() ?: 0)
        h = h * 31L + (m.toolCalls?.hashCode() ?: 0)
    }
    return h
}

/**
 * Returns true when [url] is an `http://` URL whose host is one of the
 * loopback aliases (`localhost`, `127.0.0.1`, `[::1]`). Used by the
 * `image_url` fetcher to reject SSRF attempts at the gateway — only data:
 * URLs and loopback HTTP are allowed.
 *
 * Anything else (https://, public hosts, file:// schemes, custom schemes)
 * returns false. `https://localhost` is also rejected — we keep the surface
 * minimal and there is no reason to fetch images over TLS from the same
 * device.
 */
fun isLoopbackHttpUrl(url: String): Boolean {
    if (!url.startsWith("http://")) return false
    val rest = url.removePrefix("http://")
    val hostAndRest = rest.substringBefore('/')
    // IPv6 hosts in URL authority are bracketed: http://[::1]:port/path. We
    // can't `substringBefore(':')` directly because the IPv6 literal itself
    // contains colons. Strip the brackets first and pull the host out.
    val host = if (hostAndRest.startsWith("[")) {
        val end = hostAndRest.indexOf(']')
        if (end > 0) hostAndRest.substring(1, end) else hostAndRest
    } else {
        hostAndRest.substringBefore(':')
    }
    return host.equals("localhost", ignoreCase = true) ||
        host == "127.0.0.1" ||
        host == "::1"
}

/**
 * Decode a `data:image/...;base64,<payload>` URL to its raw bytes. Throws
 * [IllegalArgumentException] when the URL is not a `data:` URL, when the
 * base64 marker is missing, or when the payload doesn't decode.
 *
 * Uses [java.util.Base64] so the decoder works under any JVM (including
 * unit tests) without depending on `android.util.Base64`.
 */
fun decodeDataImageUrl(url: String): ByteArray {
    if (!url.startsWith("data:")) {
        throw IllegalArgumentException("Not a data: URL")
    }
    val base64 = url.substringAfter("base64,", "")
    if (base64.isEmpty()) {
        throw IllegalArgumentException("data: URL must be base64-encoded")
    }
    return try {
        java.util.Base64.getDecoder().decode(base64)
    } catch (e: Exception) {
        throw IllegalArgumentException("Invalid base64 in data: URL: ${e.message}")
    }
}

/**
 * Parse an OpenAI `function.arguments` string (always JSON, but transported
 * as a string for OpenAI wire-compat) into a Kotlin map suitable for the
 * LiteRT-LM `ToolCall(name, arguments)` constructor.
 *
 * Returns an empty map for: blank input, non-object JSON, invalid JSON.
 * Never throws — broken client arguments degrade to "call without args"
 * rather than failing the whole request.
 */
fun parseToolArguments(raw: String): Map<String, Any> {
    if (raw.isBlank()) return emptyMap()
    return try {
        val el = JsonParser.parseString(raw)
        if (!el.isJsonObject) emptyMap()
        else el.asJsonObject.entrySet().associate { (k, v) -> k to jsonToAny(v) }
    } catch (_: Exception) {
        emptyMap()
    }
}

/**
 * Recursive Gson [JsonElement] → plain Kotlin value conversion, used by
 * [parseToolArguments]. Preserves primitives (Boolean, Number, String),
 * lists, and maps; converts JSON nulls to empty strings (LiteRT-LM's
 * ToolCall argument map doesn't tolerate null values).
 */
fun jsonToAny(v: JsonElement): Any {
    return when {
        v.isJsonNull -> ""
        v.isJsonPrimitive -> {
            val p = v.asJsonPrimitive
            when {
                p.isBoolean -> p.asBoolean
                p.isNumber -> p.asNumber
                else -> p.asString
            }
        }
        v.isJsonArray -> v.asJsonArray.map { jsonToAny(it) }
        v.isJsonObject -> v.asJsonObject.entrySet().associate { (k, e) -> k to jsonToAny(e) }
        else -> v.toString()
    }
}

/**
 * Build the OpenAI-shaped tool-description JSON for a [FunctionDef], to be
 * passed to LiteRT-LM's `OpenApiTool`. Wraps the parameter schema in the
 * canonical `{name, description, parameters}` envelope.
 */
fun buildToolDescriptionJson(name: String, description: String?, parameters: JsonObject): String {
    val envelope = JsonObject().apply {
        addProperty("name", name)
        addProperty("description", description ?: "")
        add("parameters", parameters)
    }
    return envelope.toString()
}
