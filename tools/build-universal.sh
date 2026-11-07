#!/usr/bin/env bash
set -euo pipefail

root=$(cd "$(dirname "$0")/.." && pwd)
if [[ $(uname -s) != Darwin ]]; then
    echo 'The universal build needs a macOS host with the Apple SDK for its Mac libraries.' >&2
    exit 1
fi
command -v docker >/dev/null || { echo 'Missing build tool: docker' >&2; exit 1; }
docker info >/dev/null
natives="$root/native/build/universal/natives"
mkdir -p "$natives"

docker buildx build --platform linux/amd64 \
    --build-context "sdl=$root/third_party/angelica/sdl-gpu/native" \
    --output "type=local,dest=$natives" "$root/native"
"$root/tools/build-natives-macos.sh" "$natives" arm64 x64

"$root/java/gradlew" -p "$root/java" check :preload:universalJar :agent:universalJar :workshop:stagePreload "-PjuliaNatives=$natives"
echo "Universal preload jar: $root/java/modules/preload/build/libs/julia-preload.jar (staged as $root/libs/julia-preload.jar)"
echo "Universal agent: $root/java/modules/agent/build/libs/julia-agent.jar"
