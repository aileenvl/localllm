# LocalLLM Edge Server

An on-device, OpenAI-compatible LLM HTTP server for Android. Runs **Gemma 4**
(or any compatible LiteRT-LM `.litertlm` bundle) locally on the phone and
exposes a `/v1/chat/completions` endpoint that any client speaking the OpenAI
API can talk to — no cloud, no remote API key, no data leaves the device.

The app pairs a Compose UI for managing models and watching live request
stats with a foreground Service that hosts both the Ktor HTTP server and the
LiteRT-LM inference runtime.

## What it does

- Loads `.litertlm` LLM bundles via Google's **LiteRT-LM** Android runtime
  (`com.google.ai.edge.litertlm:litertlm-android:0.11.0`). Ships with two
  built-in catalog entries — **Gemma 4 E2B IT** and **Gemma 4 E4B IT** —
  pulled directly from the public `litert-community` HuggingFace org.
- Runs an embedded Ktor 3 HTTP server with three endpoints:
  - `GET /health` — liveness check + which backend each cached engine ended up on
  - `GET /v1/models` — list `.litertlm` files present on disk
  - `POST /v1/chat/completions` — OpenAI-style chat completion, streaming or not
- Real **SSE error chunks**: when inference fails mid-stream, the client gets
  a final `data: {"error":{...}}` followed by `[DONE]` — no silent connection
  drops.
- **Backend auto-fallback**: AUTO tries GPU first, transparently falls back
  to CPU when the GPU delegate fails to initialize (common on devices missing
  `libvndksupport.so`). Explicit CPU / GPU selection stays strict so you can
  debug.
- **SHA256 model verification** on download: the catalog declares the
  expected hash; mismatched downloads are deleted and surfaced to the user.
- Runs as a foreground service (`specialUse` type, `on_device_inference`
  subtype) with a persistent notification and a Stop action.
- Can autostart on app launch and on device boot.
- Optionally binds `0.0.0.0` so other devices on the same Wi-Fi can use it.

## Tabs

- **Catalog** — download a built-in model, import a `.litertlm` file from the
  device, or pull from a custom URL configured in Settings. Delete to free
  space. SHA256 is verified automatically on built-in downloads.
- **Dashboard** — live queue, in-flight request, recent history (cap 50), and
  cumulative stats (counts, avg latency, avg tok/s, error rate).
- **Console** — live in-memory log stream from the service.
- **Chat** — a usable test harness against the local server. Features:
  friendly model labels in the dropdown (instead of raw filenames), a Stop
  button that actually cancels the in-flight request (server sees the
  disconnect and calls `cancelProcess()`), a live `streaming — 12.4 tok/s ·
  73 tokens` subtitle, long-press copy on any chat bubble, and a collapsible
  system-prompt field.
- **Settings** — port, LAN bind, max tokens, temperature, top-k, backend
  (AUTO/CPU/GPU), API key, CORS toggle, request limits (timeout, queue depth,
  prompt cap), background efficiency (idle eviction, auto-stop, wake lock),
  start-on-boot, autostart, and custom model URLs. Reads are backed by
  `StateFlow` so slider drags don't blow up the SharedPreferences I/O path.

## Calling the server

```bash
curl http://127.0.0.1:8080/v1/chat/completions \
  -H "Content-Type: application/json" \
  -d '{
    "model": "gemma-4-e2b",
    "messages": [{"role": "user", "content": "Hello"}],
    "stream": false
  }'
```

Streaming (SSE):

```bash
curl -N http://127.0.0.1:8080/v1/chat/completions \
  -H "Content-Type: application/json" \
  -d '{
    "model": "gemma-4-e2b",
    "messages": [{"role": "user", "content": "Tell me a story."}],
    "stream": true,
    "temperature": 0.7,
    "top_k": 40,
    "max_tokens": 256
  }'
```

Use the model `id` from `GET /v1/models` — it's the filename minus
`.litertlm` (e.g. `gemma-4-e2b`).

From a laptop on the same Wi-Fi, enable **Bind to LAN** in Settings, then hit
`http://<phone-ip>:8080/...`. From the Android emulator, use `10.0.2.2` for
the host's localhost.

