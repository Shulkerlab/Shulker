package dev.shulker.format;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ShulkRegionTest {

    @TempDir
    Path dir;

    /** Compressible, chunk-like bytes: repeated NBT-ish text with some noise. */
    private static byte[] chunk(final int seed, final int size) {
        final Random random = new Random(seed);
        final StringBuilder text = new StringBuilder();
        while (text.length() < size) {
            text.append("minecraft:").append(random.nextBoolean() ? "stone" : "deepslate").append(random.nextInt(8)).append(';');
        }
        return text.substring(0, size).getBytes(StandardCharsets.UTF_8);
    }

    private Path file() {
        return this.dir.resolve("r.0.0" + ShulkRegion.EXTENSION);
    }

    @Test
    void roundTripAllChunksAcrossReopen() throws IOException {
        try (ShulkRegion region = ShulkRegion.open(this.file(), RegionKind.CHUNK, false)) {
            for (int i = 0; i < ShulkRegion.CHUNKS; i++) {
                region.write(i, chunk(i, 2000 + i * 7), 1000 + i, 6);
            }
            assertEquals(ShulkRegion.CHUNKS, region.chunkCount());
        }
        try (ShulkRegion region = ShulkRegion.open(this.file(), RegionKind.CHUNK, false)) {
            for (int i = 0; i < ShulkRegion.CHUNKS; i++) {
                assertArrayEquals(chunk(i, 2000 + i * 7), region.read(i), "chunk " + i);
                assertEquals(1000 + i, region.timestamp(i));
            }
            assertEquals(0, region.damagedEntries());
        }
    }

    @Test
    void absentChunks() throws IOException {
        try (ShulkRegion region = ShulkRegion.open(this.file(), RegionKind.CHUNK, false)) {
            assertFalse(region.has(5));
            assertNull(region.read(5));
            assertEquals(0, region.timestamp(5));
        }
    }

    @Test
    void everyKindRoundTripsWithItsDictionary() throws IOException {
        for (RegionKind kind : RegionKind.values()) {
            final Path path = this.dir.resolve(kind + ShulkRegion.EXTENSION);
            try (ShulkRegion region = ShulkRegion.open(path, kind, true)) {
                region.write(3, chunk(3, 50_000), 1, 6);
            }
            try (ShulkRegion region = ShulkRegion.open(path, kind, true)) {
                assertArrayEquals(chunk(3, 50_000), region.read(3), kind.toString());
            }
        }
    }

    @Test
    void rewritesReuseFreedSpace() throws IOException {
        try (ShulkRegion region = ShulkRegion.open(this.file(), RegionKind.CHUNK, false)) {
            for (int i = 0; i < 64; i++) {
                region.write(i, chunk(i, 20_000), 1, 6);
            }
            final long size = region.fileSize();
            // Rewrite everything many times with similar sizes: the file must not keep growing.
            for (int round = 0; round < 10; round++) {
                for (int i = 0; i < 64; i++) {
                    region.write(i, chunk(i * 31 + round, 20_000), 2, 6);
                }
            }
            assertTrue(region.fileSize() < size * 1.5, region.fileSize() + " vs " + size);
            for (int i = 0; i < 64; i++) {
                assertArrayEquals(chunk(i * 31 + 9, 20_000), region.read(i));
            }
        }
    }

    @Test
    void deleteThenReopen() throws IOException {
        try (ShulkRegion region = ShulkRegion.open(this.file(), RegionKind.CHUNK, false)) {
            region.write(1, chunk(1, 5000), 1, 6);
            region.write(2, chunk(2, 5000), 1, 6);
            region.delete(1);
            assertFalse(region.has(1));
        }
        try (ShulkRegion region = ShulkRegion.open(this.file(), RegionKind.CHUNK, false)) {
            assertFalse(region.has(1));
            assertArrayEquals(chunk(2, 5000), region.read(2));
        }
    }

    @Test
    void closeCompactsWastefulFiles() throws IOException {
        try (ShulkRegion region = ShulkRegion.open(this.file(), RegionKind.OTHER, false)) {
            for (int i = 0; i < 200; i++) {
                region.write(i, randomBytes(i, 8000), 1, 1); // incompressible: sizes are predictable
            }
            for (int i = 0; i < 200; i += 2) {
                region.delete(i); // punch holes in the middle of the file
            }
        }
        final long compacted = Files.size(this.file());
        assertTrue(compacted < ShulkRegion.HEADER_SIZE + 100 * 8100L, "size " + compacted);
        try (ShulkRegion region = ShulkRegion.open(this.file(), RegionKind.OTHER, false)) {
            assertEquals(100, region.chunkCount());
            for (int i = 1; i < 200; i += 2) {
                assertArrayEquals(randomBytes(i, 8000), region.read(i));
            }
        }
        assertFalse(Files.exists(this.dir.resolve("r.0.0.shulk.compact")));
    }

    @Test
    void corruptEntryLosesOnlyThatChunk() throws IOException {
        try (ShulkRegion region = ShulkRegion.open(this.file(), RegionKind.CHUNK, false)) {
            region.write(7, chunk(7, 3000), 1, 6);
            region.write(8, chunk(8, 3000), 1, 6);
        }
        flipByte(this.file(), 16L + 7L * ShulkRegion.ENTRY_SIZE + 9); // inside entry 7
        try (ShulkRegion region = ShulkRegion.open(this.file(), RegionKind.CHUNK, false)) {
            assertEquals(1, region.damagedEntries());
            assertFalse(region.has(7));
            assertArrayEquals(chunk(8, 3000), region.read(8));
        }
    }

    @Test
    void corruptDataIsDetectedByTheFrameChecksum() throws IOException {
        long dataOffset;
        try (ShulkRegion region = ShulkRegion.open(this.file(), RegionKind.OTHER, false)) {
            region.write(0, chunk(0, 3000), 1, 6);
            dataOffset = ShulkRegion.HEADER_SIZE;
        }
        flipByte(this.file(), dataOffset + 20);
        try (ShulkRegion region = ShulkRegion.open(this.file(), RegionKind.OTHER, false)) {
            assertThrows(RuntimeException.class, () -> region.read(0));
        }
    }

    @Test
    void interruptedWriteKeepsTheOldChunk() throws IOException {
        try (ShulkRegion region = ShulkRegion.open(this.file(), RegionKind.CHUNK, false)) {
            region.write(4, chunk(4, 3000), 1, 6);
        }
        // A crash after the new data but before the entry: garbage appended, entry untouched.
        try (FileChannel channel = FileChannel.open(this.file(), StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
            channel.write(ByteBuffer.wrap(randomBytes(99, 4000)));
        }
        try (ShulkRegion region = ShulkRegion.open(this.file(), RegionKind.CHUNK, false)) {
            assertArrayEquals(chunk(4, 3000), region.read(4));
            region.write(5, chunk(5, 3000), 1, 6); // and the orphaned bytes are reused/trimmed
        }
        try (ShulkRegion region = ShulkRegion.open(this.file(), RegionKind.CHUNK, false)) {
            assertArrayEquals(chunk(5, 3000), region.read(5));
        }
    }

    @Test
    void recompressKeepsDataAndTimestamp() throws IOException {
        try (ShulkRegion region = ShulkRegion.open(this.file(), RegionKind.CHUNK, false)) {
            region.write(9, chunk(9, 40_000), 123, 3);
            assertEquals(1, region.coldChunks(19, 200).size());
            assertTrue(region.recompress(9, 19));
            assertArrayEquals(chunk(9, 40_000), region.read(9));
            assertEquals(123, region.timestamp(9));
            assertEquals(0, region.coldChunks(19, 200).size());
        }
    }

    @Test
    void notAShulkFile() throws IOException {
        Files.write(this.file(), new byte[ShulkRegion.HEADER_SIZE]);
        assertThrows(IOException.class, () -> ShulkRegion.open(this.file(), RegionKind.CHUNK, false));
    }

    @Test
    void indexWrapsNegativeCoordinates() {
        assertEquals(0, ShulkRegion.index(-32, -32));
        assertEquals(31 + 31 * 32, ShulkRegion.index(-1, -1));
        assertEquals(1 + 2 * 32, ShulkRegion.index(33, 66));
    }

    private static byte[] randomBytes(final int seed, final int size) {
        final byte[] bytes = new byte[size];
        new Random(seed).nextBytes(bytes);
        return bytes;
    }

    private static void flipByte(final Path path, final long position) throws IOException {
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
            final ByteBuffer one = ByteBuffer.allocate(1);
            channel.read(one, position);
            one.put(0, (byte) (one.get(0) ^ 0x5A));
            one.rewind();
            channel.write(one, position);
        }
    }
}
