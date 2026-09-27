#!/usr/bin/env bash
# Boots a Shulker jar with a set of popular plugins and fails if any of them
# does not load and enable cleanly.
#
#   scripts/plugin-smoke-test.sh [shulker.jar] [work dir]
#
# Running this accepts the Minecraft EULA (https://aka.ms/MinecraftEULA) for the
# throwaway test server it creates.
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
jar="$(realpath "${1:-$(ls "$root"/build/libs/shulker-*.jar | head -n1)}")"
work="$(realpath -m "${2:-$root/run-smoke}")"
cache="$root/.gradle/plugin-cache"
list="$root/scripts/plugin-smoke-test.plugins"
boot_timeout=300

rm -rf "$work"
mkdir -p "$work/plugins" "$cache"

# ---- plugins ----------------------------------------------------------------
expected=0
while read -r name sha512 url; do
    [[ -z "$name" || "$name" == \#* ]] && continue
    file="$cache/$(basename "$url")"
    if [[ ! -f "$file" ]] || ! echo "$sha512  $file" | sha512sum --check --status; then
        echo "Downloading $name"
        curl -fsSL -A "shulker-smoke-test" -o "$file" "$url"
        echo "$sha512  $file" | sha512sum --check --quiet
    fi
    cp "$file" "$work/plugins/"
    expected=$((expected + 1))
done < "$list"

# ---- boot -------------------------------------------------------------------
echo "eula=true" > "$work/eula.txt"
cd "$work"
mkfifo console
exec 3<>console
java -jar "$jar" --memory=2G nogui < console > server.log 2>&1 &
server=$!

send() { echo "$1" >&3; }
cleanup() { kill "$server" 2>/dev/null || true; }
trap cleanup EXIT

for ((i = 0; i < boot_timeout; i++)); do
    grep -q 'Done (' server.log && break
    if ! kill -0 "$server" 2>/dev/null; then
        echo "Server exited during startup:"; tail -n 50 server.log; exit 1
    fi
    sleep 1
done
if ! grep -q 'Done (' server.log; then
    echo "Server did not finish starting within ${boot_timeout}s:"; tail -n 50 server.log; exit 1
fi

# Exercise a few plugin commands so their command paths load too.
for command in "plugins" "version" "lp info" "papi version" "viaversion" "essentials"; do
    send "$command"
done
sleep 5
send "stop"
wait "$server" || true
trap - EXIT

# ---- verdict ----------------------------------------------------------------
enabled=$(grep -cE '\] Enabling [^ ]+ v' server.log || true)
problems=$(grep -E 'Error occurred while enabling|Could not load plugin|Error occurred while loading|Exception in thread' server.log || true)

echo
echo "Boot:     $(grep -oE 'Done \([0-9.]+s\)' server.log)"
echo "Plugins:  $enabled of $expected enabled"
grep -E '\] Enabling [^ ]+ v' server.log | sed -E 's/.*\] Enabling /  - /'

if [[ -n "$problems" || "$enabled" -lt "$expected" ]]; then
    echo
    echo "FAILED"
    [[ -n "$problems" ]] && echo "$problems"
    exit 1
fi
echo "PASSED"
