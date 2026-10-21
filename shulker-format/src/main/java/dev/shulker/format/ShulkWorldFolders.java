package dev.shulker.format;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;

/**
 * Moves worlds between a world folder (region files plus small files) and the world layout of
 * {@code .shulk} ({@link ShulkWorld}).
 *
 * <p>A folder holds one dimension: its {@code region/}, {@code entities/} and {@code poi/}
 * folders sit side by side somewhere below the world root (directly in older layouts, under
 * {@code dimensions/<namespace>/<name>/} in current ones). That location is kept in the
 * {@link #REGION_DIR} property so an export puts the regions back where Minecraft looks for them.
 * Region files may be {@code .mca} or region {@code .shulk}; when both hold a chunk the
 * {@code .shulk} copy wins, as in {@link WorldConverter}.
 */
public final class ShulkWorldFolders {

    /** Folder (relative to the world root) holding region/, entities/ and poi/; "" for the root. */
    public static final String REGION_DIR = "shulker.region-dir";

    private static final int LEVEL = ShulkWorld.DEFAULT_LEVEL;
    private static final Set<String> REGION_FOLDERS = Set.of("region", "entities", "poi");
    /** Files that describe a running server, not the world. */
    private static final Set<String> SKIPPED_FILES = Set.of("session.lock");

    public enum RegionFormat { SHULK, MCA }

    private ShulkWorldFolders() {
    }

    /** Reads a whole world folder into memory. The folder is not modified. */
    public static ShulkWorld importFolder(final Path root) throws IOException {
        if (!Files.isDirectory(root)) {
            throw new IOException(root + " is not a directory");
        }
        final ShulkWorld world = new ShulkWorld();
        final List<Path> mca = new ArrayList<>();
        final List<Path> shulk = new ArrayList<>();
        @Nullable String regionDir = null;
        try (Stream<Path> walk = Files.walk(root, 8)) {
            for (Path path : (Iterable<Path>) walk.filter(Files::isRegularFile).sorted()::iterator) {
                final String relative = relative(root, path);
                if (isRegionFile(path)) {
                    final String dir = parentOf(relative(root, path.getParent()));
                    if (regionDir != null && !regionDir.equals(dir)) {
                        throw new IOException(root + " holds more than one dimension (" + display(regionDir) + " and " + display(dir)
                            + "); a world .shulk file stores one. Import each dimension folder on its own.");
                    }
                    regionDir = dir;
                    (path.getFileName().toString().endsWith(McaFile.EXTENSION) ? mca : shulk).add(path);
                } else if (!skipped(path)) {
                    world.putFile(relative, Files.readAllBytes(path));
                }
            }
        }
        for (Path file : mca) {
            final RegionKind kind = WorldConverter.kindOf(file);
            final int[] region = McaFile.regionCoords(file);
            final McaFile.Contents contents = McaFile.read(file);
            for (int i = 0; i < ShulkRegion.CHUNKS; i++) {
                final byte[] raw = contents.chunks()[i];
                if (raw != null) {
                    world.write(kind, chunkX(region, i), chunkZ(region, i), raw, contents.timestamps()[i], LEVEL);
                }
            }
        }
        for (Path file : shulk) { // after .mca: the .shulk copy of a chunk is the newer one
            final RegionKind kind = WorldConverter.kindOf(file);
            final int[] region = McaFile.regionCoords(file);
            try (ShulkRegion source = ShulkRegion.open(file, kind, false)) {
                for (int i = 0; i < ShulkRegion.CHUNKS; i++) {
                    final byte[] raw = source.read(i);
                    if (raw != null) {
                        world.write(kind, chunkX(region, i), chunkZ(region, i), raw, source.timestamp(i), LEVEL);
                    }
                }
            }
        }
        world.setProperty(REGION_DIR, regionDir == null ? "" : regionDir);
        return world;
    }

