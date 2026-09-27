package dev.shulker.format;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;
import org.jspecify.annotations.Nullable;

/**
 * Minimal reader and writer for vanilla Anvil {@code .mca} region files, used by the offline
 * world converter. Chunks are exposed as uncompressed NBT bytes, like {@link ShulkRegion}.
 *
 * <p>Layout: 4 KiB of locations ({@code sector << 8 | sectorCount}), 4 KiB of timestamps, then
 * 4 KiB sectors holding {@code [u32 length][u8 compression][payload]}. Compression 1 = gzip,
 * 2 = zlib, 3 = none, 4 = LZ4; +128 means the payload lives in a {@code c.X.Z.mcc} file.
 */
public final class McaFile {

    public static final String EXTENSION = ".mca";

    private static final int SECTOR = 4096;
    private static final int EXTERNAL = 128;
    private static final int GZIP = 1;
    private static final int ZLIB = 2;
    private static final int NONE = 3;
    private static final int LZ4 = 4;

    private McaFile() {
    }

    /** All chunks of a region: raw NBT (null = absent) and timestamps, indexed like ShulkRegion. */
    public record Contents(byte @Nullable [][] chunks, int[] timestamps) {
        public int count() {
            int count = 0;
            for (byte[] chunk : this.chunks) {
                if (chunk != null) {
                    count++;
                }
            }
            return count;
        }
    }

    public static Contents read(final Path path) throws IOException {
        final byte[] file = Files.readAllBytes(path);
        final ByteBuffer buffer = ByteBuffer.wrap(file);
        final byte[][] chunks = new byte[ShulkRegion.CHUNKS][];
        final int[] timestamps = new int[ShulkRegion.CHUNKS];
        if (file.length < 2 * SECTOR) {
            return new Contents(chunks, timestamps); // empty or truncated file: no chunks
        }
        final int[] coords = regionCoords(path);
        for (int i = 0; i < ShulkRegion.CHUNKS; i++) {
            final int location = buffer.getInt(i * 4);
            if (location == 0) {
                continue;
            }
            timestamps[i] = buffer.getInt(SECTOR + i * 4);
            final long start = (long) (location >>> 8) * SECTOR;
            if (start + 5 > file.length) {
                continue; // points past the end: vanilla treats this as missing too
            }
            final int length = buffer.getInt((int) start);
            final int type = Byte.toUnsignedInt(buffer.get((int) start + 4));
            final byte[] payload;
            if ((type & EXTERNAL) != 0) {
                final int chunkX = (coords[0] << 5) + (i & 31);
                final int chunkZ = (coords[1] << 5) + (i >> 5);
                payload = Files.readAllBytes(path.resolveSibling("c." + chunkX + "." + chunkZ + ".mcc"));
            } else {
                if (length <= 1 || start + 4 + length > file.length) {
                    continue;
                }
                payload = new byte[length - 1];
                System.arraycopy(file, (int) start + 5, payload, 0, length - 1);
            }
            chunks[i] = decompress(type & ~EXTERNAL, payload);
        }
        return new Contents(chunks, timestamps);
    }

    /** Writes a region with zlib, like vanilla's default, moving chunks over 1 MiB to .mcc files. */
    public static void write(final Path path, final Contents contents) throws IOException {
        final int[] coords = regionCoords(path);
        final ByteArrayOutputStream data = new ByteArrayOutputStream();
        final ByteBuffer header = ByteBuffer.allocate(2 * SECTOR);
        int sector = 2;
        for (int i = 0; i < ShulkRegion.CHUNKS; i++) {
            final byte[] raw = contents.chunks()[i];
            if (raw == null) {
                continue;
            }
            final byte[] compressed = zlib(raw);
            final ByteBuffer entry;
            if (compressed.length + 5 > 255 * SECTOR) {
                final int chunkX = (coords[0] << 5) + (i & 31);
                final int chunkZ = (coords[1] << 5) + (i >> 5);
                Files.write(path.resolveSibling("c." + chunkX + "." + chunkZ + ".mcc"), compressed);
                entry = ByteBuffer.allocate(5).putInt(1).put((byte) (ZLIB | EXTERNAL));
            } else {
                entry = ByteBuffer.allocate(5 + compressed.length).putInt(compressed.length + 1).put((byte) ZLIB).put(compressed);
            }
            final int sectors = (entry.capacity() + SECTOR - 1) / SECTOR;
            header.putInt(i * 4, (sector << 8) | sectors);
            header.putInt(SECTOR + i * 4, contents.timestamps()[i]);
            data.write(entry.array());
            data.write(new byte[sectors * SECTOR - entry.capacity()]); // pad to a whole sector
            sector += sectors;
        }
        final Path temp = path.resolveSibling(path.getFileName() + ".tmp");
        try (var out = Files.newOutputStream(temp)) {
            out.write(header.array());
            data.writeTo(out);
        }
        Files.move(temp, path, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
    }

    /** Deletes a region and any external chunk files that belong to it. */
    public static void delete(final Path path) throws IOException {
        final int[] coords = regionCoords(path);
        for (int i = 0; i < ShulkRegion.CHUNKS; i++) {
            final int chunkX = (coords[0] << 5) + (i & 31);
            final int chunkZ = (coords[1] << 5) + (i >> 5);
            Files.deleteIfExists(path.resolveSibling("c." + chunkX + "." + chunkZ + ".mcc"));
        }
        Files.deleteIfExists(path);
    }

    static int[] regionCoords(final Path path) {
        final String[] parts = path.getFileName().toString().split("\\.");
        return new int[] {Integer.parseInt(parts[1]), Integer.parseInt(parts[2])};
    }

    private static byte[] decompress(final int type, final byte[] payload) throws IOException {
        return switch (type) {
            case GZIP -> readAll(new GZIPInputStream(new ByteArrayInputStream(payload)));
            case ZLIB -> readAll(new InflaterInputStream(new ByteArrayInputStream(payload)));
            case NONE -> payload;
            case LZ4 -> Lz4.decompress(payload);
            default -> throw new IOException("Unknown .mca chunk compression " + type);
        };
    }

    private static byte[] readAll(final InputStream in) throws IOException {
        try (in) {
            return in.readAllBytes();
        }
    }

    private static byte[] zlib(final byte[] raw) throws IOException {
        final ByteArrayOutputStream out = new ByteArrayOutputStream(raw.length / 4);
        try (DeflaterOutputStream deflate = new DeflaterOutputStream(out)) {
            deflate.write(raw);
        }
        return out.toByteArray();
    }

    /** LZ4 needs lz4-java, which the server ships; loaded reflectively so tests do not need it. */
    private static final class Lz4 {
        static byte[] decompress(final byte[] payload) throws IOException {
            try {
                final Class<?> stream = Class.forName("net.jpountz.lz4.LZ4BlockInputStream");
                return readAll((InputStream) stream.getConstructor(InputStream.class).newInstance(new ByteArrayInputStream(payload)));
            } catch (ReflectiveOperationException e) {
                throw new IOException("LZ4 region compression needs lz4-java on the classpath", e);
            }
        }
    }
}