## Multi-turn (`session_id`)

To take advantage of KV-cache reuse across turns, pass a stable `session_id`
on each request in a conversation:

```jsonc
// Turn 1
POST /v1/chat/completions
{
  "model": "gemma-4-e2b",
  "session_id": "alice-2026-05-10",
  "messages": [
    {"role": "user", "content": "Hi"}
  ]
}

// Turn 2 — same session_id, full message history echoed (OpenAI convention)
POST /v1/chat/completions
{
  "model": "gemma-4-e2b",
  "session_id": "alice-2026-05-10",
  "messages": [
    {"role": "user", "content": "Hi"},
    {"role": "assistant", "content": "Hello! How can I help?"},
    {"role": "user", "content": "Tell me about kernels"}
  ]
}
```

Behavior:
- Empty / omitted `session_id` → fresh stateless conversation every request
  (default).
- Non-empty `session_id` → server caches the LiteRT-LM `Conversation` and on
  follow-up requests only sends the **new user turns**. Assistant turns
  echoed by the client are skipped because they're already in the model's
  KV cache.
- The server validates the replayed prefix via a hash. If the prefix doesn't
  match what was recorded — e.g., you rewound, edited, or used the same
  `session_id` for a different conversation — the conversation is rebuilt
  from scratch.
- Cache holds at most 4 conversations; oldest evicted. Conversations are
  also evicted along with their parent engine when the engine is unloaded
  (idle eviction or LRU pressure).
