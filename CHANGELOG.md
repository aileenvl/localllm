# Changelog

All notable changes to this project are recorded here. The format
follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and
versions follow [Semantic Versioning](https://semver.org/).

## [Unreleased]

Nothing yet.

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
