#!/usr/bin/env bash
# Measures tick time (MSPT) under a mob-heavy load, for comparing optimisations.
#
#   scripts/benchmark.sh [shulker.jar] [mob count] [work dir]
#
# Builds a flat, force-loaded, fenced arena, fills it with mobs, lets it warm up and
# reads Paper's /mspt. BENCH_SCENARIO picks the load:
#   villagers    (default) villagers, cows and sheep: brains, POI searches, wandering
#   pathfinding  husks hunting villagers standing on pillars: unreachable targets make
#                every mob search the whole path budget
#   natural      a normal world at night: BENCH_BOTS players (default 16) spread over 16000
#                blocks, natural mob spawning, random ticks; no arena, the mob count is ignored
#   worldgen     generation speed: time to generate 4096 new chunks of a normal world
# Set SHULKER_YML to a file to use as shulker.yml (to toggle optimisations), and LEAF_YML to a
# file to use as config/leaf-global.yml.
# BENCH_BOTS=n adds n headless players walking around the arena (entity tracking, chunk sending).
# BENCH_WORLDS=3 splits the mobs over the overworld, the nether roof and the end island, for
# measuring parallel world ticking.
# Other Paper forks' jars work too, for comparisons: they start with Aikar's G1 flags and the same
# heap (without pre-touching, like Shulker here) and their own default configuration.
# Running this accepts the Minecraft EULA for the throwaway server it creates.
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
source "$root/scripts/test-env.sh"
jar="$(realpath "${1:-$(ls "$root"/build/libs/shulker-*.jar | head -n1)}")"
mobs="${2:-1500}"
work="$(realpath -m "${3:-$root/run-bench}")"
# Config overrides may be given relative to the current directory.
[[ -n "${SHULKER_YML:-}" ]] && SHULKER_YML="$(realpath "$SHULKER_YML")"
[[ -n "${LEAF_YML:-}" ]] && LEAF_YML="$(realpath "$LEAF_YML")"
port="${BENCH_PORT:-25598}"
warmup=45
scenario="${BENCH_SCENARIO:-villagers}"
[[ $scenario == natural ]] && { warmup=120; BENCH_BOTS="${BENCH_BOTS:-16}"; }
measure=60
half=48 # arena is (2*half) blocks wide, centred on 0,0
floor=-60 # surface of a default flat world

rm -rf "$work"
mkdir -p "$work"
cd "$work"
echo "eula=true" > eula.txt
cat > server.properties <<EOF
$([[ $scenario == natural || $scenario == worldgen ]] && echo 'level-seed=shulker' || echo 'level-type=minecraft\:flat')
online-mode=false
server-port=$port
pause-when-empty-seconds=-1
allow-flight=true
max-players=1000
$([[ $scenario == natural ]] && printf 'view-distance=6\nsimulation-distance=6')
spawn-protection=0
difficulty=$(case $scenario in pathfinding) echo easy ;; natural) echo normal ;; *) echo peaceful ;; esac)
EOF
# Bots all connect from 127.0.0.1.
printf 'settings:\n  connection-throttle: -1\n' > bukkit.yml
# Activation range 0 = always active, so mobs tick fully without a player nearby.
cat > spigot.yml <<EOF
world-settings:
  default:
    entity-activation-range:
      animals: 0
      monsters: 0
      raiders: 0
      misc: 0
      water: 0
      villagers: 0
      flying-monsters: 0
EOF
[[ -n "${SHULKER_YML:-}" ]] && cp "$SHULKER_YML" shulker.yml
[[ -n "${LEAF_YML:-}" ]] && mkdir -p config && cp "$LEAF_YML" config/leaf-global.yml
# BENCH_JFR=file.jfr records a CPU profile of the measurement window; BENCH_JVM_ARGS adds JVM flags.
if [[ -n "${BENCH_JFR:-}" ]]; then
    launcher_props . ${BENCH_JVM_ARGS:-} "-XX:FlightRecorderOptions:stackdepth=256" "-XX:StartFlightRecording:delay=$((warmup + 15))s,duration=$((measure - 10))s,dumponexit=true,settings=profile,filename=$(realpath -m "$BENCH_JFR")"
else
    launcher_props . ${BENCH_JVM_ARGS:-}
