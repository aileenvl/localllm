# Tensor TPU AOT compile pipeline

Takes a stock `.litertlm` / `.tflite` and produces a Tensor-targeted
compiled artifact for a Pixel device (Tensor G3 → G6). Useful when you
want to run Gemma 4 E2B / E4B or other Gemma family models on the
Pixel TPU instead of the CPU.

> **This only produces the model artifact.** Running it on a Pixel
> requires the Tensor SDK's **Android runtime delegate `.so` files** to
> be present in the app's `nativeLibraryDir`. Those ship separately
> from this compiler — they're in the Tensor SDK Beta portal alongside
> the host compiler you already have. See "Missing piece" below.

## Prerequisites

1. **Docker Desktop** with `linux/amd64` emulation enabled (default on
   Apple Silicon Macs).
2. **Tensor SDK Beta access**. The pip packages this image installs
   (`ai-edge-litert-sdk-google-tensor-nightly`) are gated behind that
   signup. If pip install fails inside the build, you don't have access
   yet.

## One-time setup

```bash
cd tooling/tensor-aot
docker build --platform=linux/amd64 -t localllm-tensor-aot .
```

This pulls `python:3.11-slim` and installs the four nightlies the
upstream Colab uses (~1.5 GB image). Expect 3–5 min on first build,
seconds on subsequent rebuilds (Docker layer cache).

## Compile a model

```bash
# Assuming you've downloaded the stock Gemma 4 E2B at:
#   ~/models/gemma-4-E2B-it.litertlm

cd ~/models
/path/to/repo/tooling/tensor-aot/compile.sh \
    gemma-4-E2B-it.litertlm \
    --target=tensor_g5 \
    --out=./compiled
```

Targets: `tensor_g3`, `tensor_g4`, `tensor_g5`, `tensor_g6`.

Output lands in `./compiled/` with the SDK's canonical naming, e.g.
`gemma-4-E2B-it_Google_Tensor_G5.litertlm`. Push that to the device:

```bash
adb push compiled/gemma-4-E2B-it_Google_Tensor_G5.litertlm \
    /sdcard/Android/data/com.localllm.app/files/gemma-4-e2b-npu-tensor_g5.litertlm
```

Then in the app's Catalog, the file shows up as an importable model.
With `Settings → Inference → Backend → NPU/TPU`, LiteRT-LM will call
`Backend.NPU(nativeLibraryDir)` and load it — **provided** the runtime
delegate is present (see below).

## Missing piece — the Android runtime delegate

`Backend.NPU(nativeLibraryDir)` expects vendor delegate `.so` files in
the app's native lib dir at runtime. The Tensor SDK Beta provides
these separately from this host compiler — typically as either:

- A drop-in AAR (best: gets bundled into the APK at build time)
- Raw `.so` files (need to be packaged into `app/src/main/jniLibs/arm64-v8a/`)

Check the Tensor SDK Beta portal page where you downloaded
`liblitert_plugin_compiler.so`. The runtime libraries are usually on the
same page or in a sibling section ("Android runtime", "Device libraries",
"Sample app").

Until those are present, even a perfectly-compiled `_tensor_g5.litertlm`
will fail at `Engine.initialize()` with a delegate-not-found error.

## Troubleshooting

- **`docker build` fails on the pip install step**: you don't have Tensor
  SDK Beta access on whatever PyPI mirror Google uses for it. Sign up at
  [ai.google.dev/edge/litert/next/tensor_ml_sdk][1] or check whether the
  package is now public.
- **`compile.py` can't resolve a target object**: the SDK's target
  module name has churned. Re-check the live module names with
  `docker run --rm --platform=linux/amd64 localllm-tensor-aot
  python3 -c 'import ai_edge_litert_sdk_google_tensor as m; print(dir(m))'`
  and update `_TARGET_RESOLVERS` in `compile.py`.
- **Output file too large for the device**: Tensor-compiled artifacts
  can be larger than the CPU originals (extra schedule + buffer
  metadata). 2 GB+ models may run into Android's
  `getExternalFilesDir()` quota on older devices.

[1]: https://ai.google.dev/edge/litert/next/tensor_ml_sdk
