package dev.shulker.bench;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;

/** One shared void world; each island is the template pasted at its own region. */
final class PasteApproach extends Scenario {

    private World world;
    private long heapBefore;

    PasteApproach(final WorldBench bench) {
        super(bench, "paste");
    }

    private static int x(final int island) {
        return WorldBench.islandX(island) + 256; // the middle of region (island, 0)
    }

    @Override
    CompletableFuture<Void> run(final int islands, final int resets) {
        return this.prepareProbe()
            .thenCompose(v -> this.bench.sync(() -> {
                this.world = this.bench.createVoidWorld("wb_shared");
                return null;
            }))
            .thenCompose(v -> this.bench.async(() -> {
                this.heapBefore = WorldBench.usedHeapAfterGc();
                return null;
            }))
            .thenCompose(v -> this.measure("skyblock_create", islands, () -> WorldBench.sequence(islands, i -> this.bench.sync(() -> {
                this.bench.paste(this.world, x(i), 256);
                return null;
            }))))
            .thenCompose(v -> this.bench.async(() -> {
                this.heapPerIsland(this.heapBefore, islands);
                return null;
            }))
            .thenCompose(v -> this.bench.sync(() -> {
                this.world.save();
                return null;
            }))
            .thenCompose(v -> this.bench.settle(60))
            .thenCompose(v -> this.bench.async(() -> {
                final Path folder = this.world.getWorldFolder().toPath();
                long total = 0;
                for (String kind : new String[] {"region", "entities", "poi"}) {
                    total += WorldBench.size(folder.resolve(kind));
                }
                this.bench.record(this.name, "skyblock", "disk_kib_per_island", total / 1024.0 / islands, "KiB");
                // Moving one island to another server means moving the region files it lives in.
                long transfer = 0;
                for (String kind : new String[] {"region", "entities", "poi"}) {
                    try (var files = Files.list(folder.resolve(kind))) {
                        transfer += files.filter(p -> p.getFileName().toString().startsWith("r.1.0.")).mapToLong(p -> p.toFile().length()).sum();
                    } catch (java.io.IOException e) {
                        // no such folder
                    }
                }
                this.bench.record(this.name, "cross_server", "transfer_kib", transfer / 1024.0, "KiB");
                return null;
            }))
            .thenCompose(v -> this.minigameReset(resets))
            .thenCompose(v -> this.bench.sync(() -> {
                Bukkit.unloadWorld(this.world, false);
                return null;
            }));
    }

    /** An arena reset in a shared world: every block of the arena's box set back to the template. */
    private CompletableFuture<Void> minigameReset(final int resets) {
        final Map<Long, BlockData> blocks = new HashMap<>();
        int minY = Integer.MAX_VALUE;
        int maxY = Integer.MIN_VALUE;
        for (WorldBench.Placed placed : this.bench.template()) {
            blocks.put(key(placed.x(), placed.y(), placed.z()), placed.data());
            minY = Math.min(minY, placed.y());
            maxY = Math.max(maxY, placed.y());
        }
        final BlockData air = Material.AIR.createBlockData();
        final int bottom = minY;
        final int top = maxY + 8; // room players built in
        final int ox = x(0);
        return this.bench.sync(() -> this.bench.occupy(this.world, ox, 256)).thenCompose(f -> f)
            .thenCompose(v -> this.measure("minigame_reset", resets, () -> WorldBench.sequence(resets, r -> this.bench.sync(() -> {
            // a played arena: some blocks broken and placed
            for (int i = 0; i < 300; i++) {
                this.world.getBlockAt(ox - 10 + i % 20, 65 + i / 100, 256 - 10 + (i / 20) % 20).setType(i % 3 == 0 ? Material.AIR : Material.COBBLESTONE, false);
            }
            for (int x = -12; x <= 12; x++) {
                for (int z = -12; z <= 12; z++) {
                    for (int y = bottom; y <= top; y++) {
                        final BlockData data = blocks.getOrDefault(key(x, y, z), air);
                        this.world.getBlockAt(ox + x, y, 256 + z).setBlockData(data, false);
                    }
                }
            }
            return null;
        }).thenCompose(x -> this.bench.sync(() -> this.bench.occupy(this.world, ox, 256)).thenCompose(f -> f)))));
    }

    private static long key(final int x, final int y, final int z) {
        return ((long) (x & 0xFFFF) << 32) | ((long) (y & 0xFFFF) << 16) | (z & 0xFFFF);
    }
}
