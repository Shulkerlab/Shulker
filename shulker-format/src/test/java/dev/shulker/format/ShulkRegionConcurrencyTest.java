package dev.shulker.format;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicIntegerArray;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ShulkRegionConcurrencyTest {

    @TempDir
    Path dir;

    /** Content derived from (chunk, version) so any reader can check what it got. */
    private static byte[] chunk(final int index, final int version) {
        final Random random = new Random(index * 1_000_003L + version);
        final StringBuilder text = new StringBuilder("chunk=" + index + ";version=" + version + ";");
        final int size = 2000 + random.nextInt(30_000);
        while (text.length() < size) {
            text.append("minecraft:").append(random.nextBoolean() ? "oak_log" : "dirt").append(random.nextInt(50)).append(',');
        }
        return text.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static int versionOf(final byte[] data) {
        final String head = new String(data, 0, Math.min(40, data.length), StandardCharsets.UTF_8);
        return Integer.parseInt(head.substring(head.indexOf("version=") + 8, head.indexOf(';', head.indexOf("version="))));
    }

    @Test
    void acquireSharesOneInstanceUntilTheLastRelease() throws IOException {
        final Path path = this.dir.resolve("r.0.0.shulk");
        final ShulkRegion first = ShulkRegion.acquire(path, RegionKind.CHUNK, false);
        final ShulkRegion second = ShulkRegion.acquire(path, RegionKind.CHUNK, false);
        assertSame(first, second);
        first.write(1, chunk(1, 1), 1, 6);
        first.release();
        assertArrayEquals(chunk(1, 1), second.read(1)); // still open for the other holder
        second.release();
        final ShulkRegion reopened = ShulkRegion.acquire(path, RegionKind.CHUNK, false);
        assertArrayEquals(chunk(1, 1), reopened.read(1));
        reopened.release();
    }

    @Test
    void writersReadersAndRecompressionDoNotCorruptEachOther() throws Exception {
        final Path path = this.dir.resolve("r.1.1.shulk");
        final int chunks = 256;
        final AtomicIntegerArray latest = new AtomicIntegerArray(chunks);
        final ShulkRegion region = ShulkRegion.acquire(path, RegionKind.CHUNK, false);
        for (int i = 0; i < chunks; i++) {
            region.write(i, chunk(i, 0), 1, 3);
        }
        final ExecutorService pool = Executors.newFixedThreadPool(6);
        final List<Future<?>> tasks = new ArrayList<>();
        for (int w = 0; w < 3; w++) {
            final int writer = w;
            tasks.add(pool.submit(() -> {
                // Writers own disjoint chunks, so each chunk's latest version is well defined.
                for (int round = 1; round <= 40; round++) {
                    for (int i = writer; i < chunks; i += 3) {
                        region.write(i, chunk(i, round), 2, 3);
                        latest.set(i, round);
                    }
                }
                return null;
            }));
        }
        tasks.add(pool.submit(() -> {
            for (int pass = 0; pass < 20; pass++) {
                for (int i = 0; i < chunks; i++) {
                    region.recompress(i, 19 - pass % 3);
                }
            }
            return null;
        }));
        for (int r = 0; r < 2; r++) {
            tasks.add(pool.submit(() -> {
                for (int pass = 0; pass < 30; pass++) {
                    for (int i = 0; i < chunks; i++) {
                        final byte[] data = region.read(i);
                        assertArrayEquals(chunk(i, versionOf(data)), data, "chunk " + i); // never torn or foreign
                    }
                }
                return null;
            }));
        }
        for (Future<?> task : tasks) {
            task.get();
        }
        pool.shutdown();
        region.release();

        try (ShulkRegion reopened = ShulkRegion.open(path, RegionKind.CHUNK, false)) {
            assertEquals(0, reopened.damagedEntries());
            for (int i = 0; i < chunks; i++) {
                assertArrayEquals(chunk(i, latest.get(i)), reopened.read(i), "chunk " + i);
            }
            assertTrue(reopened.fileSize() < ShulkRegion.HEADER_SIZE + reopened.liveBytes() * 2);
        }
    }
}
