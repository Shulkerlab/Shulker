#!/usr/bin/env bash
# Runs every benchmark scenario on several servers and appends the results to a CSV file.
#
#   scripts/fork-benchmark.sh <results.csv> <name>=<jar> [<name>=<jar> ...]
#
# Example: scripts/fork-benchmark.sh results.csv shulker=build/libs/shulker-26.2.jar paper=paper.jar
# Scenarios run one after another, each on every server in turn, so drift over time is spread
# evenly. RUN labels the rows (default 1), SCENARIOS limits the scenarios. Turn the CSV into the
# README charts with tools/bench-charts.py. Running this accepts the Minecraft EULA for the
# throwaway servers it creates.
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
results="$(realpath -m "$1")"; shift
run="${RUN:-1}"
for scenario in ${SCENARIOS:-villagers pathfinding bots natural worldgen}; do
    for entry in "$@"; do
        name=${entry%%=*}
        jar="$(realpath "${entry#*=}")"
        case $scenario in
            villagers) out=$("$root/scripts/benchmark.sh" "$jar" 2>&1 | tail -n1) ;;
            pathfinding) out=$(BENCH_SCENARIO=pathfinding "$root/scripts/benchmark.sh" "$jar" 1000 2>&1 | tail -n1) ;;
            bots) out=$(BENCH_BOTS=40 "$root/scripts/benchmark.sh" "$jar" 2>&1 | tail -n1) ;;
            natural) out=$(BENCH_SCENARIO=natural "$root/scripts/benchmark.sh" "$jar" 2>&1 | tail -n1) ;;
            worldgen) out=$(BENCH_SCENARIO=worldgen "$root/scripts/benchmark.sh" "$jar" 2>&1 | tail -n1) ;;
            *) echo "unknown scenario $scenario" >&2; exit 1 ;;
        esac
        echo "$run,$scenario,$name,$out" | tee -a "$results"
    done
done
