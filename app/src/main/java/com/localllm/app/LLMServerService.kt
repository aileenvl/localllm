package com.localllm.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentCallbacks2
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.serialization.gson.*
import io.ktor.server.request.*
import io.ktor.http.HttpStatusCode
import io.ktor.http.ContentType
import io.ktor.http.CacheControl
import io.ktor.utils.io.*
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.SamplerConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import com.google.gson.Gson
import android.util.LruCache
import com.google.ai.edge.litertlm.Message as LlmMessage

/**
 * Process-wide server state. The UI observes this to render the status badge,
 * the Start/Stop button, and the bound URL.
 */
object ServerState {
    enum class Status { STOPPED, STARTING, RUNNING, ERROR }

    private val _status = MutableStateFlow(Status.STOPPED)
    val status = _status.asStateFlow()

    private val _boundUrl = MutableStateFlow<String?>(null)
    val boundUrl = _boundUrl.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError = _lastError.asStateFlow()

    internal fun setStatus(s: Status) { _status.value = s }
    internal fun setBoundUrl(url: String?) { _boundUrl.value = url }
    internal fun setError(msg: String?) { _lastError.value = msg }
}

class LLMServerService : Service() {
    private var server: EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration>? = null

    /**
     * Engine cache. Keyed by `model_maxTokens_backend` because LiteRT-LM's
     * `EngineConfig.maxNumTokens` is the *total* KV-cache budget
     * (input + output), not just an output cap. Reusing an engine built
     * with a larger budget for a request that asked for less would let the
     * model overgenerate. Each model takes 1–2 GB so we keep at most 2 resident.
     *
     * When an engine is evicted, every conversation that holds a handle to it is
     * also evicted first — conversations can't outlive their parent engine.
     */
    /**
     * Wraps the engine alongside the backend label that actually succeeded at
     * `initialize()` time. When AUTO falls back from GPU to CPU we want
     * `/health` to surface what each engine *really* ended up on.
     */
    private data class CachedEngine(val engine: Engine, val backend: String)

    private val engines = object : LruCache<String, CachedEngine>(2) {
        override fun entryRemoved(evicted: Boolean, key: String?, oldValue: CachedEngine?, newValue: CachedEngine?) {
            if (evicted) {
                // Close conversations tied to this engine BEFORE closing the engine —
                // a conversation referencing a closed engine is undefined behavior.
                if (key != null) {
                    val tied = sessions.snapshot().filter { it.value.engineKey == key }.keys
                    tied.forEach { sessions.remove(it) }
                }
                try {
                    oldValue?.engine?.close()
                    LogManager.i("LLMServerService", "Evicted engine: $key")
                } catch (e: Exception) {
                    LogManager.e("LLMServerService", "Error closing evicted engine", e)
                }
            }
        }
    }

    /**
     * Cached `Conversation`s keyed by `session_id + engineKey`. Conversations
     * preserve the KV cache across turns: on a follow-up request we only need
     * to send the NEW user turns. Saves the cost of re-tokenizing and
     * re-prefilling the full conversation each time.
     *
     * Bounded at 4 cached conversations — beyond that the LRU evicts oldest.
     */
    private data class CachedSession(
        val conversation: Conversation,
        val engineKey: String,
        val temperature: Float,
        val topK: Int,
        val prefixHash: Long,
        val seenCount: Int,
        val createdAt: Long
    )

    private val sessions = object : LruCache<String, CachedSession>(4) {
        override fun entryRemoved(evicted: Boolean, key: String?, oldValue: CachedSession?, newValue: CachedSession?) {
            // Always close the conversation — both eviction and explicit removal go
            // through here. Compare by identity so a put() that replaces the
            // entry with the SAME conversation doesn't accidentally close it.
            if (oldValue != null && oldValue.conversation !== newValue?.conversation) {
                try { oldValue.conversation.close() } catch (_: Exception) {}
            }
        }
    }

    /**
     * What [resolveSession] returns. Carries enough state for the caller to
     * either commit (on success) or invalidate (on failure) the conversation.
     */
    private data class ResolvedSession(
        val conversation: Conversation,
        val prompt: String,           // text to send via sendMessageAsync
        val cacheKey: String?,        // null for stateless (no session_id)
        val engineKey: String,
        val temperature: Float,
        val topK: Int
    ) {
        val isCached: Boolean get() = cacheKey != null
    }

    private val inferenceMutex = Mutex()
    private val gson = Gson()

    /**
     * Last time we received a request. Used for idle-based engine eviction and
     * optional service auto-stop.
     */
    private val lastActivityAt = AtomicLong(System.currentTimeMillis())

