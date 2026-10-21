package dev.shulker.api.world;

import java.util.Collection;
import java.util.concurrent.CompletableFuture;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.ApiStatus;
import org.jspecify.annotations.Nullable;

/**
 * Loads, creates, saves and unloads Shulk Worlds: small worlds stored whole in one
 * world-layout {@code .shulk} file and kept in memory while loaded.
 *
 * <p>A loaded Shulk World is an ordinary {@link World}: teleports, block changes, world
 * guards and every other plugin work on it as on any world. What differs is storage - chunks
 * live in memory and reach the {@linkplain ShulkWorldLoader loader} only when the world is
 * saved - and that worlds are created from {@linkplain ShulkWorldTemplate templates} instead of
 * being generated or pasted block by block.
 *
 * <pre>{@code
 * ShulkWorlds worlds = ShulkWorlds.get();
 * ShulkWorldTemplate island = worlds.readTemplate(worlds.fileLoader(), "island").join();
 * worlds.create("island_" + owner, island, worlds.fileLoader(), ShulkWorldProperties.defaults())
 *     .thenAccept(world -> player.teleportAsync(world.getSpawnLocation()));
 * }</pre>
 *
 * <p>Futures that return a {@link World} complete on the main thread. Detect Shulker at runtime
 * with {@link #isAvailable()} before touching this class on servers that may run plain Paper.
 */
public interface ShulkWorlds {

    /**
     * The server's Shulk World manager.
     *
     * @return the manager
     * @throws IllegalStateException before the server has started it
     */
    static ShulkWorlds get() {
        final ShulkWorlds instance = Holder.instance;
        if (instance == null) {
            throw new IllegalStateException("Shulk Worlds are not ready yet");
        }
        return instance;
    }

    /**
     * Whether this server supports Shulk Worlds. Safe to call from plugins that also run on
     * other Paper servers, as long as the call is guarded by a check that this class exists.
     *
     * @return whether {@link #get()} can be used
     */
    static boolean isAvailable() {
        return Holder.instance != null;
    }

    /**
     * The loader for the {@code shulker-worlds/} folder, registered as {@code "file"}.
     *
     * @return the file loader
     */
    ShulkWorldLoader fileLoader();

    /**
     * Registers a loader under an id, so commands and configuration can refer to it. The
     * registration is dropped when the plugin disables.
     *
     * @param plugin owning plugin
     * @param id     loader id, such as {@code "mysql"}
     * @param loader the loader
     * @throws IllegalArgumentException if the id is taken
     */
    void registerLoader(Plugin plugin, String id, ShulkWorldLoader loader);

    /**
     * Looks up a registered loader.
     *
     * @param id loader id
     * @return the loader, or null
     */
    @Nullable ShulkWorldLoader loader(String id);

    /**
     * Reads a stored world into memory as a template.
     *
     * @param loader where the world is stored
     * @param name   stored world name
     * @return the template
     */
    CompletableFuture<ShulkWorldTemplate> readTemplate(ShulkWorldLoader loader, String name);

    /**
     * Captures a loaded Shulk World as it is now, for example after building an island layout
     * in game. The world keeps running; the template does not follow later changes.
     *
     * @param world a loaded Shulk World
     * @param name  template name
     * @return the template
     */
    CompletableFuture<ShulkWorldTemplate> captureTemplate(World world, String name);

    /**
     * Loads a stored world.
     *
     * @param loader     where the world is stored, and where it is saved back to
     * @param name       stored world name, also the Shulk World name
     * @param properties how the world runs
     * @return the loaded world
     */
    CompletableFuture<World> load(ShulkWorldLoader loader, String name, ShulkWorldProperties properties);

    /**
     * Creates a new world from a template, without copying blocks.
     *
     * @param name       Shulk World name
     * @param template   the template
     * @param saveTo     where the world is saved, or null for a world that is never saved
     * @param properties how the world runs
     * @return the new world
     */
    CompletableFuture<World> create(String name, ShulkWorldTemplate template, @Nullable ShulkWorldLoader saveTo, ShulkWorldProperties properties);

