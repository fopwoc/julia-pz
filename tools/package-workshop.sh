#!/usr/bin/env bash
# Packs the Workshop folder: the committed tree at <revision> plus the prebuilt agent and its Windows loader in libs/. The upload tool applies
# .workshopignore, so the archive keeps the full source, as ZombieBuddy recommends.
# Usage: tools/package-workshop.sh <agent.jar> <julia.dll> <output.zip> [revision]
set -euo pipefail

root=$(cd "$(dirname "$0")/.." && pwd)
if [[ $# -lt 3 || $# -gt 4 ]]; then
    echo "Usage: $0 <agent.jar> <julia.dll> <output.zip> [revision]" >&2
    exit 1
fi
agent=$(cd "$(dirname "$1")" && pwd)/$(basename "$1")
loader=$(cd "$(dirname "$2")" && pwd)/$(basename "$2")
output=$(cd "$(dirname "$3")" && pwd)/$(basename "$3")
revision=${4:-HEAD}
[[ -f $agent ]] || { echo "Missing agent jar: $agent" >&2; exit 1; }
[[ -f $loader ]] || { echo "Missing Windows loader: $loader" >&2; exit 1; }

stage=$(mktemp -d)
trap 'rm -rf "$stage"' EXIT
git -C "$root" archive --format=tar --prefix=Julia/ "$revision" | tar -x -C "$stage"
mkdir -p "$stage/Julia/libs"
cp "$agent" "$stage/Julia/libs/julia-agent.jar"
cp "$loader" "$stage/Julia/libs/julia.dll"
rm -f "$output"
(cd "$stage" && find Julia -exec touch -t 198001010000 {} + && find Julia -type f | LC_ALL=C sort | zip -X -q "$output" -@)
echo "Workshop folder: $output"
