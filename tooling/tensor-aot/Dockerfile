# Tensor AOT compiler image — wraps Google's ai-edge-litert + Tensor Beta SDK
# Python tooling so we can convert a stock `.litertlm` into a Tensor-G3/G4/G5/G6
# compiled artifact. Pinned to linux/amd64 because the underlying
# `liblitert_plugin_compiler.so` ships only as an x86_64 ELF.
#
# Build once (~30s, ~600 MB image), reuse for every compile run:
#   docker build --platform=linux/amd64 -t localllm-tensor-aot .
#
# Then call the compile script via the wrapper in `compile.sh`. The SDK is
# bind-mounted at runtime (NOT baked into the image) so:
#   - `docker build` stays fast and memory-light on Apple Silicon emulation
#   - swapping in a newer compiler is a `cp` away — no rebuild needed
#   - the 166 MB SDK blob never lives in a Docker layer

FROM --platform=linux/amd64 python:3.11-slim

# Runtime deps for the Tensor SDK compiler .so.
RUN apt-get update && apt-get install -y --no-install-recommends \
        libstdc++6 \
        libgomp1 \
        ca-certificates \
    && rm -rf /var/lib/apt/lists/*

# The `ai-edge-litert-sdk-google-tensor-nightly` PyPI package is a thin
# stub whose setup.py refuses to install unless `GOOGLE_TENSOR_SDK_BETA`
# points at the SDK tarball. Its own escape hatch is `SKIP_SDK_DOWNLOAD=1`
# — set that during pip install so the package installs *without* baking
# in any .so. The runtime entrypoint below symlinks the bind-mounted SDK
# into the package's `data/` dir before invoking compile.py.
ENV SKIP_SDK_DOWNLOAD=1
RUN pip install --no-cache-dir \
        ai-edge-litert-nightly \
        ai-edge-litert-sdk-google-tensor-nightly \
        litert-torch-nightly

COPY entrypoint.sh /usr/local/bin/entrypoint.sh
COPY compile.py    /usr/local/bin/compile.py
RUN chmod +x /usr/local/bin/entrypoint.sh /usr/local/bin/compile.py

WORKDIR /work
ENTRYPOINT ["/usr/local/bin/entrypoint.sh"]
CMD ["--help"]
