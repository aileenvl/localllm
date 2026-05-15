# Changelog

All notable changes to this project are recorded here. The format
follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and
versions follow [Semantic Versioning](https://semver.org/).

## [Unreleased]

### Fixed

#### Engine init on Google Tensor SoCs (Pixel 6 / Pixel 10) — with visible feedback

- **Direct `Backend.CPU()` init no longer fails on Tensor.** A cold CPU init
  on Tensor throws inside `llm_litert_compiled_model_executor.cc:2023` unless
  the JNI library has first attempted another backend in the same process.
  The fix runs a no-op `Backend.NPU(...)` primer call before the real CPU
  init — the primer is expected to fail (no vendor delegate on stock
  hardware) but the side effects unblock CPU. The primer attempt appears
  in `/health`'s new `attempts` field as `NPU-primer=expected-fail`, not
  hidden.
- **`Backend.GPU()` on Tensor no longer SIGSEGVs the service** thanks to
  the same NPU primer. GPU init now throws a catchable exception
  (typically `TF_LITE_AUX not found in the model` on Tensor G5 with the
  stock Gemma 4 `.litertlm`) which propagates to the caller as a proper
  HTTP 500. The pill stays selectable but failures are now visible and
  recoverable — not a process kill.
- **No silent fallback for explicit choices.** If you pick GPU and GPU
  fails, you get an error. Same for NPU. AUTO is the only mode that rolls
  down the chain, and every step in that chain — including skips — is
  recorded.

### Added

#### `/health` exposes the engine-init attempt chain

Each cached engine now reports the full sequence of backends tried, what
each one returned, and how long it took:

```json
"engines": [{
  "key": "gemma-4-e2b_16_AUTO",
  "backend": "CPU",
  "attempts": [
    {"backend": "NPU", "result": "failed: TF_LITE_AUX not found in the model", "duration_ms": 5394},
    {"backend": "GPU", "result": "skipped: known SIGSEGV on Tensor", "duration_ms": 0},
    {"backend": "CPU", "result": "ok", "duration_ms": 3168}
  ]
}]
```

The same chain is logged via `LogManager` (visible in the Console tab)
under `LLMServerService: Engine <key> resolved to <backend>. Chain: ...`.
Error responses for explicit choices also include the attempts summary
inline in the error message.

### Added

#### SoC-specific NPU model variants in the Catalog

- Catalog now lists the **seven NPU-compiled Gemma 3 1B `.litertlm` files**
  Google publishes at `huggingface.co/litert-community/Gemma3-1B-IT`:
  - Qualcomm Snapdragon: SM8550 (8 Gen 2), SM8650 (8 Gen 3), SM8750 (8 Elite),
    SM8850 (8 Elite Gen 5) — ~690 MB each
  - MediaTek Dimensity: MT6989 (9300), MT6991 (9400), MT6993 (9500) —
    ~1.03 GB each
- `ModelInfo` gains a `requiredSocMarker: String?` field; non-null entries
  are NPU-gated. `npuSocLabel()` produces a human-readable SoC chip
  ("Snapdragon 8 Elite (SM8750)") and `matchesCurrentSoc()` checks against
  `Build.SOC_MODEL` (API 31+).
- Catalog cards render a Memory-icon chip for NPU entries; when the chip
  matches the device's SoC it switches to the tertiary container colour
  with a "· matches your device" suffix, so a Qualcomm/MediaTek user spots
  their one-tap variant immediately.

SHA-256 left null for these entries — verifying all seven requires
downloading ~6 GB; lock them in opportunistically as users report
successful runs.

#### NPU/TPU backend pill in Settings

- **`Backend.NPU(nativeLibraryDir)`** wired into both the explicit backend
  selector and the AUTO fallback chain (NPU → GPU → CPU). LiteRT-LM treats
  Qualcomm Hexagon, MediaTek APU, and Google's Edge TPU on Tensor as one
  accelerator family ("NPU"), so this is the right surface — there's no
  separate `Backend.TPU`.
- **Auto-detection** (`Settings.hasNpuDelegate`) probes
  `applicationInfo.nativeLibraryDir` for known vendor delegate `.so` names
  (`libqnn*.so`, `*hexagon*`, `*neuron*`, `libapu*`). The NPU/TPU pill in
  Settings is enabled only when a delegate is detected; otherwise it shows
  disabled, and the legend row is hidden to avoid clutter.
- The legend documents what's actually required to use NPU per vendor
  (QAIRT SDK + `ADSP_LIBRARY_PATH` + SoC-specific `.litertlm` for Qualcomm;
  Tensor ML SDK signup gating on Pixel) and links to the official
  LiteRT-LM NPU page.

On a stock Pixel 6 build (no vendor delegates present) the pill stays
disabled — which matches reality: Google Tensor NPU is experimental-access
only, not yet a public Maven artifact.

#### RAG: on-device document store + semantic search

- **ObjectBox 4.0.3 vector store**, persisted under the app's private data
  directory. Each `DocumentChunk` carries an HNSW-indexed `FloatArray`
  (dimensions=384, DOT_PRODUCT distance — equivalent to cosine for the
  L2-normalised vectors produced by `/v1/embeddings`).
- **`POST /v1/documents`** ingests a `{id, text, model, metadata?}` payload,
  paragraph-chunks it (~400 char windows with ~60 char overlap, sliding-
  window fallback for paragraphs that exceed the cap), embeds each chunk,
  and persists them. Re-POSTing the same id replaces the prior chunks
  (upsert semantics).
- **`GET /v1/documents`** lists all stored documents with chunk count and
  the embedding model used.
- **`DELETE /v1/documents/{id}`** removes every chunk for a document.
- **`POST /v1/search`** embeds a `{query, model, k?}` payload and returns
  the top-K `{document_id, chunk_index, text, score, metadata}` hits.
  `score` is cosine similarity (not raw HNSW distance) so values are in
  the familiar [-1, 1] range.
- Pure-JVM unit tests cover the chunker (paragraph-aware split, overlap
  carry-over, sliding-window fallback for oversized paragraphs).

Verified on Pixel 6: matching documents score 0.57–0.67 vs ~0.30–0.36 for
unrelated ones — clean separation.

#### Embeddings (`POST /v1/embeddings`)

- **OpenAI-compatible embeddings endpoint** powered by ONNX Runtime Android
  (`com.microsoft.onnxruntime:onnxruntime-android:1.18.0`) and a hand-rolled
  BERT WordPiece tokenizer (no JNI tokenizer dependency). Accepts `input` as
  either a single string or an array of strings; returns mean-pooled,
  L2-normalised float vectors plus `usage.prompt_tokens`.
- **`/v1/models` surfaces ONNX embedding models** alongside LiteRT-LM
  language models. A model is considered available when both
  `<modelId>.onnx` and `<modelId>-vocab.txt` are present in the app's
  external files dir.
- **Validated on Pixel 6 with `bge-small-en-v1.5`** (384-dim): cosine
  synonyms 0.74, cosine unrelated 0.32, ~210 ms steady-state per 128-token
  text on the CPU backend.
- Embedding services share the same idle-eviction and memory-pressure
  lifecycle as LM engines: closed automatically after `Settings.idleEvictMs`
  of inactivity, evicted hard on `TRIM_MEMORY_RUNNING_CRITICAL`.

### Changed

- APK grows ~30 MB on `arm64-v8a` from the bundled ONNX runtime native
  library. The other ABIs were already excluded by the splits config.

## [1.2.0] — 2026-05-13

Stage 1 of the AI roadmap. Two coherent additions to the OpenAI-compatible
HTTP API that both already had runtime support: function/tool calling and
multimodal image input.

### Added

#### Tool / function calling

- **`POST /v1/chat/completions` accepts OpenAI-shaped `tools` + `tool_choice`.**
  Each `ToolDef` (`type` + `function: {name, description, parameters}`) is
  wrapped as a LiteRT-LM `OpenApiTool` (one tool per provider) and threaded
  into `ConversationConfig.tools`. `tool_choice` is honored at the gateway:
  `"none"` strips tools before the conversation is built; `"auto"` and the
  `{type:"function", function:{name:"..."}}` object form both pass the full
  set through (LiteRT-LM does not expose a single-tool selector, so the
  object form degrades to "auto").
- **Server returns `tool_calls` and `finish_reason: "tool_calls"`** when the
  model elects to invoke a function. Each LiteRT-LM `ToolCall` is translated
  into a `ToolCallApi` with a stable-ish ID (`call_${entry.id}_${index}`),
  `type: "function"`, and `function: {name, arguments}` where `arguments` is
  the JSON-encoded argument map per the OpenAI contract.
- **Streaming path emits a final `delta.tool_calls` chunk** with
  `finish_reason: "tool_calls"` instead of `"stop"` when a tool call lands.
  Text deltas still stream as before for messages that mix text + tool use.
- **Two-turn protocol round-trips correctly.** `role: "tool"` follow-up
  messages with `tool_call_id` and a serialized `content` are translated to
  a LiteRT-LM `Role.TOOL` message carrying a `Content.ToolResponse`. The
  session-reuse path treats a single new `tool` turn the same as a single
  new `user` turn so the KV cache survives the round trip.
- **`automaticToolCalling = false`** on the conversation — the server
  forwards the tool call to the HTTP client rather than executing it
  in-process. (The `OpenApiTool.execute` shim is implemented defensively to
  return a structured error if the runtime ever tries to auto-call it.)

#### Multimodal image input

- **`POST /v1/chat/completions` accepts the OpenAI `content` array** with
  `{type:"text",...}` and `{type:"image_url",...}` parts. Plain string
  content still works unchanged (polymorphic `JsonElement` on the wire,
  inspected at the call site).
- **`data:image/...;base64,...` URLs** decode immediately to bytes via
  `android.util.Base64`. **`http://localhost(:port)/...` URLs** are fetched
  via OkHttp with a 5 MB cap, 10s read timeout. Every other scheme — public
  HTTP, file:, custom schemes — is rejected with a 400 for SSRF
  protection.
- **Image downscaling**: any image exceeding 1024×1024 is decoded with
  `BitmapFactory.inSampleSize` and re-encoded as JPEG@85% before being
  handed to LiteRT-LM. Saves prefill time on phone-camera-sized inputs.
- **`EngineConfig.visionBackend = Backend.CPU()`** is now always set.
  Adds a small startup cost (~hundreds of MB resident, a few hundred ms
  init) so the first multimodal request doesn't have to rebuild the engine.

#### API types

- **`Message.content` is now polymorphic (`JsonElement?`)** — string,
  parts array, or null. Backwards-compatible: existing text-only clients
  see no behavior change.
- **New types**: `ToolDef`, `FunctionDef`, `ToolCallApi`, `ToolCallFunction`,
  sealed `ContentPart.{TextPart, ImagePart}`, plus extension helpers
  `Message.contentString()`, `Message.contentParts()`, `Message.textChars()`,
  and `JsonElement.toContentParts()`.
- **`StreamDelta`** gains an optional `tool_calls` field for the streaming
  tool-call emission.

### Changed

- **Prompt-size cap** now counts characters across `text` parts rather than
  the old `content.length`. Image parts don't contribute to the limit.
- **`messagesPrefixHash`** mixes in `tool_call_id` and `tool_calls` so a
  client that swaps a tool turn mid-session correctly invalidates the
  cached conversation.
- **`runInferenceBlocking`** returns a `LlmMessage` (not just text) so the
  route handler can inspect `toolCalls` and choose the right `finish_reason`.
  `runInferenceStreaming` similarly tracks the last non-empty `toolCalls`
  snapshot of the Flow.
- **`ChatBubble`** renders a `[tool: pending — see API response]` placeholder
  for empty assistant messages (defensive — the in-app Chat tab doesn't
  send `tools`, so this is reachable only when an external client drives
  the local server).

### Fixed during the v1.2.0 cycle

- **`automaticToolCalling = false` is now passed explicitly.** LiteRT-LM
  0.11.0's 4-arg `ConversationConfig` overload defaults this to **true**,
  not false as the initial Stage 1 implementation assumed. The runtime
  was auto-executing our `OpenApiTool.execute()` stub instead of
  surfacing the tool call to the HTTP client. The OpenAI contract is
  "model emits `tool_calls`, client executes, client sends a `role:tool`
  follow-up" — and that round-trip now works as designed.
- **Better `ChatRequest` parse-error logging.** Root-cause exception
  class + message surface in the 400 response and via `LogManager.e`
  instead of Ktor's opaque "Failed to convert request body".

### Extracted helpers

- `MessageHelpers.kt` collects five pure top-level functions
  (`messagesPrefixHash`, `isLoopbackHttpUrl`, `decodeDataImageUrl`,
  `parseToolArguments`, `jsonToAny`, `buildToolDescriptionJson`) extracted
  from `LLMServerService.kt` so they're independently unit-testable on
  the JVM without spinning up the Service or LiteRT-LM JNI.
- Fixed an IPv6 bracket-notation bug in `isLoopbackHttpUrl` discovered
  via the new tests — `http://[::1]/img` was previously mis-rejected.

### Tests

- **`ApiTypesTest.kt`** — pure-JVM Gson round-trip tests for both
  polymorphic content shapes (string + parts array), null content on
  tool-call assistant messages, `tool` follow-up turns, `tools` +
  `tool_choice` envelope deserialization, tool-call response shape. 11
  cases. Verifies the v1.1.0 text-only request contract is preserved
  byte-for-byte.
- **`MessageHelpersTest.kt`** — 25 cases covering the extracted helpers.
  Total project test count is now 77, all green.

### End-to-end verification on Pixel 6 (Tensor G1, CPU backend)

- Tool calling: round 1 emits `finish_reason: "tool_calls"` +
  `tool_calls[0].function.name = "get_weather"`; round 2 with a
  `role: "tool"` follow-up produces a natural-language answer using the
  injected result.
- Multimodal image: 3.2 KB JPEG → vision encoder → text description
  correctly identifying the colors and overlaid text.

## [1.1.0] — 2026-05-12

Production-readiness pass + tab-by-tab UX overhaul. Inference layer is
unchanged; this is all the operational and visual scaffolding around it.

### Added

#### Release & build

- **R8 + resource shrinking** on the release buildType. ProGuard rules
  already covered LiteRT-LM, Ktor, Netty, Gson, Compose — no new keep
  rules surfaced. `:app:assembleRelease` and `:app:bundleRelease` both
  green.
- **Per-ABI APK splits**. `arm64-v8a` only (LiteRT-LM 0.11.0 ships JNI
  `.so` files for `arm64-v8a` + `x86_64` only — no `armeabi-v7a`). The
  arm64-v8a release APK is **~28 MB**, the universal is ~39 MB, the
  `.aab` is ~33 MB.
- **`signingConfigs.release`** reading from `~/.gradle/gradle.properties`
  or environment (`LOCALLLM_KEYSTORE_PATH` / `_PASSWORD` / `_ALIAS` /
  `_PASSWORD`). Gracefully falls back to the debug signing key when any
  of the four is missing — so contributors run `:app:assembleRelease`
  without needing the production keystore.
- **`scripts/release.sh`** — one-command release: assembleDebug + mkdocs
  gh-deploy + tag + push + `gh release create` with notes scraped from
  this CHANGELOG.
- **`.github/workflows/docs.yml`** — Material site build + Pages deploy,
  triggered on `docs/` / `mkdocs.yml` changes. Pages currently sourced
  from the `gh-pages` branch (legacy mode) because GitHub Actions is
  administratively restricted on the hosting account — the workflow
  auto-resumes once Actions is re-enabled.
- **`.github/dependabot.yml`** — weekly Monday updates for gradle,
  github-actions, and the pip-based docs requirements; Compose / Kotlin /
  Ktor each in their own update group; LiteRT-LM explicitly pinned
  (manual bumps only — model-side smoke test required).

#### Performance & lifecycle

- **Baseline Profiles** via a new `:macrobenchmark` module
  (`com.android.test` + `androidx.baselineprofile`). `StartupBenchmark`
  measures cold-start under `CompilationMode.None / Partial / Full`;
  `BaselineProfileGenerator` walks Catalog → Dashboard → Console → Chat
  → Settings. Run on a device with
  `./gradlew :app:generateReleaseBaselineProfile`.
- **`onTrimMemory` engine eviction**. `RUNNING_LOW / MODERATE` shrinks
  the engine LRU to 1; `RUNNING_CRITICAL / COMPLETE` evicts everything.
  Both gated by `inferenceMutex.tryLock` so eviction never interrupts
  an active request.
- **`Lifecycle.Event.ON_START` re-kick** in `MainActivity`. If the OS
  killed the foreground service while the Activity was backgrounded
  and autostart is on, the service comes back up the next time the
  user returns to the app.
- **`START_STICKY` contract documented** on `onStartCommand`.

#### AUTO backend with real fallback

- AUTO now tries `Backend.GPU` first; on `Engine.initialize()` failure
  (the common case on stock Pixel images missing `libvndksupport.so`),
  logs a warning and rebuilds on `Backend.CPU`. Explicit CPU / GPU
  selections stay strict (no fallback) so the user can debug them.
- New **`engines` array** in `GET /health` surfaces the backend each
  cached engine actually initialized on:

  ```json
  "engines": [
    { "key": "gemma-4-e2b_model_AUTO", "backend": "CPU" }
  ]
  ```

#### Settings layer

- **`SettingsRepository`** backed by `androidx.datastore.preferences:
  1.1.1` with `SharedPreferencesMigration("settings")` so existing prefs
  carry over. Compose UI observes `StateFlow`s instead of re-reading
  SharedPreferences on every recomposition (slider drag was triggering
  ~60 disk reads/sec before).
- **Public `Settings.xxx(context)` API** preserved byte-for-byte — every
  existing caller (LLMServerService, BootReceiver, etc.) keeps working
  unchanged.

#### Debug-build hygiene

- **`StrictMode`** thread + VM policies installed under `BuildConfig.
  DEBUG`. `detectDiskReads / detectDiskWrites / detectNetwork /
  detectLeakedClosableObjects / detectActivityLeaks`, all with
  `penaltyLog` only — never `penaltyDeath`.

#### Catalog tab (UX overhaul)

- **`LinearProgressIndicator`** with `"X.X MB / Y.Y GB"` subtitle and
  inline `Cancel` (`Icons.Outlined.Close`) — replaces the text-only
  percentage.
- **SHA-256 verified badge** (`Icons.Outlined.Verified` for built-ins
  with a known hash; `Icons.Outlined.Info` for custom URLs).
- **File size + last-used relative time** on installed models, via
  `Formatter.formatShortFileSize` and `DateUtils.getRelativeTimeSpanString`.
- **"Get started" hero card** when nothing is installed yet.
- **OutlinedCard hierarchy** with proper M3 spacing, icons on every
  action (`Download`, `Delete`, `UploadFile`, `Close`).

#### Chat tab (markdown + visual polish)

- **`MarkdownText`** composable backed by `org.commonmark:commonmark:
  0.22.0` — renders assistant messages with code blocks, lists (capped
  at depth 2), inline code, headings, bold/italic, block quotes, and
  links. Code blocks have a copy-to-clipboard icon. No WebView.
- **Bubble overhaul**: role icons (`Icons.Outlined.Person` /
  `Icons.Outlined.AutoAwesome`), right-aligned timestamps, asymmetric
  rounded corners, 90% max-width, `primaryContainer` vs `surfaceVariant`
  backgrounds.
- **Streaming reveal animation**: `Animatable` fades trailing delta
  characters from 0.5α to full opacity over `tween(200ms)`. Swaps to
  `MarkdownText` rendering once streaming completes.
- **Empty-state hero**: `Icons.Outlined.AutoAwesome` 56dp + title + body
  + 4 `AssistChip` sample prompts. Tap a chip to fill the input — never
  auto-sends.
- **Send / Stop buttons get icons** (`AutoMirrored.Outlined.Send`,
  `Icons.Outlined.Stop` with `errorContainer` colors).
- **`UiMessage.timestampMs`** field added (default-valued, backwards
  compatible).

#### Settings tab (restructure + Pixel-6 awareness)

- **Six collapsible domain sections** with leading icons: Server
  (`Dns`, expanded by default), Inference (`Memory`), Security (`Lock`),
  Background (`Battery5Bar`), Limits (`Speed`), Startup
  (`PowerSettingsNew`). Animated chevron rotation.
- **Per-row Help expandables** (`Icons.Outlined.HelpOutline`) — tap to
  toggle inline description without crowding the surface.
- **Backend description rewrite**: removed the old MediaPipe / Pixel 10 /
  Tensor G5 / "NPU auto" claims. New copy describes AUTO as
  GPU-first-then-CPU fallback, CPU as ~6–12 tok/s on Pixel-class
  hardware for Gemma 4 E2B, GPU as strict-no-fallback. The selected
  mode's line gets a primary-container-tinted background.
- **Chipset hint** above the backend selector, driven by `Build.SOC_MODEL`
  (API 31+). Renders *"Your device: Pixel 6 (Tensor). GPU delegate often
  fails; AUTO will fall back to CPU."* on Tensor SoCs (`gs101+`),
  *"…(Snapdragon). NPU variant `.litertlm` files in the catalog should
  work."* on Snapdragon, otherwise *"AUTO is the safe choice."*
- **Port-in-use validator**: `ServerSocket(port).also{close}` attempt
  500 ms after the port field changes. On `IOException` the field
  shows error-tinted helper text without blocking save.

#### Dashboard tab

- **2×2 stat-card grid** with leading icons: Total / Avg latency / Avg
  tok/s / Error rate. Error rate severity-colored (green <1%, amber <5%,
  error >5%).
- **Tok/s sparkline** via pure Compose `Canvas` — no chart library
  added. Catmull-Rom → cubic Bezier smoothing, 20%-alpha fill under
  the line, max-Y label top-right. Handles empty history / single
  point / NaN / all-zeros cleanly.
- **Promoted in-flight card** with rotating `Icons.Outlined.Bolt` and
  indeterminate `LinearProgressIndicator`. Collapses to "Idle" with
  `Icons.Outlined.Pause` when nothing is running.
- **Status-icon history rows**: `CheckCircle` / `Cancel` / `Error`
  leading icons. Tap to expand and see full request details inline.

#### Console tab

- **Debounced search** (300 ms via `snapshotFlow + debounce`) with
  `Icons.Outlined.Search` leading icon and `Icons.Outlined.Close`
  clear-query trailing icon.
- **Level FilterChips** (DEBUG / INFO / WARN / ERROR) — each chip's
  leading dot is colored to match its corresponding log-level text
  color.
- **Top-5 tag FilterChips** parsed from `[tag] message` prefixes, with
  a "More…" overflow dropdown when the buffer has more than 5 distinct
  tags.
- **Auto-scroll toggle** (`Icons.Outlined.VerticalAlignBottom`).
- **Color-coded log lines** by level.
- **Long-press copy** writes the full `[time] LEVEL message` line to
  the clipboard with a "Copied" toast.
- **"No matching log entries" empty state** with a "Clear filters"
  `TextButton`.

#### Chrome restructure (header + tabs + theme)

- **`Scaffold` layout** replacing the bespoke `Column { Header +
  ScrollableTabRow + Box }`. The old 2-row LIVE banner (~120dp of
  vertical chrome) is gone.
- **Compact `CenterAlignedTopAppBar`** (56dp): status dot in the
  leading slot, middle-ellipsized URL as the title, context-aware
  trailing actions (Tune + Refresh on Chat tab; Copy URL elsewhere).
- **Top `ScrollableTabRow` → bottom `NavigationBar`** with proper M3
  icons (`FolderOpen` / `BarChart` / `Terminal` /
  `AutoMirrored.Outlined.Chat` / `Settings`). Better one-handed reach
  on a 6.4" phone, more content above the fold.
- **Palette overhaul**: primary desaturated `#4ECDC4 → #6BD3CC`,
  full M3 surface tonal scale (background `#0E1113`, surface
  `#14181A`, surfaceVariant `#222729`), brand teal reserved for the
  status dot, primary CTAs, progress, and user-message bubbles.
  WCAG-AA contrast verified.
- **`Header.kt`** trimmed to a `StatusDot(status)` helper used by the
  app bar's leading slot.
- **Chat bubble redo**: assistant messages are now borderless
  full-bleed text with a 3dp primary-tinted left rail (no card
  outline); user messages are tighter right-aligned pills (80%
  max-width, 20dp radius). Role icons removed — alignment + tint
  carry the signal.
- **Chat input row**: rounded `Surface` containing a borderless
  `BasicTextField` and one circular Send/Stop button that swaps icon
  + tint based on `isChatting`. No more `OutlinedTextField` chrome.
- **System prompt** moved out of the chat body into a
  `ModalBottomSheet`, reachable from either the app-bar `Tune` icon
  or an inline edit icon on a persistent strip (only shown when a
  prompt is set).
- **Model selector**: replaced the labelled `OutlinedTextField` with
  a compact `AssistChip` + `DropdownMenu`. Live tok/s collapses to a
  chip on the same row when streaming.

#### General

- **`androidx.compose.material:material-icons-extended`** dep
  (BOM-managed, no version pin) — now available app-wide for the new
  iconography across every tab.
- **`CHANGELOG.md`** introduced (Keep a Changelog 1.1.0 format).

### Changed

- **JDK source / target** bumped 1.8 → 11 to silence AGP 8.7 deprecation
  warnings (Kotlin source target was already 11).
- **`AndroidManifest.xml` `foregroundServiceType`**: `dataSync` →
  **`specialUse`** with the Play-required
  `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` justification declaring on-device
  inference. The `FOREGROUND_SERVICE_DATA_SYNC` permission swapped for
  `FOREGROUND_SERVICE_SPECIAL_USE`.
- **ProGuard rules** retargeted from MediaPipe keeps to LiteRT-LM
  (`com.google.ai.edge.litertlm.**`). Old MediaPipe + protobuf entries
  removed.
- **Pages deploy mode** flipped from "GitHub Actions" to "Deploy from a
  branch (gh-pages)" — works around the account-level Actions
  restriction. The `docs.yml` workflow stays in tree for when Actions
  is re-enabled.

### Removed

- Unused imports: `com.google.ai.edge.litertlm.Role` and
  `kotlinx.coroutines.flow.collect` in `LLMServerService.kt`;
  `kotlinx.coroutines.flow.map` in `SettingsRepository.kt`.
- Unused `kotlinx-serialization-json` dependency and the corresponding
  `kotlinSerialization` Gradle plugin alias (the project uses Gson
  exclusively).

### Fixed

- **`FAILED_PRECONDITION: A session already exists`** on
  `engine.createConversation()`. LiteRT-LM's Engine enforces at most
  one active `Conversation` per engine, but our sessions LRU (size 4)
  could hold multiple cached conversations against the same engine,
  and stateless conversations whose `close()` didn't fully propagate
  before the next request would also leave the slot occupied. Both
  manifested as HTTP 500 on the second or third inference request.
  New `activeConversations: ConcurrentHashMap<String, Conversation>`
  tracks the single live conversation per engine; the new
  `purgeConversationsOnEngine(engineKey)` helper closes every
  conversation we know about on a given engine before constructing a
  new one. Defensive: if `createConversation` still throws "session
  already exists" (race), force-evict the engine and surface a
  retry-able error.
- **Stale source-code references** to MediaPipe / `tasks-genai` /
  Pixel 10 / Tensor G5 / NPU-as-universal / `LlmInferenceSession` /
  `addQueryChunk`. Final grep across `app/src/main/java/com/localllm/
  app/**` returns zero hits.
- **`Settings.kt` `KEY_BACKEND` comment** — replaced false claim that
  AUTO passes `Backend.DEFAULT` to MediaPipe (it doesn't, since the
  migration) with accurate GPU-first-then-CPU fallback semantics.
- **`ApiTypes.kt` `sessionId` KDoc** — replaced
  `LlmInferenceSession` / `addQueryChunk` description with the
  LiteRT-LM `Conversation`-based reality.

### Known issues

- **GitHub Actions administratively restricted** on the hosting account
  pending Trust & Safety review. `build.yml` and `docs.yml` workflows
  are dormant; CI parity is enforced locally
  (`./gradlew lint testDebugUnitTest assembleDebug`). Docs deploy works
  manually via `mkdocs gh-deploy --force --remote-branch gh-pages`.
- **GPU backend init fails on stock Pixel images** missing
  `libvndksupport.so`. AUTO transparently falls back to CPU. On Pixel 6
  / Tensor G1 this is the expected path. CPU + XNNPACK gives ~6–12
  tok/s on Gemma 4 E2B.
- **Multi-process isolation** (`android:process=":server"` for
  `LLMServerService`) deferred — would require IPC for the cross-process
  singletons (`ServerState`, `RequestTracker`, `LogManager`). Tracked
  but not in this release.

## [1.0.0] — 2026-05-12

First public release. Replaces the original MediaPipe `tasks-genai`
prototype with a Gemma-4-ready stack built on Google's LiteRT-LM
runtime.

### Added

- **LiteRT-LM 0.11.0** inference backend
  (`com.google.ai.edge.litertlm:litertlm-android:0.11.0`) — replaces
  MediaPipe `tasks-genai`. Loads `.litertlm` bundles directly; chat
  templating is read from the bundle metadata instead of being
  hand-rolled in our code.
- **Gemma 4 E2B / E4B** catalog entries pointing at the public
  `litert-community/gemma-4-*-it-litert-lm` HuggingFace repos.
- **SHA-256 download verification** — catalog declares the expected
  hash (sourced from HuggingFace's xet `x-linked-etag`, empirically
  confirmed to be the artifact's SHA-256); mismatched downloads are
  deleted and surfaced to the user with a toast.
- **AUTO backend fallback** — tries GPU first; on
  `Engine.initialize()` failure (e.g. missing `libvndksupport.so` on
  some Pixel images) transparently falls back to CPU and logs the
  GPU error.
- **`/health` "engines" array** — exposes which backend each cached
  engine actually initialized on.
- **SSE error chunks** — when inference fails mid-stream, the client
  gets a final `data: {"error":...}` chunk followed by `[DONE]`
  instead of a silently-closed connection.
- **Chat tab UX**:
  - Friendly model labels in the dropdown
    (`Gemma 4 E2B IT` vs `gemma-4-e2b.litertlm`).
  - **Stop** button mid-stream — cancels via
    `suspendCancellableCoroutine` + OkHttp `call.cancel()`, server
    sees the disconnect and calls `Conversation.cancelProcess()`.
  - Live `streaming — N tok/s · M tokens` subtitle.
  - Long-press to copy any chat bubble.
  - Collapsible system-prompt field.
- **`SettingsRepository`** — exposes preferences as `StateFlow` so
  the Settings tab no longer hits `SharedPreferences` on every
  Compose recomposition. Public `Settings.xxx(context)` API is
  preserved for the service-side reads.
- **GitHub Actions CI** — `build.yml` runs lint + `testDebugUnitTest`
  + `assembleDebug` on push and PR against `main`; debug APK and
  lint report are uploaded as workflow artifacts (14-day retention).
- **GitHub Pages docs workflow** — `docs.yml` builds the mkdocs
  Material site and deploys to GitHub Pages on `main`-branch pushes
  that touch `docs/`, `mkdocs.yml`, or the workflow itself.
- **mkdocs Material site** under `docs/` — home, getting started,
  HTTP API, architecture, development pages with per-tab screenshots.
- **Adaptive launcher icon** — vector foreground (chip + chat-bubble
  notch + typing dots + amber "active" spark) on a teal gradient
  background.
- **Distinct Material 3 palette** — separate `primary` / `secondary`
  / `tertiary` / `error` colors with WCAG-AA contrast verified
  against the dark surface.
- **Dependabot** — weekly grouped updates for Gradle, GitHub
  Actions, and the docs `pip` requirements. LiteRT-LM is pinned
  (manual bumps only) because major upgrades require model
  validation.

### Changed

- **Kotlin** 2.0.21 → 2.2.21 (required by the LiteRT-LM AAR's
  bundled Kotlin metadata).
- **Ktor** 2.3.7 → 3.4.3 (required for compatibility with
  kotlinx-coroutines 1.9.0 that LiteRT-LM pulls in;
  `LockFreeLinkedListHead.addLast` was removed and old Ktor was
  compiled against it).
- **Java source / target** 1.8 → 11 (AGP 8.7 deprecation; removes
  the build warnings).
- **`foregroundServiceType`** `dataSync` → `specialUse`, with the
  Play-required `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` justification
  string declaring on-device inference as the special use.
- **Tab bar** — `TabRow` → `ScrollableTabRow`; tab labels are
  forced single-line (`maxLines = 1, softWrap = false`) and no
  longer wrap on narrow screens.
- **Model file extension** — `.task` → `.litertlm` end-to-end.
  File scans, dropdowns, file picker, import flow, and catalog
  filenames all use `.litertlm` only.
- **ProGuard rules** retargeted from MediaPipe keeps
  (`com.google.mediapipe.**`, `com.google.protobuf.**`) to LiteRT-LM
  (`com.google.ai.edge.litertlm.**`).
- **Engine cache** — value type wrapped in
  `private data class CachedEngine(engine, backend)` so each cached
  entry records which backend actually succeeded.
- **`maxNumTokens`** in `EngineConfig` is now passed as `null` when
  the client didn't specify one — lets LiteRT-LM use the
  model-compiled budget. Forcing our generic default (1024) was
  causing `DYNAMIC_UPDATE_SLICE` shape mismatches on big-context
  Gemma 4 weights.

### Removed

- **MediaPipe `tasks-genai`** dependency.
- **`kotlinx-serialization-json`** dependency and its
  `kotlinSerialization` Gradle plugin alias — unused; Gson covers
  all wire serialization.
- **Hand-rolled Gemma chat template** (`formatGemmaPrompt`) —
  LiteRT-LM handles templating from the bundle metadata.

### Fixed

- **Pre-existing NPE** on `ChatRequest.sessionId` when the field
  was omitted from the request — Gson doesn't honor Kotlin
  primary-constructor defaults, so a missing field deserialized as
  `null` even for non-null Kotlin properties. Field is now nullable
  and handled with `isNullOrEmpty()`.
- **Streaming-path error swallowing** — exceptions after the SSE
  headers committed used to call `call.respond(...)` which is a
  no-op once `text/event-stream` is in flight; the client just saw
  the connection close. Now emits a structured SSE error chunk.

### Known issues

- **GPU backend init fails on stock Pixel images** missing
  `libvndksupport.so`. AUTO falls back to CPU transparently;
  explicit GPU selection surfaces the error so users can debug. CPU
  + XNNPACK gives ~10–30 tok/s on a recent Pixel for Gemma 4 E2B.
- **Release build path** is uncertified — `isMinifyEnabled = false`,
  no release signing config. The shipped APK is debug-signed; fine
  for sideloading, not for the Play Store.
- **No on-device E2E tests** — only JVM unit tests for `Settings`
  and `RequestTracker`. End-to-end verification is a manual `curl`
  round-trip per the docs.

[Unreleased]: https://github.com/mlnomadpy/localllm/compare/v1.0.0...HEAD
[1.0.0]: https://github.com/mlnomadpy/localllm/releases/tag/v1.0.0
