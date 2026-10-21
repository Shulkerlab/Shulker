package dev.shulker.server.world;

import com.mojang.logging.LogUtils;
import dev.shulker.api.world.ShulkWorldLoader;
import dev.shulker.api.world.ShulkWorldProperties;
import dev.shulker.api.world.ShulkWorldTemplate;
import dev.shulker.api.world.ShulkWorlds;
import dev.shulker.api.world.event.ShulkWorldLoadEvent;
import dev.shulker.api.world.event.ShulkWorldPreSaveEvent;
import dev.shulker.api.world.event.ShulkWorldSaveEvent;
import dev.shulker.api.world.event.ShulkWorldUnloadEvent;
import dev.shulker.format.ShulkWorld;
import dev.shulker.format.ShulkWorldFolders;
import dev.shulker.server.ShulkerConfig;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * Runs Shulk Worlds on top of ordinary Bukkit worlds.
 *
 * <p>A Shulk World named {@code n} is the dimension {@code shulker:n}. Its small files
 * ({@code data/...}: world UUID, PDC, game rules, generator settings) are written to the
 * dimension folder while it is loaded; its chunks are not: {@link ShulkRegionFiles} hands the
 * region storage of that folder a {@link ShulkWorldRegionFile} over the in-memory
 * {@link ShulkWorld}. Saving flushes the level into memory, picks the small files back up and
 * gives the whole world to the loader; unloading deletes the folder again.
 *
 * <p>Everything that touches a level runs on the main thread; serializing and loaders run on
 * {@code shulk-worlds.save-threads} worker threads.
 */
public final class ShulkWorldManager implements ShulkWorlds {

    private static final Logger LOGGER = LogUtils.getClassLogger();
    public static final String NAMESPACE = "shulker";
    private static final String FILE_LOADER = "file";
    private static final Pattern NAME = Pattern.compile("[a-z0-9_.-]{1,64}");
    private static final Pattern LOADER_ID = Pattern.compile("[a-z0-9_-]{1,32}");
    /** Holds the world's UUID; a world created from a template gets a new one. */
    private static final String METADATA_FILE = "data/paper/metadata.dat";

    private static @Nullable ShulkWorldManager instance;

    private final ExecutorService io;
    private final ScheduledExecutorService autosaver;
    private final ShulkWorldFileLoader fileLoader;
    private final Map<String, RegisteredLoader> loaders = new ConcurrentHashMap<>();
    /** Loaded worlds by name, including ones still being opened (to refuse a second load). */
    private final Map<String, Loaded> worlds = new ConcurrentHashMap<>();
    private final AtomicBoolean cleanedUp = new AtomicBoolean();

    private record RegisteredLoader(@Nullable Plugin plugin, ShulkWorldLoader loader) {
    }

    record Template(String name, ShulkWorld world) implements ShulkWorldTemplate {
        @Override
        public int chunkCount() {
            return this.world.chunkCount(dev.shulker.format.RegionKind.CHUNK);
        }

        @Override
        public long sizeBytes() {
            return this.world.compressedBytes();
        }
    }

    /** A loaded (or loading) Shulk World. */
    static final class Loaded {
        final String name;
        final ShulkWorld data;
        final Path folder;
        final @Nullable ShulkWorldLoader loader;
        final ShulkWorldProperties properties;
        volatile @Nullable World world;
        volatile @Nullable ScheduledFuture<?> autosave;
        /** Saves of one world run one after the other. */
        CompletableFuture<?> lastSave = CompletableFuture.completedFuture(null);
        volatile long savedModifications = -1;

        Loaded(final String name, final ShulkWorld data, final Path folder, final @Nullable ShulkWorldLoader loader, final ShulkWorldProperties properties) {
            this.name = name;
            this.data = data;
            this.folder = folder;
            this.loader = loader;
            this.properties = properties;
        }

        boolean writesBack() {
            return this.loader != null && !this.properties.readOnly();
        }
    }

