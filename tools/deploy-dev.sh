#!/usr/bin/env bash
# Installs this checkout as a separate JuliaDev mod. The game searches Workshop folders before ~/Zomboid/mods, so a local
# copy with the published id would be shadowed; its own id lets it sit beside a Workshop subscription.
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
target="${ZOMBOID_MODS:-$HOME/Zomboid/mods}/JuliaDev"

if pgrep -f ProjectZomboid >/dev/null; then
    echo "Close the game first: it keeps the mod jar open." >&2
    exit 1
fi

(cd "$root/java" && ./gradlew --console=plain --quiet :workshop:modZip)

staging="$(mktemp -d)"
trap 'rm -rf "$staging"' EXIT
unzip -q "$root/java/modules/workshop/build/distributions/Julia.zip" -d "$staging"

if [[ ! -f "$staging/Julia/libs/julia-agent.jar.zbs" ]]; then
    echo "The build is unsigned, so ZombieBuddy would refuse to preload it. Run: ./gradlew :agent:generateSigningKey" >&2
    exit 1
fi

sed -i.bak -e 's/^name=.*/name=Julia (local dev)/' -e 's/^id=.*/id=JuliaDev/' "$staging/Julia/42/mod.info"
rm "$staging/Julia/42/mod.info.bak"

rm -rf "$target"
mkdir -p "$(dirname "$target")"
mv "$staging/Julia" "$target"

unzip -p "$target/libs/julia-agent.jar" META-INF/MANIFEST.MF | grep Implementation-Version | tr -d '\r'
echo "Installed $target"
echo "Enable \"Julia (local dev)\" and disable the Workshop \"Julia\" in the mod manager."
