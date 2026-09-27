package dev.shulker.format;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorldConverterTest {

    @TempDir
    Path world;

    private static byte[] chunk(final int seed, final int size) {
        final Random random = new Random(seed);
        final StringBuilder text = new StringBuilder();
        while (text.length() < size) {
            text.append("minecraft:grass_block;").append(random.nextInt(100)).append(';');
        }
        return text.substring(0, size).getBytes(StandardCharsets.UTF_8);
    }

    /** A region with a few normal chunks and one incompressible 1.5 MiB chunk (goes to .mcc). */
    private McaFile.Contents sample() {
        final byte[][] chunks = new byte[ShulkRegion.CHUNKS][];
        final int[] timestamps = new int[ShulkRegion.CHUNKS];
        for (int i = 0; i < 300; i += 3) {
            chunks[i] = chunk(i, 4000 + i * 11);
            timestamps[i] = 1_700_000_000 + i;
        }
        final byte[] huge = new byte[1536 * 1024];
        new Random(7).nextBytes(huge);
        chunks[500] = huge;
        timestamps[500] = 42;
        return new McaFile.Contents(chunks, timestamps);
    }

    private Path regionDir(final String kind) throws IOException {
        return Files.createDirectories(this.world.resolve("dimensions/minecraft/overworld").resolve(kind));
    }

    @Test
    void mcaToShulkAndBackPreservesEveryChunk() throws IOException {
        final Path mca = this.regionDir("region").resolve("r.-1.2.mca");
        final McaFile.Contents original = this.sample();
        McaFile.write(mca, original);
        assertTrue(Files.exists(mca.resolveSibling("c.-12.79.mcc")), "oversized chunk goes to .mcc"); // index 500: x=20, z=15

        final WorldConverter.Result toShulk = WorldConverter.convert(this.world, WorldConverter.Target.SHULK, s -> { });
        assertEquals(1, toShulk.regions());
        assertEquals(original.count(), toShulk.chunks());
        assertFalse(Files.exists(mca));
        assertFalse(Files.exists(mca.resolveSibling("c.-12.79.mcc")));
        final Path shulk = mca.resolveSibling("r.-1.2.shulk");
        try (ShulkRegion region = ShulkRegion.open(shulk, RegionKind.CHUNK, false)) {
            for (int i = 0; i < ShulkRegion.CHUNKS; i++) {
                assertArrayEquals(original.chunks()[i], region.read(i));
                assertEquals(original.timestamps()[i], region.timestamp(i));
            }
        }

        WorldConverter.convert(this.world, WorldConverter.Target.MCA, s -> { });
        assertFalse(Files.exists(shulk));
        final McaFile.Contents back = McaFile.read(mca);
        for (int i = 0; i < ShulkRegion.CHUNKS; i++) {
            assertArrayEquals(original.chunks()[i], back.chunks()[i]);
            assertEquals(original.timestamps()[i], back.timestamps()[i]);
        }
    }

    @Test
    void newerShulkChunksWinWhenBothExist() throws IOException {
        final Path dir = this.regionDir("entities");
        final Path mca = dir.resolve("r.0.0.mca");
        McaFile.write(mca, this.sample());
        try (ShulkRegion region = ShulkRegion.open(dir.resolve("r.0.0.shulk"), RegionKind.ENTITIES, false)) {
            region.write(3, "newer".getBytes(StandardCharsets.UTF_8), 1, 6);
        }
        WorldConverter.convert(this.world, WorldConverter.Target.SHULK, s -> { });
        try (ShulkRegion region = ShulkRegion.open(dir.resolve("r.0.0.shulk"), RegionKind.ENTITIES, false)) {
            assertArrayEquals("newer".getBytes(StandardCharsets.UTF_8), region.read(3));
            assertArrayEquals(this.sample().chunks()[6], region.read(6));
        }
    }

    @Test
    void refusesAWorldInUse() throws IOException {
        McaFile.write(this.regionDir("region").resolve("r.0.0.mca"), this.sample());
        final Path lockFile = this.world.resolve("session.lock");
        Files.writeString(lockFile, "");
        try (FileChannel channel = FileChannel.open(lockFile, StandardOpenOption.WRITE); FileLock ignored = channel.lock()) {
            assertThrows(IOException.class, () -> WorldConverter.convert(this.world, WorldConverter.Target.SHULK, s -> { }));
        }
        assertTrue(Files.exists(this.world.resolve("dimensions/minecraft/overworld/region/r.0.0.mca")));
    }

    @Test
    void kindFollowsTheFolder() {
        assertEquals(RegionKind.CHUNK, WorldConverter.kindOf(Path.of("w/region/r.0.0.mca")));
        assertEquals(RegionKind.ENTITIES, WorldConverter.kindOf(Path.of("w/entities/r.0.0.mca")));
        assertEquals(RegionKind.POI, WorldConverter.kindOf(Path.of("w/poi/r.0.0.mca")));
    }
}
