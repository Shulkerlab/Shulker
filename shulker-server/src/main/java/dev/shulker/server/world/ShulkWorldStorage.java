package dev.shulker.server.world;

import dev.shulker.format.ShulkWorld;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;

/**
 * Which dimension folders belong to loaded Shulk Worlds. Region storage looks a folder up here
 * on every region it opens; a hit means the chunks live in memory, not in the folder.
 */
final class ShulkWorldStorage {

    /** A registered world: its chunks, and whether empty chunks are dropped instead of stored. */
    record Registered(ShulkWorld world, boolean dropEmpty) {
    }

    /** Dimension folder (absolute, normalized) -> the world's chunks. */
    private static final Map<Path, Registered> WORLDS = new ConcurrentHashMap<>();

    private ShulkWorldStorage() {
    }

    static void register(final Path dimensionFolder, final ShulkWorld world, final boolean dropEmpty) {
        WORLDS.put(key(dimensionFolder), new Registered(world, dropEmpty));
    }

    static void unregister(final Path dimensionFolder) {
        WORLDS.remove(key(dimensionFolder));
    }

    /** The Shulk World whose {@code region/}, {@code entities/} or {@code poi/} folder this is. */
    static @Nullable Registered forRegionFolder(final @Nullable Path regionFolder) {
        if (WORLDS.isEmpty() || regionFolder == null || regionFolder.getParent() == null) {
            return null;
        }
        return WORLDS.get(key(regionFolder.getParent()));
    }

    private static Path key(final Path path) {
        return path.toAbsolutePath().normalize();
    }
}
