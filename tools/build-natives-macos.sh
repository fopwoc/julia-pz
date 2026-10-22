#!/usr/bin/env bash
# Builds Julia's SDL for the given macOS architectures into <natives>/macos/<arch>/libSDL3Julia.dylib.
# Usage: tools/build-natives-macos.sh <natives-dir> <arm64|x64>...
set -euo pipefail

root=$(cd "$(dirname "$0")/.." && pwd)
if [[ $(uname -s) != Darwin ]]; then
    echo 'macOS libraries need a macOS host with the Apple SDK.' >&2
    exit 1
fi
if [[ $# -lt 2 ]]; then
    echo "Usage: $0 <natives-dir> <arm64|x64>..." >&2
    exit 1
fi
command -v cmake >/dev/null || { echo 'Missing build tool: cmake' >&2; exit 1; }
natives=$1
shift
build="$root/native/sdl/build/universal"

for target in "$@"; do
    case $target in
        arm64) architecture=arm64 ;;
        x64) architecture=x86_64 ;;
        *) echo "Unknown macOS architecture: $target" >&2; exit 1 ;;
    esac
    directory="$build/macos-$target"
    cmake -S "$root/native/sdl" -B "$directory" -DCMAKE_BUILD_TYPE=Release \
        "-DCMAKE_OSX_ARCHITECTURES=$architecture" \
        "-DCMAKE_LIBRARY_OUTPUT_DIRECTORY=$directory/lib" -DSDL_HIDAPI_LIBUSB=OFF
    cmake --build "$directory" --target SDL3-shared --config Release --parallel 8
    mkdir -p "$natives/macos/$target"
    cp "$directory/lib/libSDL3Julia.0.dylib" "$natives/macos/$target/libSDL3Julia.dylib"
done
