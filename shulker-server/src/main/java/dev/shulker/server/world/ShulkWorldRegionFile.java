package dev.shulker.server.world;

import dev.shulker.format.RegionKind;
import dev.shulker.format.ShulkWorld;
import dev.shulker.server.ShulkerConfig;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.RegionFile;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;
import org.jspecify.annotations.Nullable;

/**
 * One 32x32 region of a Shulk World, presented as a {@link RegionFile} like
 * {@link ShulkRegionFile}, but backed by the world's in-memory {@link ShulkWorld} instead of a
 * file: reads decompress the stored chunk, writes compress it and replace the entry. Nothing is
 * written to disk; the world reaches its loader as a whole when it is saved.
 */
public final class ShulkWorldRegionFile extends RegionFile {

    private final ShulkWorldStorage.Registered registered;
    private final RegionKind kind;

    ShulkWorldRegionFile(final RegionStorageInfo info, final Path path, final ShulkWorldStorage.Registered registered) {
        super(info, path);
        this.registered = registered;
        this.kind = RegionKind.fromStorageType(info.type());
    }

    /** Resolved on every access: a reset swaps the world's chunks while the level stays loaded. */
    private ShulkWorld world() {
        return this.registered.world;
    }

    // ---- reading ---------------------------------------------------------------------------

    @Override
    public @Nullable DataInputStream getChunkDataInputStream(final ChunkPos pos) {
        final byte[] raw = this.world().read(this.kind, pos.x(), pos.z());
        return raw == null ? null : new DataInputStream(new ByteArrayInputStream(raw));
    }

    @Override
    public boolean doesChunkExist(final ChunkPos pos) {
        return this.world().has(this.kind, pos.x(), pos.z());
    }

    @Override
    public boolean hasChunk(final ChunkPos pos) {
        return this.world().has(this.kind, pos.x(), pos.z());
    }

    // ---- writing ---------------------------------------------------------------------------

    @Override
    public ca.spottedleaf.moonrise.patches.chunk_system.io.MoonriseRegionFileIO.RegionDataController.WriteData moonrise$startWrite(
        final CompoundTag data, final ChunkPos pos
    ) {
        if (this.registered.dropEmpty && EmptyChunks.isEmpty(this.kind, data)) {
            // Nothing in it, and the void generator makes the same chunk again: do not store it.
            return new ca.spottedleaf.moonrise.patches.chunk_system.io.MoonriseRegionFileIO.RegionDataController.WriteData(
                data, ca.spottedleaf.moonrise.patches.chunk_system.io.MoonriseRegionFileIO.RegionDataController.WriteData.WriteResult.WRITE,
                new DataOutputStream(java.io.OutputStream.nullOutputStream()), regionFile -> this.clear(pos)
            );
        }
        final ChunkBytes buffer = new ChunkBytes();
        return new ca.spottedleaf.moonrise.patches.chunk_system.io.MoonriseRegionFileIO.RegionDataController.WriteData(
            data, ca.spottedleaf.moonrise.patches.chunk_system.io.MoonriseRegionFileIO.RegionDataController.WriteData.WriteResult.WRITE,
            new DataOutputStream(buffer), regionFile -> ((ShulkWorldRegionFile) regionFile).writeChunk(pos, buffer.toByteArray())
        );
    }

    @Override
    public DataOutputStream getChunkDataOutputStream(final ChunkPos pos) {
        return new DataOutputStream(new ChunkBytes() {
            @Override
            public void close() {
                ShulkWorldRegionFile.this.writeChunk(pos, this.toByteArray());
            }
        });
    }

    @Override
    public void write(final ChunkPos pos, final ByteBuffer data) {
        throw new UnsupportedOperationException("Shulk Worlds take uncompressed chunk data, not .mca sectors");
    }

    private void writeChunk(final ChunkPos pos, final byte[] raw) {
        if (this.registered.discardWrites) {
            return;
        }
        this.world().write(this.kind, pos.x(), pos.z(), raw, (int) (System.currentTimeMillis() / 1000L), ShulkerConfig.shulkWorldsCompressionLevel);
    }

    @Override
    public void clear(final ChunkPos pos) {
        if (!this.registered.discardWrites) {
            this.world().delete(this.kind, pos.x(), pos.z());
        }
    }

    // ---- lifecycle: nothing to flush or close, the world lives in memory --------------------

    @Override
    public void flush() {
    }

    @Override
    public void close() {
    }

    // ---- Paper's .mca specific features do not apply ----------------------------------------

    @Override
    public int getRecalculateCount() {
        return 0;
    }

    @Override
    public boolean recalculateHeader() {
        return false;
    }

    @Override
    public boolean isOversized(final int x, final int z) {
        return false;
    }

    @Override
    public void setOversized(final int x, final int z, final boolean oversized) {
    }

    @Override
    public @Nullable CompoundTag getOversizedData(final int x, final int z) throws IOException {
        return null;
    }

    /** Collects uncompressed NBT; enforces Paper's chunk size limit like RegionFile.ChunkBuffer. */
    private static class ChunkBytes extends ByteArrayOutputStream {
        ChunkBytes() {
            super(8096);
        }

        @Override
        public void write(final int b) {
            if (this.count > MAX_CHUNK_SIZE) {
                throw new net.minecraft.world.level.chunk.storage.RegionFileStorage.RegionFileSizeException("Region file too large: " + this.count);
            }
            super.write(b);
        }

        @Override
        public void write(final byte[] b, final int off, final int len) {
            if (this.count + len > MAX_CHUNK_SIZE) {
                throw new net.minecraft.world.level.chunk.storage.RegionFileStorage.RegionFileSizeException("Region file too large: " + (this.count + len));
            }
            super.write(b, off, len);
        }
    }
}
