#!/usr/bin/env bash
# Package committed Julia source, source revision links, and the matching prebuilt agent/Windows loader.
# Usage: tools/package-workshop.sh <agent.jar> <julia.dll> <output.zip> [revision]
set -euo pipefail
root=$(cd "$(dirname "$0")/.." && pwd)
if [[ $# -lt 3 || $# -gt 4 ]]; then
    echo "Usage: $0 <agent.jar> <julia.dll> <output.zip> [revision]" >&2
    exit 1
fi
exec python3 "$root/tools/package-distribution.py" workshop "$3" --agent "$1" --loader "$2" --revision "${4:-HEAD}"
