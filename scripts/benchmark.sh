#!/usr/bin/env bash
# Measures tick time (MSPT) under a mob-heavy load, for comparing optimisations.
#
#   scripts/benchmark.sh [shulker.jar] [mob count] [work dir]
#
# Builds a flat, force-loaded, fenced arena, fills it with villagers, cows and sheep
# (lots of AI and pathfinding), lets it warm up and reads Paper's /mspt.
# Set SHULKER_YML to a file to use as shulker.yml (to toggle optimisations).
# Running this accepts the Minecraft EULA for the throwaway server it creates.
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
jar="$(realpath "${1:-$(ls "$root"/build/libs/shulker-*.jar | head -n1)}")"
mobs="${2:-1500}"
work="$(realpath -m "${3:-$root/run-bench}")"
port="${BENCH_PORT:-25598}"
warmup=45
measure=60
half=48 # arena is (2*half) blocks wide, centred on 0,0
floor=-60 # surface of a default flat world

rm -rf "$work"
mkdir -p "$work"
cd "$work"
echo "eula=true" > eula.txt
cat > server.properties <<EOF
level-type=minecraft\:flat
online-mode=false
server-port=$port
pause-when-empty-seconds=-1
spawn-protection=0
difficulty=peaceful
EOF
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
# BENCH_JFR=file.jfr records a CPU profile of the measurement window.
if [[ -n "${BENCH_JFR:-}" ]]; then
    echo "extra-jvm-args=-XX:StartFlightRecording:delay=$((warmup + 15))s,duration=$((measure - 10))s,dumponexit=true,settings=profile,filename=$(realpath -m "$BENCH_JFR")" \
        > shulker-launcher.properties
fi

mkfifo console
exec 3<>console
java -jar "$jar" --memory=4G --gc=g1 nogui < console > server.log 2>&1 &
server=$!
send() { echo "$1" >&3; }
cleanup() { kill "$server" 2>/dev/null || true; }
trap cleanup EXIT

for ((i = 0; i < 300; i++)); do
    grep -q "Done (" server.log 2>/dev/null && break
    kill -0 "$server" 2>/dev/null || { tail -n 30 server.log; exit 1; }
    sleep 1
done

send "gamerule spawn_mobs false"
send "gamerule advance_time false"
send "gamerule advance_weather false"
send "time set day"
send "forceload add -$((half + 1)) -$((half + 1)) $half $half"
for wall in \
    "-$((half + 1)) $floor -$((half + 1)) $half $((floor + 3)) -$((half + 1))" \
    "-$((half + 1)) $floor $half $half $((floor + 3)) $half" \
    "-$((half + 1)) $floor -$((half + 1)) -$((half + 1)) $((floor + 3)) $half" \
    "$half $floor -$((half + 1)) $half $((floor + 3)) $half"; do
    send "fill $wall minecraft:barrier"
done
sleep 3

# Spread mobs on a grid: half villagers (brains + pathfinding), rest cows and sheep.
side=$(awk -v n="$mobs" 'BEGIN { s = int(sqrt(n)); if (s * s < n) s++; print s }')
step=$(awk -v s="$side" -v h="$half" 'BEGIN { print (2 * h - 4) / s }')
for ((n = 0; n < mobs; n++)); do
    x=$(awk -v n="$n" -v s="$side" -v st="$step" -v h="$half" 'BEGIN { printf "%.1f", -h + 2 + (n % s) * st }')
    z=$(awk -v n="$n" -v s="$side" -v st="$step" -v h="$half" 'BEGIN { printf "%.1f", -h + 2 + int(n / s) * st }')
    case $((n % 4)) in
        0|1) type=villager ;;
        2) type=cow ;;
        *) type=sheep ;;
    esac
    send "summon minecraft:$type $x $floor $z"
done

sleep "$warmup"
send "mspt"
sleep "$measure"
send "mspt"
sleep 2
count_line=$(grep -c 'Summoned new' server.log || true)
send "stop"
wait "$server" || true
trap - EXIT

# /mspt prints avg/min/max for 5s, 10s and 1m windows; take the last report's 1m window.
report=$(grep -A1 'Server tick times' server.log | grep -vE 'Server tick times|^--' | tail -n1)
one_minute=$(echo "$report" | sed -E 's/\x1b\[[0-9;]*m//g' | grep -oE '[0-9.]+/[0-9.]+/[0-9.]+' | tail -n1)
echo "mobs=$count_line/$mobs  mspt(avg/min/max, 1m)=$one_minute"
