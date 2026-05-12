package com.localllm.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
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
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
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
    private var server: NettyApplicationEngine? = null

    /**
     * Engine cache. Keyed by `model_maxTokens_backend` because MediaPipe's
     * `LlmInferenceOptions.setMaxTokens` is the *total* KV-cache budget
     * (input + output), not just an output cap. Reusing an engine built
     * with a larger budget for a request that asked for less would let the
     * model overgenerate. Each model takes 1–2 GB so we keep at most 2 resident.
     *
     * When an engine is evicted, every session that holds a handle to it is
     * also evicted first — sessions can't outlive their parent engine.
     */
    private val engines = object : LruCache<String, LlmInference>(2) {
        override fun entryRemoved(evicted: Boolean, key: String?, oldValue: LlmInference?, newValue: LlmInference?) {
            if (evicted) {
                // Close sessions tied to this engine BEFORE closing the engine —
                // a session referencing a closed engine is undefined behavior.
                if (key != null) {
                    val tied = sessions.snapshot().filter { it.value.engineKey == key }.keys
                    tied.forEach { sessions.remove(it) }
                }
                try {
                    oldValue?.close()
                    LogManager.i("LLMServerService", "Evicted engine: $key")
                } catch (e: Exception) {
                    LogManager.e("LLMServerService", "Error closing evicted engine", e)
                }
            }
        }
    }

    /**
     * Cached `LlmInferenceSession`s keyed by `session_id + engineKey`. Sessions
     * preserve the KV cache across turns: on a follow-up request we only need
     * to `addQueryChunk` the NEW user turns. Saves the cost of re-tokenizing
     * and re-prefilling the full conversation each time.
     *
     * Bounded at 4 cached sessions — beyond that the LRU evicts oldest.
     */
    private data class CachedSession(
        val session: LlmInferenceSession,
        val engineKey: String,
        val temperature: Float,
        val topK: Int,
        val prefixHash: Long,
        val seenCount: Int,
        val createdAt: Long
    )

    private val sessions = object : LruCache<String, CachedSession>(4) {
        override fun entryRemoved(evicted: Boolean, key: String?, oldValue: CachedSession?, newValue: CachedSession?) {
            // Always close the session — both eviction and explicit removal go
            // through here. Compare by identity so a put() that replaces the
            // entry with the SAME session doesn't accidentally close it.
            if (oldValue != null && oldValue.session !== newValue?.session) {
                try { oldValue.session.close() } catch (_: Exception) {}
            }
        }
    }

    /**
     * What [resolveSession] returns. Carries enough state for the caller to
     * either commit (on success) or invalidate (on failure) the session.
     */
    private data class ResolvedSession(
        val session: LlmInferenceSession,
        val prompt: String,
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
        val filename = if (modelId.endsWith(".task")) modelId else "$modelId.task"
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
        return START_STICKY
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

    private fun formatGemmaPrompt(messages: List<Message>): String {
        val sb = StringBuilder()
        for (msg in messages) {
            val role = if (msg.role == "system") "user" else msg.role
            sb.append("<start_of_turn>$role\n")
            sb.append(msg.content)
            sb.append("<end_of_turn>\n")
        }
        sb.append("<start_of_turn>model\n")
        return sb.toString()
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
                            "engines_loaded" to engines.size()
                        ))
                    }

                    get("/v1/models") {
                        if (!authorize(call)) return@get
                        val dir = getExternalFilesDir(null)
                        val files = dir?.listFiles { file -> file.name.endsWith(".task") } ?: emptyArray()
                        val models = files.map { file ->
                            val modelId = file.name.removeSuffix(".task")
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
                        try {
                            LogManager.i("LLMServerService", "Request #${entry.id} from $remoteIp [$ua]: model=${req.model}, stream=${req.stream}, msgs=${req.messages.size}, chars=$promptChars, session=${req.sessionId.ifEmpty { "(stateless)" }}")

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
                                    withTimeout(timeoutMs) {
                                        inferenceMutex.withLock {
                                            RequestTracker.markStarted(entry.id)
                                            withWakeLock(needWakeLock, timeoutMs) {
                                                runInferenceStreaming(
                                                    session = resolvedLocal.session,
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
                                                runInferenceBlocking(resolvedLocal.session, resolvedLocal.prompt)
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
                            RequestTracker.markCompleted(entry.id, error = "timeout after ${timeoutMs} ms")
                            try {
                                call.respond(
                                    HttpStatusCode.RequestTimeout,
                                    ErrorResponse(ErrorDetails("Inference timeout", "timeout", 408))
                                )
                            } catch (_: Exception) { /* stream already started */ }
                        } catch (ce: kotlinx.coroutines.CancellationException) {
                            RequestTracker.markCompleted(entry.id, cancelled = true)
                            throw ce
                        } catch (e: Exception) {
                            LogManager.e("LLMServerService", "Request #${entry.id} error", e)
                            RequestTracker.markCompleted(entry.id, error = e.message ?: e.javaClass.simpleName)
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
                        } finally {
                            val r = resolved
                            if (r != null) {
                                if (r.isCached) {
                                    if (inferenceOk) commitSession(r, req.messages)
                                    else invalidateSession(r)
                                } else {
                                    // Stateless: close the one-shot session regardless of outcome.
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
     * Streaming inference using a pre-built [session]. The session's lifecycle
     * is owned by the caller — this function never closes it.
     *
     * Writes OpenAI-style SSE chunks to [writer], notifies [onChunk] for stats,
     * and emits a heartbeat comment every 10s so long TTFTs aren't killed by
     * intermediaries or idle-connection detectors.
     *
     * Caller is expected to hold [inferenceMutex].
     */
    private suspend fun runInferenceStreaming(
        session: LlmInferenceSession,
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
            session.addQueryChunk(prompt)
            val flow = callbackFlow<Pair<String, Boolean>> {
                session.generateResponseAsync { partialResult, done ->
                    trySend(Pair(partialResult, done))
                    if (done) close()
                }
                awaitClose { /* No-op; cancellation is best-effort */ }
            }

            val initResp = StreamResponse(
                id = responseId,
                `object` = "chat.completion.chunk",
                created = System.currentTimeMillis() / 1000,
                model = modelName,
                choices = listOf(StreamChoice(0, StreamDelta(role = "assistant"), null))
            )
            safeWrite("data: ${gson.toJson(initResp)}\n\n")

            flow.collect { (chunk, done) ->
                if (chunk.isNotEmpty()) {
                    onChunk(chunk)
                    val chunkResp = StreamResponse(
                        id = responseId,
                        `object` = "chat.completion.chunk",
                        created = System.currentTimeMillis() / 1000,
                        model = modelName,
                        choices = listOf(StreamChoice(0, StreamDelta(content = chunk), if (done) "stop" else null))
                    )
                    safeWrite("data: ${gson.toJson(chunkResp)}\n\n")
                }
            }

            safeWrite("data: [DONE]\n\n")
        } finally {
            heartbeat.cancel()
        }
    }

    /**
     * Non-streaming inference. Like [runInferenceStreaming], the session is
     * caller-owned. Caller is expected to hold [inferenceMutex].
     */
    private fun runInferenceBlocking(session: LlmInferenceSession, prompt: String): String {
        session.addQueryChunk(prompt)
        return session.generateResponse()
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

    private fun createSession(engine: LlmInference, temperature: Float, topK: Int): LlmInferenceSession {
        val options = LlmInferenceSession.LlmInferenceSessionOptions.builder()
            .setTemperature(temperature)
            .setTopK(topK)
            .build()
        return LlmInferenceSession.createFromOptions(engine, options)
    }

    private data class EngineHandle(val engine: LlmInference, val cacheKey: String)

    /**
     * Engine cache lookup. Builds a new engine when this exact
     * (model, maxTokens, backend) combination isn't cached. Returns both the
     * engine and its cache key so callers (notably session resolution) can
     * tag downstream resources with the right parent.
     */
    private fun getOrCreateEngine(req: ChatRequest): EngineHandle {
        val maxTokens = req.maxTokens ?: Settings.maxTokens(this)
        val backendChoice = Settings.backend(this)
        val cacheKey = "${req.model}_${maxTokens}_${backendChoice}"

        engines.get(cacheKey)?.let { return EngineHandle(it, cacheKey) }

        val modelFile = getModelFile(req.model)
        if (!modelFile.exists()) {
            throw IllegalStateException("Model file not found: ${modelFile.name}. Download or import it first.")
        }

        LogManager.i("LLMServerService", "Loading engine for $cacheKey")
        val builder = LlmInference.LlmInferenceOptions.builder()
            .setModelPath(modelFile.absolutePath)
            .setMaxTokens(maxTokens)

        // Apply backend preference. AUTO == DEFAULT — let MediaPipe pick the best
        // available backend for this device, which on Pixel 10 / Tensor G5 means
        // the NPU is used when the model is NPU-compatible.
        val backendEnum = when (backendChoice) {
            Settings.BACKEND_CPU -> LlmInference.Backend.CPU
            Settings.BACKEND_GPU -> LlmInference.Backend.GPU
            else -> LlmInference.Backend.DEFAULT
        }
        builder.setPreferredBackend(backendEnum)

        val engine = try {
            LlmInference.createFromOptions(this, builder.build())
        } catch (e: Exception) {
            val msg = e.message ?: ""
            if (msg.contains("zip", ignoreCase = true)) {
                throw IllegalStateException("Failed to initialize engine: Model file is not a valid zip archive. It might be corrupted or in an unsupported format. Try re-downloading.", e)
            }
            throw IllegalStateException("Failed to initialize engine: $msg", e)
        }

        try {
            engines.put(cacheKey, engine)
        } catch (e: Exception) {
            try { engine.close() } catch (_: Exception) {}
            throw e
        }
        return EngineHandle(engine, cacheKey)
    }

    /**
     * Stable hash of `messages[0 until count]`. Used to validate that a client
     * isn't lying about conversation continuity: if their replayed prefix
     * doesn't match what we recorded, we reset the cached session.
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
     * Decide whether to reuse a cached session or build a fresh one, and
     * compute the prompt fragment to `addQueryChunk` accordingly.
     *
     * Stateless (empty `session_id`): always a fresh session, full prompt,
     * caller must close on exit.
     *
     * Sessioned: look up the cache. Reuse only when
     *   - sampling params match (different temperature/top_k → different session)
     *   - the cached prefix hash matches what the client just replayed
     *   - `messages.size >= cached.seenCount`
     * On reuse we addQueryChunk only the NEW user/system turns; assistant
     * turns in the new range are already in the model's KV cache from the
     * previous generation and would corrupt the conversation if re-fed.
     */
    private fun resolveSession(
        req: ChatRequest,
        handle: EngineHandle,
        temperature: Float,
        topK: Int
    ): ResolvedSession {
        // Stateless path.
        if (req.sessionId.isEmpty()) {
            val session = createSession(handle.engine, temperature, topK)
            return ResolvedSession(
                session = session,
                prompt = formatGemmaPrompt(req.messages),
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
            cached.seenCount <= req.messages.size &&
            cached.prefixHash == messagesPrefixHash(req.messages, cached.seenCount)

        if (canReuse) {
            cached!!
            val newRange = req.messages.subList(cached.seenCount, req.messages.size)
            // Skip assistant turns in the new range — those are server-generated
            // and already in the KV cache. Only feed user / system turns.
            val toAdd = newRange.filter { it.role != "assistant" }
            if (toAdd.isNotEmpty()) {
                LogManager.i("LLMServerService", "Session $cacheKey reused (added ${toAdd.size} of ${newRange.size} new turns)")
                return ResolvedSession(
                    session = cached.session,
                    prompt = formatGemmaPrompt(toAdd),
                    cacheKey = cacheKey,
                    engineKey = handle.cacheKey,
                    temperature = temperature,
                    topK = topK
                )
            }
            // Empty after filtering: client only added assistant turns. Treat as
            // a regeneration request — rebuild to be safe rather than try to
            // re-prompt an in-progress conversation.
            sessions.remove(cacheKey)
        }

        // Rebuild path.
        val session = createSession(handle.engine, temperature, topK)
        return ResolvedSession(
            session = session,
            prompt = formatGemmaPrompt(req.messages),
            cacheKey = cacheKey,
            engineKey = handle.cacheKey,
            temperature = temperature,
            topK = topK
        )
    }

    /**
     * Call on successful generation. Stores or updates the session in cache so
     * the next request for this session_id can pick up where we left off.
     * No-op for stateless sessions — caller must close those explicitly.
     */
    private fun commitSession(resolved: ResolvedSession, messages: List<Message>) {
        val cacheKey = resolved.cacheKey ?: return
        sessions.put(cacheKey, CachedSession(
            session = resolved.session,
            engineKey = resolved.engineKey,
            temperature = resolved.temperature,
            topK = resolved.topK,
            prefixHash = messagesPrefixHash(messages, messages.size),
            seenCount = messages.size,
            createdAt = sessions.get(cacheKey)?.createdAt ?: System.currentTimeMillis()
        ))
    }

    /**
     * Call on failure to drop a (possibly half-initialized) session from cache.
     * Closes the session as a side effect.
     */
    private fun invalidateSession(resolved: ResolvedSession) {
        val cacheKey = resolved.cacheKey
        if (cacheKey != null) {
            sessions.remove(cacheKey)
        } else {
            try { resolved.session.close() } catch (_: Exception) {}
        }
    }

    /** Stateless cleanup helper. */
    private fun closeIfStateless(resolved: ResolvedSession) {
        if (!resolved.isCached) {
            try { resolved.session.close() } catch (_: Exception) {}
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
        // Close sessions before engines — sessions reference engines and must
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
