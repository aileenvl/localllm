# Tensor AOT compiler image — wraps Google's ai-edge-litert + Tensor Beta SDK
# so we can convert a stock `.litertlm` into a Tensor-G3/G4/G5/G6 compiled
# artifact. Pinned to linux/amd64 because the underlying
# `liblitert_plugin_compiler.so` ships only as an x86_64 ELF.
#
# Build once (~3 min), reuse for every compile run:
#   docker build --platform=linux/amd64 -t localllm-tensor-aot .
#
# Then call the compile script via the wrapper in `compile.sh`.

FROM --platform=linux/amd64 python:3.11-slim

# litert-torch and the Tensor SDK pull libc++ at runtime. Slim image lacks it,
# and the upstream Colab installs llvm.sh — we use the distro libstdc++ here
# instead (smaller layer, no fetching `apt.llvm.org` at build time).
RUN apt-get update && apt-get install -y --no-install-recommends \
        libstdc++6 \
        libgomp1 \
        ca-certificates \
    && rm -rf /var/lib/apt/lists/*

# Each package below is the nightly upstream Google publishes for the AOT
# compilation flow. Tensor (Beta) is the one the user got access to; the
# others are needed because the Python driver imports a unified target
# enum that references all four vendors. If a future stable release lands,
# pin the version here.
RUN pip install --no-cache-dir \
        ai-edge-litert-nightly \
        ai-edge-litert-sdk-google-tensor-nightly \
        litert-torch-nightly \
    || (echo '!! pip install failed — Tensor SDK may be gated.' \
        && echo '!! If you have private wheel access, mount the wheelhouse at' \
        && echo '!! /wheels and rebuild with `--build-arg WHEELHOUSE=/wheels`.' \
        && exit 1)

WORKDIR /work
COPY compile.py /usr/local/bin/compile.py
RUN chmod +x /usr/local/bin/compile.py

# Default entrypoint is the compile driver. Pass model + target as args:
#   docker run --rm -v $PWD:/work localllm-tensor-aot \
#     /work/gemma-4-e2b.litertlm --target=tensor_g5
ENTRYPOINT ["python3", "/usr/local/bin/compile.py"]
CMD ["--help"]
