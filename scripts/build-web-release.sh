#!/usr/bin/env bash
# Builds the production web bundle from a copy of the working tree at a neutral path,
# /tmp/fluxit, so build paths cannot name this machine's user, then copies the bundle back
# to composeApp/build/dist/wasmJs/productionExecutable, where Firebase Hosting
# (firebase.json predeploy) and the local test server pick it up.
# The build's checkWebDistributionForLocalPaths task still fails on any local path.
set -euo pipefail

repo=$(cd "$(dirname "$0")/.." && pwd -P)
mirror=/tmp/fluxit
marker=.fluxit-release-mirror
dist=composeApp/build/dist/wasmJs/productionExecutable

if [ ! -f "$repo/composeApp/firebase-web-config.json" ]; then
    echo "build-web-release: missing composeApp/firebase-web-config.json (see README)." >&2
    exit 1
fi

refuse() {
    echo "build-web-release: $mirror $1; move it away or remove it, then run again." >&2
    exit 1
}

# The mirror is wiped before every copy, so only touch a folder this script made.
if [ -L "$mirror" ]; then
    refuse "is a symbolic link"
elif [ -e "$mirror" ]; then
    { [ -d "$mirror" ] && [ -O "$mirror" ]; } || refuse "is not a folder owned by you"
    if [ ! -e "$mirror/$marker" ] && [ -n "$(ls -A "$mirror")" ]; then
        refuse "has files this script did not create"
    fi
else
    mkdir -m 700 "$mirror"
fi
chmod 700 "$mirror"

# Sources are replaced on every run; Gradle's output and cache folders are kept so repeated
# builds stay incremental. The marker is written first and kept, so an interrupted run
# cannot leave a mirror this script would then refuse.
touch "$mirror/$marker"
find "$mirror" -mindepth 1 \
    \( -path "$mirror/build" -o -path "$mirror/.gradle" -o -path "$mirror/.kotlin" -o -path "$mirror/composeApp/build" \
       -o -path "$mirror/$marker" \) -prune \
    -o \( -type f -o -type l \) -exec rm -f {} +

cd "$repo"

# Tracked and new files, not ignored ones, plus the two gitignored files the build needs
# (Android SDK location, web Firebase config). Deleted-but-tracked files are skipped.
{
    git ls-files -z --cached --others --exclude-standard
    printf '%s\0' local.properties composeApp/firebase-web-config.json
} | while IFS= read -r -d '' file; do
    if [ -f "$file" ]; then printf '%s\0' "$file"; fi
done | tar --null -T - -cf - | tar -xf - -C "$mirror"

# No build cache: outputs cached by a build in the home folder must not be reused here.
(cd "$mirror" && ./gradlew --quiet --no-build-cache :composeApp:wasmJsBrowserDistribution)

mkdir -p "$repo/$dist"
rsync -a --delete "$mirror/$dist/" "$repo/$dist/"
echo "Web bundle built in $mirror and copied to $dist"
