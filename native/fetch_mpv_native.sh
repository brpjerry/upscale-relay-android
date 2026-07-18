#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
destination="${1:-$root/player-mpv/src/main/jniLibs/arm64-v8a}"
url="https://github.com/mpv-android/mpv-android/releases/download/2026-04-25/app-default-arm64-v8a-release.apk"
expected="4400bcba6be9cec1128e24d1eba153d8727384926b0639fa7fe44d4e36b04f81"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

curl --fail --location "$url" --output "$work/mpv.apk"
echo "$expected  $work/mpv.apk" | sha256sum --check --status
mkdir -p "$destination"
unzip -jo "$work/mpv.apk" 'lib/arm64-v8a/*.so' -d "$destination"
test -f "$destination/libmpv.so"
test -f "$destination/libplayer.so"
echo "Installed pinned arm64 mpv libraries in $destination"

