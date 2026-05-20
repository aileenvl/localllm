#!/usr/bin/env python3
"""
Tensor TPU AOT compile driver.

Takes a stock `.litertlm` (or `.tflite`) and produces a Tensor-targeted
compiled artifact that LiteRT-LM's `Backend.NPU(nativeLibraryDir)` can
load on a matching Pixel device.

Runs inside the `localllm-tensor-aot` Docker image (host SDK is x86_64
Linux). Driven by `compile.sh` in the same directory.

The SDK API surface was reverse-engineered from
`ai_edge_litert.aot.aot_compile` (`aot_compile()` function) and
`ai_edge_litert.aot.vendors.google_tensor.target` (`Target` +
`SocModel`). Pinned here so a future SDK rename only touches this file.
"""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

from ai_edge_litert.aot.aot_compile import aot_compile
from ai_edge_litert.aot.vendors.google_tensor.target import (
    SocModel,
    Target as GoogleTensorTarget,
)


# Short-label → SocModel enum lookup. Keys are what compile.sh forwards
# via `--target=tensor_g5`. The SDK's own SocModel enum uses
# `Tensor_G5`-style strings; we wrap both naming conventions here so the
# CLI feels native without leaking SDK details.
_SOC_MAP = {
    "tensor_g3": SocModel.TENSOR_G3,
    "tensor_g4": SocModel.TENSOR_G4,
    "tensor_g5": SocModel.TENSOR_G5,
    "tensor_g6": SocModel.TENSOR_G6,
}


def _resolve_target(name: str) -> GoogleTensorTarget:
    key = name.lower().replace("-", "_")
    soc = _SOC_MAP.get(key)
    if soc is None:
        raise SystemExit(
            f"Unknown target '{name}'. Known: " + ", ".join(_SOC_MAP)
        )
    return GoogleTensorTarget(soc_model=soc)


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
        help="Don't abort the run on first compile error.",
    )
    args = parser.parse_args()

    if not args.model.exists():
        raise SystemExit(f"Input model not found: {args.model}")
    args.out.mkdir(parents=True, exist_ok=True)

    target = _resolve_target(args.target)
    print(f"compile.py: target={target} (SocModel.{_SOC_MAP[args.target.lower()].name})")
    print(f"            input  = {args.model.resolve()}")
    print(f"            output = {args.out.resolve()}")

    result = aot_compile(
        input_model=str(args.model.resolve()),
        output_dir=str(args.out.resolve()),
        target=[target],
        keep_going=args.keep_going,
    )

    # CompilationResult shape is not publicly documented but the upstream
    # AOT tutorial treats it as an iterable of compiled-model wrappers.
    # Be defensive — print whatever attributes we can find that look
    # like output paths, plus a summary.
    print("compile.py: done.")
    print(f"  result type: {type(result).__name__}")
    written = sorted(args.out.glob("**/*"))
    written_files = [p for p in written if p.is_file()]
    if written_files:
        print(f"  wrote {len(written_files)} file(s) under {args.out.resolve()}:")
        for p in written_files:
            size_mb = p.stat().st_size / (1024 * 1024)
            print(f"    -> {p.relative_to(args.out.resolve())}  ({size_mb:.1f} MB)")
    else:
        print("  WARN: no files written under --out. Inspect the result object:")
        for attr in dir(result):
            if attr.startswith("_"):
                continue
            try:
                val = getattr(result, attr)
            except Exception:
                continue
            if callable(val):
                continue
            print(f"    {attr} = {val!r}"[:200])
    return 0


if __name__ == "__main__":
    sys.exit(main())
