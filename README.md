# LocalLLM

An on-device, OpenAI-compatible LLM HTTP server for Android. Runs Gemma 4 (or
any compatible MediaPipe `.task` bundle) locally and exposes a
`/v1/chat/completions` endpoint that any client speaking the OpenAI API can
talk to — no cloud, no API key, no data leaves the device.

## What it does

- Loads MediaPipe `tasks-genai` LLM models (Gemma 4 E2B / E4B by default)
- Runs an embedded Ktor HTTP server with three endpoints:
  - `GET /health` — liveness check
  - `GET /v1/models` — list models present on disk
  - `POST /v1/chat/completions` — OpenAI-style chat completion, streaming or not
- Runs as a foreground service with a persistent notification (and a Stop action)
- Can autostart on app launch and on device boot
- Optionally binds `0.0.0.0` so other devices on the same Wi-Fi can use it

## Tabs

- **Catalog** — download a built-in model, import a `.task` file from the device, or pull from a custom URL configured in Settings. Delete to free space.
- **Console** — live in-memory log stream from the service.
- **Chat** — quick test harness that hits the local server (`127.0.0.1:<port>`).
- **Settings** — port, LAN bind, max tokens, temperature, top-k, start-on-boot, autostart, custom model URLs, Start/Stop server.

## Calling the server

```bash
curl http://127.0.0.1:8080/v1/chat/completions \
  -H "Content-Type: application/json" \
  -d '{
    "model": "gemma-4-E2B-it-web",
    "messages": [{"role": "user", "content": "Hello"}],
    "stream": false
  }'
```

Streaming (SSE):

```bash
curl -N http://127.0.0.1:8080/v1/chat/completions \
  -H "Content-Type: application/json" \
  -d '{
    "model": "gemma-4-E2B-it-web",
    "messages": [{"role": "user", "content": "Tell me a story."}],
    "stream": true,
    "temperature": 0.7,
    "top_k": 40,
    "max_tokens": 256
  }'
```

Use the model `id` from `GET /v1/models` — it's the filename minus `.task`.

From a laptop on the same Wi-Fi, enable **Bind to LAN** in Settings, then hit
`http://<phone-ip>:8080/...`. From the Android emulator, use `10.0.2.2` for
the host's localhost.

## Custom models

Any MediaPipe `tasks-genai` `.task` bundle should work. Two ways to add one:

1. **Custom URL** — Settings → Custom model URLs, one URL per line. Must end in `.task`. The Catalog tab will show it as a downloadable entry.
2. **Local import** — Catalog → "Import .task file from device" and pick a `.task` from the file picker.

## Build

