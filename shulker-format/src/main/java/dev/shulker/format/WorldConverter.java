package dev.shulker.format;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * Converts a whole world between {@code .mca} and {@code .shulk} while the server is stopped.
 *
 * <pre>java -cp .shulker/boot.jar dev.shulker.format.WorldConverter &lt;world dir&gt; shulk|mca|verify</pre>
 *
 * <p>Every region is written, read back and compared chunk by chunk before its source file is
 * deleted, so an interrupted run leaves both files and can simply be run again. When both
 * formats exist for a region (a world that was migrating), the {@code .shulk} copy of a chunk
 * wins: it is the newer one.
 */
public final class WorldConverter {

    public enum Target { SHULK, MCA }

    public record Result(int regions, int chunks, long bytesBefore, long bytesAfter) {
    }

    private static final int LEVEL = 6;

    private WorldConverter() {
    }

    public static void main(final String[] args) throws IOException {
        if (args.length != 2) {
            System.err.println("usage: WorldConverter <world dir> shulk|mca|verify");
            System.exit(2);
        }
        final Path world = Path.of(args[0]);
        if (args[1].equalsIgnoreCase("verify")) {
            final Verification check = verify(world);
            System.out.printf(Locale.ROOT, "Checked %d .shulk regions, %d chunks: %d unreadable, %d damaged entries%n",
                check.regions(), check.chunks(), check.unreadable(), check.damagedEntries());
            System.exit(check.ok() ? 0 : 1);
        }
        final Target target = Target.valueOf(args[1].toUpperCase(Locale.ROOT));
        final Result result = convert(world, target, System.out::println);
        System.out.printf(Locale.ROOT, "Converted %d regions, %d chunks: %.1f MiB -> %.1f MiB%n",
            result.regions(), result.chunks(), result.bytesBefore() / 1048576.0, result.bytesAfter() / 1048576.0);
    }

    public static Result convert(final Path world, final Target target, final Consumer<String> log) throws IOException {
        if (!Files.isDirectory(world)) {
            throw new IOException(world + " is not a directory");
        }
        final Path lockFile = world.resolve("session.lock");
        try (FileChannel lockChannel = Files.exists(lockFile) ? FileChannel.open(lockFile, StandardOpenOption.WRITE) : null) {
            final FileLock lock = lockChannel == null ? null : tryLock(lockChannel);
            if (lockChannel != null && lock == null) {
                throw new IOException(world + " is in use: stop the server before converting it");
            }
            final String sourceExtension = target == Target.SHULK ? McaFile.EXTENSION : ShulkRegion.EXTENSION;
            final List<Path> sources;
            try (Stream<Path> files = Files.walk(world, 8)) {
                sources = files.filter(p -> isRegionFile(p, sourceExtension)).sorted().toList();
            }
            int regions = 0;
            int chunks = 0;
            long before = 0;
            long after = 0;
            for (Path source : sources) {
                final Path destination = sibling(source, target == Target.SHULK ? ShulkRegion.EXTENSION : McaFile.EXTENSION);
                before += Files.size(source) + (Files.exists(destination) ? Files.size(destination) : 0);
                chunks += target == Target.SHULK ? toShulk(source, destination) : toMca(source, destination);
                after += Files.size(destination);
                regions++;
                if (regions % 100 == 0) {
                    log.accept("  " + regions + " / " + sources.size() + " regions");
                }
            }
            return new Result(regions, chunks, before, after);
        }
    }

    public record Verification(int regions, int chunks, int unreadable, int damagedEntries) {
        public boolean ok() {
            return this.unreadable == 0 && this.damagedEntries == 0;
        }
    }

