# Development

How to build, extend, and ship the app yourself.

## Project layout

```
localllm/
├── app/                               main Gradle module
│   ├── build.gradle.kts
│   ├── proguard-rules.pro             LiteRT-LM keeps; minify disabled in release for now
│   └── src/
│       ├── main/
│       │   ├── AndroidManifest.xml    foregroundServiceType=specialUse
│       │   ├── java/com/localllm/app/ Kotlin sources (see Architecture page)
│       │   └── res/
│       │       ├── drawable/          ic_launcher_foreground + ic_launcher_background (adaptive)
│       │       ├── mipmap-anydpi-v26/ adaptive icon manifests
│       │       └── values/            strings, themes, colors
│       └── test/                      JVM unit tests
├── docs/                              this mkdocs site
├── gradle/libs.versions.toml          Version catalog
├── mkdocs.yml
├── settings.gradle.kts
└── .github/workflows/build.yml        CI: lint + test + assembleDebug
```

## Local build

```bash
git clone https://github.com/mlnomadpy/localllm.git
cd localllm
echo "sdk.dir=$HOME/Library/Android/sdk" > local.properties   # macOS
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Requirements:

- **JDK 17** (`temurin` works; AGP 8.7 needs >=17, the source target
  is Java 11).
- **Android SDK API 35** installed.
- **Gradle** is wrapper-pinned (`8.x`) — don't install separately.

Runtime versions are all locked in `gradle/libs.versions.toml`:

| | |
|---|---|
| Kotlin | 2.2.21 |
| AGP | 8.7.3 |
| Ktor | 3.4.3 |
| Compose BOM | 2024.09.02 |
| LiteRT-LM | 0.11.0 |

## Adding a model to the catalog

`ModelCatalog.kt` is the source of truth.

```kotlin
ModelInfo(
    id          = "gemma-4-e2b",                     // bare name shown in /v1/models
    name        = "Gemma 4 E2B IT",                  // human label in the Catalog UI
    description = "Instruction tuned, ~2.6 GB.",
    url         = "https://.../gemma-4-E2B-it.litertlm",
    filename    = "gemma-4-e2b.litertlm",            // .litertlm extension required
    sha256      = "181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c"
),
```

A few rules:

- `filename` must end in `.litertlm`. Anything else is ignored by the
  on-device file scan.
- `sha256` is optional. When set, the file is verified after
  download; on mismatch the file is deleted and the user is toasted.
  Skip for non-HF mirrors where you don't control the integrity.
- For HuggingFace xet-backed artifacts the `x-linked-etag` HTTP
  header is the SHA-256 — copy it from `curl -sI <url>` rather than
  downloading 2.6 GB to hash.

## Running on a real device

The app runs fine on emulators, but inference is *very* slow on
emulated x86 — figure 5+ seconds per token on Gemma 4 E2B on an
`x86_64` AVD. For development, use a real ARM phone. Anything
post-2022 should manage 10–30 tok/s on CPU.

Tested on Pixel-class devices. NPU acceleration via the Qualcomm
`.litertlm` variants requires a Snapdragon device.

## GPU vs CPU on your device

The AUTO backend tries GPU first and falls back to CPU on any
`Engine.initialize()` failure. The most common GPU failure on stock
Pixel images is `dlopen failed: library libvndksupport.so not found`
— Google ships `libvndksupport.so` on some images but not others. If
you see this and want to force CPU explicitly (skip the failed GPU
attempt), set **Settings → Backend → CPU**.

To see what your device picked, `curl /health`:

```bash
curl -s http://localhost:8099/health | jq '.engines'
# [{"key":"gemma-4-e2b_model_AUTO","backend":"CPU"}]
```

## Tests

```bash
./gradlew :app:testDebugUnitTest
```

Two unit-test files today, both JVM (Robolectric):

- `SettingsTest.kt` — preference clamping (`maxTokens`, `temperature`,
  etc.), `bindHost` derivation, backend normalization.
- `RequestTrackerTest.kt` — atomic queue cap, stats derivation,
  cancel/error counters.

There is **no on-device instrumentation test** today. End-to-end
verification is a manual `curl` against a real `adb forward`. If you
add an `androidTest` source set, expect the model fixture to be the
hard part — a 2.6 GB binary blob doesn't belong in git.

## Continuous integration

`.github/workflows/build.yml` runs on every push and PR against
`main`:

```yaml
- ./gradlew lint testDebugUnitTest assembleDebug --no-daemon --stacktrace
```

Gradle is cached on `gradle/libs.versions.toml` + `**/*.gradle.kts`.
Lint report + the debug APK are uploaded as workflow artifacts (14
day retention). JDK 17, Temurin. No daemon — fresh VMs don't benefit
and the Gradle daemon's resident heap occasionally OOMs the 7 GB
runner.

There is *no* release pipeline in CI. Cutting a release is manual:

```bash
./gradlew :app:assembleDebug
gh release create v1.x.y \
  --target $(git branch --show-current) \
  --title "LocalLLM v1.x.y" \
  --notes "..." \
  app/build/outputs/apk/debug/app-debug.apk
```

Don't try to ship to the Play Store from this debug APK — minify is
disabled (`isMinifyEnabled = false`), there's no signing config, and
the version code is hardcoded. Production-ready signing + R8 are
roadmap items.

## Extending the HTTP API

The route handler lives in `LLMServerService.kt` inside the
`embeddedServer(Netty, ...) { routing { ... } }` block. A new
endpoint is one route registration + (usually) one Gson DTO in
`ApiTypes.kt`.

For anything that's actually inference-shaped, mind the existing
contract:

1. Call `authorize(call)` first if you want the bearer-token gate.
2. Atomic admission via `RequestTracker.tryEnqueue(...)` so the
   global queue cap is honored.
3. Use `inferenceMutex.withLock { ... }` — only one inference at a
   time per device.
4. Wrap the inference in `withWakeLock(needWakeLock, timeoutMs) {
   ... }`.
5. Use `withTimeout(timeoutMs) { ... }` to enforce the budget. On
   timeout, call `conversation.cancelProcess()` so the native engine
   actually stops burning compute.
6. Mind the SSE error path: if you start a streaming response,
   capture the writer (`streamWriter = this@respondBytesWriter`) and
   emit a `writeSseError` chunk on failure — *don't* try to
   `call.respond` after headers have committed.

## Building from source for distribution

This APK is not currently shipped to the Play Store. For your own
sideload distribution:

1. Generate a release keystore (one-time):
   ```bash
   keytool -genkey -v -keystore release.keystore -alias localllm \
     -keyalg RSA -keysize 4096 -validity 10000
   ```

2. Add a `signingConfigs.release` block to `app/build.gradle.kts`
   pointing at it. **Don't commit the keystore.** Read the
   passwords from environment variables or `~/.gradle/gradle.properties`.

3. Set `isMinifyEnabled = true` in the release `buildTypes` block.
   The `proguard-rules.pro` file already has keeps for LiteRT-LM,
   Ktor, Netty, Gson, Compose, and our `ApiTypes.kt` data classes —
   verify with `./gradlew :app:assembleRelease` and a smoke-test on
   device.

4. Distribute via the
   [GitHub Releases page](https://github.com/mlnomadpy/localllm/releases)
   or your own channel.

If you want **Play Store distribution**, add Crashlytics or Sentry,
target `targetSdk = 35`, audit
`Settings.allowCors` defaults, and replace the in-memory `LogManager`
ring buffer with a persistent crash log. None of that is in tree yet.

## Roadmap

Tracked in [GitHub Issues](https://github.com/mlnomadpy/localllm/issues).
Big items:

- [ ] Multimodal content blocks (`Content.ImageBytes` /
      `Content.AudioBytes`) — LiteRT-LM already supports them.
- [ ] Tool / function calling (`ConversationConfig.tools`).
- [ ] Qualcomm NPU catalog entries
      (`gemma-4-E2B-it_qualcomm_sm8750.litertlm`).
- [ ] Per-IP token-bucket rate limiting.
- [ ] Persistent log buffer + Sentry/Crashlytics integration.
- [ ] `androidTest` end-to-end with a tiny fixture model.
- [ ] Release signing + R8 production build.
