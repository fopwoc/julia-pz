#!/usr/bin/env bash
set -euo pipefail

root=$(cd "$(dirname "$0")/.." && pwd)
if [[ $(uname -s) != Darwin ]]; then
    echo 'The universal build needs a macOS host with the Apple SDK for its Mac libraries.' >&2
    exit 1
fi
command -v docker >/dev/null || { echo 'Missing build tool: docker' >&2; exit 1; }
docker info >/dev/null
natives="$root/native/sdl/build/universal/natives"
mkdir -p "$natives"

docker buildx build --platform linux/amd64 \
    --output "type=local,dest=$natives" "$root/native"
"$root/tools/build-natives-macos.sh" "$natives" arm64 x64

"$root/java/gradlew" -p "$root/java" check :agent:universalJar :workshop:stageAgent "-PjuliaNatives=$natives"
echo "Universal agent: $root/java/modules/agent/build/libs/julia-agent.jar (staged as $root/libs/julia-agent.jar)"
