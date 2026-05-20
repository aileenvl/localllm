#!/usr/bin/env bash
# Thin host-side wrapper around the Docker AOT compile image. Forwards
# args to compile.py inside the container, with the current directory
# bind-mounted as /work so input / output paths just work.
#
# Usage:
#   ./compile.sh path/to/gemma-4-e2b.litertlm --target=tensor_g5 \
#                                              --out=./compiled
#
# Build the image once before the first run:
#   docker build --platform=linux/amd64 -t localllm-tensor-aot .

set -euo pipefail

if ! command -v docker >/dev/null 2>&1; then
    echo "compile.sh: docker not found. Install Docker Desktop (with linux/amd64 emulation enabled)." >&2
    exit 1
fi

IMAGE="localllm-tensor-aot"
if ! docker image inspect "$IMAGE" >/dev/null 2>&1; then
    echo "compile.sh: image '$IMAGE' missing — run \`docker build --platform=linux/amd64 -t $IMAGE .\` first." >&2
    exit 1
fi

# Mount $PWD as /work so any relative paths the caller passes (model
# input, --out dir) resolve to the host filesystem.
exec docker run --rm \
    --platform=linux/amd64 \
    -v "$PWD:/work" \
    -w /work \
    "$IMAGE" \
    "$@"