    /** Service-scoped coroutines (idle monitor, etc.). Cancelled on onDestroy. */
    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.Default + serviceJob)
    private var idleMonitorJob: Job? = null

    /** Lazily-acquired partial wake lock — only held while inference is active. */
    private val wakeLock by lazy {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LocalLLM:Inference").apply {
            setReferenceCounted(false)
        }
    }

    private fun getModelFile(modelId: String): File {
        val filename = if (modelId.endsWith(".litertlm")) modelId else "$modelId.litertlm"
        return File(getExternalFilesDir(null), filename)
    }

    override fun onCreate() {
        super.onCreate()
        startForeground()
        startServer()
        startIdleMonitor()
    }

    /**
     * Background loop that:
     *   - evicts cached engines after [Settings.idleEvictMs] of no activity
     *     (frees ~1–2 GB per model)
     *   - optionally stops the service entirely after [Settings.idleStopMs]
     *
     * Both are configurable; 0 disables.
     */
    private fun startIdleMonitor() {
        idleMonitorJob?.cancel()
        idleMonitorJob = serviceScope.launch {
            while (isActive) {
                delay(30_000L)
                val idleMs = System.currentTimeMillis() - lastActivityAt.get()
                val evictAfter = Settings.idleEvictMs(this@LLMServerService)
                val stopAfter = Settings.idleStopMs(this@LLMServerService)

                if (evictAfter > 0 && idleMs >= evictAfter && engines.size() > 0) {
                    // Only evict if nothing is currently running.
                    if (inferenceMutex.tryLock()) {
                        try {
                            val n = engines.size()
                            engines.evictAll()
                            if (n > 0) LogManager.i("LLMServerService", "Idle eviction: released $n engine(s) after ${idleMs / 1000}s idle")
                        } finally {
                            inferenceMutex.unlock()
                        }
                    }
                }

                if (stopAfter > 0 && idleMs >= stopAfter) {
                    LogManager.i("LLMServerService", "Idle auto-stop after ${idleMs / 1000}s")
                    stopSelf()
                    return@launch
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        // START_STICKY contract: when the OS kills us under memory pressure
        // (LMK) Android will re-create the service later with a `null` intent.
        // That's fine — [onCreate] unconditionally calls [startServer] and
        // [startIdleMonitor], so the HTTP listener is back on its bound port
        // within ~2s of the cold-start. The original triggering intent is
        // intentionally not redelivered (we don't need REDELIVER_INTENT — the
        // service has no per-intent work, only ambient long-running state).
        return START_STICKY
    }

    /**
     * Memory-pressure callback. Each cached engine pins 2–3 GB of model weights,
     * so dropping even one entry under pressure is often the difference between
     * surviving the next LMK pass and being killed cold.
     *
     * Contract: this fires on the main thread, so we only do the bookkeeping
     * inline (mutex try-lock + log) and offload the actual `evictAll`/`remove`
     * work to [serviceScope] so the system callback returns immediately.
     * Eviction never interrupts an active inference — if [inferenceMutex] is
     * held we just bail and log.
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        when (level) {
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE,
            ComponentCallbacks2.TRIM_MEMORY_BACKGROUND -> {
                LogManager.i("MemoryPressure", "trim level=$level, action=log-only (moderate/background)")
            }
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW,
            ComponentCallbacks2.TRIM_MEMORY_MODERATE -> {
                serviceScope.launch {
                    if (inferenceMutex.tryLock()) {
                        try {
                            val before = engines.size()
                            if (before > 1) {
                                engines.trimToSize(1)
                                LogManager.i("MemoryPressure", "trim level=$level, action=shrunk LRU from $before to ${engines.size()}")
                            } else {
                                LogManager.i("MemoryPressure", "trim level=$level, action=noop (engines=$before)")
                            }
                        } finally {
                            inferenceMutex.unlock()
                        }
                    } else {
                        LogManager.i("MemoryPressure", "trim level=$level, action=skipped (inference active)")
                    }
                }
            }
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL,
            ComponentCallbacks2.TRIM_MEMORY_COMPLETE -> {
                serviceScope.launch {
                    if (inferenceMutex.tryLock()) {
                        try {
                            val nEngines = engines.size()
                            val nSessions = sessions.size()
                            // Sessions hold conversations tied to engines; clear them
                            // first so the engine eviction callback doesn't double-close.
                            sessions.evictAll()
                            engines.evictAll()
                            LogManager.i("MemoryPressure", "trim level=$level, action=evicted all ($nEngines engines, $nSessions sessions)")
                        } finally {
                            inferenceMutex.unlock()
                        }
                    } else {
                        LogManager.w("MemoryPressure", "trim level=$level, action=could-not-acquire-lock (inference active; LMK may kill us)")
                    }
                }
            }
            ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN -> {
                // App went to background. The idle eviction loop already handles
                // long-running background; evicting eagerly here would just thrash
                // (evict + reload) every time the user tabs out.
                LogManager.i("MemoryPressure", "trim level=$level, action=noop (UI hidden; idle loop handles background)")
            }
            else -> {
                LogManager.i("MemoryPressure", "trim level=$level, action=noop (unknown level)")
            }
        }
    }

    private fun startForeground() {
        val port = Settings.port(this)

        val channelId = "llm_service_channel"
        val chan = NotificationChannel(channelId, getString(R.string.notif_channel_name), NotificationManager.IMPORTANCE_LOW)
        val manager = (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
        manager.createNotificationChannel(chan)

        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, LLMServerService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val host = Settings.bindHost(this)
        val notification = NotificationCompat.Builder(this, channelId)
            .setOngoing(true)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(getString(R.string.notif_listening, host, port))
            .setContentIntent(openIntent)
            .addAction(0, getString(R.string.notif_action_stop), stopIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()

        startForeground(1, notification)
    }

    private fun startServer() {
        ServerState.setStatus(ServerState.Status.STARTING)
        ServerState.setError(null)
        try {
            val port = Settings.port(this)
            val host = Settings.bindHost(this)

            server = embeddedServer(Netty, port = port, host = host) {
                install(ContentNegotiation) { gson() }
                // CORS is opt-in. Native HTTP clients don't need CORS headers,
                // so the safe default is "no CORS plugin installed" — that way
                // a random web page can't drive the API from a user's browser.
                if (Settings.allowCors(this@LLMServerService)) {
                    install(CORS) {
                        anyHost()
                        allowMethod(io.ktor.http.HttpMethod.Post)
                        allowMethod(io.ktor.http.HttpMethod.Get)
                        allowHeader(io.ktor.http.HttpHeaders.ContentType)
                        allowHeader(io.ktor.http.HttpHeaders.Authorization)
                    }
                }

                routing {
                    get("/health") {
                        call.respond(mapOf(
                            "status" to "ok",
                            "service" to "localllm-android",
                            "version" to "1.0",
                            "queue_depth" to RequestTracker.queue.value.size,
                            "engines_loaded" to engines.size(),
                            "engines" to engines.snapshot().map { (key, v) ->
                                mapOf("key" to key, "backend" to v.backend)
                            }
                        ))
                    }

                    get("/v1/models") {
                        if (!authorize(call)) return@get
                        val dir = getExternalFilesDir(null)
                        val files = dir?.listFiles { file -> file.name.endsWith(".litertlm") } ?: emptyArray()
                        val models = files.map { file ->
                            val modelId = file.name.removeSuffix(".litertlm")
                            ModelData(id = modelId, created = file.lastModified() / 1000)
                        }
                        call.respond(ModelListResponse(data = models))
                    }

                    post("/v1/chat/completions") {
                        if (!authorize(call)) return@post
                        lastActivityAt.set(System.currentTimeMillis())

                        // Pre-parse body-size guard. The prompt-char cap below
                        // fires AFTER JSON parsing, which is too late if the
                        // body itself is huge. ~2 bytes per char covers JSON
                        // escapes and structure overhead with margin.
                        val maxChars = Settings.maxPromptChars(this@LLMServerService)
                        val bodyCap = maxChars.toLong() * 2L + 8_192L
                        val contentLength = call.request.headers["Content-Length"]?.toLongOrNull()
                        if (contentLength != null && contentLength > bodyCap) {
                            call.respond(
                                HttpStatusCode.PayloadTooLarge,
                                ErrorResponse(ErrorDetails(
                                    message = "Request body of $contentLength bytes exceeds cap of $bodyCap",
                                    type = "invalid_request_error",
                                    code = 413
                                ))
                            )
                            return@post
                        }

                        val req = try {
                            call.receive<ChatRequest>()
                        } catch (e: Exception) {
                            call.respond(
                                HttpStatusCode.BadRequest,
                                ErrorResponse(ErrorDetails(
                                    message = "Invalid JSON body: ${e.message}",
                                    type = "invalid_request_error",
                                    code = 400
                                ))
                            )
                            return@post
                        }
                        val promptChars = req.messages.sumOf { it.content.length }

                        // Prompt-size cap (413)
                        if (promptChars > maxChars) {
                            call.respond(
                                HttpStatusCode.PayloadTooLarge,
                                ErrorResponse(ErrorDetails(
                                    message = "Prompt of $promptChars chars exceeds limit of $maxChars",
                                    type = "invalid_request_error",
                                    code = 413
                                ))
                            )
                            return@post
                        }

                        // Queue cap (429) — atomic vs other concurrent requests
                        val maxDepth = Settings.maxQueueDepth(this@LLMServerService)
                        val entry = RequestTracker.tryEnqueue(
                            model = req.model,
                            stream = req.stream,
                            messageCount = req.messages.size,
                            promptChars = promptChars,
                            maxDepth = maxDepth
                        )
                        if (entry == null) {
                            call.response.headers.append("Retry-After", "5")
                            call.respond(
                                HttpStatusCode.TooManyRequests,
                                ErrorResponse(ErrorDetails(
                                    message = "Queue full ($maxDepth in flight). Retry shortly.",
                                    type = "rate_limit_error",
                                    code = 429
                                ))
                            )
                            return@post
                        }

                        // Client origin — useful when multiple apps share the server
                        val remoteIp = call.request.local.remoteHost
                        val ua = call.request.headers["User-Agent"] ?: "-"
                        val timeoutMs = Settings.requestTimeoutMs(this@LLMServerService)

                        // Session lifecycle: resolved once outside the inference, committed
                        // (or invalidated) once inference resolves either way.
                        var resolved: ResolvedSession? = null
                        var inferenceOk = false
                        var streamWriter: io.ktor.utils.io.ByteWriteChannel? = null
                        try {
                            LogManager.i("LLMServerService", "Request #${entry.id} from $remoteIp [$ua]: model=${req.model}, stream=${req.stream}, msgs=${req.messages.size}, chars=$promptChars, session=${req.sessionId?.ifEmpty { null } ?: "(stateless)"}")

                            val handle = getOrCreateEngine(req)
                            val temp = req.temperature ?: Settings.temperature(this@LLMServerService)
                            val topK = req.topK ?: Settings.topK(this@LLMServerService)
                            val resolvedLocal = resolveSession(req, handle, temp, topK)
                            resolved = resolvedLocal
                            val responseId = "chatcmpl-${entry.id}"
                            val needWakeLock = Settings.keepAwake(this@LLMServerService)

                            if (req.stream) {
                                call.response.cacheControl(CacheControl.NoCache(null))
                                call.respondBytesWriter(contentType = ContentType.Text.EventStream) {
                                    streamWriter = this@respondBytesWriter
                                    withTimeout(timeoutMs) {
                                        inferenceMutex.withLock {
                                            RequestTracker.markStarted(entry.id)
                                            withWakeLock(needWakeLock, timeoutMs) {
                                                runInferenceStreaming(
                                                    conversation = resolvedLocal.conversation,
                                                    prompt = resolvedLocal.prompt,
                                                    writer = this@respondBytesWriter,
                                                    responseId = responseId,
                                                    modelName = req.model,
                                                    onChunk = { chunk -> RequestTracker.recordChunk(entry.id, chunk) }
                                                )
                                            }
                                        }
                                    }
                                }
                            } else {
                                val responseText = withTimeout(timeoutMs) {
                                    inferenceMutex.withLock {
                                        RequestTracker.markStarted(entry.id)
                                        withWakeLock(needWakeLock, timeoutMs) {
                                            withContext(Dispatchers.Default) {
                                                runInferenceBlocking(resolvedLocal.conversation, resolvedLocal.prompt)
                                            }
                                        }
                                    }
                                }
                                RequestTracker.recordChunk(entry.id, responseText)

                                val resp = ChatResponse(
                                    id = responseId,
                                    `object` = "chat.completion",
                                    created = System.currentTimeMillis() / 1000,
                                    model = req.model,
                                    choices = listOf(
                                        Choice(
                                            index = 0,
                                            message = Message(role = "assistant", content = responseText),
                                            finishReason = "stop"
                                        )
                                    )
                                )
                                call.respond(resp)
                            }
                            inferenceOk = true
                            RequestTracker.markCompleted(entry.id)
                            lastActivityAt.set(System.currentTimeMillis())
                        } catch (te: TimeoutCancellationException) {
                            LogManager.e("LLMServerService", "Request #${entry.id} timed out after ${timeoutMs} ms")
                            // Tell the native engine to stop, otherwise generation
                            // keeps burning compute after the HTTP request is dead.
                            try { resolved?.conversation?.cancelProcess() } catch (_: Exception) {}
                            RequestTracker.markCompleted(entry.id, error = "timeout after ${timeoutMs} ms")
                            val w = streamWriter
                            if (w != null) {
                                writeSseError(w, "Inference timeout", "timeout", 408)
                            } else {
                                try {
                                    call.respond(
                                        HttpStatusCode.RequestTimeout,
                                        ErrorResponse(ErrorDetails("Inference timeout", "timeout", 408))
                                    )
                                } catch (_: Exception) { /* stream already started */ }
                            }
                        } catch (ce: kotlinx.coroutines.CancellationException) {
                            try { resolved?.conversation?.cancelProcess() } catch (_: Exception) {}
                            RequestTracker.markCompleted(entry.id, cancelled = true)
                            throw ce
                        } catch (e: Exception) {
                            LogManager.e("LLMServerService", "Request #${entry.id} error", e)
                            RequestTracker.markCompleted(entry.id, error = e.message ?: e.javaClass.simpleName)
                            val w = streamWriter
                            if (w != null) {
                                writeSseError(w, e.message ?: "Unknown error", "server_error", 500)
                            } else {
                                try {
                                    call.respond(
                                        HttpStatusCode.InternalServerError,
                                        ErrorResponse(ErrorDetails(
                                            message = e.message ?: "Unknown error",
                                            type = "server_error",
                                            code = 500
                                        ))
                                    )
                                } catch (_: Exception) { /* stream already started */ }
                            }
                        } finally {
                            val r = resolved
                            if (r != null) {
                                if (r.isCached) {
                                    if (inferenceOk) commitSession(r, req.messages)
                                    else invalidateSession(r)
                                } else {
                                    // Stateless: close the one-shot conversation regardless of outcome.
                                    closeIfStateless(r)
                                }
                            }
                        }
                    }
                }
            }.start(wait = false)

            val displayHost = if (host == "0.0.0.0") getLanIp() ?: "0.0.0.0" else host
            ServerState.setBoundUrl("http://$displayHost:$port")
            ServerState.setStatus(ServerState.Status.RUNNING)
            LogManager.i("LLMServerService", "Server listening on http://$displayHost:$port")
        } catch (e: Exception) {
            ServerState.setStatus(ServerState.Status.ERROR)
            val msg = e.message ?: e.javaClass.simpleName
            ServerState.setError("Failed to start server: $msg")
            ServerState.setBoundUrl(null)
            LogManager.e("LLMServerService", "Failed to start server", e)
        }
    }

    private fun getLanIp(): String? {
        try {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                if (iface.isLoopback || !iface.isUp) continue
                val addresses = iface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val addr = addresses.nextElement()
                    if (addr is java.net.Inet4Address && !addr.isLoopbackAddress) {
                        return addr.hostAddress
                    }
                }
            }
        } catch (e: Exception) {
            LogManager.e("LLMServerService", "Failed to get LAN IP", e)
        }
        return null
    }

    /**
     * Pulls the plain-text content out of a LiteRT-LM [LlmMessage]. Multimodal
     * outputs (images / audio) are ignored — we only render text chunks back
     * to the OpenAI-compatible client.
     */
    private fun messageText(msg: LlmMessage): String {
        val parts = msg.contents.contents
        if (parts.isEmpty()) return ""
        val sb = StringBuilder()
        for (p in parts) {
            if (p is Content.Text) sb.append(p.text)
        }
        return sb.toString()
    }

    /**
     * Streaming inference using a pre-built [conversation]. The conversation's
     * lifecycle is owned by the caller — this function never closes it.
     *
     * Writes OpenAI-style SSE chunks to [writer], notifies [onChunk] for stats,
     * and emits a heartbeat comment every 10s so long TTFTs aren't killed by
     * intermediaries or idle-connection detectors.
     *
     * Each [LlmMessage] emitted by LiteRT-LM is treated as a cumulative snapshot
     * of the generation so far; we diff against the previous snapshot to extract
     * the delta. If we instead receive deltas (some LiteRT-LM build configs do
     * that), the diff logic still produces the right result because the previous
     * snapshot never becomes a prefix of an unrelated string.
     *
     * Caller is expected to hold [inferenceMutex].
     */
    private suspend fun runInferenceStreaming(
        conversation: Conversation,
        prompt: String,
        writer: ByteWriteChannel,
        responseId: String,
        modelName: String,
        onChunk: (String) -> Unit = {}
    ) {
        val writeMutex = Mutex()  // serialize writer access across heartbeat + chunks

        suspend fun safeWrite(s: String) {
            writeMutex.withLock {
                writer.writeStringUtf8(s)
                writer.flush()
            }
        }

        val heartbeat = serviceScope.launch {
            while (isActive) {
                delay(10_000L)
                try { safeWrite(": ka\n\n") } catch (_: Throwable) { return@launch }
            }
        }

        try {
            val initResp = StreamResponse(
                id = responseId,
                `object` = "chat.completion.chunk",
                created = System.currentTimeMillis() / 1000,
                model = modelName,
                choices = listOf(StreamChoice(0, StreamDelta(role = "assistant"), null))
            )
            safeWrite("data: ${gson.toJson(initResp)}\n\n")

            var prev = ""
            conversation.sendMessageAsync(Contents.of(prompt)).collect { msg ->
                val full = messageText(msg)
                val delta = if (full.startsWith(prev) && full.length > prev.length) full.substring(prev.length)
                            else if (full == prev) ""
                            else full   // not a prefix → treat as delta-mode emission
                if (delta.isNotEmpty()) {
                    prev = if (full.startsWith(prev)) full else prev + delta
                    onChunk(delta)
                    val chunkResp = StreamResponse(
                        id = responseId,
                        `object` = "chat.completion.chunk",
                        created = System.currentTimeMillis() / 1000,
                        model = modelName,
                        choices = listOf(StreamChoice(0, StreamDelta(content = delta), null))
                    )
                    safeWrite("data: ${gson.toJson(chunkResp)}\n\n")
                }
            }

            val finalResp = StreamResponse(
                id = responseId,
                `object` = "chat.completion.chunk",
                created = System.currentTimeMillis() / 1000,
                model = modelName,
                choices = listOf(StreamChoice(0, StreamDelta(), "stop"))
            )
            safeWrite("data: ${gson.toJson(finalResp)}\n\n")
            safeWrite("data: [DONE]\n\n")
        } finally {
            heartbeat.cancel()
        }
    }

    /**
     * Non-streaming inference. Like [runInferenceStreaming], the conversation is
     * caller-owned. Caller is expected to hold [inferenceMutex].
     */
    private fun runInferenceBlocking(conversation: Conversation, prompt: String): String {
        val response = conversation.sendMessage(Contents.of(prompt), emptyMap())
        return messageText(response)
    }

    /**
     * Emit an OpenAI-shaped error as a final SSE chunk followed by the [DONE]
     * sentinel. Used when an exception fires AFTER the SSE response has already
     * committed headers — at that point [call.respond] is a no-op, so the only
     * way to tell the client what went wrong is to write into the open stream.
     *
     * Swallows IOException because the client may have already disconnected.
     */
    private suspend fun writeSseError(writer: io.ktor.utils.io.ByteWriteChannel, message: String, type: String, code: Int) {
        try {
            val json = gson.toJson(ErrorResponse(ErrorDetails(message, type, code)))
            writer.writeStringUtf8("data: $json\n\n")
            writer.writeStringUtf8("data: [DONE]\n\n")
            writer.flush()
        } catch (_: java.io.IOException) {
            // Client gone; nothing actionable.
        } catch (_: Exception) {
            // Defensive: never let error-reporting itself throw out of a catch arm.
        }
    }

    /**
     * Returns true if the request carries a valid bearer token (or auth is disabled).
     * On failure, writes a 401 response and returns false — the caller should bail out.
     */
    private suspend fun authorize(call: ApplicationCall): Boolean {
        val configured = Settings.apiKey(this)
        if (configured.isEmpty()) return true
        val header = call.request.headers["Authorization"]
        val ok = header != null && header.startsWith("Bearer ") &&
            header.substring(7).trim() == configured
        if (!ok) {
            call.response.headers.append("WWW-Authenticate", "Bearer")
            call.respond(
                HttpStatusCode.Unauthorized,
                ErrorResponse(ErrorDetails("Invalid or missing API key", "invalid_api_key", 401))
            )
        }
        return ok
    }

    /**
     * Hold a partial wake lock for the duration of [block]. The lock has a hard
     * timeout slightly larger than the inference budget so a buggy code path
     * can't drain the battery forever.
     */
    private suspend inline fun <T> withWakeLock(enabled: Boolean, timeoutMs: Long, crossinline block: suspend () -> T): T {
        if (!enabled) return block()
        @Suppress("WakelockTimeout")
        wakeLock.acquire(timeoutMs + 5_000L)
        return try {
            block()
        } finally {
            try { if (wakeLock.isHeld) wakeLock.release() } catch (_: Exception) {}
        }
    }

    /**
     * Build a fresh [Conversation] pre-loaded with the OpenAI-style chat history
     * minus the final user turn (which the caller will send via sendMessage).
     * System messages are collapsed into [ConversationConfig.systemInstruction];
     * the rest become [ConversationConfig.initialMessages] so they prefill the
     * KV cache without triggering generation.
     */
    private fun createConversation(
        engine: Engine,
        temperature: Float,
        topK: Int,
        systemText: String?,
        initial: List<Message>
    ): Conversation {
        val systemInstruction = systemText?.takeIf { it.isNotBlank() }?.let { Contents.of(it) }
        val priorMessages = initial.map { m ->
            when (m.role) {
                "assistant" -> LlmMessage.Companion.model(Contents.of(m.content), emptyList(), emptyMap())
                "system"    -> LlmMessage.Companion.system(m.content)
                else        -> LlmMessage.Companion.user(m.content)
            }
        }
        val cfg = ConversationConfig(
            systemInstruction,
            priorMessages,
            emptyList(),                                       // tools
            SamplerConfig(topK, /*topP=*/0.95, temperature.toDouble(), /*seed=*/0)
        )
        return engine.createConversation(cfg)
    }

    private data class EngineHandle(val engine: Engine, val cacheKey: String)

    /**
     * Map an explicit user backend choice (CPU / GPU) to a [Backend] instance.
     * NOT used for AUTO — that path is resolved in [getOrCreateEngine] with a
     * try/fallback so it can actually probe what works on this device.
     */
    private fun resolveBackend(choice: String): Backend = when (choice) {
        Settings.BACKEND_GPU -> Backend.GPU()
        else                 -> Backend.CPU()
    }

    /**
     * Construct + initialize a fresh engine on the given backend. Throws on
     * any failure (lib-missing, OOM, op unsupported, model corrupt, …). Kept
     * as a small seam so the AUTO fallback path can call this twice without
     * duplicating the EngineConfig wiring.
     */
    private fun buildEngine(modelFile: File, maxTokens: Int?, backend: Backend): Engine {
        val cfg = EngineConfig(
            modelFile.absolutePath,
            backend,
            /*visionBackend=*/null,
            /*audioBackend=*/null,
            /*maxNumTokens=*/maxTokens,
            /*maxNumImages=*/null,
            /*cacheDir=*/null
        )
        return Engine(cfg).also { it.initialize() }
    }

    /**
     * Engine cache lookup. Builds a new engine when this exact
     * (model, maxTokens, backend) combination isn't cached. AUTO is meaningful
     * here: try GPU, on failure log + fall back to CPU. Explicit CPU / GPU
     * choices are strict (no fallback) so the user can actually debug them.
     */
    private fun getOrCreateEngine(req: ChatRequest): EngineHandle {
        // Only honor a per-request maxTokens cap when the client explicitly
        // sent one. Otherwise pass null so LiteRT-LM uses the budget that the
        // model file was compiled with — overriding it with our generic
        // Settings.maxTokens (default 1024) is what produces the
        // DYNAMIC_UPDATE_SLICE shape mismatch on big-context Gemma 4 weights.
        val maxTokens: Int? = req.maxTokens
        val backendChoice = Settings.backend(this)
        val cacheKey = "${req.model}_${maxTokens ?: "model"}_${backendChoice}"

        engines.get(cacheKey)?.let { return EngineHandle(it.engine, cacheKey) }

        val modelFile = getModelFile(req.model)
        if (!modelFile.exists()) {
            throw IllegalStateException("Model file not found: ${modelFile.name}. Download or import it first.")
        }

        LogManager.i("LLMServerService", "Loading engine for $cacheKey")
        val (engine, actualBackend) = try {
            when (backendChoice) {
                Settings.BACKEND_AUTO -> {
                    // Prefer GPU; fall back to CPU if init throws (libvndksupport
                    // missing, OpenCL driver missing, op unsupported, …).
                    try {
                        buildEngine(modelFile, maxTokens, Backend.GPU()) to "GPU"
                    } catch (e: Exception) {
                        LogManager.w("LLMServerService", "GPU init failed for $cacheKey, falling back to CPU: ${e.message}")
                        buildEngine(modelFile, maxTokens, Backend.CPU()) to "CPU"
                    }
                }
                Settings.BACKEND_GPU -> buildEngine(modelFile, maxTokens, Backend.GPU()) to "GPU"
                else                 -> buildEngine(modelFile, maxTokens, Backend.CPU()) to "CPU"
            }
        } catch (e: Exception) {
            throw IllegalStateException("Failed to initialize engine: ${e.message ?: e.javaClass.simpleName}", e)
        }

        try {
            engines.put(cacheKey, CachedEngine(engine, actualBackend))
        } catch (e: Exception) {
            try { engine.close() } catch (_: Exception) {}
            throw e
        }
        return EngineHandle(engine, cacheKey)
    }

    /**
     * Stable hash of `messages[0 until count]`. Used to validate that a client
     * isn't lying about conversation continuity: if their replayed prefix
     * doesn't match what we recorded, we reset the cached conversation.
     */
    private fun messagesPrefixHash(messages: List<Message>, count: Int): Long {
        var h = 1L
        val n = minOf(count, messages.size)
        for (i in 0 until n) {
            val m = messages[i]
            h = h * 31L + m.role.hashCode()
            h = h * 31L + m.content.hashCode()
        }
        return h
    }

    /**
     * Decide whether to reuse a cached conversation or build a fresh one, and
     * compute the prompt fragment to send accordingly.
     *
     * Stateless (empty `session_id`): always a fresh conversation prefilled
     * with all-but-the-last message; caller sends the last user turn and must
     * close on exit.
     *
     * Sessioned: look up the cache. Reuse only when
     *   - sampling params match (different temperature/top_k → different conversation)
     *   - the cached prefix hash matches what the client just replayed
     *   - `messages.size > cached.seenCount`
     *   - the new range collapses to exactly one user turn after filtering out
     *     assistant turns (which are already in the KV cache) and system turns
     *     (which can't be retroactively re-bound)
     * Otherwise we rebuild from scratch.
     */
    private fun resolveSession(
        req: ChatRequest,
        handle: EngineHandle,
        temperature: Float,
        topK: Int
    ): ResolvedSession {
        val systemText = req.messages.firstOrNull { it.role == "system" }?.content
        val nonSystem = req.messages.filter { it.role != "system" }
        if (nonSystem.isEmpty() || nonSystem.last().role != "user") {
            throw IllegalArgumentException("Last message must have role=user")
        }
        val lastUserPrompt = nonSystem.last().content
        val prior = nonSystem.dropLast(1)

        // Stateless path.
        if (req.sessionId.isNullOrEmpty()) {
            val conversation = createConversation(handle.engine, temperature, topK, systemText, prior)
            return ResolvedSession(
                conversation = conversation,
                prompt = lastUserPrompt,
                cacheKey = null,
                engineKey = handle.cacheKey,
                temperature = temperature,
                topK = topK
            )
        }

        val cacheKey = "${req.sessionId}_${handle.cacheKey}"
        val cached = sessions.get(cacheKey)

        val canReuse = cached != null &&
            cached.temperature == temperature &&
            cached.topK == topK &&
            cached.seenCount < req.messages.size &&
            cached.prefixHash == messagesPrefixHash(req.messages, cached.seenCount) &&
            run {
                // The "new range" since the cached conversation last saw the client.
                // Reuse is only safe when this contains exactly one user turn
                // (the rest must be assistant turns already replayed back by the
                // server, which the engine already has in its KV cache).
                val newRange = req.messages.subList(cached.seenCount, req.messages.size)
                val nonAssistant = newRange.filter { it.role != "assistant" }
                nonAssistant.size == 1 && nonAssistant[0].role == "user"
            }

        if (canReuse) {
            cached!!
            val newUser = req.messages.subList(cached.seenCount, req.messages.size)
                .first { it.role != "assistant" }
            LogManager.i("LLMServerService", "Session $cacheKey reused (sending 1 new user turn)")
            return ResolvedSession(
                conversation = cached.conversation,
                prompt = newUser.content,
                cacheKey = cacheKey,
                engineKey = handle.cacheKey,
                temperature = temperature,
                topK = topK
            )
        }

        // Rebuild path — either no cache, sampling params changed, prefix
        // mismatched, or the client added something we can't merge in-place.
        if (cached != null) sessions.remove(cacheKey)
        val conversation = createConversation(handle.engine, temperature, topK, systemText, prior)
        return ResolvedSession(
            conversation = conversation,
            prompt = lastUserPrompt,
            cacheKey = cacheKey,
            engineKey = handle.cacheKey,
            temperature = temperature,
            topK = topK
        )
    }

    /**
     * Call on successful generation. Stores or updates the conversation in cache so
     * the next request for this session_id can pick up where we left off.
     * No-op for stateless conversations — caller must close those explicitly.
     */
    private fun commitSession(resolved: ResolvedSession, messages: List<Message>) {
        val cacheKey = resolved.cacheKey ?: return
        sessions.put(cacheKey, CachedSession(
            conversation = resolved.conversation,
            engineKey = resolved.engineKey,
            temperature = resolved.temperature,
            topK = resolved.topK,
            prefixHash = messagesPrefixHash(messages, messages.size),
            seenCount = messages.size,
            createdAt = sessions.get(cacheKey)?.createdAt ?: System.currentTimeMillis()
        ))
    }

    /**
     * Call on failure to drop a (possibly half-initialized) conversation from cache.
     * Closes the conversation as a side effect.
     */
    private fun invalidateSession(resolved: ResolvedSession) {
        val cacheKey = resolved.cacheKey
        if (cacheKey != null) {
            sessions.remove(cacheKey)
        } else {
            try { resolved.conversation.close() } catch (_: Exception) {}
        }
    }

    /** Stateless cleanup helper. */
    private fun closeIfStateless(resolved: ResolvedSession) {
        if (!resolved.isCached) {
            try { resolved.conversation.close() } catch (_: Exception) {}
        }
    }

    override fun onDestroy() {
        ServerState.setStatus(ServerState.Status.STOPPED)
        ServerState.setBoundUrl(null)
        idleMonitorJob?.cancel()
        serviceScope.cancel()
        try { if (wakeLock.isHeld) wakeLock.release() } catch (_: Exception) {}
        try {
            server?.stop(500, 1000)
            server = null
        } catch (e: Exception) {
            LogManager.e("LLMServerService", "Error stopping server", e)
        }
        // Close conversations before engines — conversations reference engines and must
        // not outlive them.
        sessions.evictAll()
        engines.evictAll()
        try {
            kotlinx.coroutines.runBlocking { RequestTracker.resetAll() }
        } catch (_: Exception) {}
        LogManager.i("LLMServerService", "Server stopped")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_STOP = "com.localllm.app.ACTION_STOP"
    }
}
