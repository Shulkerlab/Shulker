# Shulker

A fast, self-tuning Minecraft server built on [Paper](https://papermc.io) for Minecraft 26.2.
Runs Bukkit, Spigot and Paper plugins, including ones that use server internals (NMS).

## Running

```bash
java -jar shulker-26.2.jar nogui
```

You don't need a list of JVM flags. The Shulker launcher inspects the machine (CPU count, RAM or
container limit, Java version, which garbage collectors this JDK has) and restarts itself as
the server JVM with tuned settings:

- **Heap sizing** from the container or panel limit (Docker, Pterodactyl), or from physical and
  currently free RAM on bare metal.
- **Garbage collector choice**: G1 for small heaps, Generational Shenandoah for mid-size heaps,
  Generational ZGC for large ones. It falls back to whatever this JDK build has available.
- **Compact object headers** (JEP 519) on Java 25/26; they are already the default on 27.
- **Tick thread isolation** (Linux, 6+ physical cores): the tick thread gets a physical core of its
  own, chosen by CPU topology (P-core on hybrid Intel, the larger-cache die on dual-CCD X3D Ryzen),
  and every other thread runs elsewhere. About 7% lower average MSPT on a 6-core test machine.
- **Leyden AOT cache**: the first run records class loading, and later starts reuse it for a
  much faster boot.
- **Plugin compatibility**: Shulker grants native access, final-field mutation (JEP 500) and
  `sun.misc.Unsafe` access, so older plugins keep working on newer JDKs.

Java 25 is the minimum. Java 26 and 27 are faster: they add G1 throughput work (JEP 522), AOT
caching with any GC (JEP 516) and compact headers by default.

### Profiles

```bash
java -jar shulker-26.2.jar --profile=skyblock nogui
```

| Profile    | Tuned for |
|------------|-----------|
| `survival` | large worlds, throughput, fixed pre-touched heap |
| `skyblock` | many small worlds, low pauses |
| `minigame` | short-lived arenas, low pauses |
| `lobby`    | hubs; heap capped at 6 GiB and shrinks when idle |
| `vanilla`  | technical play: every exploit and dupe allowed (TNT/rail/carpet dupers, bedrock breaking, sand dupe...), vanilla farm rates, no behaviour-changing optimisations |

Settings live in `shulker-launcher.properties`, which is created on the first start. Command
line options `--profile=`, `--memory=` and `--gc=` override the file, and so do the
`SHULKER_PROFILE`, `SHULKER_MEMORY` and `SHULKER_GC` environment variables.

- `--dry-run` prints the planned command without starting the server.
- `--no-relaunch` (or `managed=false`) uses exactly the flags you pass to `java`.

## World storage

New worlds are stored as `.shulk` region files: per-chunk zstd with dictionaries trained on
Minecraft data. They are about half the size of `.mca` and faster to read and write. Existing
`.mca` worlds are left alone unless `storage.format: shulk` is set in `shulker.yml`; the world
then migrates chunk by chunk as chunks are saved. See [docs/SHULK-FORMAT.md](docs/SHULK-FORMAT.md).

```bash
java -jar shulker-26.2.jar --convert-world=world --to=shulk   # or --to=mca, server stopped
java -jar shulker-26.2.jar --verify-world=world               # read back every chunk
```

Tools that read region files directly (BlueMap, MCA Selector) need `.mca`: convert back first.

## Building

```bash
./gradlew applyAllPatches
./gradlew createShulkerJar
```

The jar is written to `build/libs/shulker-<mc version>.jar`. The build needs a Git checkout,
because Paper embeds the commit hash in the server version.

| Directory            | Contents |
|----------------------|----------|
| `shulker-launcher/`  | the launcher (plain Java 25, no dependencies) |
| `shulker-server/`    | server patches on top of Paper (`paper-patches/`, `minecraft-patches/`) |
| `shulker-api/`       | API patches on top of Paper API |

## License

Shulker is licensed under the [GNU GPL v3](LICENSE). Patches ported from other projects keep
their original authors and license notes in the patch header. Paper, and the upstream projects
Shulker builds on, are licensed under their own terms.
