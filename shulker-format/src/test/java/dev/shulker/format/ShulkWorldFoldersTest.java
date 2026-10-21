package dev.shulker.format;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ShulkWorldFoldersTest {

    @TempDir
    Path dir;

    private static byte[] bytes(final String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    /** A world folder in the current layout: regions under dimensions/minecraft/overworld/. */
    private Path folder(final String name) throws IOException {
        final Path root = this.dir.resolve(name);
        final Path dimension = root.resolve("dimensions/minecraft/overworld");
        Files.createDirectories(dimension.resolve("region"));
        Files.createDirectories(dimension.resolve("entities"));
        Files.createDirectories(root.resolve("data"));
        Files.write(root.resolve("level.dat"), bytes("level"));
        Files.write(root.resolve("uid.dat"), bytes("uid"));
        Files.write(root.resolve("session.lock"), bytes("lock"));
        Files.write(root.resolve("data/raids.dat"), bytes("raids"));

        final byte[][] chunks = new byte[ShulkRegion.CHUNKS][];
        final int[] timestamps = new int[ShulkRegion.CHUNKS];
        chunks[ShulkRegion.index(-1, -1)] = bytes("chunk -1,-1 from mca");
        chunks[ShulkRegion.index(-2, -1)] = bytes("chunk -2,-1 from mca");
        timestamps[ShulkRegion.index(-1, -1)] = 42;
        McaFile.write(dimension.resolve("region/r.-1.-1.mca"), new McaFile.Contents(chunks, timestamps));
        // The same region half migrated: .shulk has a newer copy of one chunk.
        try (ShulkRegion region = ShulkRegion.open(dimension.resolve("region/r.-1.-1.shulk"), RegionKind.CHUNK, false)) {
            region.write(ShulkRegion.index(-2, -1), bytes("chunk -2,-1 from shulk"), 43, 6);
        }
        try (ShulkRegion region = ShulkRegion.open(dimension.resolve("entities/r.0.0.shulk"), RegionKind.ENTITIES, false)) {
            region.write(ShulkRegion.index(3, 4), bytes("entities 3,4"), 44, 6);
        }
        return root;
    }

    @Test
    void importReadsChunksFilesAndRegionLocation() throws IOException {
        final ShulkWorld world = ShulkWorldFolders.importFolder(this.folder("world"));
        assertArrayEquals(bytes("chunk -1,-1 from mca"), world.read(RegionKind.CHUNK, -1, -1));
        assertEquals(42, world.timestamp(RegionKind.CHUNK, -1, -1));
        assertArrayEquals(bytes("chunk -2,-1 from shulk"), world.read(RegionKind.CHUNK, -2, -1), ".shulk copy wins");
        assertArrayEquals(bytes("entities 3,4"), world.read(RegionKind.ENTITIES, 3, 4));
        assertEquals(Set.of("level.dat", "uid.dat", "data/raids.dat"), world.filePaths(), "session.lock is not part of the world");
        assertEquals("dimensions/minecraft/overworld", world.property(ShulkWorldFolders.REGION_DIR));
    }

    @Test
    void exportPutsEverythingBackInBothFormats() throws IOException {
        final ShulkWorld world = ShulkWorldFolders.importFolder(this.folder("world"));
        for (ShulkWorldFolders.RegionFormat format : ShulkWorldFolders.RegionFormat.values()) {
            final Path out = this.dir.resolve("out-" + format);
            ShulkWorldFolders.exportFolder(world, out, format);
            final String extension = format == ShulkWorldFolders.RegionFormat.SHULK ? ShulkRegion.EXTENSION : McaFile.EXTENSION;
            assertTrue(Files.exists(out.resolve("dimensions/minecraft/overworld/region/r.-1.-1" + extension)));
            assertArrayEquals(bytes("raids"), Files.readAllBytes(out.resolve("data/raids.dat")));

            final ShulkWorld again = ShulkWorldFolders.importFolder(out);
            for (RegionKind kind : RegionKind.values()) {
                assertEquals(world.chunkKeys(kind), again.chunkKeys(kind), kind + " in " + format);
                for (long key : world.chunkKeys(kind)) {
                    final int x = ShulkWorld.keyX(key);
                    final int z = ShulkWorld.keyZ(key);
                    assertArrayEquals(world.read(kind, x, z), again.read(kind, x, z));
                }
            }
        }
    }

    @Test
    void importLeavesTheFolderUntouched() throws IOException {
        final Path root = this.folder("world");
        ShulkWorldFolders.importFolder(root);
        assertTrue(Files.exists(root.resolve("dimensions/minecraft/overworld/region/r.-1.-1.mca")));
        assertTrue(Files.exists(root.resolve("session.lock")));
    }

    @Test
    void twoDimensionsAreRejected() throws IOException {
        final Path root = this.folder("world");
        final Path nether = root.resolve("dimensions/minecraft/the_nether/region");
        Files.createDirectories(nether);
        try (ShulkRegion region = ShulkRegion.open(nether.resolve("r.0.0.shulk"), RegionKind.CHUNK, false)) {
            region.write(0, bytes("nether"), 1, 6);
        }
        final IOException error = assertThrows(IOException.class, () -> ShulkWorldFolders.importFolder(root));
        assertTrue(error.getMessage().contains("more than one dimension"), error.getMessage());
    }

    @Test
    void exportRefusesANonEmptyFolder() throws IOException {
        final Path out = this.dir.resolve("busy");
        Files.createDirectories(out);
        Files.write(out.resolve("something"), bytes("x"));
        assertThrows(IOException.class, () -> ShulkWorldFolders.exportFolder(new ShulkWorld(), out, ShulkWorldFolders.RegionFormat.SHULK));
    }

    @Test
    void readFilesTracksAddedChangedAndRemovedFiles() throws IOException {
        final ShulkWorld world = new ShulkWorld();
        world.putFile("level.dat", bytes("old"));
        world.putFile("data/gone.dat", bytes("gone"));
        final Path root = this.dir.resolve("runtime");
        Files.createDirectories(root.resolve("data"));
        Files.createDirectories(root.resolve("region"));
        Files.write(root.resolve("level.dat"), bytes("new"));
        Files.write(root.resolve("data/new.dat"), bytes("added"));
        Files.write(root.resolve("session.lock"), bytes("lock"));
        Files.write(root.resolve("region/r.0.0.shulk"), bytes("not a file of the world"));

        final long before = world.modifications();
        ShulkWorldFolders.readFiles(world, root);
        assertEquals(Set.of("level.dat", "data/new.dat"), world.filePaths());
        assertArrayEquals(bytes("new"), world.file("level.dat"));

        final long after = world.modifications();
        assertTrue(after > before);
        ShulkWorldFolders.readFiles(world, root);
        assertEquals(after, world.modifications(), "unchanged files are not a change");
        assertFalse(world.filePaths().contains("session.lock"));
    }
}
