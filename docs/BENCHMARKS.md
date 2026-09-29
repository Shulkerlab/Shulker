# Benchmarks

The charts in the README compare Shulker with Paper and three popular Paper forks. This page
describes how they were made, so they can be checked and repeated.

## Servers

All five were built from source for Minecraft 26.2 on 2026-09-29.

| Server | Source | Commit |
|---|---|---|
| Shulker | this repository | `19ca175` |
| Paper | [PaperMC/Paper](https://github.com/PaperMC/Paper) | `9240f586` (the base Shulker builds on) |
| Purpur | [PurpurMC/Purpur](https://github.com/PurpurMC/Purpur) `ver/26.2` | `3f5d9c0` |
| Leaf | [Winds-Studio/Leaf](https://github.com/Winds-Studio/Leaf) `ver/26.2` | `fcbabf54` |
| DivineMC | [BX-Team/DivineMC](https://github.com/BX-Team/DivineMC) `ver/26.2` | `6b97ace` |

Each server ran with its own default configuration, as it is after the first start. For Leaf
this means most of its optimisations are off, since Leaf ships them disabled; Shulker's survival
profile turns on the ones that keep vanilla behaviour, which is part of what is being measured.

## Setup

- AMD Ryzen AI 9 365, Linux 7.2, OpenJDK 27.
- Every server restricted to 8 hardware threads (`taskset -c 0-7`) at `nice 10`, with a 2 GiB
  heap and no heap pre-touching (`scripts/test-env.sh`).
- Shulker started through its launcher (`--memory=2G --gc=g1`); the others with
  [Aikar's G1 flags](https://docs.papermc.io/paper/aikars-flags) and
  `--add-modules=jdk.incubator.vector`, which Pufferfish-based forks use.
- Each scenario was run twice on every server, alternating servers; the charts show the mean.

## Scenarios

All scenarios come from [`scripts/benchmark.sh`](../scripts/benchmark.sh). The tick scenarios
report the average of Paper's `/mspt` one-minute window after a warm-up.

| Scenario | What runs |
|---|---|
| 1500 villagers, cows and sheep | a flat, fenced arena full of mobs with activation range off: brains, POI searches, wandering |
| 1000 husks pathfinding | husks chasing villagers on unreachable pillars, so every path search uses its whole budget |
| 40 players + 1500 mobs | the villager arena with 40 headless clients walking around: entity tracking and packets |
| 16 players in a normal world | a normal world at night with 16 clients spread far apart: natural spawning, random ticks, chunk loading |
| World generation | 4096 new chunks force-loaded far from spawn; time until all are fully generated, then the size of the saved region files |

The headless clients are [`scripts/mc-bots.py`](../scripts/mc-bots.py).

## Results

| | Shulker | Leaf | DivineMC | Purpur | Paper |
|---|---|---|---|---|---|
| 1500 villagers, cows and sheep (ms) | **9.1** | 9.9 | 16.4 | 30.8 | 30.5 |
| 1000 husks pathfinding (ms) | **7.7** | 15.8 | 8.4 | 18.2 | 18.4 |
| 40 players + 1500 mobs (ms) | **19.1** | 21.4 | 32.1 | 40.2 | 36.3 |
| 16 players in a normal world (ms) | **16.9** | 19.6 | 20.2 | 24.4 | 23.3 |
| World generation (chunks/s) | **110.1** | 82.4 | 98.2 | 96.2 | 95.0 |
| Region files for 4096 chunks (MiB) | **29.6** | 57.0 | 57.2 | 56.5 | 56.5 |

Means of two runs; the best value in each row is bold.

Raw rows: [benchmarks/2026-09-29.csv](benchmarks/2026-09-29.csv).

## Repeating it

```bash
scripts/fork-benchmark.sh results.csv shulker=build/libs/shulker-26.2.jar paper=paper.jar leaf=leaf.jar
RUN=2 scripts/fork-benchmark.sh results.csv shulker=build/libs/shulker-26.2.jar paper=paper.jar leaf=leaf.jar
tools/bench-charts.py results.csv docs/assets
```

A full run of five servers takes a little over an hour. Differences below about 5% are within
the run-to-run noise of these scenarios.