    /** Decompresses every chunk of every .shulk region (checking each frame's checksum). */
    public static Verification verify(final Path world) throws IOException {
        final List<Path> files;
        try (Stream<Path> walk = Files.walk(world, 8)) {
            files = walk.filter(p -> isRegionFile(p, ShulkRegion.EXTENSION)).sorted().toList();
        }
        int chunks = 0;
        int unreadable = 0;
        int damaged = 0;
        for (Path file : files) {
            try (ShulkRegion region = ShulkRegion.open(file, kindOf(file), false)) {
                damaged += region.damagedEntries();
                for (int i = 0; i < ShulkRegion.CHUNKS; i++) {
                    if (!region.has(i)) {
                        continue;
                    }
                    chunks++;
                    try {
                        region.read(i);
                    } catch (IOException | RuntimeException e) {
                        unreadable++;
                    }
                }
            }
        }
        return new Verification(files.size(), chunks, unreadable, damaged);
    }

    private static FileLock tryLock(final FileChannel channel) throws IOException {
        try {
            return channel.tryLock();
        } catch (OverlappingFileLockException e) {
            return null;
        }
    }

    private static int toShulk(final Path mca, final Path shulk) throws IOException {
        final McaFile.Contents contents = McaFile.read(mca);
        final RegionKind kind = kindOf(mca);
        final boolean[] copied = new boolean[ShulkRegion.CHUNKS];
        try (ShulkRegion region = ShulkRegion.open(shulk, kind, false)) {
            for (int i = 0; i < ShulkRegion.CHUNKS; i++) {
                final byte[] raw = contents.chunks()[i];
                if (raw != null && !region.has(i)) { // an existing .shulk chunk is newer
                    region.write(i, raw, contents.timestamps()[i], LEVEL);
                    copied[i] = true;
                }
            }
        }
        try (ShulkRegion region = ShulkRegion.open(shulk, kind, false)) {
            for (int i = 0; i < ShulkRegion.CHUNKS; i++) {
                final boolean ok = copied[i] ? Arrays.equals(contents.chunks()[i], region.read(i)) : contents.chunks()[i] == null || region.has(i);
                if (!ok) {
                    throw new IOException("Verification failed for " + shulk + " chunk " + i + "; " + mca + " was kept");
                }
            }
        }
        McaFile.delete(mca);
        return contents.count();
    }

    private static int toMca(final Path shulk, final Path mca) throws IOException {
        final McaFile.Contents existing = Files.exists(mca) ? McaFile.read(mca) : new McaFile.Contents(new byte[ShulkRegion.CHUNKS][], new int[ShulkRegion.CHUNKS]);
        final byte[][] chunks = existing.chunks().clone();
        final int[] timestamps = existing.timestamps().clone();
        try (ShulkRegion region = ShulkRegion.open(shulk, kindOf(shulk), false)) {
            for (int i = 0; i < ShulkRegion.CHUNKS; i++) {
                final byte[] raw = region.read(i);
                if (raw != null) {
                    chunks[i] = raw;
                    timestamps[i] = region.timestamp(i);
                }
            }
        }
        final McaFile.Contents merged = new McaFile.Contents(chunks, timestamps);
        McaFile.write(mca, merged);
        final McaFile.Contents check = McaFile.read(mca);
        for (int i = 0; i < ShulkRegion.CHUNKS; i++) {
            if (!Arrays.equals(chunks[i], check.chunks()[i])) {
                throw new IOException("Verification failed for " + mca + " chunk " + i + "; " + shulk + " was kept");
            }
        }
        Files.delete(shulk);
        return merged.count();
    }

    private static boolean isRegionFile(final Path path, final String extension) {
        final String name = path.getFileName().toString();
        return name.startsWith("r.") && name.endsWith(extension) && name.split("\\.").length == 4 && Files.isRegularFile(path);
    }

    private static Path sibling(final Path path, final String extension) {
        final String name = path.getFileName().toString();
        return path.resolveSibling(name.substring(0, name.lastIndexOf('.')) + extension);
    }

    /** Region folders are named region/, entities/ and poi/ in every world layout. */
    static RegionKind kindOf(final Path regionFile) {
        return RegionKind.fromFolderName(regionFile.getParent().getFileName().toString());
    }
}
