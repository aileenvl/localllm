#!/usr/bin/env python3
"""
Tensor TPU AOT compile driver.

Takes a stock `.litertlm` (or `.tflite`) and produces a Tensor-targeted
compiled artifact that LiteRT-LM's `Backend.NPU(nativeLibraryDir)` can load
on a matching Pixel device.

Runs inside the `localllm-tensor-aot` Docker image (the host SDK is x86_64
Linux). Driven by `compile.sh` in the same directory, but can be invoked
directly if you've already set up the Python env:

    python3 compile.py model.litertlm --target=tensor_g5 --out=./out

The Google Tensor SDK is in Beta; the exact Python API surface is the same
as the upstream LiteRT AOT tutorial
(github.com/google-ai-edge/litert-samples · compiled_model_api). If a
future version renames `aot_compile`, the resolver below falls through a
small set of known import paths so we can patch in one place.
"""

from __future__ import annotations

import argparse
import importlib
import os
import sys
from pathlib import Path


# Each tuple is (importable module, attribute we want to bind locally).
# Listed in priority order — first one that resolves wins.
_AOT_RESOLVERS = [
    ("ai_edge_litert.aot", "aot_compile"),
    ("ai_edge_litert", "aot_compile"),
    ("litert_aot", "aot_compile"),
]

# Same idea for the SoC target objects. The package name shape has churned
# during Beta — list the candidates and pick the first that's importable.
_TARGET_RESOLVERS = [
    "ai_edge_litert_sdk_google_tensor.targets",
    "ai_edge_litert.targets",
    "ai_edge_litert_sdk.google_tensor.targets",
]


def _resolve_aot_compile():
    for module_name, attr in _AOT_RESOLVERS:
        try:
            mod = importlib.import_module(module_name)
        except ImportError:
            continue
        fn = getattr(mod, attr, None)
        if callable(fn):
            return fn, f"{module_name}.{attr}"
    raise SystemExit(
        "Couldn't find `aot_compile` in any of: "
        + ", ".join(f"{m}.{a}" for m, a in _AOT_RESOLVERS)
        + ". The Tensor SDK pip packages may not be installed, or their API "
        "has moved since this script was written."
    )


def _resolve_target(name: str):
    """`name` is a short label like 'tensor_g5' — return the SDK's target object."""
    short = name.lower().replace("-", "_")
    candidates = {
        "tensor_g3": ["GOOGLE_TENSOR_G3", "google_tensor_g3", "TensorG3"],
        "tensor_g4": ["GOOGLE_TENSOR_G4", "google_tensor_g4", "TensorG4"],
        "tensor_g5": ["GOOGLE_TENSOR_G5", "google_tensor_g5", "TensorG5"],
        "tensor_g6": ["GOOGLE_TENSOR_G6", "google_tensor_g6", "TensorG6"],
    }
    wanted = candidates.get(short)
    if not wanted:
        raise SystemExit(
            f"Unknown target '{name}'. Known: " + ", ".join(candidates)
        )
    for module_name in _TARGET_RESOLVERS:
        try:
            mod = importlib.import_module(module_name)
        except ImportError:
            continue
        for attr in wanted:
            obj = getattr(mod, attr, None)
            if obj is not None:
                return obj, f"{module_name}.{attr}"
    raise SystemExit(
        f"Couldn't resolve a target object for '{name}'. Tried attributes "
        f"{wanted} in modules {_TARGET_RESOLVERS}. The Tensor SDK may not be "
        "installed correctly, or the API has churned — inspect the module "
        "with `python -c 'import ai_edge_litert_sdk_google_tensor as m; "
        "print(dir(m))'`."
    )


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Compile a generic .litertlm/.tflite for a Tensor TPU SoC."
    )
    parser.add_argument(
        "model",
        type=Path,
        help="Path to the input `.litertlm` (or `.tflite`).",
    )
    parser.add_argument(
        "--target",
        required=True,
        help="SoC target: tensor_g3, tensor_g4, tensor_g5, tensor_g6.",
    )
    parser.add_argument(
        "--out",
        type=Path,
        default=Path("./compiled"),
        help="Output directory (default: ./compiled).",
    )
    parser.add_argument(
        "--keep-going",
        action="store_true",
        help="Don't abort the run on first compile error (multi-target compiles).",
    )
    args = parser.parse_args()

    if not args.model.exists():
        raise SystemExit(f"Input model not found: {args.model}")
    args.out.mkdir(parents=True, exist_ok=True)

    aot_compile, aot_name = _resolve_aot_compile()
    target_obj, target_name = _resolve_target(args.target)

    print(f"compile.py: using {aot_name} with target {target_name}")
    print(f"            input  = {args.model.resolve()}")
    print(f"            output = {args.out.resolve()}")

    # The output filename convention from the upstream Colab is
    # `{model}_{backend_id}.{ext}`. We let the SDK pick that naming so a
    # future tweak upstream doesn't trip us up.
    result = aot_compile(
        str(args.model),
        target=[target_obj],
        keep_going=args.keep_going,
    )

    # `result` shape isn't stable across SDK versions — try the two common
    # forms: a list of paths, or a dict keyed by target. Either way we want
    # to copy the artifact(s) into args.out and print the final paths.
    produced: list[Path] = []
    if isinstance(result, dict):
        for tgt, val in result.items():
            if isinstance(val, (str, os.PathLike)):
                produced.append(Path(val))
    elif isinstance(result, (list, tuple)):
        for val in result:
            if isinstance(val, (str, os.PathLike)):
                produced.append(Path(val))
            elif hasattr(val, "path"):
                produced.append(Path(val.path))
    else:
        # Likely a bespoke CompileResult object — try common attrs.
        for attr in ("paths", "outputs", "path"):
            val = getattr(result, attr, None)
            if isinstance(val, (str, os.PathLike)):
                produced.append(Path(val))
            elif isinstance(val, (list, tuple)):
                produced.extend(Path(p) for p in val)

    if not produced:
        print(
            "WARN: compile() succeeded but no output path could be extracted "
            "from the result object. Inspect the SDK's return shape:",
            type(result).__name__,
            dir(result),
        )
        return 1

    final = []
    for src in produced:
        dst = args.out / src.name
        if src.resolve() != dst.resolve():
            dst.write_bytes(src.read_bytes())
        final.append(dst)

    print("compile.py: done.")
    for p in final:
        size_mb = p.stat().st_size / (1024 * 1024)
        print(f"  -> {p}  ({size_mb:.1f} MB)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
