#!/usr/bin/env bash
# Checks that a Shulker build generates the same terrain as a reference build, block by block.
#
#   scripts/worldgen-parity-test.sh <reference.jar> [candidate.jar] [work dir]
#
# Both servers generate the same seed: the spawn area, the nearest village and trial chambers
# (jigsaw structures), the End's main island and outer islands, and some of the Nether. Then
# tools/worldgen-compare.py compares every chunk that is fully generated in both worlds: biomes
# exactly, terrain shape to within a few ppm (see the tool for why exact blocks cannot match).
# Each pass prints how long generation took, a rough measure of world generation speed.
# CANDIDATE_SHULKER_YML adds settings to the candidate's shulker.yml.
# KEEP_REFERENCE=1 reuses the reference world of an earlier run in the same work dir.
# CANDIDATE_LEAF_YML sets config/leaf-global.yml for the candidate (e.g. to test Leaf's
# density function compiler). Running this accepts the Minecraft EULA for the throwaway servers.
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
source "$root/scripts/test-env.sh"
reference="$(realpath "$1")"
candidate="$(realpath "${2:-$(ls "$root"/build/libs/shulker-*.jar | head -n1)}")"
work="$(realpath -m "${3:-$root/run-worldgen-test}")"
[[ -n "${CANDIDATE_LEAF_YML:-}" ]] && CANDIDATE_LEAF_YML="$(realpath "$CANDIDATE_LEAF_YML")"
[[ -n "${CANDIDATE_SHULKER_YML:-}" ]] && CANDIDATE_SHULKER_YML="$(realpath "$CANDIDATE_SHULKER_YML")"
port="${WORLDGEN_TEST_PORT:-25590}"
seed="${WORLDGEN_TEST_SEED:-shulker}"

# generate <jar> <dir> <command...>: start, run the commands, let generation finish, save, stop.
generate() {
    local jar=$1 dir=$2; shift 2
    mkdir -p "$dir"
    (
        cd "$dir"
        echo "eula=true" > eula.txt
        printf 'online-mode=false\nserver-port=%s\nlevel-seed=%s\npause-when-empty-seconds=-1\n' "$port" "$seed" > server.properties
        printf 'storage:\n  format: mca\n' > shulker.yml # plain region files for the comparison
        [[ -f shulker-extra.yml ]] && cat shulker-extra.yml >> shulker.yml
        rm -f console run.log
        mkfifo console
        exec 3<>console
        launcher_props .
        limited java -jar "$jar" --memory="$SHULKER_TEST_HEAP" nogui < console > run.log 2>&1 &
        local pid=$!
        for _ in $(seq 1 300); do grep -q "Done (" run.log 2>/dev/null && break; sleep 1; done
        local started=$SECONDS
        # Freeze the game so only generation changes the world: no flowing water, random ticks
        # or mobs, whose results depend on how long each server ran. Chunks still generate.
        echo "tick freeze" >&3
        for cmd in "$@"; do echo "$cmd" >&3; sleep 1; done
        # Generation is done when saving no longer makes the region files grow.
        local last=-1 size
        for _ in $(seq 1 60); do
            echo "save-all" >&3
            sleep 15
            size=$(find world -name '*.mca' -printf '%s\n' 2>/dev/null | awk '{ s += $1 } END { print s + 0 }')
            [[ $size -eq $last ]] && break
            last=$size
        done
        echo "  generated $(find world -name '*.mca' | wc -l) region files in about $((SECONDS - started - 15)) s" >&2
        echo "save-all flush" >&3
        sleep 10
        echo stop >&3
        wait "$pid"
    )
}

area() { # area <dimension> <block x> <block z> <radius in chunks>: forceload command
    local r=$(($4 * 16))
    echo "execute in minecraft:$1 run forceload add $(($2 - r)) $(($3 - r)) $(($2 + r - 1)) $(($3 + r - 1))"
}

# world <jar> <dir> [leaf-global.yml]: generate the test areas in two passes, the second one around
# the structures the first pass located. Both servers follow exactly the same steps: features that
# cross chunk borders can depend on the order chunks are generated in, even in vanilla.
world() {
    local jar=$1 dir=$2 leaf=${3:-}
    mkdir -p "$dir/config"
    [[ -n "$leaf" ]] && cp "$leaf" "$dir/config/leaf-global.yml"
    [[ -n "${4:-}" ]] && cp "$4" "$dir/shulker-extra.yml"
    generate "$jar" "$dir" "locate structure #minecraft:village" "locate structure minecraft:trial_chambers" \
        "$(area overworld 0 0 4)" "$(area the_end 0 0 3)" "$(area the_end 1400 0 3)" "$(area the_nether 0 0 2)"
    local spots extra=()
    mapfile -t spots < <(grep -oE 'is at \[-?[0-9]+, ~, -?[0-9]+\]' "$dir/run.log" | grep -oE '\-?[0-9]+' | paste - -)
    echo "  structures at: ${spots[*]:-none found}"
    for spot in "${spots[@]}"; do
        read -r x z <<< "$spot"
        extra+=("$(area overworld "$x" "$z" 3)")
    done
    [[ ${#extra[@]} -gt 0 ]] && generate "$jar" "$dir" "${extra[@]}"
    return 0
}

if [[ "${KEEP_REFERENCE:-0}" == 1 && -d "$work/reference/world" ]]; then
    echo "1. reference: reusing $work/reference"
    rm -rf "$work/candidate"
else
    rm -rf "$work"
    mkdir -p "$work"
    echo "1. reference"
    world "$reference" "$work/reference"
fi
echo "2. candidate"
world "$candidate" "$work/candidate" "${CANDIDATE_LEAF_YML:-}" "${CANDIDATE_SHULKER_YML:-}"
echo "3. compare"
if python3 "$root/tools/worldgen-compare.py" "$work/reference/world" "$work/candidate/world"; then
    echo "PASSED"
else
    echo "FAILED"
    exit 1
fi
