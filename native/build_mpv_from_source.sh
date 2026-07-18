#!/usr/bin/env bash
set -euo pipefail

# Upstream's native build supports Linux/macOS, explicitly not WSL.
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
work="$root/native/.work/mpv-android"
commit="$(tr -d '\r\n' < "$root/native/MPV_ANDROID_COMMIT")"

if [[ ! -d "$work/.git" ]]; then
    git clone https://github.com/mpv-android/mpv-android.git "$work"
fi
git -C "$work" fetch --tags origin
git -C "$work" checkout --detach "$commit"
(
    cd "$work/buildscripts"
    ./download.sh
    ./buildall.sh --arch arm64 mpv
    ./buildall.sh -n mpv-android
)

destination="$root/player-mpv/src/main/jniLibs/arm64-v8a"
mkdir -p "$destination"
cp "$work/app/src/main/libs/arm64-v8a/"*.so "$destination/"
echo "Built and installed mpv-android $commit arm64 native libraries."

