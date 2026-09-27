#!/usr/bin/env bash
# End-to-end test of .shulk storage on a real server. Every step ends by reading back every
# .shulk chunk (--verify-world) and checking marker blocks placed across several regions.
#
#   scripts/world-format-test.sh [shulker.jar] [work dir]
#
# 1. generate an .mca world and place markers
# 2. migrate it in place (storage.format: shulk) with cold recompression forced on, edit markers
# 3. restart and read the mixed .mca/.shulk world
# 4. convert to .mca and back to .shulk offline
# 5. generate a fresh native .shulk world
# Running this accepts the Minecraft EULA for the throwaway servers it creates.
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
jar="$(realpath "${1:-$(ls "$root"/build/libs/shulker-*.jar | head -n1)}")"
work="$(realpath -m "${2:-$root/run-world-test}")"
port="${WORLD_TEST_PORT:-25589}"
coords=(-500 -300 100 400) # four different regions
failures=0

run_server() { # run_server <dir> <commands...>: start, forceload markers, run commands, save, stop
    local dir=$1; shift
    (
        cd "$dir"
        rm -f console run.log
        mkfifo console
        exec 3<>console
        java -jar "$jar" --memory=3G nogui < console > run.log 2>&1 &
        local pid=$!
        for _ in $(seq 1 300); do grep -q "Done (" run.log 2>/dev/null && break; sleep 1; done
        for c in "${coords[@]}"; do echo "forceload add $c $c" >&3; done
        # Generate some terrain so regions are written, rewritten and recompressed.
        echo "forceload add -256 -256 -1 -1" >&3
        sleep 5
        for cmd in "$@"; do echo "$cmd" >&3; done
        sleep "${SETTLE:-5}"
        echo "save-all flush" >&3
        sleep 5
        echo stop >&3
        wait "$pid"
    )
}

expect() { # expect <dir> <n>: the last run printed n "Test passed" and no chunk IO errors
    local dir=$1 want=$2
    local passed errors
    passed=$(grep -c "Test passed" "$dir/run.log" || true)
    errors=$(grep -cE "Failed to (read|load|write) chunk|damaged chunk entries|ZstdException" "$dir/run.log" || true)
    if [[ $passed -ne $want || $errors -ne 0 ]]; then
        echo "  FAIL: $passed/$want markers, $errors chunk IO errors"; grep -E "Failed to|damaged|Zstd" "$dir/run.log" | head -5
        failures=$((failures + 1))
    else
        echo "  ok: $passed/$want markers, no chunk IO errors"
    fi
}

verify() { # verify <dir>
    if (cd "$1" && java -jar "$jar" --verify-world=world 2>&1 | tail -n1 | sed 's/^/  /'; exit "${PIPESTATUS[0]}"); then :; else
        echo "  FAIL: verification"; failures=$((failures + 1)); fi
}

markers() { # markers <block> <y>: setblock commands
    for c in "${coords[@]}"; do echo "setblock $c $2 $c minecraft:$1"; done
}
tests() { # tests <block> <y>: execute-if commands
    for c in "${coords[@]}"; do echo "execute if block $c $2 $c minecraft:$1"; done
}

rm -rf "$work"
mkdir -p "$work/mca" "$work/native"
for dir in "$work/mca" "$work/native"; do
    echo "eula=true" > "$dir/eula.txt"
    printf 'online-mode=false\nserver-port=%s\nlevel-seed=shulker\npause-when-empty-seconds=-1\n' "$port" > "$dir/server.properties"
done
mapfile -t set_diamond < <(markers diamond_block 200)
mapfile -t test_diamond < <(tests diamond_block 200)
mapfile -t set_gold < <(markers gold_block 201)
mapfile -t test_gold < <(tests gold_block 201)

echo "1. .mca world"
printf 'storage:\n  format: mca\n' > "$work/mca/shulker.yml"
run_server "$work/mca" "${set_diamond[@]}" "${test_diamond[@]}"
expect "$work/mca" 4
echo "  files: $(find "$work/mca/world" -name '*.mca' | wc -l) .mca, $(find "$work/mca/world" -name '*.shulk' | wc -l) .shulk"

echo "2. migrate in place with cold recompression"
printf 'storage:\n  format: shulk\n  cold-recompression:\n    after-hours: 0\n    chunks-per-second: 400\n' > "$work/mca/shulker.yml"
SETTLE=40 run_server "$work/mca" "${test_diamond[@]}" "${set_gold[@]}"
expect "$work/mca" 4
echo "  files: $(find "$work/mca/world" -name '*.mca' | wc -l) .mca, $(find "$work/mca/world" -name '*.shulk' | wc -l) .shulk"
verify "$work/mca"

echo "3. restart on the mixed world"
run_server "$work/mca" "${test_diamond[@]}" "${test_gold[@]}"
expect "$work/mca" 8
verify "$work/mca"

echo "4. convert to .mca and back"
(cd "$work/mca" && java -jar "$jar" --convert-world=world --to=mca | tail -n1 | sed 's/^/  /')
(cd "$work/mca" && java -jar "$jar" --convert-world=world --to=shulk | tail -n1 | sed 's/^/  /')
verify "$work/mca"
run_server "$work/mca" "${test_diamond[@]}" "${test_gold[@]}"
expect "$work/mca" 8

echo "5. native .shulk world"
printf 'storage:\n  cold-recompression:\n    after-hours: 0\n    chunks-per-second: 400\n' > "$work/native/shulker.yml"
SETTLE=40 run_server "$work/native" "${set_diamond[@]}" "${test_diamond[@]}"
expect "$work/native" 4
verify "$work/native"
run_server "$work/native" "${test_diamond[@]}"
expect "$work/native" 4

echo
if [[ $failures -eq 0 ]]; then echo "PASSED"; else echo "FAILED ($failures)"; exit 1; fi