```bash
cd localllm-android
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

`local.properties` needs `sdk.dir=...` pointing at your Android SDK.

Min SDK 29, target 34, compile 35. Built with Kotlin 2.0.21, AGP 8.7.3, MediaPipe `tasks-genai` 0.10.35.

## Backend selection (Pixel 10 / NPU)

In Settings → Inference defaults → **Backend** you can choose:

- **Auto** (default, recommended) — passes `Backend.DEFAULT` to MediaPipe. On Pixel 10 / Tensor G5, the runtime picks the NPU when the loaded `.task` model is NPU-compatible; otherwise it falls back to GPU or CPU. This is the only path that reaches the Tensor G5 NPU through the public `tasks-genai` API.
- **CPU** — forces `Backend.CPU`. Slowest but always works. Useful as a control for benchmarking.
- **GPU** — forces `Backend.GPU`. Usually faster than CPU on modern Pixels.

The MediaPipe public `Backend` enum has exactly three values: `DEFAULT`, `CPU`, `GPU`. There's no separate "NPU" or "TPU" enum value — NPU access is implicit in `DEFAULT` on supported devices. If you need explicit/fine-grained TPU control on Pixel 10, that lives in Google's newer **LiteRT-LM** runtime (a separate library / API), which is on our follow-up list.

Switching the backend forces an engine rebuild on the next request (cold load, several seconds). The engine cache key is `model + maxTokens + backend`.

## Multi-app use & limits

The server is designed to be hit by multiple apps on the same device (or LAN with `Bind to LAN` enabled). Settings → Request limits exposes:

- **Request timeout** (default 120s) — beyond this we return `408` or close the SSE stream.
- **Max queue depth** (default 8) — beyond this we return `429 Too Many Requests` with `Retry-After: 5`. The atomic `tryEnqueue` ensures the cap is honored even under concurrent enqueues.
- **Max prompt chars** (default 100,000) — beyond this we return `413 Payload Too Large`.

Settings → Security exposes an optional **API key**. When set, every `/v1/chat/completions` and `/v1/models` request must carry `Authorization: Bearer <key>`. `/health` stays unauthenticated for liveness probes.

Each request is logged with its **remote IP and User-Agent**, so you can tell which app is doing what.

## Background efficiency

Settings → Background:

- **Unload models after N min idle** (default 5) — frees the engine LRU's ~1–2 GB per model when nothing is happening. Set to `0` to disable.
- **Auto-stop server after N min idle** (default `0`, disabled) — stops the foreground service entirely. The user will need to relaunch the app or boot.
- **Keep CPU awake during inference** (default on) — holds a `PARTIAL_WAKE_LOCK` only while a request is running, so Doze can't throttle inference mid-stream. The lock has a hard timeout slightly above the configured request timeout — defense against any bug that forgets to release it.

The idle monitor is a single 30s-tick coroutine inside the service. Eviction only runs when the inference mutex isn't held, so it can never interrupt an active request.

## Endpoints

| Path | Notes |
|---|---|
| `GET /health` | Liveness. Includes `queue_depth` and `engines_loaded` for client-side health checks. Never gated by the API key. |
| `GET /v1/models` | Gated by API key when set. Lists `.task` files on disk. |
| `POST /v1/chat/completions` | Gated by API key. Honors `stream`, `temperature`, `top_k`, `max_tokens` per request. |

## Notes

- Inference is serialized via a `Mutex`: only one request at a time per device, even if multiple clients connect. MediaPipe's GPU/NPU backend is single-tenant in practice anyway.
- Per-request `temperature` and `top_k` are applied via `LlmInferenceSession` — the engine itself doesn't accept them.
- The Stop button on the notification cleanly tears down the server, cancels the service coroutine scope, releases the wakelock, and evicts cached engines.
- The Dashboard tab shows the live queue, the in-flight request, recent history (cap 50), and cumulative stats (counts, avg latency, avg tok/s, error rate).

## Multi-turn (`session_id`)

To take advantage of KV-cache reuse across turns, pass a stable `session_id`
on each request in a conversation:

```jsonc
// Turn 1
POST /v1/chat/completions
{
  "model": "gemma-4-E2B-it-web",
  "session_id": "alice-2026-05-10",
  "messages": [
    {"role": "user", "content": "Hi"}
  ]
}

// Turn 2 — same session_id, full message history echoed (OpenAI convention)
POST /v1/chat/completions
{
  "model": "gemma-4-E2B-it-web",
  "session_id": "alice-2026-05-10",
  "messages": [
    {"role": "user", "content": "Hi"},
    {"role": "assistant", "content": "Hello! How can I help?"},
    {"role": "user", "content": "Tell me about kernels"}
  ]
}
```

Behavior:
- Empty / omitted `session_id` → fresh stateless session every request (default; matches the previous behavior).
- Non-empty `session_id` → server caches the `LlmInferenceSession` and on follow-up requests only `addQueryChunk`s the **new user turns**. Assistant turns echoed by the client are skipped because they're already in the model's KV cache.
- The server validates the replayed prefix via a hash. If the prefix doesn't match what was recorded — e.g., you rewound, edited, or used the same `session_id` for a different conversation — the session is rebuilt from scratch (correct but slow).
- Cache holds at most 4 sessions; oldest evicted. Sessions are evicted along with their parent engine when the engine is unloaded (idle eviction or LRU pressure).
- Changing `temperature` or `top_k` mid-session triggers a rebuild (those are session-construction params in MediaPipe).

The big win: on a 10-turn conversation, request N pays only the cost of tokenizing + prefilling turn N's new user message, not the entire history each time.

## Roadmap

- **LiteRT-LM migration** — newer Google runtime with explicit Pixel 10 TPU control and successor to `tasks-genai`. The deprecation warnings on `LlmInference` already point here.
- **Per-IP rate limiting** — token bucket per client IP. Currently the global queue cap is the only backpressure.
- **Model checksum verification** after download.
