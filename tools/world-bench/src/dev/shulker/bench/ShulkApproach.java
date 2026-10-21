package dev.shulker.bench;

import dev.shulker.api.world.ShulkWorldProperties;
import dev.shulker.api.world.ShulkWorldTemplate;
import dev.shulker.api.world.ShulkWorlds;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;

/** One Shulk World per island, created from a template in memory. Loaded only on Shulker. */
final class ShulkApproach extends Scenario {

    private final FolderApproach folder;
    private final List<World> worlds = new ArrayList<>();
    private ShulkWorldTemplate template;
    private long heapBefore;
    private byte[] moved;
    private double idleBefore;

    ShulkApproach(final WorldBench bench, final FolderApproach folder) {
        super(bench, "shulk");
        this.folder = folder;
    }

    static boolean available() {
        try {
            Class.forName("dev.shulker.api.world.ShulkWorlds");
            return ShulkWorlds.isAvailable();
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    private static ShulkWorlds api() {
        return ShulkWorlds.get();
    }

    @Override
    CompletableFuture<Void> run(final int islands, final int resets) {
        final Path templateFolder = this.folder.templateFolder();
        return this.prepareProbe()
            .thenCompose(v -> this.bench.sync(() -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(),
                "shulker world import " + templateFolder.toAbsolutePath() + " wbtpl")))
            .thenCompose(v -> this.waitForTemplate(0))
            .thenCompose(v -> api().readTemplate(api().fileLoader(), "wbtpl"))
            .thenAccept(t -> this.template = t)
            .thenCompose(v -> this.bench.async(() -> {
                this.heapBefore = WorldBench.usedHeapAfterGc();
                return null;
            }))
            .thenCompose(v -> this.measure("skyblock_create", islands, () -> WorldBench.sequence(islands, i ->
                this.bench.sync(() -> api().create("wbi" + i, this.template, api().fileLoader(), ShulkWorldProperties.defaults()))
                    .thenCompose(future -> future)
                    .thenAccept(this.worlds::add))))
            .thenCompose(v -> this.bench.async(() -> {
                this.heapPerIsland(this.heapBefore, islands);
                return null;
            }))
            .thenCompose(v -> this.bench.sync(() -> api().save(this.worlds.getFirst())).thenCompose(f -> f))
            .thenCompose(v -> api().fileLoader().read("wbi0"))
            .thenAccept(bytes -> this.bench.record(this.name, "skyblock", "disk_kib_per_island", bytes.length / 1024.0, "KiB"))
            // Moving an island: serialize it here, load the bytes there.
            .thenCompose(v -> this.bench.sync(() -> api().serialize(this.worlds.getFirst())).thenCompose(f -> f))
            .thenAccept(bytes -> {
                this.moved = bytes;
                this.bench.record(this.name, "cross_server", "transfer_kib", bytes.length / 1024.0, "KiB");
            })
            .thenCompose(v -> this.measure("skyblock_unload", islands, () -> WorldBench.sequence(islands, i ->
                this.bench.sync(() -> api().unload(this.worlds.get(i), false)).thenCompose(f -> f))))
            .thenRun(this.worlds::clear)
            // The pool: fill it in the background (measured as a whole), then create islands from it.
            .thenCompose(v -> this.bench.sync(() -> {
                this.probe.baselineStart();
                return null;
            }))
            .thenCompose(v -> this.bench.settle(40))
            .thenCompose(v -> this.bench.sync(() -> {
                this.probe.baselineEnd();
                this.idleBefore = this.probe.idleMillisPerTick();
                this.probe.start();
                api().setPoolSize(islands);
                return null;
            }))
            .thenCompose(v -> this.waitForPool(islands, 0))
            .thenRun(() -> {
                this.bench.record(this.name, "pool_fill", "main_cpu_ms_per_op", this.probe.mainCpuMillis() / islands, "ms");
                this.bench.record(this.name, "pool_fill", "wall_ms_per_op", this.probe.wallMillis() / islands, "ms");
            })
            // What keeping the pool loaded costs every tick, with nothing happening in it.
            .thenCompose(v -> this.bench.sync(() -> {
                this.probe.baselineStart();
                return null;
            }))
            .thenCompose(v -> this.bench.settle(100))
            .thenRun(() -> {
                this.probe.baselineEnd();
                this.bench.record(this.name, "pool_idle", "main_ms_per_tick", this.probe.idleMillisPerTick() - this.idleBefore, "ms");
            })
            .thenCompose(v -> this.measure("skyblock_create_pooled", islands, () -> WorldBench.sequence(islands, i ->
                this.bench.sync(() -> api().create("wbp" + i, this.template, api().fileLoader(), ShulkWorldProperties.defaults()))
                    .thenCompose(future -> future)
                    .thenAccept(this.worlds::add))))
            .thenCompose(v -> this.bench.sync(() -> {
                api().setPoolSize(0);
                return null;
            }))
            .thenCompose(v -> WorldBench.sequence(islands, i -> this.bench.sync(() -> api().unload(this.worlds.get(i), false)).thenCompose(f -> f)))
            .thenRun(this.worlds::clear)
            .thenCompose(v -> this.waitForPool(0, 0))
            .thenCompose(v -> this.measure("cross_server_load", 10, () -> WorldBench.sequence(10, i ->
                this.bench.sync(() -> api().load("wbmoved", this.moved, null, ShulkWorldProperties.defaults())).thenCompose(f -> f)
                    .thenCompose(world -> this.bench.sync(() -> api().unload(world, false)).thenCompose(f -> f)))))
            .thenCompose(v -> this.bench.sync(() -> api().create("wbarena", this.template, null, ShulkWorldProperties.readOnlyWorld())).thenCompose(f -> f))
            .thenAccept(this.worlds::add)
            // An arena reset the old way: drop the played world, create it again from the template.
            .thenCompose(v -> this.measure("minigame_recreate", resets, () -> WorldBench.sequence(resets, r ->
                this.bench.sync(() -> api().unload(this.worlds.removeFirst(), false)).thenCompose(f -> f)
                    .thenCompose(x -> this.bench.sync(() -> api().create("wbarena", this.template, null, ShulkWorldProperties.readOnlyWorld())).thenCompose(f -> f))
                    .thenAccept(this.worlds::add))))
            // An arena reset in place: play (break and place blocks), then put the template's chunks back.
            .thenCompose(v -> this.measure("minigame_reset", resets, () -> WorldBench.sequence(resets, r ->
                this.bench.sync(() -> {
                    this.play(this.worlds.getFirst());
                    return api().reset(this.worlds.getFirst(), this.template);
                }).thenCompose(f -> f))))
            .thenCompose(v -> this.bench.sync(() -> {
                final World arena = this.worlds.getFirst();
                int leftovers = 0; // blocks the last game placed that the reset should have removed
                for (int i = 0; i < 300; i++) {
                    if (arena.getBlockAt(-10 + i % 20, 65 + i / 100, -10 + (i / 20) % 20).getType() == Material.COBBLESTONE) {
                        leftovers++;
                    }
                }
                this.bench.getLogger().info("Arena reset check: " + leftovers + " placed blocks left after the last reset (0 expected)");
                return api().unload(this.worlds.removeFirst(), false);
            }).thenCompose(f -> f))
            .thenCompose(v -> this.bench.async(() -> {
                for (int i = 0; i < islands; i++) {
                    api().fileLoader().delete("wbi" + i).join();
                    api().fileLoader().delete("wbp" + i).join();
                }
                return null;
            }));
    }

    /** Waits until the pool holds {@code target} ready worlds (at most about five minutes). */
    private CompletableFuture<Void> waitForPool(final int target, final int attempt) {
        if (api().pooledWorlds() == target || attempt > 3000) {
            return CompletableFuture.completedFuture(null);
        }
        return this.bench.settle(2).thenCompose(v -> this.waitForPool(target, attempt + 1));
    }

    /** The same blocks the shared-world reset breaks and places. */
    private void play(final World world) {
        for (int i = 0; i < 300; i++) {
            world.getBlockAt(-10 + i % 20, 65 + i / 100, -10 + (i / 20) % 20).setType(i % 3 == 0 ? Material.AIR : Material.COBBLESTONE, false);
        }
    }

        private CompletableFuture<Void> waitForTemplate(final int attempt) {
        return api().fileLoader().exists("wbtpl").thenCompose(exists -> {
            if (exists || attempt > 200) {
                return CompletableFuture.completedFuture(null);
            }
            return this.bench.settle(5).thenCompose(v -> this.waitForTemplate(attempt + 1));
        });
    }
}
