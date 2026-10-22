package dev.shulker.format;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ShulkWorldTest {

    @TempDir
    Path dir;

    private static byte[] chunk(final int seed, final int size) {
        final Random random = new Random(seed);
        final StringBuilder text = new StringBuilder();
        while (text.length() < size) {
            text.append("minecraft:").append(random.nextBoolean() ? "stone" : "grass_block").append(random.nextInt(8)).append(';');
        }
        return text.substring(0, size).getBytes(StandardCharsets.UTF_8);
    }

    /** A small island-like world: a few chunks around the origin, negative coordinates included. */
    private static ShulkWorld island() {
        final ShulkWorld world = new ShulkWorld();
        for (int x = -2; x <= 1; x++) {
            for (int z = -2; z <= 1; z++) {
                world.write(RegionKind.CHUNK, x, z, chunk(x * 31 + z, 3000), 100 + x, 6);
            }
        }
        world.write(RegionKind.ENTITIES, 0, 0, chunk(99, 400), 7, 6);
        world.write(RegionKind.POI, -1, -1, chunk(98, 120), 8, 6);
        world.putFile("level.dat", chunk(1, 900));
        world.putFile("data/raids.dat", chunk(2, 50));
        world.setProperty("island.owner", "8f3c");
        return world;
    }

    @Test
    void roundTripThroughBytes() throws IOException {
        final ShulkWorld world = island();
        final ShulkWorld read = ShulkWorld.fromBytes(world.toBytes());
        for (int x = -2; x <= 1; x++) {
            for (int z = -2; z <= 1; z++) {
                assertArrayEquals(chunk(x * 31 + z, 3000), read.read(RegionKind.CHUNK, x, z), x + "," + z);
                assertEquals(100 + x, read.timestamp(RegionKind.CHUNK, x, z));
            }
        }
        assertEquals(16, read.chunkCount(RegionKind.CHUNK));
        assertArrayEquals(chunk(99, 400), read.read(RegionKind.ENTITIES, 0, 0));
        assertArrayEquals(chunk(98, 120), read.read(RegionKind.POI, -1, -1));
        assertArrayEquals(chunk(1, 900), read.file("level.dat"));
        assertArrayEquals(chunk(2, 50), read.file("data/raids.dat"));
        assertEquals("8f3c", read.property("island.owner"));
        assertNull(read.read(RegionKind.CHUNK, 5, 5));
        assertFalse(read.has(RegionKind.ENTITIES, 1, 1));
    }

    @Test
    void outputIsDeterministic() {
        assertArrayEquals(island().toBytes(), island().toBytes());
    }

    @Test
    void emptyWorldRoundTrips() throws IOException {
        final ShulkWorld read = ShulkWorld.fromBytes(new ShulkWorld().toBytes());
        assertEquals(0, read.chunkCount(RegionKind.CHUNK));
        assertTrue(read.filePaths().isEmpty());
    }

    @Test
    void extremeCoordinates() throws IOException {
        final ShulkWorld world = new ShulkWorld();
        world.write(RegionKind.CHUNK, Integer.MIN_VALUE, Integer.MAX_VALUE, chunk(1, 10), 1, 3);
        world.write(RegionKind.CHUNK, -1, -1, chunk(2, 10), 1, 3);
        final ShulkWorld read = ShulkWorld.fromBytes(world.toBytes());
        assertArrayEquals(chunk(1, 10), read.read(RegionKind.CHUNK, Integer.MIN_VALUE, Integer.MAX_VALUE));
        assertArrayEquals(chunk(2, 10), read.read(RegionKind.CHUNK, -1, -1));
    }

    @Test
    void everyFlippedByteIsDetected() {
        final byte[] bytes = island().toBytes();
        for (int i = 0; i < bytes.length; i += 97) {
            final byte[] damaged = bytes.clone();
            damaged[i] ^= 0x10;
            assertThrows(IOException.class, () -> ShulkWorld.fromBytes(damaged), "byte " + i);
        }
    }

    @Test
    void truncatedFileIsRejected() {
        final byte[] bytes = island().toBytes();
        for (int length : new int[] {0, 7, 30, bytes.length / 2, bytes.length - 1}) {
            assertThrows(IOException.class, () -> ShulkWorld.fromBytes(Arrays.copyOf(bytes, length)), "length " + length);
        }
    }

    @Test
    void regionFileIsRecognisedAndRejected() throws IOException {
        final Path regionFile = this.dir.resolve("r.0.0" + ShulkRegion.EXTENSION);
        try (ShulkRegion region = ShulkRegion.open(regionFile, RegionKind.CHUNK, false)) {
            region.write(0, chunk(1, 100), 1, 6);
        }
        assertFalse(ShulkWorld.isWorldFile(regionFile));
        final IOException error = assertThrows(IOException.class, () -> ShulkWorld.read(regionFile));
        assertTrue(error.getMessage().contains("region .shulk"), error.getMessage());

        final Path worldFile = this.dir.resolve("island" + ShulkWorld.EXTENSION);
        island().write(worldFile);
        assertTrue(ShulkWorld.isWorldFile(worldFile));
    }

    @Test
    void newerVersionIsRejected() {
        final byte[] bytes = island().toBytes();
        bytes[8] = (byte) (ShulkWorld.VERSION + 1);
        final IOException error = assertThrows(IOException.class, () -> ShulkWorld.fromBytes(bytes));
        assertTrue(error.getMessage().contains("newer"), error.getMessage());
    }

    @Test
    void copySharesChunkDataButNotState() {
        final ShulkWorld template = island();
        final ShulkWorld clone = template.copy();
        assertSame(
            template.chunksForTest(RegionKind.CHUNK).get(0L).data(),
            clone.chunksForTest(RegionKind.CHUNK).get(0L).data(),
            "a copy must not recompress chunks"
        );
        clone.write(RegionKind.CHUNK, 0, 0, chunk(500, 100), 1, 6);
        clone.delete(RegionKind.CHUNK, 1, 1);
        clone.putFile("level.dat", chunk(3, 10));
        assertArrayEquals(chunk(0, 3000), template.read(RegionKind.CHUNK, 0, 0));
        assertTrue(template.has(RegionKind.CHUNK, 1, 1));
        assertArrayEquals(chunk(1, 900), template.file("level.dat"));
        assertArrayEquals(chunk(500, 100), clone.read(RegionKind.CHUNK, 0, 0));
    }

    @Test
    void differentChunksFindsWhatACopyChanged() {
        final ShulkWorld template = island();
        final ShulkWorld played = template.copy();
        assertTrue(played.differentChunks(template, RegionKind.CHUNK).isEmpty());

        played.write(RegionKind.CHUNK, 0, 0, chunk(0, 3000), 1, 6); // same bytes, new entry
        played.delete(RegionKind.CHUNK, 1, 1);
        played.write(RegionKind.CHUNK, 7, 7, chunk(9, 100), 1, 6); // not in the template
        played.write(RegionKind.ENTITIES, 0, 0, chunk(5, 50), 1, 6);
        final Set<Long> changed = played.differentChunks(template, RegionKind.CHUNK);
        assertEquals(3, changed.size());
        assertEquals(Set.of(changed), Set.of(template.differentChunks(played, RegionKind.CHUNK)), "symmetric");
        assertEquals(1, played.differentChunks(template, RegionKind.ENTITIES).size());
        assertTrue(played.differentChunks(template, RegionKind.POI).isEmpty());

        for (long key : changed) {
            played.copyChunkFrom(template, RegionKind.CHUNK, ShulkWorld.keyX(key), ShulkWorld.keyZ(key));
        }
        played.copyChunkFrom(template, RegionKind.ENTITIES, 0, 0);
        for (RegionKind kind : RegionKind.values()) {
            assertTrue(played.differentChunks(template, kind).isEmpty(), kind.name());
        }
        assertArrayEquals(chunk(1 * 31 + 1, 3000), played.read(RegionKind.CHUNK, 1, 1));
        assertFalse(played.has(RegionKind.CHUNK, 7, 7));
    }

    @Test
    void copyChunkFromCountsOnlyRealChanges() {
        final ShulkWorld template = island();
        final ShulkWorld copy = template.copy();
        final long before = copy.modifications();
        copy.copyChunkFrom(template, RegionKind.CHUNK, 0, 0); // already the same entry
        copy.copyChunkFrom(template, RegionKind.CHUNK, 50, 50); // absent in both
        assertEquals(before, copy.modifications());
    }

    @Test
    void modificationsTrackChanges() {
        final ShulkWorld world = new ShulkWorld();
        final long start = world.modifications();
        world.delete(RegionKind.CHUNK, 0, 0); // absent: not a change
        assertEquals(start, world.modifications());
        world.write(RegionKind.CHUNK, 0, 0, chunk(1, 10), 1, 6);
        assertNotEquals(start, world.modifications());
    }

    @Test
    void filePathsStayInsideTheWorld() {
        final ShulkWorld world = new ShulkWorld();
        for (String bad : new String[] {"", "/etc/passwd", "../level.dat", "data/../../x", "C:/x", "a//b", "./a"}) {
            assertThrows(IllegalArgumentException.class, () -> world.putFile(bad, new byte[1]), bad);
        }
        world.putFile("data\\maps\\map_0.dat", new byte[] {1});
        assertEquals(Set.of("data/maps/map_0.dat"), world.filePaths());
    }

    @Test
    void atomicWriteReplacesAndLeavesNoTemporary() throws IOException {
        final Path file = this.dir.resolve("worlds/island" + ShulkWorld.EXTENSION);
        island().write(file);
        final ShulkWorld changed = island();
        changed.setProperty("island.owner", "other");
        changed.write(file);
        assertEquals("other", ShulkWorld.read(file).property("island.owner"));
        try (Stream<Path> files = Files.list(file.getParent())) {
            assertEquals(Set.of("island.shulk"), files.map(p -> p.getFileName().toString()).collect(Collectors.toSet()));
        }
    }

    @Test
    void concurrentWritesAndSerialisationStayConsistent() throws Exception {
        final ShulkWorld world = new ShulkWorld();
        final ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            final Future<?>[] writers = new Future<?>[3];
            for (int t = 0; t < writers.length; t++) {
                final int thread = t;
                writers[t] = pool.submit(() -> {
                    for (int i = 0; i < 300; i++) {
                        world.write(RegionKind.CHUNK, thread * 1000 + i, i, chunk(i, 500), i, 3);
                    }
                });
            }
            for (int i = 0; i < 30; i++) {
                ShulkWorld.fromBytes(world.toBytes()); // every snapshot must be a valid file
            }
            for (Future<?> writer : writers) {
                writer.get();
            }
        } finally {
            pool.shutdownNow();
        }
        assertEquals(900, ShulkWorld.fromBytes(world.toBytes()).chunkCount(RegionKind.CHUNK));
    }
}