    /**
     * Writes a world out as a normal world folder: its files, and its chunks as region files in
     * the requested format. Fails rather than overwrite a non-empty folder.
     */
    public static void exportFolder(final ShulkWorld world, final Path root, final RegionFormat format) throws IOException {
        if (Files.isDirectory(root)) {
            try (Stream<Path> existing = Files.list(root)) {
                if (existing.findAny().isPresent()) {
                    throw new IOException(root + " is not empty");
                }
            }
        }
        Files.createDirectories(root);
        writeFiles(world, root);
        final String dir = world.property(REGION_DIR);
        final Path regionRoot = dir == null || dir.isEmpty() ? root : root.resolve(ShulkWorld.normalize(dir));
        for (RegionKind kind : List.of(RegionKind.CHUNK, RegionKind.ENTITIES, RegionKind.POI)) {
            final Path folder = regionRoot.resolve(folderName(kind));
            for (Map.Entry<Long, List<Long>> region : byRegion(world.chunkKeys(kind)).entrySet()) {
                final int regionX = ShulkWorld.keyX(region.getKey());
                final int regionZ = ShulkWorld.keyZ(region.getKey());
                Files.createDirectories(folder);
                final String base = "r." + regionX + "." + regionZ;
                if (format == RegionFormat.SHULK) {
                    try (ShulkRegion out = ShulkRegion.open(folder.resolve(base + ShulkRegion.EXTENSION), kind, false)) {
                        for (long key : region.getValue()) {
                            final int x = ShulkWorld.keyX(key);
                            final int z = ShulkWorld.keyZ(key);
                            final byte[] raw = world.read(kind, x, z);
                            if (raw != null) {
                                out.write(ShulkRegion.index(x, z), raw, world.timestamp(kind, x, z), LEVEL);
                            }
                        }
                    }
                } else {
                    final byte[][] chunks = new byte[ShulkRegion.CHUNKS][];
                    final int[] timestamps = new int[ShulkRegion.CHUNKS];
                    for (long key : region.getValue()) {
                        final int x = ShulkWorld.keyX(key);
                        final int z = ShulkWorld.keyZ(key);
                        chunks[ShulkRegion.index(x, z)] = world.read(kind, x, z);
                        timestamps[ShulkRegion.index(x, z)] = world.timestamp(kind, x, z);
                    }
                    McaFile.write(folder.resolve(base + McaFile.EXTENSION), new McaFile.Contents(chunks, timestamps));
                }
            }
        }
    }

    /** Writes only the world's small files (level.dat, data/...) below {@code root}. */
    public static void writeFiles(final ShulkWorld world, final Path root) throws IOException {
        for (String path : world.filePaths()) {
            final byte[] contents = world.file(path);
            if (contents == null) {
                continue; // removed meanwhile
            }
            final Path target = root.resolve(path);
            Files.createDirectories(target.getParent());
            Files.write(target, contents);
        }
    }

    /**
     * Replaces the world's small files with those currently below {@code root}, skipping region
     * files and server lock files. Used to pick up level.dat and data/ after the server saved them.
     */
    public static void readFiles(final ShulkWorld world, final Path root) throws IOException {
        final Map<String, byte[]> current = new HashMap<>();
        try (Stream<Path> walk = Files.walk(root, 8)) {
            for (Path path : (Iterable<Path>) walk.filter(Files::isRegularFile)::iterator) {
                if (!isRegionFile(path) && !skipped(path)) {
                    current.put(relative(root, path), Files.readAllBytes(path));
                }
            }
        }
        for (String old : world.filePaths()) {
            if (!current.containsKey(old)) {
                world.removeFile(old);
            }
        }
        for (Map.Entry<String, byte[]> file : current.entrySet()) {
            final byte[] old = world.file(file.getKey());
            if (old == null || !java.util.Arrays.equals(old, file.getValue())) {
                world.putFile(file.getKey(), file.getValue());
            }
        }
    }

    // ---- helpers ---------------------------------------------------------------------------

    private static Map<Long, List<Long>> byRegion(final Set<Long> keys) {
        final Map<Long, List<Long>> regions = new HashMap<>();
        for (long key : keys) {
            final long region = ((long) (ShulkWorld.keyX(key) >> 5) << 32) | ((ShulkWorld.keyZ(key) >> 5) & 0xFFFFFFFFL);
            regions.computeIfAbsent(region, k -> new ArrayList<>()).add(key);
        }
        return regions;
    }

    private static int chunkX(final int[] region, final int index) {
        return (region[0] << 5) + (index & 31);
    }

    private static int chunkZ(final int[] region, final int index) {
        return (region[1] << 5) + (index >> 5);
    }

    private static String folderName(final RegionKind kind) {
        return switch (kind) {
            case CHUNK -> "region";
            case ENTITIES -> "entities";
            case POI -> "poi";
            case OTHER -> throw new IllegalArgumentException("no folder for " + kind);
        };
    }

    static boolean isRegionFile(final Path path) {
        final String name = path.getFileName().toString();
        final Path parent = path.getParent();
        return parent != null && REGION_FOLDERS.contains(parent.getFileName().toString()) && name.startsWith("r.")
            && (name.endsWith(McaFile.EXTENSION) || name.endsWith(ShulkRegion.EXTENSION)) && name.split("\\.").length == 4;
    }

    private static boolean skipped(final Path path) {
        final String name = path.getFileName().toString();
        return SKIPPED_FILES.contains(name) || name.endsWith(".tmp") || name.endsWith("_old");
    }

    private static String relative(final Path root, final Path path) {
        return root.relativize(path).toString().replace('\\', '/');
    }

    private static String parentOf(final String relative) {
        final int slash = relative.lastIndexOf('/');
        return slash < 0 ? "" : relative.substring(0, slash);
    }

    private static String display(final String dir) {
        return dir.isEmpty() ? "the world root" : dir;
    }
}
