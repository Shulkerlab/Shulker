package dev.shulker.bench;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.block.data.BlockData;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Compares ways of running many small worlds (skyblock islands, minigame arenas) on the server
 * it is installed on, with one island template:
 *
 * <ul>
 *   <li><b>paste</b>: one shared void world, the template pasted block by block at a grid
 *       position (how BentoBox, SuperiorSkyblock and most island plugins work)</li>
 *   <li><b>folder</b>: one world per island, copied from a template world folder of region files
 *       and loaded with {@link Bukkit#createWorld} (how most minigame plugins reset arenas)</li>
 *   <li><b>shulk</b>: one Shulk World per island, created from a template in memory (Shulker
 *       only; skipped elsewhere)</li>
 * </ul>
 *
 * <p>{@code /worldbench run <islands> <resets>} runs every scenario once and appends the rows to
 * {@code plugins/WorldBench/results.csv}. Main-thread time is measured from the gaps between
 * server ticks, so work the server runs between ticks is counted too.
 */
public final class WorldBench extends JavaPlugin implements Listener {

    static final String CSV_HEADER = "server,approach,scenario,metric,value,unit";
    private static final int SPACING = 512; // one region file per island in the shared world
    static final ChunkGenerator VOID = new ChunkGenerator() {
        @Override public boolean shouldGenerateNoise() { return false; }
        @Override public boolean shouldGenerateSurface() { return false; }
        @Override public boolean shouldGenerateCaves() { return false; }
        @Override public boolean shouldGenerateDecorations() { return false; }
        @Override public boolean shouldGenerateMobs() { return false; }
        @Override public boolean shouldGenerateStructures() { return false; }
    };

    final TickGaps ticks = new TickGaps();
    private final List<String> rows = new ArrayList<>();
    private List<Placed> template = List.of();
    private boolean running;

    record Placed(int x, int y, int z, BlockData data) {
    }

    @Override
    public void onEnable() {
        this.getServer().getPluginManager().registerEvents(this.ticks, this);
        this.template = buildTemplate();
        this.getLogger().info("Island template: " + this.template.size() + " blocks");
    }

    @Override
    public boolean onCommand(final CommandSender sender, final Command command, final String label, final String[] args) {
        if (args.length < 1 || !args[0].equals("run")) {
            sender.sendMessage("/worldbench run <islands> <resets>");
            return true;
        }
        if (this.running) {
            sender.sendMessage("Already running");
            return true;
        }
        final int islands = args.length > 1 ? Integer.parseInt(args[1]) : 50;
        final int resets = args.length > 2 ? Integer.parseInt(args[2]) : 20;
        this.running = true;
        this.rows.clear();
        this.getLogger().info("Running: " + islands + " islands, " + resets + " resets on " + serverName());
        this.runAll(islands, resets).whenComplete((done, error) -> {
            this.running = false;
            if (error != null) {
                this.getLogger().log(java.util.logging.Level.SEVERE, "Benchmark failed", error);
            } else {
                this.getLogger().info("WORLDBENCH DONE");
            }
        });
        return true;
    }

    private CompletableFuture<Void> runAll(final int islands, final int resets) {
        final PasteApproach paste = new PasteApproach(this);
        final FolderApproach folder = new FolderApproach(this);
        CompletableFuture<Void> chain = paste.run(islands, resets).thenCompose(v -> folder.run(islands, resets));
        if (ShulkApproach.available()) {
            chain = chain.thenCompose(v -> new ShulkApproach(this, folder).run(islands, resets));
        }
        return chain.thenRun(this::writeRows);
    }

    // ---- shared helpers --------------------------------------------------------------------

    List<Placed> template() {
        return this.template;
    }

    /** A small floating island: grass over dirt over a stone cone, a tree, a chest, some water. */
    static List<Placed> buildTemplate() {
        final List<Placed> blocks = new ArrayList<>();
        final Random random = new Random(42);
        final int radius = 12;
        for (int x = -radius; x <= radius; x++) {
            for (int z = -radius; z <= radius; z++) {
                final double distance = Math.sqrt(x * x + z * z);
                if (distance > radius) {
                    continue;
                }
                final int depth = (int) Math.round((radius - distance) * 1.2) + 1;
                for (int y = 0; y < depth; y++) {
                    final Material material = y == 0 ? Material.GRASS_BLOCK : y < 4 ? Material.DIRT
                        : random.nextInt(12) == 0 ? Material.COAL_ORE : random.nextInt(25) == 0 ? Material.IRON_ORE : Material.STONE;
                    blocks.add(new Placed(x, 64 - y, z, material.createBlockData()));
                }
                if (y0Decoration(random, distance) && !(Math.abs(x - 4) <= 2 && Math.abs(z - 4) <= 2)) {
                    blocks.add(new Placed(x, 65, z, Material.SHORT_GRASS.createBlockData()));
                }
            }
        }
        for (int y = 65; y <= 69; y++) {
            blocks.add(new Placed(4, y, 4, Material.OAK_LOG.createBlockData()));
        }
        for (int x = 2; x <= 6; x++) {
            for (int z = 2; z <= 6; z++) {
                for (int y = 68; y <= 71; y++) {
                    final boolean trunk = x == 4 && z == 4 && y <= 69;
                    final boolean corner = (x == 2 || x == 6) && (z == 2 || z == 6);
                    if (!trunk && !(corner && y > 69) && !(y == 71 && (Math.abs(x - 4) > 1 || Math.abs(z - 4) > 1))) {
                        blocks.add(new Placed(x, y, z, Material.OAK_LEAVES.createBlockData("[persistent=true]")));
                    }
                }
            }
        }
        blocks.add(new Placed(-3, 65, -3, Material.CHEST.createBlockData()));
        blocks.add(new Placed(-6, 64, 2, Material.WATER.createBlockData()));
        blocks.add(new Placed(-6, 64, 3, Material.LAVA.createBlockData()));
        return blocks;
    }

    private static boolean y0Decoration(final Random random, final double distance) {
        return distance < 10 && random.nextInt(6) == 0;
    }

    /** Pastes the template at a position, loading the chunks it needs. Main thread. */
    void paste(final World world, final int originX, final int originZ) {
        for (int cx = (originX - 12) >> 4; cx <= (originX + 12) >> 4; cx++) {
            for (int cz = (originZ - 12) >> 4; cz <= (originZ + 12) >> 4; cz++) {
                world.getChunkAt(cx, cz);
            }
        }
        for (Placed block : this.template) {
            world.getBlockAt(originX + block.x(), block.y(), originZ + block.z()).setBlockData(block.data(), false);
        }
    }

    World createVoidWorld(final String name) {
        final World world = new WorldCreator(name).generator(VOID).createWorld();
        world.setAutoSave(false);
        world.setKeepSpawnInMemory(false);
        return world;
    }

    void record(final String approach, final String scenario, final String metric, final double value, final String unit) {
        final String row = String.join(",", serverName(), approach, scenario, metric, String.format(Locale.ROOT, "%.3f", value), unit);
        this.rows.add(row);
        this.getLogger().info("RESULT " + row);
    }

    private void writeRows() {
        try {
            final Path csv = this.getDataFolder().toPath().resolve("results.csv");
            Files.createDirectories(csv.getParent());
            if (!Files.exists(csv)) {
                Files.writeString(csv, CSV_HEADER + "\n");
            }
            Files.write(csv, this.rows, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static String serverName() {
        return Bukkit.getName().toLowerCase(Locale.ROOT);
    }

    /** Runs on the main thread and completes with its result. */
    <T> CompletableFuture<T> sync(final Supplier<T> task) {
        final CompletableFuture<T> future = new CompletableFuture<>();
        Bukkit.getScheduler().runTask(this, () -> {
            try {
                future.complete(task.get());
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });
        return future;
    }

    <T> CompletableFuture<T> async(final Supplier<T> task) {
        final CompletableFuture<T> future = new CompletableFuture<>();
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            try {
                future.complete(task.get());
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });
        return future;
    }

    /** Waits a few ticks so one step's leftovers do not land in the next measurement. */
    CompletableFuture<Void> settle(final int ticks) {
        final CompletableFuture<Void> future = new CompletableFuture<>();
        Bukkit.getScheduler().runTaskLater(this, () -> future.complete(null), ticks);
        return future;
    }

    /** Runs steps 0..count-1 one after the other; step i starts once step i-1 completed. */
    static CompletableFuture<Void> sequence(final int count, final java.util.function.IntFunction<CompletableFuture<?>> step) {
        final Scenario scenario = Scenario.measuring; // measured steps get an idle baseline each
        CompletableFuture<?> chain = CompletableFuture.completedFuture(null);
        for (int i = 0; i < count; i++) {
            final int index = i;
            final Supplier<CompletableFuture<?>> run = () -> step.apply(index);
            chain = chain.thenCompose(v -> (scenario == null ? run.get() : scenario.step(run)).thenApply(x -> null));
        }
        return chain.thenApply(v -> null);
    }

    static int islandX(final int index) {
        return index * SPACING;
    }

    static long size(final Path path) {
        if (!Files.exists(path)) {
            return 0;
        }
        try (Stream<Path> files = Files.walk(path)) {
            return files.filter(Files::isRegularFile).mapToLong(file -> {
                try {
                    return Files.size(file);
                } catch (IOException e) {
                    return 0;
                }
            }).sum();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static void delete(final Path path) {
        if (!Files.exists(path)) {
            return;
        }
        try (Stream<Path> files = Files.walk(path)) {
            for (Path file : files.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(file);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static void copy(final Path from, final Path to) {
        try (Stream<Path> files = Files.walk(from)) {
            for (Path file : files.toList()) {
                final Path target = to.resolve(from.relativize(file).toString());
                if (Files.isDirectory(file)) {
                    Files.createDirectories(target);
                } else if (!file.getFileName().toString().equals("session.lock")) {
                    Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static long usedHeapAfterGc() {
        for (int i = 0; i < 3; i++) {
            System.gc();
        }
        return ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
    }

    Path dimensionFolder(final World world) {
        return world.getWorldFolder().toPath();
    }

    /** Main-thread busy time measured from tick start gaps (see {@link TickGaps}). */
    static final class TickGaps implements Listener {
        private long last;
        private long window;
        private long busy;
        private long worst;
        private int ticks;
        private int late;

        @EventHandler
        public void onTick(final com.destroystokyo.paper.event.server.ServerTickStartEvent event) {
            final long now = System.nanoTime();
            if (this.last != 0) {
                final long gap = now - this.last;
                // A tick that starts more than 50 ms after the previous one was held up by work.
                this.busy += Math.max(0, gap - 50_000_000L);
                this.worst = Math.max(this.worst, gap);
                if (gap > 55_000_000L) {
                    this.late++;
                }
                this.ticks++;
            }
            this.last = now;
        }

        int ticks() {
            return this.ticks;
        }

        void start() {
            this.last = 0; // a gap that began before the measurement (a GC, the previous step) is not its own
            this.window = System.nanoTime();
            this.busy = 0;
            this.worst = 0;
            this.ticks = 0;
            this.late = 0;
        }

        /** Time beyond the 50 ms tick budget that work held the main thread, in ms. */
        double overrunMillis() {
            return this.busy / 1e6;
        }

        double worstGapMillis() {
            return this.worst / 1e6;
        }

        int lateTicks() {
            return this.late;
        }

        double elapsedMillis() {
            return (System.nanoTime() - this.window) / 1e6;
        }
    }
}
