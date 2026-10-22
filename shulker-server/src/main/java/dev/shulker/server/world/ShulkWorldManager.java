package dev.shulker.server.world;

import com.mojang.logging.LogUtils;
import dev.shulker.api.world.ShulkWorldLoader;
import dev.shulker.api.world.ShulkWorldProperties;
import dev.shulker.api.world.ShulkWorldTemplate;
import dev.shulker.api.world.ShulkWorlds;
import dev.shulker.api.world.event.ShulkWorldLoadEvent;
import dev.shulker.api.world.event.ShulkWorldPreSaveEvent;
import dev.shulker.api.world.event.ShulkWorldResetEvent;
import dev.shulker.api.world.event.ShulkWorldSaveEvent;
import dev.shulker.api.world.event.ShulkWorldUnloadEvent;
import dev.shulker.format.ShulkWorld;
import dev.shulker.format.ShulkWorldFolders;
import dev.shulker.server.ShulkerConfig;
import io.papermc.paper.world.saveddata.PaperLevelOverrides;
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
    private static final String WORLD_GEN_SETTINGS_FILE = "data/minecraft/world_gen_settings.dat";

    private static @Nullable ShulkWorldManager instance;

    private final ExecutorService io;
    private final ScheduledExecutorService autosaver;
    private final ShulkWorldFileLoader fileLoader;
    private final Map<String, RegisteredLoader> loaders = new ConcurrentHashMap<>();
    /** Loaded worlds by name, including ones still being opened (to refuse a second load). */
    private final Map<String, Loaded> worlds = new ConcurrentHashMap<>();
    /** Every loaded or loading world, pooled ones included, by the path of its dimension key. */
    private final Map<String, Loaded> byKey = new ConcurrentHashMap<>();
    private final AtomicBoolean cleanedUp = new AtomicBoolean();
    /** Empty worlds kept loaded so that creating a world only has to reset one. */
    private final java.util.concurrent.ConcurrentLinkedDeque<Loaded> pool = new java.util.concurrent.ConcurrentLinkedDeque<>();
    private volatile int poolSize;
    /** Pool worlds being opened right now. Main thread only. */
    private int poolOpening;
    private final java.util.concurrent.atomic.AtomicLong poolCounter = new java.util.concurrent.atomic.AtomicLong();

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
        /** Path of the dimension key {@code shulker:<key>}; fixed, unlike the name of a pooled world. */
        final String key;
        volatile String name;
        /** Replaced by a reset; saves read it once and work on that instance. */
        volatile ShulkWorld data;
        volatile ShulkWorldStorage.@Nullable Registered registered;
        final Path folder;
        volatile @Nullable ShulkWorldLoader loader;
        volatile ShulkWorldProperties properties;
        volatile @Nullable World world;
        volatile @Nullable ScheduledFuture<?> autosave;
        /** Saves of one world run one after the other. */
        CompletableFuture<?> lastSave = CompletableFuture.completedFuture(null);
        volatile long savedModifications = -1;

        Loaded(final String key, final String name, final ShulkWorld data, final Path folder, final @Nullable ShulkWorldLoader loader, final ShulkWorldProperties properties) {
            this.key = key;
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
        this.poolSize = Math.max(0, ShulkerConfig.shulkWorldsPoolSize);
        final long interval = Math.max(1, ShulkerConfig.shulkWorldsPoolRefillTicks) * 50L;
        this.autosaver.scheduleWithFixedDelay(() -> {
            if (server() != null && server().isReady() && server().isRunning()) {
                onMain(() -> {
                    this.tendPool();
                    return null;
                });
            }
        }, interval, interval, TimeUnit.MILLISECONDS);
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

    /** A template read before, with the bytes it was read from. */
    private record CachedTemplate(byte[] bytes, Template template) {
    }

    /** Templates by loader and name; see {@link #readTemplate}. */
    private final Map<ShulkWorldLoader, Map<String, java.lang.ref.SoftReference<CachedTemplate>>> templates =
        java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    /**
     * Reads a template. Reading the same unchanged template again returns the same instance: a
     * world created from it shares its chunks, so a {@linkplain #reset reset} to it only has to
     * replace what a game changed.
     */
    @Override
    public CompletableFuture<ShulkWorldTemplate> readTemplate(final ShulkWorldLoader loader, final String name) {
        return loader.read(name).thenApplyAsync(bytes -> {
            final Map<String, java.lang.ref.SoftReference<CachedTemplate>> byName = this.templates.computeIfAbsent(loader, key -> new ConcurrentHashMap<>());
            final java.lang.ref.SoftReference<CachedTemplate> reference = byName.get(name);
            final CachedTemplate cached = reference == null ? null : reference.get();
            if (cached != null && java.util.Arrays.equals(cached.bytes(), bytes)) {
                return cached.template();
            }
            final Template template = new Template(name, parse(bytes));
            byName.put(name, new java.lang.ref.SoftReference<>(new CachedTemplate(bytes, template)));
            return template;
        }, this.io);
    }

    @Override
    public CompletableFuture<ShulkWorldTemplate> captureTemplate(final World world, final String name) {
        final Loaded loaded = this.loaded(world);
        return this.snapshot(loaded).thenApplyAsync(snapshot -> {
            if (loaded.properties.generator() == null) {
                pruneQuietly(snapshot);
            }
            return new Template(name, snapshot);
        }, this.io);
    }

    /**
     * Imports a world folder (one dimension) as a stored Shulk World, dropping its empty chunks:
     * Shulk Worlds are void outside what they store. Completes with the stored size in bytes.
     */
    public CompletableFuture<Integer> importFolder(final Path folder, final String name) {
        checkName(name);
        return CompletableFuture.supplyAsync(() -> {
            try {
                final ShulkWorld world = toDimensionLayout(ShulkWorldFolders.importFolder(folder));
                final int dropped = EmptyChunks.prune(world);
                LOGGER.info("Imported {} as Shulk World {}: {} chunks kept, {} empty chunks dropped", folder, name,
                    world.chunkCount(dev.shulker.format.RegionKind.CHUNK), dropped);
                return world.toBytes();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }, this.io).thenCompose(bytes -> this.fileLoader.write(name, bytes).thenApply(done -> bytes.length));
    }

    private static void pruneQuietly(final ShulkWorld world) {
        try {
            EmptyChunks.prune(world);
        } catch (IOException e) {
            LOGGER.warn("Could not drop empty chunks", e);
        }
    }

    // ---- loading ---------------------------------------------------------------------------

    @Override
    public CompletableFuture<World> load(final ShulkWorldLoader loader, final String name, final ShulkWorldProperties properties) {
        checkName(name);
        return loader.read(name)
            .thenApplyAsync(ShulkWorldManager::parse, this.io)
            .thenCompose(data -> this.open(name, name, data, loader, properties, null));
    }

    @Override
    public CompletableFuture<World> create(final String name, final ShulkWorldTemplate template, final @Nullable ShulkWorldLoader saveTo, final ShulkWorldProperties properties) {
        checkName(name);
        if (properties.generator() == null && properties.environment() == World.Environment.NORMAL) {
            final Loaded pooled = this.pool.poll();
            if (pooled != null) {
                return this.takeFromPool(pooled, name, template, saveTo, properties);
            }
        }
        final ShulkWorld data = ((Template) template).world().copy();
        data.removeFile(METADATA_FILE); // a new world, so a new UUID
        return this.open(name, name, data, saveTo, properties, template.name());
    }

    @Override
    public CompletableFuture<World> load(final String name, final byte[] data, final @Nullable ShulkWorldLoader saveTo, final ShulkWorldProperties properties) {
        checkName(name);
        return CompletableFuture.supplyAsync(() -> parse(data), this.io)
            .thenCompose(world -> this.open(name, name, world, saveTo, properties, null));
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

    /**
     * Opens a world under the dimension {@code shulker:<key>}. Named worlds use their name as the
     * key; pool worlds ({@code name == null}) get a {@code pool/<n>} key and stay out of the
     * name map until they are taken.
     */
    private CompletableFuture<World> open(final String key, final @Nullable String name, final ShulkWorld data, final @Nullable ShulkWorldLoader loader,
                                          final ShulkWorldProperties properties, final @Nullable String template) {
        final Path folder = dimensionFolder(key);
        final Loaded loaded = new Loaded(key, name == null ? key : name, data, folder, loader, properties);
        if (Bukkit.getWorld(key(key)) != null || this.byKey.containsKey(key) || name != null && this.worlds.putIfAbsent(name, loaded) != null) {
            return CompletableFuture.failedFuture(new IllegalStateException("A world named " + (name == null ? key : name) + " is already loaded"));
        }
        this.byKey.put(key, loaded);
        if (properties.generator() == null) {
            // Void worlds generate nothing, so they need no noise settings: drop stored ones (a
            // world imported from a regular world carries them) and start on a flat base, which
            // spares building and compiling the overworld noise router for every world.
            data.removeFile(WORLD_GEN_SETTINGS_FILE);
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
            loaded.registered = ShulkWorldStorage.register(folder, data, properties.generator() == null);
            final World world;
            try {
                world = Bukkit.createWorld(WorldCreator.ofKey(key(key))
                    .environment(properties.environment())
                    .type(properties.generator() == null ? org.bukkit.WorldType.FLAT : org.bukkit.WorldType.NORMAL)
                    .generator(properties.generator() != null ? properties.generator() : VoidChunkGenerator.INSTANCE));
            } catch (RuntimeException e) {
                ShulkWorldStorage.unregister(folder);
                throw e;
            }
            if (world == null) {
                ShulkWorldStorage.unregister(folder);
                throw new IllegalStateException("The server refused to create world " + key);
            }
            loaded.world = world;
            loaded.savedModifications = data.modifications();
            if (name == null) {
                LOGGER.debug("Pool world {} ready in {} ms", key, (System.nanoTime() - start) / 1_000_000);
                return world;
            }
            this.scheduleAutosave(loaded);
            new ShulkWorldLoadEvent(world, template).callEvent();
            LOGGER.info("Shulk World {} {} ({} chunks, {} KiB) in {} ms on the main thread", name, template == null ? "loaded" : "created from " + template,
                data.chunkCount(dev.shulker.format.RegionKind.CHUNK), data.compressedBytes() / 1024, String.format(Locale.ROOT, "%.1f", (System.nanoTime() - start) / 1e6));
            return world;
        })).whenComplete((world, error) -> {
            if (error != null) {
                if (name != null) {
                    this.worlds.remove(name, loaded);
                }
                this.byKey.remove(key, loaded);
                this.io.execute(() -> deleteQuietly(folder));
            }
        });
    }

    private static NamespacedKey key(final String name) {
        return new NamespacedKey(NAMESPACE, name);
    }

    /** The dimension key path of a world in the {@code shulker} namespace, else null. */
    private static @Nullable String keyOf(final World world) {
        final NamespacedKey key = world.getKey();
        return key.getNamespace().equals(NAMESPACE) ? key.getKey() : null;
    }

    private Path dimensionFolder(final String name) {
        final ResourceKey<net.minecraft.world.level.Level> key = ResourceKey.create(Registries.DIMENSION, Identifier.fromNamespaceAndPath(NAMESPACE, name));
        return server().storageSource.getDimensionPath(key).toAbsolutePath().normalize();
    }

    /**
     * Folders of Shulk Worlds that were loaded when the server last stopped without unloading them.
     * Runs before the first world of this run writes its files, and every other open waits for it.
     */
    private synchronized void cleanUpLeftovers() throws IOException {
        if (this.cleanedUp.compareAndSet(false, true)) {
            final Path root = dimensionFolder("x").getParent();
            if (root != null && Files.isDirectory(root)) {
                try (Stream<Path> folders = Files.list(root)) {
                    for (Path folder : folders.toList()) {
                        FolderTrash.discard(folder);
                    }
                }
            }
        }
    }

    // ---- pool ------------------------------------------------------------------------------

    @Override
    public void setPoolSize(final int size) {
        if (size < 0) {
            throw new IllegalArgumentException("Pool size must not be negative");
        }
        this.poolSize = size;
    }

    @Override
    public int poolSize() {
        return this.poolSize;
    }

    @Override
    public int pooledWorlds() {
        return this.pool.size();
    }

    /**
     * Runs every few ticks on the main thread: opens one pool world while the pool is short and the
     * server has tick time to spare, or discards one while the pool is too large.
     */
    private void tendPool() {
        if (this.pool.size() > this.poolSize) {
            final Loaded extra = this.pool.pollLast();
            if (extra != null) {
                this.discardPooled(extra);
            }
            return;
        }
        if (this.pool.size() + this.poolOpening >= this.poolSize || this.poolOpening > 0
            || Bukkit.getAverageTickTime() > ShulkerConfig.shulkWorldsPoolMaxMspt) {
            return;
        }
        this.poolOpening++;
        final String key = "pool/" + this.poolCounter.incrementAndGet();
        this.open(key, null, new ShulkWorld(), null, ShulkWorldProperties.builder().autosave(java.time.Duration.ZERO).build(), null)
            .whenComplete((world, error) -> onMain(() -> {
                this.poolOpening--;
                final Loaded opened = this.byKey.get(key);
                if (error != null || opened == null) {
                    LOGGER.warn("Could not open pool world {}", key, error == null ? null : unwrap(error));
                } else {
                    this.pool.add(opened);
                }
                return null;
            }));
    }

    /** Gives a pool world a name and its template's chunks: a new world without createWorld. */
    private CompletableFuture<World> takeFromPool(final Loaded pooled, final String name, final ShulkWorldTemplate template,
                                                  final @Nullable ShulkWorldLoader saveTo, final ShulkWorldProperties properties) {
        final World world = pooled.world;
        if (world == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("Pool world " + pooled.key + " is gone"));
        }
        if (this.worlds.putIfAbsent(name, pooled) != null) {
            this.pool.addFirst(pooled);
            return CompletableFuture.failedFuture(new IllegalStateException("A world named " + name + " is already loaded"));
        }
        final long start = System.nanoTime();
        pooled.name = name;
        pooled.loader = saveTo;
        pooled.properties = properties;
        return this.reset(world, template).thenCompose(ignored -> onMain(() -> {
            // The world keeps its own level data, so give it the template's spawn.
            final PaperLevelOverrides.@Nullable RespawnData spawn = templateSpawn(((Template) template).world());
            if (spawn != null) {
                world.setSpawnLocation(new org.bukkit.Location(world, spawn.pos().getX(), spawn.pos().getY(), spawn.pos().getZ(), spawn.yaw(), spawn.pitch()));
            }
            this.scheduleAutosave(pooled);
            new ShulkWorldLoadEvent(world, template.name()).callEvent();
            LOGGER.info("Shulk World {} created from {} out of the pool ({}) in {} ms", name, template.name(), pooled.key,
                String.format(Locale.ROOT, "%.1f", (System.nanoTime() - start) / 1e6));
            return world;
        })).whenComplete((done, error) -> {
            if (error != null) {
                this.worlds.remove(name, pooled);
                this.discardPooled(pooled);
            }
        });
    }

    private static final String LEVEL_OVERRIDES_FILE = "data/paper/level_overrides.dat";

    /** The spawn stored in a template's level data, or null when it has none. */
    private static PaperLevelOverrides.@Nullable RespawnData templateSpawn(final ShulkWorld template) {
        final byte[] file = template.file(LEVEL_OVERRIDES_FILE);
        if (file == null) {
            return null;
        }
        try {
            final net.minecraft.nbt.CompoundTag root = net.minecraft.nbt.NbtIo.readCompressed(new java.io.ByteArrayInputStream(file), net.minecraft.nbt.NbtAccounter.unlimitedHeap());
            return PaperLevelOverrides.RespawnData.CODEC
                .parse(net.minecraft.nbt.NbtOps.INSTANCE, root.getCompoundOrEmpty("data").get("spawn"))
                .result().orElse(null);
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("Could not read the spawn of a template", e);
            return null;
        }
    }

    /** Unloads a pool world without saving and forgets it. */
    private void discardPooled(final Loaded pooled) {
        onMain(() -> {
            final World world = pooled.world;
            if (world != null) {
                discardSavedData(world);
                Bukkit.unloadWorld(world, false);
                pooled.world = null;
            }
            ShulkWorldStorage.unregister(pooled.folder);
            this.byKey.remove(pooled.key, pooled);
            this.io.execute(() -> deleteQuietly(pooled.folder));
            return null;
        });
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

    // ---- resetting -------------------------------------------------------------------------

    /** How often, and how many times, a reset checks whether the world's chunks are gone. */
    private static final long RESET_POLL_MILLIS = 5;
    private static final int RESET_TIMEOUT_POLLS = 2000;
    /** Main-thread time one check may spend driving the chunk system forward. */
    private static final long RESET_BUDGET_NANOS = 2_000_000L;

    /**
     * Puts the template's chunks back. Only what differs from the template is replaced: worlds made
     * from a template share its unchanged chunks, so comparing references finds what a game changed
     * without decompressing anything. Loaded chunks are thrown away, not saved: their entities are
     * removed and they are marked saved first, so the chunk system neither serializes nor writes
     * them while it unloads them.
     */
    @Override
    public CompletableFuture<Void> reset(final World world, final ShulkWorldTemplate template) {
        final Loaded loaded = this.loaded(world);
        final ShulkWorld chunks = ((Template) template).world();
        final long start = System.nanoTime();
        final int[] counts = new int[2]; // replaced chunk positions, loaded chunks thrown away
        return loaded.lastSave.handle((ignored, error) -> null).thenCompose(ignored -> onMain(() -> {
            final ShulkWorldStorage.Registered registered = requireRegistered(loaded);
            final World spawn = Bukkit.getWorlds().getFirst();
            for (Player player : List.copyOf(world.getPlayers())) {
                player.teleport(spawn.getSpawnLocation());
            }
            registered.discardWrites = true; // the old chunks are thrown away, not saved
            for (org.bukkit.Chunk chunk : List.copyOf(world.getForceLoadedChunks())) {
                chunk.setForceLoaded(false);
            }
            for (Plugin plugin : List.copyOf(world.getPluginChunkTickets().keySet())) {
                world.removePluginChunkTickets(plugin);
            }
            final ServerLevel level = ((CraftWorld) world).getHandle();
            for (org.bukkit.entity.Entity entity : world.getEntities()) {
                if (!(entity instanceof Player)) {
                    entity.remove(); // nothing to save in the entity chunks
                }
            }
            for (org.bukkit.Chunk chunk : world.getLoadedChunks()) {
                final net.minecraft.world.level.chunk.LevelChunk handle = level.getChunkSource().getChunkNow(chunk.getX(), chunk.getZ());
                if (handle != null) {
                    handle.tryMarkSaved(); // nothing to serialize when it unloads
                }
                counts[1]++;
            }
            drainChunks(world);
            return null;
        })).thenCompose(ignored -> this.awaitNoChunks(world, 0)).thenCompose(ignored -> onMain(() -> {
            final ShulkWorldStorage.Registered registered = requireRegistered(loaded);
            final ServerLevel level = ((CraftWorld) world).getHandle();
            ca.spottedleaf.moonrise.patches.chunk_system.io.MoonriseRegionFileIO.flush(level); // writes of the old chunks are done
            final ShulkWorld data = loaded.data;
            final java.util.Set<Long> positions = new java.util.HashSet<>();
            for (dev.shulker.format.RegionKind kind : dev.shulker.format.RegionKind.values()) {
                positions.addAll(data.differentChunks(chunks, kind));
            }
            for (long position : positions) {
                for (dev.shulker.format.RegionKind kind : dev.shulker.format.RegionKind.values()) {
                    data.copyChunkFrom(chunks, kind, ShulkWorld.keyX(position), ShulkWorld.keyZ(position));
                }
            }
            counts[0] = positions.size();
            registered.discardWrites = false;
            loaded.savedModifications = -1; // the next save writes the reset world
            new ShulkWorldResetEvent(world, template.name()).callEvent();
            LOGGER.info("Shulk World {} reset to {}: {} chunk positions replaced, {} loaded chunks dropped, in {} ms", loaded.name, template.name(),
                counts[0], counts[1], String.format(Locale.ROOT, "%.1f", (System.nanoTime() - start) / 1e6));
            return (Void) null;
        })).whenComplete((ignored, error) -> {
            final ShulkWorldStorage.Registered registered = loaded.registered;
            if (error != null && registered != null) {
                registered.discardWrites = false;
            }
        });
    }

    /**
     * Drives the chunk system of an emptied world until it holds no chunks at all. Loaded chunks
     * are not enough: an unloaded chunk keeps its holder for a while and comes back from memory,
     * not from storage. Unloads finish asynchronously, so this checks every few milliseconds, each
     * time within {@link #RESET_BUDGET_NANOS} of main-thread time.
     */
    private CompletableFuture<Void> awaitNoChunks(final World world, final int polls) {
        return onMain(() -> drainChunks(world)).thenCompose(count -> {
            if (count == 0) {
                return CompletableFuture.completedFuture(null);
            }
            if (polls >= RESET_TIMEOUT_POLLS) {
                return CompletableFuture.failedFuture(new IllegalStateException(
                    count + " chunks of " + world.getName() + " stay loaded (a plugin holds them?); reset aborted"));
            }
            return CompletableFuture.supplyAsync(() -> null, CompletableFuture.delayedExecutor(RESET_POLL_MILLIS, TimeUnit.MILLISECONDS))
                .thenCompose(next -> this.awaitNoChunks(world, polls + 1));
        });
    }

    /**
     * Nothing keeps an emptied world except tickets that expire on their own: chunk load tickets
     * linger for a few ticks, refused unloads get a 5 s cooldown, and the chunk system unloads a
     * fraction of its queue per tick. The world is being thrown away, so this runs its ticket clock
     * and unloads forward, stopping when its time budget is used. Returns how many chunk holders
     * are left.
     */
    private static int drainChunks(final World world) {
        final long deadline = System.nanoTime() + RESET_BUDGET_NANOS;
        final ca.spottedleaf.moonrise.patches.chunk_system.scheduling.ChunkHolderManager holders = chunkHolders(world);
        final ca.spottedleaf.moonrise.patches.chunk_system.scheduling.ChunkTaskScheduler scheduler =
            ((ca.spottedleaf.moonrise.patches.chunk_system.level.ChunkSystemServerLevel) ((CraftWorld) world).getHandle()).moonrise$getChunkTaskScheduler();
        while (holders.size() > 0 && System.nanoTime() < deadline) {
            holders.removeAllTicketsFor(ca.spottedleaf.moonrise.patches.chunk_system.scheduling.ChunkHolderManager.UNLOAD_COOLDOWN,
                ca.spottedleaf.moonrise.patches.chunk_system.scheduling.ChunkHolderManager.MAX_TICKET_LEVEL, null);
            holders.tick();
            holders.processTicketUpdates();
            while (System.nanoTime() < deadline && scheduler.executeMainThreadTask()) {
                // demote the chunks whose tickets went away, so they reach the unload queue
            }
            holders.processUnloads();
        }
        return holders.size();
    }

    /**
     * Unloading a world closes its saved data storage, which writes every changed saved data file
     * (raids, world border, game rules, ...) and waits for the writes, even when the world is not
     * saved; on a busy disk that held the main thread for over 10 s. A discarded world drops them.
     */
    private static void discardSavedData(final World world) {
        ((CraftWorld) world).getHandle().getDataStorage().cache.values().forEach(data -> data.ifPresent(saved -> saved.setDirty(false)));
    }

    private static ca.spottedleaf.moonrise.patches.chunk_system.scheduling.ChunkHolderManager chunkHolders(final World world) {
        return ((ca.spottedleaf.moonrise.patches.chunk_system.level.ChunkSystemServerLevel) ((CraftWorld) world).getHandle())
            .moonrise$getChunkTaskScheduler().chunkHolderManager;
    }

    private static ShulkWorldStorage.Registered requireRegistered(final Loaded loaded) {
        final ShulkWorldStorage.Registered registered = loaded.registered;
        if (registered == null || loaded.world == null) {
            throw new IllegalStateException(loaded.name + " is not loaded");
        }
        return registered;
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
            // A world that is not written back is discarded: saving it, even into memory, would only
            // make the main thread wait for Minecraft's level data files to be written.
            if (!(save && loaded.writesBack())) {
                discardSavedData(world);
            }
            if (!Bukkit.unloadWorld(world, save && loaded.writesBack())) {
                throw new IllegalStateException("The server refused to unload " + loaded.name);
            }
            loaded.world = null;
            return null;
        })).thenCompose(ignored -> save && loaded.writesBack() ? this.writeBack(loaded, true) : CompletableFuture.completedFuture(null))
            .whenCompleteAsync((ignored, error) -> {
                if (loaded.world == null) {
                    ShulkWorldStorage.unregister(loaded.folder);
                    this.worlds.remove(loaded.name, loaded);
                    this.byKey.remove(loaded.key, loaded);
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
        if (manager == null || manager.byKey.isEmpty()) {
            return;
        }
        for (Loaded pooled : List.copyOf(manager.pool)) { // empty and never saved
            ShulkWorldStorage.unregister(pooled.folder);
            deleteQuietly(pooled.folder);
        }
        manager.pool.clear();
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
        manager.byKey.clear();
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

    /** The named Shulk World behind a world; pool worlds are not Shulk Worlds until taken. */
    private @Nullable Loaded find(final World world) {
        final String key = keyOf(world);
        final Loaded loaded = key == null ? null : this.byKey.get(key);
        return loaded != null && loaded.world == world && this.worlds.get(loaded.name) == loaded ? loaded : null;
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

    /** Gets rid of a world folder without a burst of disk work: see {@link FolderTrash}. */
    private static void deleteQuietly(final Path folder) {
        FolderTrash.discard(folder);
    }
}