fi

mkfifo console
exec 3<>console
if unzip -p "$jar" META-INF/MANIFEST.MF 2>/dev/null | grep -q 'dev.shulker.launcher'; then
    limited java -jar "$jar" --memory="$SHULKER_TEST_HEAP" --gc=g1 nogui < console > server.log 2>&1 &
else
    # Aikar's flags (https://docs.papermc.io/paper/aikars-flags) minus AlwaysPreTouch.
    jfr_args=()
    [[ -n "${BENCH_JFR:-}" ]] && jfr_args=("-XX:FlightRecorderOptions:stackdepth=256" "-XX:StartFlightRecording:delay=$((warmup + 15))s,duration=$((measure - 10))s,dumponexit=true,settings=profile,filename=$(realpath -m "$BENCH_JFR")")
    limited java -Xms"$SHULKER_TEST_HEAP" -Xmx"$SHULKER_TEST_HEAP" -XX:+UseG1GC -XX:+ParallelRefProcEnabled -XX:MaxGCPauseMillis=200 \
        -XX:+UnlockExperimentalVMOptions -XX:+DisableExplicitGC -XX:G1NewSizePercent=30 -XX:G1MaxNewSizePercent=40 \
        -XX:G1HeapRegionSize=8M -XX:G1ReservePercent=20 -XX:G1HeapWastePercent=5 -XX:G1MixedGCCountTarget=4 \
        -XX:InitiatingHeapOccupancyPercent=15 -XX:G1MixedGCLiveThresholdPercent=90 -XX:G1RSetUpdatingPauseTimePercent=5 \
        -XX:SurvivorRatio=32 -XX:+PerfDisableSharedMem -XX:MaxTenuringThreshold=1 --add-modules=jdk.incubator.vector \
        ${BENCH_JVM_ARGS:-} "${jfr_args[@]}" -jar "$jar" --nogui < console > server.log 2>&1 &
fi
server=$!
send() { echo "$1" >&3; }
cleanup() { kill "$server" 2>/dev/null || true; }
trap cleanup EXIT

for ((i = 0; i < 300; i++)); do
    grep -q "Done (" server.log 2>/dev/null && break
    kill -0 "$server" 2>/dev/null || { tail -n 30 server.log; exit 1; }
    sleep 1
done

if [[ $scenario == worldgen ]]; then
    # Forceloaded chunks count as entity ticking once they are fully generated.
    ticking() {
        send "paper chunkinfo" # the last line totals all worlds
        sleep 0.5
        grep -oE 'Entity Ticking: [0-9]+' server.log | tail -n1 | grep -oE '[0-9]+$' || echo 0
    }
    base=$(ticking)
    started=$(date +%s.%N)
    for tx in 0 1 2 3; do
        for tz in 0 1 2 3; do # 16x16-chunk tiles (forceload takes at most 256 chunks at once)
            x0=$((4096 + tx * 256)); z0=$((4096 + tz * 256))
            send "forceload add $x0 $z0 $((x0 + 255)) $((z0 + 255))"
        done
    done
    generated=0
    for ((i = 0; i < 1200; i++)); do
        generated=$(( $(ticking) - base ))
        [[ $generated -ge 4096 ]] && break
    done
    elapsed=$(awk -v s="$started" -v e="$(date +%s.%N)" 'BEGIN { printf "%.1f", e - s }')
    send "stop"
    wait "$server" || true
    trap - EXIT
    # The server saves on stop: the overworld's region files now hold the generated chunks.
    region_mib=$(find world -path '*overworld/region/*' -type f -printf '%s\n' 2>/dev/null | awk '{ s += $1 } END { printf "%.1f", s / 1048576 }')
    echo "worldgen: $generated chunks in $elapsed s ($(awk -v n="$generated" -v t="$elapsed" 'BEGIN { printf "%.1f", n / t }') chunks/s), overworld regions $region_mib MiB"
    exit 0
fi

send "gamerule advance_time false"
send "gamerule advance_weather false"
if [[ $scenario == natural ]]; then
    send "time set night" # natural spawning runs; the arena and the summoned mobs are skipped
