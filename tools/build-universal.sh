#!/usr/bin/env bash
set -euo pipefail

root=$(cd "$(dirname "$0")/.." && pwd)
if [[ $(uname -s) != Darwin ]]; then
    echo 'The universal build needs a macOS host with the Apple SDK for its Mac libraries.' >&2
    exit 1
fi
for tool in cmake docker; do
    command -v "$tool" >/dev/null || { echo "Missing build tool: $tool" >&2; exit 1; }
done
docker info >/dev/null
build="$root/native/sdl/build/universal"
natives="$build/natives"
mkdir -p "$natives"

docker buildx build --platform linux/amd64 \
    --output "type=local,dest=$natives" "$root/native"

for target in arm64 x64; do
    architecture=$target
    [[ $target != x64 ]] || architecture=x86_64
    directory="$build/macos-$target"
    cmake -S "$root/native/sdl" -B "$directory" -DCMAKE_BUILD_TYPE=Release \
        "-DCMAKE_OSX_ARCHITECTURES=$architecture" \
        "-DCMAKE_LIBRARY_OUTPUT_DIRECTORY=$directory/lib" -DSDL_HIDAPI_LIBUSB=OFF
    cmake --build "$directory" --target SDL3-shared --config Release --parallel 8
    mkdir -p "$natives/macos/$target"
    cp "$directory/lib/libSDL3Julia.0.dylib" "$natives/macos/$target/libSDL3Julia.dylib"
done

"$root/java/gradlew" -p "$root/java" check :agent:universalJar "-PjuliaNatives=$natives"
echo "Universal agent: $root/java/modules/agent/build/libs/julia-agent.jar"
