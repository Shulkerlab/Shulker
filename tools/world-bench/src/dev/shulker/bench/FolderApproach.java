package dev.shulker.bench;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.WorldCreator;

/** One world per island, copied from a template folder of region files and loaded. */
final class FolderApproach extends Scenario {

    private Path templateFolder;
    private final List<World> worlds = new ArrayList<>();
    private long heapBefore;

    FolderApproach(final WorldBench bench) {
        super(bench, "folder");
    }

    /** The template world's dimension folder, also used by the Shulk World approach. */
    Path templateFolder() {
        return this.templateFolder;
    }

    private Path folderOf(final String name) {
        return this.templateFolder.resolveSibling(name);
    }

    /** Copies the template for a new world; drops its identity so the copy gets its own UUID. */
    private void copyTemplate(final String name) {
        final Path target = this.folderOf(name);
        WorldBench.delete(target);
        WorldBench.copy(this.templateFolder, target);
        try {
            Files.deleteIfExists(target.resolve("data/paper/metadata.dat"));
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private World load(final String name) {
        final World world = new WorldCreator(name).generator(WorldBench.VOID).createWorld();
        world.setKeepSpawnInMemory(false);
        return world;
    }

    @Override
    CompletableFuture<Void> run(final int islands, final int resets) {
        return this.prepareProbe()
            .thenCompose(v -> this.bench.sync(() -> {
                final World template = this.bench.createVoidWorld("wb_template");
                this.bench.paste(template, 0, 0);
                template.save();
                this.templateFolder = template.getWorldFolder().toPath();
                Bukkit.unloadWorld(template, true);
                return null;
            }))
            .thenCompose(v -> this.bench.settle(40))
            .thenCompose(v -> this.bench.async(() -> {
                this.heapBefore = WorldBench.usedHeapAfterGc();
                return null;
            }))
            .thenCompose(v -> this.measure("skyblock_create", islands, () -> WorldBench.sequence(islands, i -> this.bench.async(() -> {
                this.copyTemplate("wb_island_" + i);
                return null;
            }).thenCompose(x -> this.bench.sync(() -> this.worlds.add(this.load("wb_island_" + i)))))))
            .thenCompose(v -> this.bench.async(() -> {
                this.heapPerIsland(this.heapBefore, islands);
                return null;
            }))
            .thenCompose(v -> this.bench.sync(() -> {
                this.worlds.getFirst().save();
                return null;
            }))
            .thenCompose(v -> this.bench.settle(40))
            .thenCompose(v -> this.bench.async(() -> {
                final double kib = WorldBench.size(this.folderOf("wb_island_0")) / 1024.0;
                this.bench.record(this.name, "skyblock", "disk_kib_per_island", kib, "KiB");
                this.bench.record(this.name, "cross_server", "transfer_kib", kib, "KiB");
                return null;
            }))
            .thenCompose(v -> this.measure("skyblock_unload", islands, () -> WorldBench.sequence(islands, i -> this.bench.sync(() -> {
                Bukkit.unloadWorld(this.worlds.get(i), false);
                return null;
            }))))
            .thenRun(this.worlds::clear)
            // Bringing a moved island up on the target server: its folder arrived, load it.
            .thenCompose(v -> this.measure("cross_server_load", 10, () -> WorldBench.sequence(10, i -> this.bench.sync(() -> {
                final World world = this.load("wb_island_" + i);
                Bukkit.unloadWorld(world, false);
                return null;
            }))))
            .thenCompose(v -> this.bench.async(() -> {
                this.copyTemplate("wb_arena");
                return null;
            }))
            .thenCompose(v -> this.bench.sync(() -> this.worlds.add(this.load("wb_arena"))))
            .thenCompose(v -> this.bench.sync(() -> this.bench.occupy(this.worlds.getLast(), 0, 0)).thenCompose(f -> f))
            // An arena reset: drop the played world, copy the template back, load it, until the
            // players' view around it is loaded again.
            .thenCompose(v -> this.measure("minigame_reset", resets, () -> WorldBench.sequence(resets, r -> this.bench.sync(() -> {
                Bukkit.unloadWorld(this.worlds.removeFirst(), false);
                return null;
            }).thenCompose(x -> this.bench.async(() -> {
                this.copyTemplate("wb_arena");
                return null;
            })).thenCompose(x -> this.bench.sync(() -> this.worlds.add(this.load("wb_arena"))))
                .thenCompose(x -> this.bench.sync(() -> this.bench.occupy(this.worlds.getLast(), 0, 0)).thenCompose(f -> f)))))
            .thenCompose(v -> this.bench.sync(() -> {
                this.worlds.forEach(world -> Bukkit.unloadWorld(world, false));
                this.worlds.clear();
                return null;
            }))
            .thenCompose(v -> this.bench.async(() -> {
                for (int i = 0; i < islands; i++) {
                    WorldBench.delete(this.folderOf("wb_island_" + i));
                }
                WorldBench.delete(this.folderOf("wb_arena"));
                return null;
            }));
    }
}
