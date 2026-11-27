#!/bin/sh
set -eu

ROOT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
CXX="${CXX:-clang++}"
if ! command -v "$CXX" >/dev/null 2>&1; then
    echo "[ERROR] clang++ not found. Run: xcode-select --install" >&2
    exit 1
fi

FLAGS="-dynamiclib -fPIC -O3 -std=c++17 -ffp-contract=off -fno-math-errno -fno-trapping-math -fno-exceptions -fno-rtti -fvisibility=hidden -mmacosx-version-min=11.0 -Wall -Wextra -Werror"

X64_OUT="$ROOT_DIR/src/main/resources/natives/macos-x64/libchunking_avx2.dylib"
mkdir -p "$(dirname "$X64_OUT")"
# shellcheck disable=SC2086
"$CXX" $FLAGS -arch x86_64 -mavx2 -install_name "@rpath/libchunking_avx2.dylib" "$ROOT_DIR/src/main/native/chunking_avx2.cpp" -o "$X64_OUT"
strip -x "$X64_OUT" || true
echo "[Chunking macOS] Built: $X64_OUT"

ARM_OUT="$ROOT_DIR/src/main/resources/natives/macos-arm64/libchunking_neon.dylib"
mkdir -p "$(dirname "$ARM_OUT")"
# shellcheck disable=SC2086
"$CXX" $FLAGS -arch arm64 -install_name "@rpath/libchunking_neon.dylib" "$ROOT_DIR/src/main/native/chunking_neon.cpp" -o "$ARM_OUT"
strip -x "$ARM_OUT" || true
echo "[Chunking macOS] Built: $ARM_OUT"
