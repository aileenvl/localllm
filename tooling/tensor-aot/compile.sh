#!/usr/bin/env bash
# Host-side wrapper around the Docker AOT compile image. Forwards args to
# compile.py inside the container, with:
#   - $PWD bind-mounted as /work (so the caller's relative paths just work)
#   - $SDK_PATH (default: tooling/tensor-aot/sdk/) bind-mounted as /sdk so
#     the entrypoint can wire the compiler .so into the SDK Python package
#
# Build the image once before the first run (fast — no SDK baked in):
#   docker build --platform=linux/amd64 -t localllm-tensor-aot .
#
# Usage:
#   ./compile.sh path/to/gemma-4-E2B-it.litertlm \
#       --target=tensor_g5 --out=./compiled
#
# Override the SDK location with: SDK_PATH=/somewhere/else ./compile.sh ...

set -euo pipefail

if ! command -v docker >/dev/null 2>&1; then
    echo "compile.sh: docker not found. Install Docker Desktop." >&2
    exit 1
fi

IMAGE="localllm-tensor-aot"
if ! docker image inspect "$IMAGE" >/dev/null 2>&1; then
    echo "compile.sh: image '$IMAGE' missing — run \`docker build --platform=linux/amd64 -t $IMAGE .\` first." >&2
    exit 1
fi

# Locate the SDK. Defaults to a sibling `sdk/` next to this script —
# `tooling/tensor-aot/sdk/liblitert_plugin_compiler.so`. The .gitignore
# excludes it so the 166 MB blob never enters the repo.
SCRIPT_DIR=$( cd -- "$( dirname -- "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )
SDK_PATH="${SDK_PATH:-$SCRIPT_DIR/sdk}"
if [ ! -f "$SDK_PATH/liblitert_plugin_compiler.so" ]; then
    cat >&2 <<EOF
compile.sh: Tensor SDK not found.
            Expected: $SDK_PATH/liblitert_plugin_compiler.so
            Either copy the SDK .so there, or set SDK_PATH to the directory
            that contains liblitert_plugin_compiler.so.
EOF
    exit 1
fi

exec docker run --rm \
    --platform=linux/amd64 \
    -v "$PWD:/work" \
    -v "$SDK_PATH:/sdk:ro" \
    -w /work \
    "$IMAGE" \
    "$@"
