#!/usr/bin/env bash
# Container entrypoint. Wires the bind-mounted Tensor SDK into the
# installed `ai_edge_litert_sdk_google_tensor` Python package's `data/`
# directory, then execs the compile driver. Runs on every `docker run`
# so a swapped-in SDK blob takes effect without an image rebuild.

set -euo pipefail

if [ ! -f "/sdk/liblitert_plugin_compiler.so" ]; then
    echo "entrypoint: expected the Tensor SDK at /sdk/liblitert_plugin_compiler.so" >&2
    echo "entrypoint: bind-mount the directory containing that .so into /sdk (compile.sh handles this)." >&2
    exit 1
fi

# Find where pip installed the Tensor SDK stub package and wire its
# data/ subdir to our mounted /sdk. We use a symlink so the package
# code's `pathlib.Path(__file__).parent / "data"` still resolves, but
# the bytes live in the bind mount (no copy).
PKG_DIR=$(python3 -c "import pathlib, ai_edge_litert_sdk_google_tensor as m; print(pathlib.Path(m.__file__).parent)")
DATA_DIR="$PKG_DIR/data"
# Replace any prior data/ (e.g. from a previous run with a different SDK).
rm -rf "$DATA_DIR"
ln -s /sdk "$DATA_DIR"

exec python3 /usr/local/bin/compile.py "$@"
