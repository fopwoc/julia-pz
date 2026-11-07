#!/usr/bin/env bash
# Finds the game and its bundled Java, then hands over to the jar, which holds all the logic.
# Usage: profile.sh run <scene.json> [options] | bake <Mode/SaveName> | compare <a> <b> | audit <jar>
# PZ_GAME overrides where the game is installed.
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
jar="$here/agent/build/libs/profiling-agent.jar"
[[ -f $here/lib/profiling-agent.jar ]] && jar="$here/lib/profiling-agent.jar"
if [[ ! -f $jar ]]; then
    echo "Build it first: ./java/gradlew -p java :profiling-agent:jar" >&2
    exit 1
fi

if [[ $(uname) == Darwin ]]; then
    game="${PZ_GAME:-$HOME/Library/Application Support/Steam/steamapps/common/ProjectZomboid}"
    [[ $(uname -m) == arm64 ]] && runtime=jre-aarch64 || runtime=jre-x86_64
    java="$game/Project Zomboid.app/Contents/PlugIns/$runtime/Contents/Home/bin/java"
else
    game="${PZ_GAME:-$HOME/.steam/steam/steamapps/common/ProjectZomboid}"
    java="$game/jre64/bin/java"
fi
if [[ ! -x $java ]]; then
    echo "No game found at $game; set PZ_GAME" >&2
    exit 1
fi

command="${1:-}"
[[ $# -gt 0 ]] && shift
if [[ $command == run ]]; then
    exec "$java" -jar "$jar" run "$@" --game "$game"
fi
exec "$java" -jar "$jar" "$command" "$@"
