#!/usr/bin/env bash
# Write ZombieBuddy's <jar>.zbs beside a built preload jar, the same sidecar as :preload:signJar.
# Release CI signs here, after the shared build, so branch and pull-request builds never run near the key.
# Usage: ZBS_PRIVATE_KEY=<base64 PKCS#8 DER> tools/sign-preload.sh <julia-preload.jar> <SteamID64>
set -euo pipefail
if [[ $# -ne 2 || -z ${ZBS_PRIVATE_KEY:-} ]]; then
    echo "Usage: ZBS_PRIVATE_KEY=<base64 DER> $0 <julia-preload.jar> <SteamID64>" >&2
    exit 1
fi
jar=$1 steam_id=$2
[[ $steam_id =~ ^[0-9]{17}$ ]] || { echo "SteamID64 must be 17 digits" >&2; exit 1; }

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
base64 --decode <<<"$ZBS_PRIVATE_KEY" >"$work/key.der" 2>/dev/null || base64 -D <<<"$ZBS_PRIVATE_KEY" >"$work/key.der"
digest=$(openssl dgst -sha256 -r "$jar" | cut -d' ' -f1)
printf 'ZBS:%s:%s' "$steam_id" "$digest" >"$work/message"
# Ed25519 signs the message itself, so pkeyutl needs -rawin rather than a pre-hashed input.
openssl pkeyutl -sign -rawin -inkey "$work/key.der" -keyform DER -in "$work/message" -out "$work/signature"
signature=$(od -An -v -tx1 "$work/signature" | tr -d ' \n')
printf 'ZBS\nSteamID64:%s\nSignature:%s\n' "$steam_id" "$signature" >"$jar.zbs"
