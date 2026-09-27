package dev.shulker.server.world;

import com.mojang.logging.LogUtils;
import dev.shulker.format.RegionKind;
import dev.shulker.format.ShulkRegion;
import dev.shulker.server.ShulkerConfig;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.RegionFile;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * A {@code r.X.Z.shulk} region presented as a {@link RegionFile}, so everything that holds a
 * RegionFile - Moonrise, Paper, NMS plugins - keeps working unchanged.
 *
 * <p>While a world migrates, the region may still have its old {@code .mca} file ("legacy").
 * Chunks missing from the .shulk file are read from it; every chunk written goes to the .shulk
 * file and is removed from the .mca file, which is deleted once it holds no chunks.
 */
public final class ShulkRegionFile extends RegionFile {

    private static final Logger LOGGER = LogUtils.getClassLogger();

    private final Path path;
    private final RegionKind kind;
    private final boolean sync;
    private final int baseChunkX;
    private final int baseChunkZ;
    private @Nullable ShulkRegion region;
    private @Nullable RegionFile legacy;
    private final @Nullable Path legacyPath;
    private boolean closed;

    ShulkRegionFile(final RegionStorageInfo info, final Path path, final int regionX, final int regionZ,
                    final boolean sync, final @Nullable RegionFile legacy, final @Nullable Path legacyPath) throws IOException {
        super(info, path);
        this.path = path;
        this.kind = RegionKind.fromStorageType(info.type());
        this.sync = sync;
        this.baseChunkX = regionX << 5;
        this.baseChunkZ = regionZ << 5;
        this.legacy = legacy;
        this.legacyPath = legacyPath;
        if (Files.exists(path)) {
            this.region = ShulkRegion.acquire(path, this.kind, sync);
            if (this.region.damagedEntries() > 0) {
                LOGGER.error("{}: {} damaged chunk entries were skipped", path, this.region.damagedEntries());
            }
        }
        ColdStorage.opened(this);
    }

    private static int index(final ChunkPos pos) {
        return ShulkRegion.index(pos.x(), pos.z());
    }

    private ShulkRegion region() throws IOException {
        if (this.closed) {
            throw new IOException(this.path + " is closed"); // never silently re-acquire a released region
        }
        if (this.region == null) {
            // Created on first write, so reading a not yet migrated region leaves no empty file.
            this.region = ShulkRegion.acquire(this.path, this.kind, this.sync);
        }
        return this.region;
    }

    // ---- reading ---------------------------------------------------------------------------

    @Override
    public Path getPath() {
        return this.path;
    }

    @Override
    public synchronized @Nullable DataInputStream getChunkDataInputStream(final ChunkPos pos) throws IOException {
        if (this.region != null) {
            final byte[] raw = this.region.read(index(pos));
            if (raw != null) {
                return new DataInputStream(new ByteArrayInputStream(raw));
            }
        }
        return this.legacy == null ? null : this.legacy.getChunkDataInputStream(pos);
    }

    @Override
    public synchronized boolean doesChunkExist(final ChunkPos pos) {
        return (this.region != null && this.region.has(index(pos))) || (this.legacy != null && this.legacy.doesChunkExist(pos));
    }

    @Override
    public synchronized boolean hasChunk(final ChunkPos pos) {
        return (this.region != null && this.region.has(index(pos))) || (this.legacy != null && this.legacy.hasChunk(pos));
    }

    // ---- writing ---------------------------------------------------------------------------

    @Override
    public ca.spottedleaf.moonrise.patches.chunk_system.io.MoonriseRegionFileIO.RegionDataController.WriteData moonrise$startWrite(
        final CompoundTag data, final ChunkPos pos
    ) {
        final ChunkBytes buffer = new ChunkBytes();
        return new ca.spottedleaf.moonrise.patches.chunk_system.io.MoonriseRegionFileIO.RegionDataController.WriteData(
            data, ca.spottedleaf.moonrise.patches.chunk_system.io.MoonriseRegionFileIO.RegionDataController.WriteData.WriteResult.WRITE,
            new DataOutputStream(buffer), regionFile -> ((ShulkRegionFile) regionFile).writeChunk(pos, buffer.bytes())
        );
    }

