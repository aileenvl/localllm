package com.localllm.app

import android.content.Context
import android.content.SharedPreferences

/**
 * Centralized typed access to user preferences for the LocalLLM server.
 *
 * Single source of truth — both the UI (Settings tab) and the LLMServerService
 * read from here so they can never drift apart.
 */
object Settings {
    private const val PREFS = "settings"

    const val KEY_SERVER_PORT = "server_port"
    const val KEY_MAX_TOKENS = "max_tokens"
    const val KEY_TEMPERATURE = "temperature"
    const val KEY_TOP_K = "top_k"
    const val KEY_BIND_LAN = "bind_lan"
    const val KEY_START_ON_BOOT = "start_on_boot"
    const val KEY_AUTOSTART = "autostart_on_launch"
    const val KEY_CUSTOM_MODEL_URLS = "custom_model_urls"

    // Limits / safety
    const val KEY_REQUEST_TIMEOUT_MS = "request_timeout_ms"
    const val KEY_MAX_QUEUE_DEPTH = "max_queue_depth"
    const val KEY_MAX_PROMPT_CHARS = "max_prompt_chars"
    const val KEY_API_KEY = "api_key"
    const val KEY_KEEP_AWAKE = "keep_awake"

    // Background efficiency
    const val KEY_IDLE_EVICT_MS = "idle_evict_ms"
    const val KEY_IDLE_STOP_MS = "idle_stop_ms"

    // Inference backend: "AUTO" lets MediaPipe pick (on Pixel 10 with a compatible
    // model this routes to the Tensor G5 NPU). "CPU" and "GPU" force a backend.
    const val KEY_BACKEND = "backend"
    const val BACKEND_AUTO = "AUTO"
    const val BACKEND_CPU = "CPU"
    const val BACKEND_GPU = "GPU"

    // CORS: when off (default) the server responds without CORS headers — safe
    // because only non-browser clients (native apps, curl) can use it. When on,
    // `anyHost()` is installed so browser-based clients can call the API.
    const val KEY_ALLOW_CORS = "allow_cors"

    const val DEFAULT_PORT = 8080
    const val DEFAULT_MAX_TOKENS = 1024
    const val DEFAULT_TEMPERATURE = 0.8f
    const val DEFAULT_TOP_K = 40
    const val DEFAULT_REQUEST_TIMEOUT_MS = 120_000L
    const val DEFAULT_MAX_QUEUE_DEPTH = 8
    const val DEFAULT_MAX_PROMPT_CHARS = 100_000
    const val DEFAULT_IDLE_EVICT_MS = 5L * 60_000L   // 5 minutes; 0 disables
    const val DEFAULT_IDLE_STOP_MS = 0L               // disabled by default

    fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun port(context: Context): Int = prefs(context).getInt(KEY_SERVER_PORT, DEFAULT_PORT)
    fun setPort(context: Context, value: Int) =
        prefs(context).edit().putInt(KEY_SERVER_PORT, value.coerceIn(1024, 65535)).apply()

    fun maxTokens(context: Context): Int = prefs(context).getInt(KEY_MAX_TOKENS, DEFAULT_MAX_TOKENS)
    fun setMaxTokens(context: Context, value: Int) =
        prefs(context).edit().putInt(KEY_MAX_TOKENS, value.coerceIn(64, 8192)).apply()

    fun temperature(context: Context): Float =
        prefs(context).getFloat(KEY_TEMPERATURE, DEFAULT_TEMPERATURE)
    fun setTemperature(context: Context, value: Float) =
        prefs(context).edit().putFloat(KEY_TEMPERATURE, value.coerceIn(0f, 2f)).apply()

    fun topK(context: Context): Int = prefs(context).getInt(KEY_TOP_K, DEFAULT_TOP_K)
    fun setTopK(context: Context, value: Int) =
        prefs(context).edit().putInt(KEY_TOP_K, value.coerceIn(1, 200)).apply()

    fun bindLan(context: Context): Boolean = prefs(context).getBoolean(KEY_BIND_LAN, false)
    fun setBindLan(context: Context, value: Boolean) =
        prefs(context).edit().putBoolean(KEY_BIND_LAN, value).apply()