    /**
     * Loads a world from the bytes of a world-layout {@code .shulk} file, for example one moved
     * between servers without a shared loader.
     *
     * @param name       world name
     * @param data       the bytes of a world-layout {@code .shulk} file
     * @param saveTo     where the world is saved, or null for a world that is never saved
     * @param properties how the world runs
     * @return the loaded world
     */
    CompletableFuture<World> load(String name, byte[] data, @Nullable ShulkWorldLoader saveTo, ShulkWorldProperties properties);

    /**
     * Saves a Shulk World to its loader now. Does nothing for read-only worlds and worlds
     * without a loader.
     *
     * @param world a loaded Shulk World
     * @return completes once the loader stored it
     */
    CompletableFuture<Void> save(World world);

    /**
     * Serializes a Shulk World as it is now into the bytes of a world-layout {@code .shulk} file.
     *
     * @param world a loaded Shulk World
     * @return the bytes
     */
    CompletableFuture<byte[]> serialize(World world);

    /**
     * Puts the chunks of a template back into a loaded Shulk World without unloading it: the
     * arena reset of minigames. Players are moved to the main world's spawn, the world's chunks
     * are unloaded without being saved, and the template's chunks take their place; the world
     * itself, its settings, game rules, UUID and persistent data stay as they are.
     *
     * <p>Plugins must not keep chunks of the world loaded meanwhile (plugin chunk tickets and
     * force-loaded chunks are released first); the reset fails if chunks stay loaded.
     *
     * @param world    a loaded Shulk World
     * @param template the chunks to put in place, usually the template it was created from
     * @return completes on the main thread once the world can be played again
     */
    CompletableFuture<Void> reset(World world, ShulkWorldTemplate template);

    /**
     * Sets how many empty worlds the server keeps loaded and ready. While the pool has one,
     * {@link #create} for a void world in the normal environment takes it and
     * {@linkplain #reset resets} it to the template instead of creating a world, which keeps
     * world creation out of the moment a player waits for it. The pool refills in the background,
     * one world at a time while the server has tick time to spare. 0 turns the pool off.
     *
     * <p>A world taken from the pool keeps the dimension key it was opened with,
     * {@code shulker:pool/<n>}; look it up by its name with {@link #world(String)}.
     *
     * @param size worlds to keep ready, zero or more
     */
    void setPoolSize(int size);

    /**
     * The pool size set by {@link #setPoolSize} or {@code shulk-worlds.pool.size}.
     *
     * @return worlds the pool aims to keep ready
     */
    int poolSize();

    /**
     * Empty worlds ready in the pool right now.
     *
     * @return ready pool worlds
     */
    int pooledWorlds();

    /**
     * Unloads a Shulk World, moving its players to the main world's spawn first.
     *
     * @param world a loaded Shulk World
     * @param save  whether to save it to its loader first
     * @return completes once the world is unloaded (and saved)
     */
    CompletableFuture<Void> unload(World world, boolean save);

    /**
     * Whether a world is a Shulk World.
     *
     * @param world any world
     * @return whether this manager owns it
     */
    boolean isShulkWorld(World world);

    /**
     * The properties a Shulk World was loaded with.
     *
     * @param world a loaded Shulk World
     * @return its properties, or null for other worlds
     */
    @Nullable ShulkWorldProperties properties(World world);

    /**
     * A loaded Shulk World by its Shulk World name. Its Bukkit key is {@code shulker:<name>}
     * (the dimension vanilla commands see) and its Bukkit name {@code shulker_<name>}, except
     * for worlds taken from the pool, which keep {@code shulker:pool/<n>}.
     *
     * @param name Shulk World name
     * @return the world, or null when it is not loaded
     */
    @Nullable World world(String name);

    /**
     * The Shulk World name of a loaded Shulk World.
     *
     * @param world any world
     * @return its Shulk World name, or null for other worlds
     */
    @Nullable String name(World world);

    /**
     * Every loaded Shulk World.
     *
     * @return the loaded Shulk Worlds
     */
    Collection<World> worlds();

    /** Set by the server on startup. */
    @ApiStatus.Internal
    final class Holder {
        private static volatile @Nullable ShulkWorlds instance;

        private Holder() {
        }

        /**
         * Installs the server's manager.
         *
         * @param worlds the manager
         */
        @ApiStatus.Internal
        public static void set(final ShulkWorlds worlds) {
            instance = worlds;
        }
    }
}
