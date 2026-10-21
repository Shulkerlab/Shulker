package dev.shulker.server.world;

import com.mojang.logging.LogUtils;
import dev.shulker.format.RegionKind;
import dev.shulker.format.ShulkRegion;
import dev.shulker.server.ShulkerConfig;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;
import net.minecraft.world.level.chunk.storage.RegionFile;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * Decides, per region, whether RegionFileStorage gets a vanilla {@code .mca} RegionFile or a
 * {@link ShulkRegionFile}. RegionFileStorage keeps asking for {@code r.X.Z.mca} paths; this class
 * maps them to the {@code .shulk} sibling where needed.
 *
 * <ul>
 *   <li>An existing .shulk file is always used (so switching back to mca never hides data).</li>
 *   <li>Otherwise the world's format decides: {@code shulk} worlds create .shulk files and pull
 *       chunks over from an existing .mca file as they are saved; {@code mca} worlds stay
 *       vanilla.</li>
 *   <li>Region folders of a loaded Shulk World ({@link ShulkWorldStorage}) never touch the disk:
 *       they get a {@link ShulkWorldRegionFile} over the world's in-memory chunks.</li>
 *   <li>{@code storage.format: auto} makes new worlds (no .mca files anywhere) use .shulk and
 *       leaves existing .mca worlds alone.</li>
 * </ul>
 */
public final class ShulkRegionFiles {

    private static final Logger LOGGER = LogUtils.getClassLogger();
    private static final String MCA = ".mca";

    /** World root -> whether it stores new regions as .shulk. */
    private static final Map<Path, Boolean> WORLD_FORMAT = new ConcurrentHashMap<>();

    private ShulkRegionFiles() {
    }

    public static boolean exists(final Path mcaPath) {
        final ShulkWorldStorage.@Nullable Registered world = ShulkWorldStorage.forRegionFolder(mcaPath.getParent());
        if (world != null) {
            final int[] coords = regionCoords(mcaPath);
            return world.world().hasRegion(RegionKind.fromFolderName(mcaPath.getParent().getFileName().toString()), coords[0], coords[1]);
        }
        return Files.exists(mcaPath) || Files.exists(shulkPath(mcaPath));
    }

    public static RegionFile open(final RegionStorageInfo info, final Path mcaPath, final Path folder, final boolean sync) throws IOException {
        final ShulkWorldStorage.@Nullable Registered world = ShulkWorldStorage.forRegionFolder(folder);
        if (world != null) {
            return new ShulkWorldRegionFile(info, shulkPath(mcaPath), world.world(), world.dropEmpty()); // a Shulk World: chunks live in memory
        }
        final Path shulkPath = shulkPath(mcaPath);
        final boolean hasShulk = Files.exists(shulkPath);
        final boolean hasMca = Files.exists(mcaPath);
        if (!hasShulk && !usesShulk(folder)) {
            return new RegionFile(info, mcaPath, folder, sync);
        }
        final int[] coords = regionCoords(mcaPath);
        final RegionFile legacy = hasMca ? new RegionFile(info, mcaPath, folder, sync) : null;
        try {
            return new ShulkRegionFile(info, shulkPath, coords[0], coords[1], sync, legacy, hasMca ? mcaPath : null);
        } catch (IOException | RuntimeException e) {
            if (legacy != null) {
                legacy.close();
            }
            throw e;
        }
    }

    static Path shulkPath(final Path mcaPath) {
        final String name = mcaPath.getFileName().toString();
        if (!name.endsWith(MCA)) {
            throw new IllegalArgumentException("Not a region file path: " + mcaPath);
        }
        return mcaPath.resolveSibling(name.substring(0, name.length() - MCA.length()) + ShulkRegion.EXTENSION);
    }

    /** "r.-3.7.mca" -> {-3, 7}. */
    static int[] regionCoords(final Path path) {
        final String[] parts = path.getFileName().toString().split("\\.");
        return new int[] {Integer.parseInt(parts[1]), Integer.parseInt(parts[2])};
    }

    private static boolean usesShulk(final Path regionFolder) {
        return switch (ShulkerConfig.storageFormat.toLowerCase(Locale.ROOT)) {
            case "shulk" -> true;
            case "mca" -> false;
            default -> WORLD_FORMAT.computeIfAbsent(worldRoot(regionFolder), ShulkRegionFiles::isNewOrShulkWorld);
        };
    }

    /** A world with no .mca region files anywhere is new (or already .shulk): store it as .shulk. */
    private static boolean isNewOrShulkWorld(final Path root) {
        try (Stream<Path> files = Files.walk(root, 6)) {
            final boolean hasMca = files.anyMatch(p -> p.getFileName().toString().endsWith(MCA));
            LOGGER.info("World {} stores regions as {}", root.getFileName(), hasMca ? ".mca (existing world; set storage.format: shulk in shulker.yml to migrate)" : ".shulk");
            return !hasMca;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The directory holding level.dat above a region folder, or the folder's parent. */
    private static Path worldRoot(final Path regionFolder) {
        final Path absolute = regionFolder.toAbsolutePath().normalize();
        for (@Nullable Path dir = absolute; dir != null; dir = dir.getParent()) {
            if (Files.exists(dir.resolve("level.dat"))) {
                return dir;
            }
        }
        return absolute.getParent() != null ? absolute.getParent() : absolute;
    }
}