else
send "gamerule spawn_mobs false"
send "time set day"
send "forceload add -$((half + 1)) -$((half + 1)) $half $half"
worlds="${BENCH_WORLDS:-1}"
if [[ $worlds -ge 3 ]]; then
    send "execute in minecraft:the_nether run forceload add -$((half + 1)) -$((half + 1)) $half $half"
    send "execute in minecraft:the_end run forceload add -$((half + 1)) -$((half + 1)) $half $half"
fi
for wall in \
    "-$((half + 1)) $floor -$((half + 1)) $half $((floor + 3)) -$((half + 1))" \
    "-$((half + 1)) $floor $half $half $((floor + 3)) $half" \
    "-$((half + 1)) $floor -$((half + 1)) -$((half + 1)) $((floor + 3)) $half" \
    "$half $floor -$((half + 1)) $half $((floor + 3)) $half"; do
    send "fill $wall minecraft:barrier"
done
sleep 3

if [[ $scenario == pathfinding ]]; then
    # Nine villagers on 6-block stone pillars (not Invulnerable: invulnerable mobs are never targeted) spread over the arena: visible, never reachable.
    for cx in -32 0 32; do
        for cz in -32 0 32; do
            send "fill $cx $floor $cz $cx $((floor + 5)) $cz minecraft:stone"
            send "summon minecraft:villager $cx $((floor + 6)) $cz {NoAI:1b,PersistenceRequired:1b}"
        done
    done
    sleep 2
fi

# Spread mobs on a grid: half villagers (brains + pathfinding), rest cows and sheep.
side=$(awk -v n="$mobs" 'BEGIN { s = int(sqrt(n)); if (s * s < n) s++; print s }')
step=$(awk -v s="$side" -v h="$half" 'BEGIN { print (2 * h - 4) / s }')
for ((n = 0; n < mobs; n++)); do
    x=$(awk -v n="$n" -v s="$side" -v st="$step" -v h="$half" 'BEGIN { printf "%.1f", -h + 2 + (n % s) * st }')
    z=$(awk -v n="$n" -v s="$side" -v st="$step" -v h="$half" 'BEGIN { printf "%.1f", -h + 2 + int(n / s) * st }')
    if [[ $scenario == pathfinding ]]; then
        type=husk # husks do not burn in daylight
    else
        case $((n % 4)) in
            0|1) type=villager ;;
            2) type=cow ;;
            *) type=sheep ;;
        esac
    fi
    case $((worlds >= 3 ? n % 3 : 0)) in
        0) send "summon minecraft:$type $x $floor $z" ;;
        1) send "execute in minecraft:the_nether run summon minecraft:$type $x 129 $z" ;; # flat bedrock roof
        *) send "execute in minecraft:the_end run summon minecraft:$type $x 70 $z" ;;      # main island
    esac
done
fi

bots_pid=
if [[ "${BENCH_BOTS:-0}" -gt 0 ]]; then
    limited python3 "$root/scripts/mc-bots.py" "$BENCH_BOTS" $((warmup + measure + 5)) 127.0.0.1 "$port" $((half - 8)) &
    bots_pid=$!
fi
if [[ $scenario == natural ]]; then
    # Bots stand where they are put (they only walk inside the arena radius around 0,0).
    sleep $((BENCH_BOTS / 5 + 10))
    send "spreadplayers 0 0 1000 8000 false @a"
fi

sleep "$warmup"
send "mspt"
sleep "$measure"
send "mspt"
sleep 2
count_line=$(grep -c 'Summoned new' server.log || true)
[[ -n "$bots_pid" ]] && wait "$bots_pid"
send "stop"
wait "$server" || true
trap - EXIT

# /mspt prints avg/min/max for 5s, 10s and 1m windows; take the last report's 1m window.
report=$(grep -A1 'Server tick times' server.log | grep -vE 'Server tick times|^--' | tail -n1)
one_minute=$(echo "$report" | sed -E 's/\x1b\[[0-9;]*m//g' | grep -oE '[0-9.]+/[0-9.]+/[0-9.]+' | tail -n1)
if [[ $scenario == natural ]]; then echo "natural, ${BENCH_BOTS} bots  mspt(avg/min/max, 1m)=$one_minute"; else echo "mobs=$count_line/$mobs  mspt(avg/min/max, 1m)=$one_minute"; fi