- Changing `temperature` or `top_k` mid-session triggers a rebuild (those
  are conversation-construction params in LiteRT-LM's `SamplerConfig`).

On a 10-turn conversation, request N pays only the cost of prefilling turn
N's new user message, not the full history.

## Custom models

Any LiteRT-LM `.litertlm` bundle should work. Two ways to add one:

1. **Custom URL** — Settings → Custom model URLs, one URL per line. Must end
   in `.litertlm`. The Catalog tab will show it as a downloadable entry.
   Custom-URL downloads are not SHA256-verified — bring-your-own integrity.
2. **Local import** — Catalog → "Import .litertlm file from device" and pick
   a `.litertlm` from the file picker.

## Backend selection

In Settings → Inference defaults → **Backend** you can choose:

- **AUTO** (default) — try GPU first; if `engine.initialize()` fails, fall
  back to CPU and log the GPU error. This is the path you want on most
  devices. Check `GET /health` to see which backend each engine actually
  ended up on.
- **CPU** — force `Backend.CPU()`. Slowest but most portable; uses XNNPACK
  under the hood and works on every device. Useful as a benchmarking
  control.
- **GPU** — force `Backend.GPU()`. Strict — no fallback. Will fail at
  request time if the OpenCL/OpenGL delegate can't initialize (e.g., on
  Pixel devices that don't ship `libvndksupport.so`).

Switching the backend forces an engine rebuild on the next request (cold
load, several seconds). The engine cache key is `model + maxTokens + backend`.

For NPU acceleration on Qualcomm devices, the same HF org also publishes
`gemma-4-E2B-it_qualcomm_*.litertlm` variants — drop one in via "Import" and
pick GPU/NPU as the backend.

## Multi-app use & limits

The server is designed to be hit by multiple apps on the same device (or LAN
when **Bind to LAN** is enabled). Settings → Request limits exposes:

- **Request timeout** (default 120s) — beyond this the server emits an SSE
  error chunk for streaming requests or a `408` for blocking requests, and
  calls `Conversation.cancelProcess()` so the native engine actually stops
  burning compute.
- **Max queue depth** (default 8) — beyond this we return `429 Too Many
  Requests` with `Retry-After: 5`. The atomic `tryEnqueue` ensures the cap
  is honored even under concurrent enqueues.
- **Max prompt chars** (default 100,000) — beyond this we return `413
  Payload Too Large`.

Settings → Security exposes an optional **API key**. When set, every
`/v1/chat/completions` and `/v1/models` request must carry `Authorization:
Bearer <key>`. `/health` stays unauthenticated for liveness probes. The CORS
plugin is **off** by default — only same-host or LAN-local clients can call
the server. Toggle Settings → Allow CORS to install `anyHost()` if you want
browser-based clients to call the API.

Each request is logged with its remote IP and User-Agent, so you can tell
which app is doing what.

## Background efficiency

Settings → Background:

- **Unload models after N min idle** (default 5) — frees the engine LRU's
  ~2.5 GB per Gemma 4 E2B when nothing is happening. Set to `0` to disable.
- **Auto-stop server after N min idle** (default `0`, disabled) — stops the
  foreground service entirely. The user will need to relaunch the app or
  reboot.
- **Keep CPU awake during inference** (default on) — holds a
  `PARTIAL_WAKE_LOCK` only while a request is running, so Doze can't
  throttle inference mid-stream. The lock has a hard timeout slightly above
  the configured request timeout — defense against any bug that forgets to
  release it.

The idle monitor is a single 30s-tick coroutine inside the service.
Eviction only runs when the inference mutex isn't held, so it can never
interrupt an active request.

## Endpoints

| Path | Notes |
|---|---|
| `GET /health` | Liveness. Returns `status`, `service`, `version`, `queue_depth`, `engines_loaded`, and an `engines` array of `{key, backend}` so callers can see which backend the AUTO fallback chose. Never gated by the API key. |
| `GET /v1/models` | Gated by API key when set. Lists `.litertlm` files on disk. |
| `POST /v1/chat/completions` | Gated by API key. Honors `stream`, `temperature`, `top_k`, `max_tokens`, `session_id` per request. Streaming uses SSE with proper error chunks on failure. |

## Build & install

```bash
cd localllm-android
./gradlew assembleDebug
# APK at app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

`local.properties` needs `sdk.dir=...` pointing at your Android SDK
(omitted on CI — `ANDROID_HOME` is set by the GitHub Actions runner).

Min SDK 29, target 34, compile 35. Built with Kotlin 2.2.21, AGP 8.7.3,
Ktor 3.4.3, Compose BOM 2024.09.02, LiteRT-LM 0.11.0.

CI: `.github/workflows/build.yml` runs `./gradlew lint testDebugUnitTest
assembleDebug` on every push and PR against `main`, with Gradle caching
keyed on `libs.versions.toml` + `**/*.gradle.kts`. The debug APK is
uploaded as a workflow artifact on each successful run.

## Pre-built APK

The latest signed-with-debug-key APK ships as a GitHub Release asset on
this repo — see the Releases page and download `app-debug.apk`. Install
with `adb install -r app-debug.apk` or by transferring the file to your
device and opening it (you'll need to allow "install from unknown
sources" for your file manager).

## Notes & internals

- **Inference is serialized** via a `Mutex`: only one request at a time per
  device, even if multiple clients connect. LiteRT-LM is single-tenant in
  practice.
- **No chat templating in our code.** LiteRT-LM reads the prompt template
  from the `.litertlm` bundle's metadata — we just pass `Contents.of(text)`
  and the engine handles the `<start_of_turn>` markers, BOS/EOS tokens, etc.
- **Per-request `temperature` and `top_k`** are applied via
  `ConversationConfig.SamplerConfig` — the engine itself doesn't accept
  them.
- The notification's **Stop** button cleanly tears down the server, cancels
  the service coroutine scope, releases the wake lock, and evicts cached
  engines + conversations.

## Roadmap

- **Per-IP rate limiting** (token bucket per client IP). The global queue
  cap is the only backpressure today.
- **Qualcomm NPU catalog entries** — `gemma-4-E2B-it_qualcomm_sm8750.litertlm`
  / `..._qcs8275.litertlm` ship in the same HF repo; we should expose them
  directly in the catalog with an NPU backend toggle on Qualcomm devices.
- **Multimodal content blocks** — LiteRT-LM natively supports `Content.Image`
  / `Content.AudioBytes`; the OpenAI-compat layer only extracts `Content.Text`
  today.
- **Crash reporting / observability** — currently `LogManager` is in-memory
  only. A persistent ring buffer plus an optional Sentry/Crashlytics
  integration would help diagnose field issues.