    fun startOnBoot(context: Context): Boolean = prefs(context).getBoolean(KEY_START_ON_BOOT, true)
    fun setStartOnBoot(context: Context, value: Boolean) =
        prefs(context).edit().putBoolean(KEY_START_ON_BOOT, value).apply()

    fun autostart(context: Context): Boolean = prefs(context).getBoolean(KEY_AUTOSTART, true)
    fun setAutostart(context: Context, value: Boolean) =
        prefs(context).edit().putBoolean(KEY_AUTOSTART, value).apply()

    fun customModelUrls(context: Context): List<String> {
        val raw = prefs(context).getString(KEY_CUSTOM_MODEL_URLS, "") ?: ""
        return raw.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
    }

    fun setCustomModelUrls(context: Context, urls: List<String>) {
        prefs(context).edit().putString(KEY_CUSTOM_MODEL_URLS, urls.joinToString("\n")).apply()
    }

    fun bindHost(context: Context): String = if (bindLan(context)) "0.0.0.0" else "127.0.0.1"

    fun requestTimeoutMs(context: Context): Long =
        prefs(context).getLong(KEY_REQUEST_TIMEOUT_MS, DEFAULT_REQUEST_TIMEOUT_MS)
    fun setRequestTimeoutMs(context: Context, value: Long) =
        prefs(context).edit().putLong(KEY_REQUEST_TIMEOUT_MS, value.coerceIn(5_000L, 600_000L)).apply()

    fun maxQueueDepth(context: Context): Int =
        prefs(context).getInt(KEY_MAX_QUEUE_DEPTH, DEFAULT_MAX_QUEUE_DEPTH)
    fun setMaxQueueDepth(context: Context, value: Int) =
        prefs(context).edit().putInt(KEY_MAX_QUEUE_DEPTH, value.coerceIn(1, 100)).apply()

    fun maxPromptChars(context: Context): Int =
        prefs(context).getInt(KEY_MAX_PROMPT_CHARS, DEFAULT_MAX_PROMPT_CHARS)
    fun setMaxPromptChars(context: Context, value: Int) =
        prefs(context).edit().putInt(KEY_MAX_PROMPT_CHARS, value.coerceIn(512, 2_000_000)).apply()

    fun apiKey(context: Context): String = prefs(context).getString(KEY_API_KEY, "") ?: ""
    fun setApiKey(context: Context, value: String) =
        prefs(context).edit().putString(KEY_API_KEY, value.trim()).apply()

    fun keepAwake(context: Context): Boolean = prefs(context).getBoolean(KEY_KEEP_AWAKE, true)
    fun setKeepAwake(context: Context, value: Boolean) =
        prefs(context).edit().putBoolean(KEY_KEEP_AWAKE, value).apply()

    fun idleEvictMs(context: Context): Long =
        prefs(context).getLong(KEY_IDLE_EVICT_MS, DEFAULT_IDLE_EVICT_MS)
    fun setIdleEvictMs(context: Context, value: Long) =
        prefs(context).edit().putLong(KEY_IDLE_EVICT_MS, value.coerceAtLeast(0L)).apply()

    fun idleStopMs(context: Context): Long =
        prefs(context).getLong(KEY_IDLE_STOP_MS, DEFAULT_IDLE_STOP_MS)
    fun setIdleStopMs(context: Context, value: Long) =
        prefs(context).edit().putLong(KEY_IDLE_STOP_MS, value.coerceAtLeast(0L)).apply()

    fun backend(context: Context): String =
        prefs(context).getString(KEY_BACKEND, BACKEND_AUTO) ?: BACKEND_AUTO
    fun setBackend(context: Context, value: String) {
        val safe = when (value.uppercase()) {
            BACKEND_CPU, BACKEND_GPU, BACKEND_AUTO -> value.uppercase()
            else -> BACKEND_AUTO
        }
        prefs(context).edit().putString(KEY_BACKEND, safe).apply()
    }

    fun allowCors(context: Context): Boolean = prefs(context).getBoolean(KEY_ALLOW_CORS, false)
    fun setAllowCors(context: Context, value: Boolean) =
        prefs(context).edit().putBoolean(KEY_ALLOW_CORS, value).apply()
}
