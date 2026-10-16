#!/usr/bin/env python3
"""Compile the coverage point and cube Filament materials
(macOS, Linux and Windows hosts; Python 3.9+ standard library only).

Usage: `python3 android/tools/compile_coverage_materials.py --matc <matc>`
(`python` on Windows, where the compiler is `matc.exe`).

The compiler must report Filament binary material format `72` (Filament
1.72.1's `matc --version` prints the format, not its patch version). Each of
`src/main/materials/{coverage_points,coverage_cubes}.mat` is compiled for
mobile with OpenGL and Vulkan shaders into
`src/main/assets/materials/<name>.filamat`.

Exit codes: 0 compiled, 1 missing compiler, wrong format or failed
compilation, 2 usage error. The compiler runs with an argument list, never a
shell string.
"""
from __future__ import annotations

import argparse
import os
import subprocess
import sys
from typing import Callable, List, Optional, Sequence

REQUIRED_FORMAT = "72"
MATERIALS = ("coverage_points", "coverage_cubes")
DEFAULT_MAIN_DIR = os.path.normpath(
    os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "src", "main"))

Runner = Callable[..., "subprocess.CompletedProcess[str]"]


class MaterialCompileError(Exception):
    """A failure reported with exit code 1."""


def resolve_compiler(matc: str) -> str:
    path = os.path.abspath(matc)
    if not os.path.isfile(path):
        raise MaterialCompileError(f"Material compiler not found: {matc}")
    return path


def check_format(compiler: str, run: Runner = subprocess.run) -> None:
    result = run([compiler, "--version"], capture_output=True, text=True)
    reported = (result.stdout or "").strip()
    if result.returncode != 0 or reported != REQUIRED_FORMAT:
        raise MaterialCompileError(
            f"Coverage materials require Filament material format {REQUIRED_FORMAT}; "
            f"compiler reported '{reported}'.")


def compile_command(compiler: str, main_dir: str, name: str) -> List[str]:
    source = os.path.join(main_dir, "materials", f"{name}.mat")
    output = os.path.join(main_dir, "assets", "materials", f"{name}.filamat")
    return [compiler, "--platform", "mobile", "--api", "opengl", "--api", "vulkan",
            "--output", output, source]


def compile_materials(compiler: str, main_dir: str = DEFAULT_MAIN_DIR,
                      run: Runner = subprocess.run) -> None:
    check_format(compiler, run)
    for name in MATERIALS:
        if run(compile_command(compiler, main_dir, name)).returncode != 0:
            raise MaterialCompileError(f"Material compilation failed: {name}")


def main(argv: Optional[Sequence[str]] = None, run: Runner = subprocess.run) -> int:
    parser = argparse.ArgumentParser(
        description="Compile the coverage Filament materials with matc (format 72).")
    parser.add_argument("--matc", required=True,
                        help="path to the Filament 1.72.1 matc executable")
    args = parser.parse_args(argv)
    try:
        compile_materials(resolve_compiler(args.matc), run=run)
    except MaterialCompileError as error:
        print(error, file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
