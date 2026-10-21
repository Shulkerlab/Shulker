package dev.shulker.bench;

import dev.shulker.api.world.ShulkWorldProperties;
import dev.shulker.api.world.ShulkWorldTemplate;
import dev.shulker.api.world.ShulkWorlds;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.bukkit.Bukkit;
import org.bukkit.World;

/** One Shulk World per island, created from a template in memory. Loaded only on Shulker. */
final class ShulkApproach extends Scenario {

    private final FolderApproach folder;
    private final List<World> worlds = new ArrayList<>();
    private ShulkWorldTemplate template;
    private long heapBefore;
    private byte[] moved;

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
            .thenCompose(v -> this.measure("cross_server_load", 10, () -> WorldBench.sequence(10, i ->
                this.bench.sync(() -> api().load("wbmoved", this.moved, null, ShulkWorldProperties.defaults())).thenCompose(f -> f)
                    .thenCompose(world -> this.bench.sync(() -> api().unload(world, false)).thenCompose(f -> f)))))
            .thenCompose(v -> this.bench.sync(() -> api().create("wbarena", this.template, null, ShulkWorldProperties.readOnlyWorld())).thenCompose(f -> f))
            .thenAccept(this.worlds::add)
            // An arena reset: drop the played world, create it again from the template.
            .thenCompose(v -> this.measure("minigame_reset", resets, () -> WorldBench.sequence(resets, r ->
                this.bench.sync(() -> api().unload(this.worlds.removeFirst(), false)).thenCompose(f -> f)
                    .thenCompose(x -> this.bench.sync(() -> api().create("wbarena", this.template, null, ShulkWorldProperties.readOnlyWorld())).thenCompose(f -> f))
                    .thenAccept(this.worlds::add))))
            .thenCompose(v -> this.bench.sync(() -> api().unload(this.worlds.removeFirst(), false)).thenCompose(f -> f))
            .thenCompose(v -> this.bench.async(() -> {
                for (int i = 0; i < islands; i++) {
                    api().fileLoader().delete("wbi" + i).join();
                }
                return null;
            }));
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
