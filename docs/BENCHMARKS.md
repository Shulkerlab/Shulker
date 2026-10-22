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

## Islands and arenas: world systems

The second chart in the README compares the ways a server can run many small worlds, such as
skyblock islands or minigame arenas. It was measured separately from the tick scenarios above,
on another machine, so the two sets of numbers should not be compared with each other.

### Setup

- AMD Ryzen 5 2600 (12 threads), 16 GiB RAM, Windows 11, Temurin 25.0.4, 2 GiB heap.
- Shulker at `4950b91`, started with `--no-relaunch`, and Paper at `9240f586` (the base Shulker
  builds on), started with Aikar's flags. Both from source, both with a flat default world and
  `pause-when-empty-seconds` off.
- [`tools/world-bench`](../tools/world-bench) is a plugin that runs every scenario on the
  server it is installed on, with one island template: a 25-block floating island of about
  4,000 blocks with a tree, a chest, water and lava.
- Four runs on 2026-10-22, alternating Shulker, Paper, Shulker, Paper; the table shows the mean
  per server. Runs on other days gave numbers up to 25% apart for both servers, so compare
  within the table only.

### Approaches

| Approach | How it works | Used by |
|---|---|---|
| Shared world + paste | one void world; each island is the template pasted block by block at its own grid cell | most skyblock plugins |
| World per island | the template world's folder is copied and loaded with `Bukkit.createWorld` | most minigame plugins |
| Shulk World | a Shulk World created from the template in memory; an arena is reset by putting the template's chunks back into the loaded world (`ShulkWorlds#reset`) ([SHULK-WORLD.md](SHULK-WORLD.md)) | Shulker only |
| Shulk World, pooled | the same, but the world is taken from a pool of empty worlds the server keeps loaded (`shulk-worlds.pool.size`) and reset to the template | Shulker only |

### Measurements

**Main-thread time** is the CPU time of the server thread beyond its idle rate, which is measured
for five ticks right before each operation. Work the server runs between ticks counts as well, and
so does nothing that other worlds' ticking adds. **Worst tick** is the longest gap between two
tick starts while the operation runs. **Arena reset** is the time until the arena is playable
again after 300 blocks were broken and placed: re-pasting every block of the arena's box, copying
the template folder back and loading it, or resetting the Shulk World in place; the Shulk World
run checks that none of the placed blocks survive. **Moving an island** is what has to be copied
to another server: the island's region files, its world folder or its serialized Shulk World.
**Loading a moved island** is the time until that copy is playable on the target server.

### Results

| | Shulk World, pooled | Shulk World | Shared world + paste (Paper) | World per island (Paper) |
|---|---|---|---|---|
| New island, main-thread time (ms) | **3.0** | 45.6 | 16.1 | 57.9 |
| New island, wall-clock time (ms) | **0.8** | 79 | 552 | 738 ¹ |
| New island, worst tick (ms) | **80** | 175 | 896 | 1,194 ¹ |
| Arena reset, wall-clock time (ms) | | 65 | **7.0** | 174 |
| Arena reset, main-thread time (ms) | | 10.7 | **7.6** | 43.6 |
| Disk space per island (KiB) | | **6.6** | 31.3 | 4,390 |
| Moving an island: data to copy (KiB) | | **6.6** | 21.3 | 4,390 |
| Loading a moved island (ms) | | **63** | — | 79 |

Means of two runs per server; the best value in each row is bold.

¹ One of the two Paper runs stalled for 1.3 s per island while creating world folders, and both
servers' world-folder runs once stalled for 20–28 s while unloading them: Minecraft writes a
world's saved data files when it unloads, even unsaved, and waits for them. The other runs: 191
and 127 ms. Shulk Worlds drop the saved data of worlds they discard (`c5079f9`), so they do not
wait on it.

The pool keeps worlds ready at a cost paid in the background: filling it took 41 ms of
main-thread time per world, at most one world every 10 ticks and only while the average tick
stays under 40 ms; 50 empty pool worlds cost 0.8 to 1.9 ms per tick while nothing happens.

What this shows:

- **Islands.** A world from the pool is ready in under a millisecond and 3 ms of main-thread
  time, and never stalls a tick waiting for chunks. Creating a Shulk World directly costs about
  as much main-thread time as loading a world folder (46 against 58 ms); pasting into a shared
  world is cheaper in CPU (16 ms) but its synchronous chunk loads stall the tick for about 0.9 s.
- **Disk and network.** A Shulk World stores only the chunks that hold something, compressed with
  the `.shulk` dictionaries, in one file: 6.6 KiB per island against 4.3 MiB for a world folder
  of `.mca` region files and 31 KiB for the island's share of a shared world.
- **Resets.** Re-pasting an arena in a loaded world is still the fastest reset. Resetting a Shulk
  World in place took 65 ms (32 and 99 in the two runs) against 174 ms for reloading a world
  folder; unloading and recreating the Shulk World took 111 ms.

Paste and world-per-island were also measured on Shulker; the raw rows hold them. Pasting costs
the same as on Paper. Loading a regular world folder took 54 ms of main-thread CPU on Shulker
against 58 ms on Paper in these runs. Shulker compiles a world's noise router (the C2ME density
function compiler, which makes generation faster), but worlds copied from one template share it,
and Shulk Worlds do not need one.

[Advanced Slime Paper](https://github.com/InfernalSuite/AdvancedSlimePaper), the other Paper
fork with single-file worlds, was not measured.

Raw rows: [benchmarks/2026-10-22-world-systems.csv](benchmarks/2026-10-22-world-systems.csv).

```bash
tools/world-bench/build.sh <a started server folder>       # builds WorldBench.jar
# put it in plugins/, start the server, then in the console:
worldbench run 50 20                                        # 50 islands, 20 arena resets
tools/world-bench/charts.py results-*.csv docs/assets/world-systems.svg
```
