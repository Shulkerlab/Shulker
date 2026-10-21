package dev.shulker.api.world;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Where Shulk Worlds are read from and written to, by name. The server ships a loader for the
 * {@code shulker-worlds/} folder ({@link ShulkWorlds#fileLoader()}); plugins register their own
 * for databases or object storage ({@link ShulkWorlds#registerLoader}).
 *
 * <p>Methods are called off the main thread and may block. A {@link #write} must replace the
 * stored world atomically: a reader sees either the old bytes or the new ones, never a mix.
 */
public interface ShulkWorldLoader {

    /**
     * Reads a stored world.
     *
     * @param name world name
     * @return the bytes of the world-layout {@code .shulk} file; fails if the world does not exist
     */
    CompletableFuture<byte[]> read(String name);

    /**
     * Stores a world, replacing any previous version atomically.
     *
     * @param name world name
     * @param data the bytes of a world-layout {@code .shulk} file
     * @return completes once the data is durably stored
     */
    CompletableFuture<Void> write(String name, byte[] data);

    /**
     * Checks whether a world is stored.
     *
     * @param name world name
     * @return whether the world exists
     */
    CompletableFuture<Boolean> exists(String name);

    /**
     * Deletes a stored world. Deleting a world that does not exist succeeds.
     *
     * @param name world name
     * @return completes once the world is gone
     */
    CompletableFuture<Void> delete(String name);

    /**
     * Lists the stored worlds.
     *
     * @return the names of every stored world
     */
    CompletableFuture<List<String>> list();
}
