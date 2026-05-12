package com.localllm.app

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Reactive, in-memory façade in front of [SharedPreferences].
 *
 * Each preference is exposed as a [StateFlow] so Compose can observe it without
 * re-reading from disk on every recomposition (a slider drag used to trigger
 * ~60 disk reads/second). Writes go through the repository: it persists the
 * value to [SharedPreferences] and emits the new value to the underlying
 * [MutableStateFlow] in one step (write-through). We deliberately do NOT
 * register an `OnSharedPreferenceChangeListener` — write-through keeps both
 * ends in sync and avoids the IPC round-trip.
 *
 * The synchronous [Settings] object is kept as a thin facade on top of this
 * repository for code paths that read from background threads
 * (e.g. [LLMServerService]).
 *
 * All clamping / sanitization for setters lives here so [Settings] and direct
 * repository users share the same validation.
 */
class SettingsRepository private constructor(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /* ---------- backing flows (seeded from disk once, at construction) ---- */

    private val _port = MutableStateFlow(prefs.getInt(Settings.KEY_SERVER_PORT, Settings.DEFAULT_PORT))
    val port: StateFlow<Int> = _port.asStateFlow()

    private val _maxTokens = MutableStateFlow(prefs.getInt(Settings.KEY_MAX_TOKENS, Settings.DEFAULT_MAX_TOKENS))
    val maxTokens: StateFlow<Int> = _maxTokens.asStateFlow()

    private val _temperature = MutableStateFlow(prefs.getFloat(Settings.KEY_TEMPERATURE, Settings.DEFAULT_TEMPERATURE))
    val temperature: StateFlow<Float> = _temperature.asStateFlow()

    private val _topK = MutableStateFlow(prefs.getInt(Settings.KEY_TOP_K, Settings.DEFAULT_TOP_K))
    val topK: StateFlow<Int> = _topK.asStateFlow()

    private val _bindLan = MutableStateFlow(prefs.getBoolean(Settings.KEY_BIND_LAN, false))
    val bindLan: StateFlow<Boolean> = _bindLan.asStateFlow()

    private val _startOnBoot = MutableStateFlow(prefs.getBoolean(Settings.KEY_START_ON_BOOT, true))
    val startOnBoot: StateFlow<Boolean> = _startOnBoot.asStateFlow()

    private val _autostart = MutableStateFlow(prefs.getBoolean(Settings.KEY_AUTOSTART, true))
    val autostart: StateFlow<Boolean> = _autostart.asStateFlow()

    private val _customModelUrls = MutableStateFlow(parseUrls(prefs.getString(Settings.KEY_CUSTOM_MODEL_URLS, "") ?: ""))
    val customModelUrls: StateFlow<List<String>> = _customModelUrls.asStateFlow()

    private val _requestTimeoutMs = MutableStateFlow(prefs.getLong(Settings.KEY_REQUEST_TIMEOUT_MS, Settings.DEFAULT_REQUEST_TIMEOUT_MS))
    val requestTimeoutMs: StateFlow<Long> = _requestTimeoutMs.asStateFlow()

    private val _maxQueueDepth = MutableStateFlow(prefs.getInt(Settings.KEY_MAX_QUEUE_DEPTH, Settings.DEFAULT_MAX_QUEUE_DEPTH))
    val maxQueueDepth: StateFlow<Int> = _maxQueueDepth.asStateFlow()

    private val _maxPromptChars = MutableStateFlow(prefs.getInt(Settings.KEY_MAX_PROMPT_CHARS, Settings.DEFAULT_MAX_PROMPT_CHARS))
    val maxPromptChars: StateFlow<Int> = _maxPromptChars.asStateFlow()

    private val _apiKey = MutableStateFlow(prefs.getString(Settings.KEY_API_KEY, "") ?: "")
    val apiKey: StateFlow<String> = _apiKey.asStateFlow()

    private val _keepAwake = MutableStateFlow(prefs.getBoolean(Settings.KEY_KEEP_AWAKE, true))
    val keepAwake: StateFlow<Boolean> = _keepAwake.asStateFlow()

    private val _idleEvictMs = MutableStateFlow(prefs.getLong(Settings.KEY_IDLE_EVICT_MS, Settings.DEFAULT_IDLE_EVICT_MS))
    val idleEvictMs: StateFlow<Long> = _idleEvictMs.asStateFlow()

    private val _idleStopMs = MutableStateFlow(prefs.getLong(Settings.KEY_IDLE_STOP_MS, Settings.DEFAULT_IDLE_STOP_MS))
    val idleStopMs: StateFlow<Long> = _idleStopMs.asStateFlow()

    private val _backend = MutableStateFlow(prefs.getString(Settings.KEY_BACKEND, Settings.BACKEND_AUTO) ?: Settings.BACKEND_AUTO)
    val backend: StateFlow<String> = _backend.asStateFlow()

    private val _allowCors = MutableStateFlow(prefs.getBoolean(Settings.KEY_ALLOW_CORS, false))
    val allowCors: StateFlow<Boolean> = _allowCors.asStateFlow()

    /* ---------- writers (apply same clamping as the legacy Settings API) -- */

    fun setPort(value: Int) {
        val safe = value.coerceIn(1024, 65535)
        prefs.edit().putInt(Settings.KEY_SERVER_PORT, safe).apply()
        _port.value = safe
    }

    fun setMaxTokens(value: Int) {
        val safe = value.coerceIn(64, 8192)
        prefs.edit().putInt(Settings.KEY_MAX_TOKENS, safe).apply()
        _maxTokens.value = safe
    }

    fun setTemperature(value: Float) {
        val safe = value.coerceIn(0f, 2f)
        prefs.edit().putFloat(Settings.KEY_TEMPERATURE, safe).apply()
        _temperature.value = safe
    }

    fun setTopK(value: Int) {
        val safe = value.coerceIn(1, 200)
        prefs.edit().putInt(Settings.KEY_TOP_K, safe).apply()
        _topK.value = safe
    }

    fun setBindLan(value: Boolean) {
        prefs.edit().putBoolean(Settings.KEY_BIND_LAN, value).apply()
        _bindLan.value = value
    }

    fun setStartOnBoot(value: Boolean) {
        prefs.edit().putBoolean(Settings.KEY_START_ON_BOOT, value).apply()
        _startOnBoot.value = value
    }

    fun setAutostart(value: Boolean) {
        prefs.edit().putBoolean(Settings.KEY_AUTOSTART, value).apply()
        _autostart.value = value
    }

    fun setCustomModelUrls(urls: List<String>) {
        prefs.edit().putString(Settings.KEY_CUSTOM_MODEL_URLS, urls.joinToString("\n")).apply()
        // Re-parse so the in-memory flow matches what a subsequent read would
        // return (trims blanks, applies filtering).
        _customModelUrls.value = parseUrls(urls.joinToString("\n"))
    }

    fun setRequestTimeoutMs(value: Long) {
        val safe = value.coerceIn(5_000L, 600_000L)
        prefs.edit().putLong(Settings.KEY_REQUEST_TIMEOUT_MS, safe).apply()
        _requestTimeoutMs.value = safe
    }

    fun setMaxQueueDepth(value: Int) {
        val safe = value.coerceIn(1, 100)
        prefs.edit().putInt(Settings.KEY_MAX_QUEUE_DEPTH, safe).apply()
        _maxQueueDepth.value = safe
    }

    fun setMaxPromptChars(value: Int) {
        val safe = value.coerceIn(512, 2_000_000)
        prefs.edit().putInt(Settings.KEY_MAX_PROMPT_CHARS, safe).apply()
        _maxPromptChars.value = safe
    }

    fun setApiKey(value: String) {
        val safe = value.trim()
        prefs.edit().putString(Settings.KEY_API_KEY, safe).apply()
        _apiKey.value = safe
    }

    fun setKeepAwake(value: Boolean) {
        prefs.edit().putBoolean(Settings.KEY_KEEP_AWAKE, value).apply()
        _keepAwake.value = value
    }

    fun setIdleEvictMs(value: Long) {
        val safe = value.coerceAtLeast(0L)
        prefs.edit().putLong(Settings.KEY_IDLE_EVICT_MS, safe).apply()
        _idleEvictMs.value = safe
    }

    fun setIdleStopMs(value: Long) {
        val safe = value.coerceAtLeast(0L)
        prefs.edit().putLong(Settings.KEY_IDLE_STOP_MS, safe).apply()
        _idleStopMs.value = safe
    }

    fun setBackend(value: String) {
        val safe = when (value.uppercase()) {
            Settings.BACKEND_CPU, Settings.BACKEND_GPU, Settings.BACKEND_AUTO -> value.uppercase()
            else -> Settings.BACKEND_AUTO
        }
        prefs.edit().putString(Settings.KEY_BACKEND, safe).apply()
        _backend.value = safe
    }

    fun setAllowCors(value: Boolean) {
        prefs.edit().putBoolean(Settings.KEY_ALLOW_CORS, value).apply()
        _allowCors.value = value
    }

    /** Exposed so tests / debug tooling can wipe state. */
    fun clearAll() {
        prefs.edit().clear().apply()
        _port.value = Settings.DEFAULT_PORT
        _maxTokens.value = Settings.DEFAULT_MAX_TOKENS
        _temperature.value = Settings.DEFAULT_TEMPERATURE
        _topK.value = Settings.DEFAULT_TOP_K
        _bindLan.value = false
        _startOnBoot.value = true
        _autostart.value = true
        _customModelUrls.value = emptyList()
        _requestTimeoutMs.value = Settings.DEFAULT_REQUEST_TIMEOUT_MS
        _maxQueueDepth.value = Settings.DEFAULT_MAX_QUEUE_DEPTH
        _maxPromptChars.value = Settings.DEFAULT_MAX_PROMPT_CHARS
        _apiKey.value = ""
        _keepAwake.value = true
        _idleEvictMs.value = Settings.DEFAULT_IDLE_EVICT_MS
        _idleStopMs.value = Settings.DEFAULT_IDLE_STOP_MS
        _backend.value = Settings.BACKEND_AUTO
        _allowCors.value = false
    }

    private fun parseUrls(raw: String): List<String> =
        raw.split("\n").map { it.trim() }.filter { it.isNotEmpty() }

    companion object {
        private const val PREFS = "settings"

        @Volatile
        private var INSTANCE: SettingsRepository? = null

        fun get(context: Context): SettingsRepository {
            // Application context guards against Activity leaks: the singleton
            // outlives any individual Activity.
            val appCtx = context.applicationContext
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: SettingsRepository(appCtx).also { INSTANCE = it }
            }
        }

        /** Visible for testing — drop the singleton so the next [get] re-reads from disk. */
        internal fun resetForTesting() {
            synchronized(this) { INSTANCE = null }
        }
    }
}
