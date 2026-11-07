#!/usr/bin/env bash
# Package committed Julia source, source revision links, and the matching prebuilt preload jar.
# Usage: tools/package-workshop.sh <preload.jar> <output.zip> [revision]
set -euo pipefail
root=$(cd "$(dirname "$0")/.." && pwd)
if [[ $# -lt 2 || $# -gt 3 ]]; then
    echo "Usage: $0 <preload.jar> <output.zip> [revision]" >&2
    exit 1
fi
exec python3 "$root/tools/package-distribution.py" workshop "$2" --preload "$1" --revision "${3:-HEAD}"