    private ShulkWorldManager() {
        final AtomicInteger threads = new AtomicInteger();
        this.io = Executors.newFixedThreadPool(Math.max(1, ShulkerConfig.shulkWorldsSaveThreads), task -> {
            final Thread thread = new Thread(task, "Shulker World IO #" + threads.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
        this.autosaver = Executors.newSingleThreadScheduledExecutor(task -> {
            final Thread thread = new Thread(task, "Shulker World Autosave");
            thread.setDaemon(true);
            return thread;
        });
        this.fileLoader = new ShulkWorldFileLoader(Path.of(ShulkerConfig.shulkWorldsFolder), this.io);
        this.loaders.put(FILE_LOADER, new RegisteredLoader(null, this.fileLoader));
    }

    /** Called once on startup, after shulker.yml is read. */
    public static synchronized void install() {
        if (instance == null) {
            instance = new ShulkWorldManager();
            ShulkWorlds.Holder.set(instance);
        }
    }

    public static ShulkWorldManager get() {
        final ShulkWorldManager manager = instance;
        if (manager == null) {
            throw new IllegalStateException("Shulk Worlds are not installed");
        }
        return manager;
    }

    static void checkName(final String name) {
        if (!NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("Invalid Shulk World name '" + name + "': use 1-64 of a-z, 0-9, _ . -");
        }
    }

    private static MinecraftServer server() {
        return MinecraftServer.getServer();
    }

    // ---- loaders ---------------------------------------------------------------------------

    @Override
    public ShulkWorldLoader fileLoader() {
        return this.fileLoader;
    }

    /** Folder of the file loader. */
    public Path fileFolder() {
        return this.fileLoader.folder();
    }

    @Override
    public void registerLoader(final Plugin plugin, final String id, final ShulkWorldLoader loader) {
        if (!LOADER_ID.matcher(id).matches()) {
            throw new IllegalArgumentException("Invalid loader id '" + id + "': use 1-32 of a-z, 0-9, _ -");
        }
        final RegisteredLoader previous = this.loaders.get(id);
        if (previous != null && (previous.plugin() == null || previous.plugin().isEnabled())) {
            throw new IllegalArgumentException("Loader id '" + id + "' is already registered");
        }
        this.loaders.put(id, new RegisteredLoader(plugin, loader)); // replaces one of a disabled plugin
    }

    @Override
    public @Nullable ShulkWorldLoader loader(final String id) {
        final RegisteredLoader registered = this.loaders.get(id);
        return registered == null || registered.plugin() != null && !registered.plugin().isEnabled() ? null : registered.loader();
    }

    public List<String> loaderIds() {
        return this.loaders.keySet().stream().filter(id -> this.loader(id) != null).sorted().toList();
    }

    // ---- templates -------------------------------------------------------------------------

    @Override
    public CompletableFuture<ShulkWorldTemplate> readTemplate(final ShulkWorldLoader loader, final String name) {
        return loader.read(name).thenApplyAsync(bytes -> new Template(name, parse(bytes)), this.io);
    }

    @Override
    public CompletableFuture<ShulkWorldTemplate> captureTemplate(final World world, final String name) {
        final Loaded loaded = this.loaded(world);
        return this.snapshot(loaded).thenApply(snapshot -> new Template(name, snapshot));
    }

    // ---- loading ---------------------------------------------------------------------------

    @Override
    public CompletableFuture<World> load(final ShulkWorldLoader loader, final String name, final ShulkWorldProperties properties) {
        checkName(name);
        return loader.read(name)
            .thenApplyAsync(ShulkWorldManager::parse, this.io)
            .thenCompose(data -> this.open(name, data, loader, properties, null));
    }

    @Override
    public CompletableFuture<World> create(final String name, final ShulkWorldTemplate template, final @Nullable ShulkWorldLoader saveTo, final ShulkWorldProperties properties) {
        checkName(name);
        final ShulkWorld data = ((Template) template).world().copy();
        data.removeFile(METADATA_FILE); // a new world, so a new UUID
        return this.open(name, data, saveTo, properties, template.name());
    }

    @Override
    public CompletableFuture<World> load(final String name, final byte[] data, final @Nullable ShulkWorldLoader saveTo, final ShulkWorldProperties properties) {
        checkName(name);
        return CompletableFuture.supplyAsync(() -> parse(data), this.io)
            .thenCompose(world -> this.open(name, world, saveTo, properties, null));
    }

    private static ShulkWorld parse(final byte[] bytes) {
        try {
            return toDimensionLayout(ShulkWorld.fromBytes(bytes));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * A world imported from a whole world folder keeps its files relative to that folder, with
     * the dimension's own files under {@link ShulkWorldFolders#REGION_DIR}. A Shulk World is a
     * single dimension: keep that dimension's files, relative to it.
     */
    static ShulkWorld toDimensionLayout(final ShulkWorld world) {
        final String dir = world.property(ShulkWorldFolders.REGION_DIR);
        if (dir == null || dir.isEmpty()) {
            return world;
        }
        final String prefix = dir + "/";
        for (String path : world.filePaths()) {
            final byte[] contents = world.file(path);
            world.removeFile(path);
            if (contents != null && path.startsWith(prefix)) {
                world.putFile(path.substring(prefix.length()), contents);
            }
        }
        world.setProperty(ShulkWorldFolders.REGION_DIR, "");
        return world;
    }

    private CompletableFuture<World> open(final String name, final ShulkWorld data, final @Nullable ShulkWorldLoader loader,
                                          final ShulkWorldProperties properties, final @Nullable String template) {
        final Path folder = dimensionFolder(name);
        final Loaded loaded = new Loaded(name, data, folder, loader, properties);
        if (Bukkit.getWorld(key(name)) != null || this.worlds.putIfAbsent(name, loaded) != null) {
            return CompletableFuture.failedFuture(new IllegalStateException("A world named " + name + " is already loaded"));
        }
        return CompletableFuture.runAsync(() -> {
            try {
                this.cleanUpLeftovers();
                deleteRecursively(folder);
                ShulkWorldFolders.writeFiles(data, folder);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }, this.io).thenCompose(ignored -> onMain(() -> {
            final long start = System.nanoTime();
            ShulkWorldStorage.register(folder, data);
            final World world;
            try {
                world = Bukkit.createWorld(WorldCreator.ofKey(key(name))
                    .environment(properties.environment())
                    .generator(properties.generator() != null ? properties.generator() : VoidChunkGenerator.INSTANCE));
            } catch (RuntimeException e) {
                ShulkWorldStorage.unregister(folder);
                throw e;
            }
            if (world == null) {
                ShulkWorldStorage.unregister(folder);
                throw new IllegalStateException("The server refused to create world " + name);
            }
            loaded.world = world;
            loaded.savedModifications = data.modifications();
            this.scheduleAutosave(loaded);
            new ShulkWorldLoadEvent(world, template).callEvent();
            LOGGER.info("Shulk World {} {} ({} chunks, {} KiB) in {} ms on the main thread", name, template == null ? "loaded" : "created from " + template,
                data.chunkCount(dev.shulker.format.RegionKind.CHUNK), data.compressedBytes() / 1024, String.format(Locale.ROOT, "%.1f", (System.nanoTime() - start) / 1e6));
            return world;
        })).whenComplete((world, error) -> {
            if (error != null) {
                this.worlds.remove(name, loaded);
                this.io.execute(() -> deleteQuietly(folder));
            }
        });
    }

    private static NamespacedKey key(final String name) {
        return new NamespacedKey(NAMESPACE, name);
    }

    /** The Shulk World name of a world in the {@code shulker} namespace, else null. */
    private static @Nullable String nameOf(final World world) {
        final NamespacedKey key = world.getKey();
        return key.getNamespace().equals(NAMESPACE) ? key.getKey() : null;
    }

    private Path dimensionFolder(final String name) {
        final ResourceKey<net.minecraft.world.level.Level> key = ResourceKey.create(Registries.DIMENSION, Identifier.fromNamespaceAndPath(NAMESPACE, name));
        return server().storageSource.getDimensionPath(key).toAbsolutePath().normalize();
    }

    /** Folders of Shulk Worlds that were loaded when the server last stopped without unloading them. */
    private void cleanUpLeftovers() throws IOException {
        if (this.cleanedUp.compareAndSet(false, true)) {
            final Path root = dimensionFolder("x").getParent();
            if (root != null && Files.isDirectory(root)) {
                try (Stream<Path> folders = Files.list(root)) {
                    for (Path folder : folders.toList()) {
                        if (!this.worlds.containsKey(folder.getFileName().toString())) {
                            deleteRecursively(folder);
                        }
                    }
                }
            }
        }
    }

    // ---- saving ----------------------------------------------------------------------------

    @Override
    public CompletableFuture<Void> save(final World world) {
        final Loaded loaded = this.loaded(world);
        if (!loaded.writesBack()) {
            return CompletableFuture.completedFuture(null);
        }
        return this.writeBack(loaded, false);
    }

    @Override
    public CompletableFuture<byte[]> serialize(final World world) {
        return this.snapshot(this.loaded(world)).thenApply(ShulkWorld::toBytes);
    }

    /** Saves the level into memory on the main thread, then copies the in-memory world. */
    private CompletableFuture<ShulkWorld> snapshot(final Loaded loaded) {
        return onMain(() -> {
            flushLevel(loaded);
            return null;
        }).thenApplyAsync(ignored -> {
            this.pickUpFiles(loaded);
            return loaded.data.copy();
        }, this.io);
    }

    private static void flushLevel(final Loaded loaded) {
        final World world = loaded.world;
        if (world == null) {
            return;
        }
        new ShulkWorldPreSaveEvent(world).callEvent();
        final ServerLevel level = ((CraftWorld) world).getHandle();
        level.save(null, true, false);
        ca.spottedleaf.moonrise.patches.chunk_system.io.MoonriseRegionFileIO.flush(level);
    }

    private void pickUpFiles(final Loaded loaded) {
        try {
            if (Files.isDirectory(loaded.folder)) {
                ShulkWorldFolders.readFiles(loaded.data, loaded.folder);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Flushes the level (unless it was just unloaded, which flushed it), then serializes and
     * writes on a worker thread. Saves of one world are chained, never concurrent.
     */
    private synchronized CompletableFuture<Void> writeBack(final Loaded loaded, final boolean alreadyFlushed) {
        final CompletableFuture<Void> save = loaded.lastSave.handle((ignored, error) -> null)
            .thenCompose(ignored -> alreadyFlushed ? CompletableFuture.completedFuture(null) : onMain(() -> {
                flushLevel(loaded);
                return null;
            }))
            .thenComposeAsync(ignored -> {
                this.pickUpFiles(loaded);
                final long modifications = loaded.data.modifications();
                if (modifications == loaded.savedModifications) {
                    return CompletableFuture.completedFuture(-1L); // nothing changed since the last save
                }
                final byte[] bytes = loaded.data.toBytes();
                return requireLoader(loaded).write(loaded.name, bytes).thenApply(done -> {
                    loaded.savedModifications = modifications;
                    return (long) bytes.length;
                });
            }, this.io)
            .thenCompose(size -> {
                final World world = loaded.world;
                if (size < 0 || world == null || !server().isRunning()) {
                    return CompletableFuture.completedFuture(null);
                }
                return onMain(() -> {
                    new ShulkWorldSaveEvent(world, size).callEvent();
                    return null;
                });
            });
        loaded.lastSave = save;
        return save;
    }

    private static ShulkWorldLoader requireLoader(final Loaded loaded) {
        final ShulkWorldLoader loader = loaded.loader;
        if (loader == null) {
            throw new IllegalStateException(loaded.name + " has no loader");
        }
        return loader;
    }

    private void scheduleAutosave(final Loaded loaded) {
        final long seconds = loaded.properties.autosave().toSeconds();
        if (!loaded.writesBack() || seconds <= 0) {
            return;
        }
        loaded.autosave = this.autosaver.scheduleAtFixedRate(() -> {
            if (loaded.world != null && this.worlds.get(loaded.name) == loaded) {
                this.writeBack(loaded, false).exceptionally(error -> {
                    LOGGER.error("Autosave of Shulk World {} failed", loaded.name, unwrap(error));
                    return null;
                });
            }
        }, seconds, seconds, TimeUnit.SECONDS);
    }

    // ---- unloading -------------------------------------------------------------------------

    @Override
    public CompletableFuture<Void> unload(final World world, final boolean save) {
        final Loaded loaded = this.loaded(world);
        return loaded.lastSave.handle((ignored, error) -> null).thenCompose(ignored -> onMain(() -> {
            final World spawn = Bukkit.getWorlds().getFirst();
            for (Player player : List.copyOf(world.getPlayers())) {
                player.teleport(spawn.getSpawnLocation());
            }
            new ShulkWorldUnloadEvent(world, save && loaded.writesBack()).callEvent();
            final ScheduledFuture<?> autosave = loaded.autosave;
            if (autosave != null) {
                autosave.cancel(false);
            }
            if (!Bukkit.unloadWorld(world, true)) { // always flush into memory; whether it is kept is decided below
                throw new IllegalStateException("The server refused to unload " + loaded.name);
            }
            loaded.world = null;
            return null;
        })).thenCompose(ignored -> save && loaded.writesBack() ? this.writeBack(loaded, true) : CompletableFuture.completedFuture(null))
            .whenCompleteAsync((ignored, error) -> {
                if (loaded.world == null) {
                    ShulkWorldStorage.unregister(loaded.folder);
                    this.worlds.remove(loaded.name, loaded);
                    deleteQuietly(loaded.folder);
                }
            }, this.io);
    }

    /**
     * Called by the server once every level was saved and the region I/O flushed on shutdown:
     * writes every Shulk World back. Plugins have been disabled by then, so a plugin's loader
     * may refuse; such worlds go to {@code <folder>/.recovery/} instead of being lost.
     */
    public static void serverStopped() {
        final ShulkWorldManager manager = instance;
        if (manager == null || manager.worlds.isEmpty()) {
            return;
        }
        for (Loaded loaded : List.copyOf(manager.worlds.values())) {
            if (loaded.world == null) {
                continue;
            }
            try {
                manager.pickUpFiles(loaded);
                if (loaded.writesBack() && loaded.data.modifications() != loaded.savedModifications) {
                    final byte[] bytes = loaded.data.toBytes();
                    try {
                        requireLoader(loaded).write(loaded.name, bytes).get(60, TimeUnit.SECONDS);
                        LOGGER.info("Saved Shulk World {}", loaded.name);
                    } catch (Exception e) {
                        final Path recovery = manager.fileLoader.folder().resolve(".recovery").resolve(loaded.name + ShulkWorld.EXTENSION);
                        ShulkWorld.writeAtomically(recovery, bytes);
                        LOGGER.error("Could not save Shulk World {} through its loader; wrote it to {}", loaded.name, recovery, unwrap(e));
                    }
                }
            } catch (IOException | RuntimeException e) {
                LOGGER.error("Could not save Shulk World {} on shutdown", loaded.name, e);
            }
            ShulkWorldStorage.unregister(loaded.folder);
            deleteQuietly(loaded.folder);
        }
        manager.worlds.clear();
        manager.autosaver.shutdownNow();
        manager.io.shutdown();
    }

    // ---- lookups ---------------------------------------------------------------------------

    @Override
    public boolean isShulkWorld(final World world) {
        return this.find(world) != null;
    }

    @Override
    public @Nullable ShulkWorldProperties properties(final World world) {
        final Loaded loaded = this.find(world);
        return loaded == null ? null : loaded.properties;
    }

    @Override
    public @Nullable World world(final String name) {
        final Loaded loaded = this.worlds.get(name);
        return loaded == null ? null : loaded.world;
    }

    @Override
    public @Nullable String name(final World world) {
        final Loaded loaded = this.find(world);
        return loaded == null ? null : loaded.name;
    }

    private @Nullable Loaded find(final World world) {
        final String name = nameOf(world);
        final Loaded loaded = name == null ? null : this.worlds.get(name);
        return loaded != null && loaded.world == world ? loaded : null;
    }

    @Override
    public Collection<World> worlds() {
        return this.worlds.values().stream().map(loaded -> loaded.world).filter(java.util.Objects::nonNull).toList();
    }

    /** What {@code /shulker world info} shows. */
    public record Info(int chunks, long bytes, String loader, boolean unsaved) {
    }

    public Info info(final World world) {
        final Loaded loaded = this.loaded(world);
        final ShulkWorldLoader loader = loaded.loader;
        final String loaderId = loader == null ? "none" : this.loaders.entrySet().stream()
            .filter(entry -> entry.getValue().loader() == loader).map(Map.Entry::getKey).findFirst().orElse("custom");
        return new Info(loaded.data.chunkCount(dev.shulker.format.RegionKind.CHUNK), loaded.data.compressedBytes(), loaderId,
            loaded.writesBack() && loaded.data.modifications() != loaded.savedModifications);
    }

    private Loaded loaded(final World world) {
        final Loaded loaded = this.find(world);
        if (loaded == null) {
            throw new IllegalArgumentException(world.getName() + " is not a Shulk World");
        }
        return loaded;
    }

    // ---- helpers ---------------------------------------------------------------------------

    /** Runs on the main thread: now if already there, otherwise as a task of the server loop. */
    private static <T> CompletableFuture<T> onMain(final Supplier<T> task) {
        if (Bukkit.isPrimaryThread()) {
            try {
                return CompletableFuture.completedFuture(task.get());
            } catch (RuntimeException e) {
                return CompletableFuture.failedFuture(e);
            }
        }
        return CompletableFuture.supplyAsync(task, server());
    }

    public static Throwable unwrap(final Throwable error) {
        return error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
    }

    static void deleteRecursively(final Path folder) throws IOException {
        if (!Files.exists(folder)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(folder)) {
            for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        }
    }

    private static void deleteQuietly(final Path folder) {
        try {
            deleteRecursively(folder);
        } catch (IOException e) {
            LOGGER.warn("Could not delete {}", folder, e);
        }
    }
}