    @Override
    public DataOutputStream getChunkDataOutputStream(final ChunkPos pos) {
        return new DataOutputStream(new ChunkBytes() {
            @Override
            public void close() throws IOException {
                ShulkRegionFile.this.writeChunk(pos, this.bytes());
            }
        });
    }

    @Override
    protected void write(final ChunkPos pos, final ByteBuffer data) {
        throw new UnsupportedOperationException(".shulk regions take uncompressed chunk data, not .mca sectors");
    }

    private synchronized void writeChunk(final ChunkPos pos, final byte[] raw) throws IOException {
        this.region().write(index(pos), raw, (int) (System.currentTimeMillis() / 1000L), ShulkerConfig.storageCompressionLevel);
        if (this.legacy != null && this.legacy.hasChunk(pos)) {
            this.legacy.clear(pos);
            this.retireLegacyIfEmpty();
        }
    }

    @Override
    public synchronized void clear(final ChunkPos pos) throws IOException {
        if (this.region != null) {
            this.region.delete(index(pos));
        }
        if (this.legacy != null && this.legacy.hasChunk(pos)) {
            this.legacy.clear(pos);
            this.retireLegacyIfEmpty();
        }
    }

    /** Deletes the .mca file once every chunk has moved to the .shulk file. */
    private void retireLegacyIfEmpty() throws IOException {
        if (this.legacy == null) {
            return;
        }
        for (int x = 0; x < 32; x++) {
            for (int z = 0; z < 32; z++) {
                if (this.legacy.hasChunk(new ChunkPos(this.baseChunkX + x, this.baseChunkZ + z))) {
                    return;
                }
            }
        }
        this.legacy.close();
        this.legacy = null;
        if (this.legacyPath != null) {
            Files.deleteIfExists(this.legacyPath);
            LOGGER.info("Migrated {} to {}", this.legacyPath.getFileName(), this.path.getFileName());
        }
    }

    // ---- lifecycle -------------------------------------------------------------------------

    @Override
    public synchronized void flush() throws IOException {
        if (this.region != null) {
            this.region.flush();
        }
        if (this.legacy != null) {
            this.legacy.flush();
        }
    }

    @Override
    public synchronized void close() throws IOException {
        if (this.closed) {
            return;
        }
        this.closed = true;
        ColdStorage.closed(this);
        try {
            if (this.region != null) {
                this.region.release();
                this.region = null;
            }
        } finally {
            if (this.legacy != null) {
                this.legacy.close();
            }
        }
    }

    /** The .shulk region, if created yet; used by background recompression. */
    synchronized @Nullable ShulkRegion shulkRegion() {
        return this.region;
    }

    // ---- Paper's .mca specific features: only meaningful for the legacy file ----------------

    @Override
    public int getRecalculateCount() {
        final RegionFile legacy = this.legacy;
        return legacy == null ? 0 : legacy.getRecalculateCount();
    }

    @Override
    public boolean recalculateHeader() throws IOException {
        final RegionFile legacy = this.legacy;
        return legacy != null && legacy.recalculateHeader();
    }

    @Override
    public boolean isOversized(final int x, final int z) {
        final RegionFile legacy = this.legacy;
        return legacy != null && legacy.isOversized(x, z);
    }

    @Override
    public void setOversized(final int x, final int z, final boolean oversized) throws IOException {
        final RegionFile legacy = this.legacy;
        if (legacy != null) {
            legacy.setOversized(x, z, oversized);
        }
    }

    @Override
    public @Nullable CompoundTag getOversizedData(final int x, final int z) throws IOException {
        final RegionFile legacy = this.legacy;
        return legacy == null ? null : legacy.getOversizedData(x, z);
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

        byte[] bytes() {
            return this.toByteArray();
        }
    }
}
