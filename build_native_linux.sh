#!/bin/sh
set -eu

ROOT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
if [ ! -d "$ROOT_DIR/src/main/native" ] && [ -d "$ROOT_DIR/Fabric/MC-26.2/src/main/native" ]; then
    ROOT_DIR="$ROOT_DIR/Fabric/MC-26.2"
fi
ARCH="$(uname -m)"

CXX="${CXX:-}"
if [ -z "$CXX" ]; then
    if command -v g++ >/dev/null 2>&1; then CXX="g++"
    elif command -v clang++ >/dev/null 2>&1; then CXX="clang++"
    else echo "[ERROR] No C++ compiler found (g++ or clang++)." >&2; exit 1
    fi
fi

FLAGS="-shared -fPIC -O3 -std=c++17 -ffp-contract=off -fno-math-errno -fno-trapping-math -fno-exceptions -fno-rtti -fvisibility=hidden -nostdlib -Wl,--no-undefined -Wl,--strip-all -Wall -Wextra -Werror"
if "$CXX" --version 2>/dev/null | grep -qi "g++\|gcc"; then
    FLAGS="$FLAGS -fno-tree-loop-distribute-patterns"
fi

case "$ARCH" in
    x86_64|amd64)
        SRC="$ROOT_DIR/src/main/native/chunking_avx2.cpp"
        OUT="$ROOT_DIR/src/main/resources/natives/linux-x64/libchunking_avx2.so"
        EXTRA="-mavx2"
        ;;
    aarch64|arm64)
        SRC="$ROOT_DIR/src/main/native/chunking_neon.cpp"
        OUT="$ROOT_DIR/src/main/resources/natives/linux-arm64/libchunking_neon.so"
        EXTRA=""
        ;;
    *)
        echo "[ERROR] Unsupported Linux architecture: $ARCH" >&2
        exit 1
        ;;
esac

mkdir -p "$(dirname "$OUT")"
# shellcheck disable=SC2086
"$CXX" $FLAGS $EXTRA "$SRC" -o "$OUT"
echo "[Chunking Linux] Built: $OUT ($(du -h "$OUT" | cut -f1))"
