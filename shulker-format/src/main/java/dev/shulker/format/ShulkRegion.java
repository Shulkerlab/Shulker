package dev.shulker.format;

import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.CRC32C;
import org.jspecify.annotations.Nullable;

/**
 * One {@code r.X.Z.shulk} file: up to 32x32 chunks, each compressed on its own.
 *
 * <pre>
 * [ header: magic, version, 1024 x 24-byte entries ][ data: compressed chunks and free gaps ]
 * </pre>
 *
 * <p>Each entry: data offset (u32), compressed length (u32), raw length (u32), timestamp (u32),
 * dictionary id (u16), codec (u8), level (u8), CRC32C of the previous 20 bytes (u32). An all-zero
 * entry is an absent chunk. All integers are little-endian.
 *
 * <p>Crash safety: new data always goes to free space and the entry is rewritten last, so an
 * interrupted write leaves either the old entry (pointing at intact old data) or an entry whose
 * CRC fails, which loses only that chunk. Old data is only released after the new entry is
 * written.
 *
 * <p>Thread-safe; all methods synchronise on the region.
 */
public final class ShulkRegion implements Closeable {

    public static final String EXTENSION = ".shulk";
    public static final int CHUNKS = 1024;

    static final long MAGIC = 0x3147524B4C554853L; // "SHULKRG1" read as little-endian bytes
    static final int VERSION = 1;
    static final int ENTRY_SIZE = 24;
    static final int HEADER_SIZE = 16 + CHUNKS * ENTRY_SIZE;

    /** Compact on close when the file is this much larger than its live data. */
    private static final double COMPACT_WASTE_RATIO = 1.25;
    private static final long COMPACT_MIN_WASTE = 64 * 1024;

    private final Path path;
    private final RegionKind kind;
    private final boolean sync;
    private final FileChannel channel;

    private final int[] offset = new int[CHUNKS];
    private final int[] length = new int[CHUNKS];
    private final int[] rawLength = new int[CHUNKS];
    private final int[] timestamp = new int[CHUNKS];
    private final short[] dictionary = new short[CHUNKS];
    private final byte[] codec = new byte[CHUNKS];
    private final byte[] level = new byte[CHUNKS];
    /** In memory only: bumped on every change, so background work can detect a racing write. */
    private final int[] generation = new int[CHUNKS];

    /** Free space inside the file: offset -> length. The file end is tracked separately. */
    private final TreeMap<Long, Long> gaps = new TreeMap<>();
    private long end;
    private int damagedEntries;
    private boolean closed;

    private ShulkRegion(final Path path, final RegionKind kind, final boolean sync, final FileChannel channel) {
        this.path = path;
        this.kind = kind;
        this.sync = sync;
        this.channel = channel;
    }

    /** Opens the file, creating it with an empty header when it does not exist. */
    public static ShulkRegion open(final Path path, final RegionKind kind, final boolean sync) throws IOException {
        final FileChannel channel = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
        final ShulkRegion region = new ShulkRegion(path, kind, sync, channel);
        try {
            if (channel.size() == 0) {
                region.writeEmptyHeader();
            } else {
                region.readHeader();
            }
        } catch (IOException | RuntimeException e) {
            channel.close();
            throw e;
        }
        return region;
    }

    public static int index(final int chunkX, final int chunkZ) {
        return (chunkX & 31) + (chunkZ & 31) * 32;
    }

    // ---- reading ---------------------------------------------------------------------------

    public synchronized boolean has(final int index) {
        return this.length[index] != 0;
    }

    /** Seconds since the epoch at which the chunk was written, 0 when absent. */
    public synchronized int timestamp(final int index) {
        return this.timestamp[index];
    }

    /** Uncompressed chunk bytes, or null when the chunk is absent. */
    public synchronized byte @Nullable [] read(final int index) throws IOException {
        this.ensureOpen();
        if (this.length[index] == 0) {
            return null;
        }
        final byte[] data = new byte[this.length[index]];
        readFully(ByteBuffer.wrap(data), Integer.toUnsignedLong(this.offset[index]));
        return Codec.decompress(this.codec[index], data, this.rawLength[index], Short.toUnsignedInt(this.dictionary[index]));
    }

    // ---- writing ---------------------------------------------------------------------------

    /** Compresses and stores a chunk with the kind's current dictionary. */
    public void write(final int index, final byte[] raw, final int timestamp, final int level) throws IOException {
        final int dictionary = Dictionaries.current(this.kind);
        final byte[] compressed = Codec.compress(raw, level, dictionary);
        synchronized (this) {
            this.commit(index, compressed, raw.length, timestamp, dictionary, Codec.ZSTD, level);
        }
    }

    public synchronized void delete(final int index) throws IOException {
        this.ensureOpen();
        if (this.length[index] == 0) {
            return;
        }
        final long oldOffset = Integer.toUnsignedLong(this.offset[index]);
        final long oldLength = this.length[index];
        this.setEntry(index, 0, 0, 0, 0, 0, 0, 0);
        this.writeEntry(index);
        this.release(oldOffset, oldLength);
    }

    /**
     * Recompresses a chunk at another level, keeping its timestamp. Returns false when the chunk
     * is gone or was rewritten meanwhile.
     */
    public boolean recompress(final int index, final int newLevel) throws IOException {
        final byte[] raw;
        final int stamp;
        final int seen;
        synchronized (this) {
            raw = this.read(index);
            stamp = this.timestamp[index];
            seen = this.generation[index];
        }
        if (raw == null) {
            return false;
        }
        final int dictionary = Dictionaries.current(this.kind);
        final byte[] compressed = Codec.compress(raw, newLevel, dictionary); // slow part, outside the lock
        synchronized (this) {
            if (this.generation[index] != seen || this.length[index] == 0) {
                return false; // rewritten while we were compressing
            }
            this.commit(index, compressed, raw.length, stamp, dictionary, Codec.ZSTD, newLevel);
            return true;
        }
    }

    private void commit(final int index, final byte[] data, final int rawLen, final int stamp,
                        final int dict, final int codecId, final int lvl) throws IOException {
        this.ensureOpen();
        final long position = this.allocate(data.length);
        writeFully(ByteBuffer.wrap(data), position);
        if (this.sync) {
            this.channel.force(false);
        }
        final long oldOffset = Integer.toUnsignedLong(this.offset[index]);
        final long oldLength = this.length[index];
        this.setEntry(index, (int) position, data.length, rawLen, stamp, dict, codecId, lvl);
        this.writeEntry(index);
        if (this.sync) {
            this.channel.force(false);
        }
        if (oldLength != 0) {
            this.release(oldOffset, oldLength);
        }
    }

    public synchronized void flush() throws IOException {
        this.ensureOpen();
        this.channel.force(false);
    }

    @Override
    public synchronized void close() throws IOException {
        if (this.closed) {
            return;
        }
        this.closed = true;
        final long live = this.liveBytes();
        final long waste = this.end - HEADER_SIZE - live;
        final boolean compact = waste > COMPACT_MIN_WASTE && this.end - HEADER_SIZE > live * COMPACT_WASTE_RATIO;
        Path compacted = null;
        try {
            if (compact) {
                compacted = this.writeCompacted();
            } else {
                this.channel.truncate(this.end); // drop free space at the end of the file
                this.channel.force(true);
            }
        } finally {
            this.channel.close();
        }
        if (compacted != null) {
            // After closing: replacing an open file fails on Windows.
            Files.move(compacted, this.path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        }
    }

    // ---- inspection ------------------------------------------------------------------------

    public Path path() {
        return this.path;
    }

    public RegionKind kind() {
        return this.kind;
    }

    public synchronized int chunkCount() {
        int count = 0;
        for (int len : this.length) {
            if (len != 0) {
                count++;
            }
        }
        return count;
    }

    public synchronized long liveBytes() {
        long total = 0;
        for (int len : this.length) {
            total += len;
        }
        return total;
    }

    public synchronized long fileSize() {
        return this.end;
    }

    /** Entries dropped on open because their checksum or bounds were wrong. */
    public synchronized int damagedEntries() {
        return this.damagedEntries;
    }

    /** Chunks stored below {@code level} that were last written before {@code writtenBefore}. */
    public synchronized List<Integer> coldChunks(final int level, final int writtenBefore) {
        final List<Integer> cold = new ArrayList<>();
        for (int i = 0; i < CHUNKS; i++) {
            if (this.length[i] != 0 && this.level[i] < level && Integer.compareUnsigned(this.timestamp[i], writtenBefore) < 0) {
                cold.add(i);
            }
        }
        return cold;
    }

    // ---- header ----------------------------------------------------------------------------

    private void writeEmptyHeader() throws IOException {
        final ByteBuffer header = ByteBuffer.allocate(HEADER_SIZE);
        header.put(this.encodeHeaderStart()).clear();
        writeFully(header, 0);
        this.end = HEADER_SIZE;
        if (this.sync) {
            this.channel.force(true);
        }
    }

    private void readHeader() throws IOException {
        final long size = this.channel.size();
        if (size < HEADER_SIZE) {
            throw new IOException(this.path + " is not a .shulk region (truncated header)");
        }
        final ByteBuffer header = ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN);
        readFully(header, 0);
        header.flip();
        if (header.getLong() != MAGIC) {
            throw new IOException(this.path + " is not a .shulk region (bad magic)");
        }
        final int version = Short.toUnsignedInt(header.getShort());
        if (version > VERSION) {
            throw new IOException(this.path + " was written by a newer Shulker (format " + version + ")");
        }
        header.position(16);

        final TreeMap<Long, Long> used = new TreeMap<>();
        final CRC32C crc = new CRC32C();
        for (int i = 0; i < CHUNKS; i++) {
            final int start = header.position();
            final int off = header.getInt();
            final int len = header.getInt();
            final int raw = header.getInt();
            final int stamp = header.getInt();
            final short dict = header.getShort();
            final byte cdc = header.get();
            final byte lvl = header.get();
            final int storedCrc = header.getInt();
            if (off == 0 && len == 0 && raw == 0 && stamp == 0 && dict == 0 && cdc == 0 && lvl == 0 && storedCrc == 0) {
                continue;
            }
            crc.reset();
            crc.update(header.array(), start, ENTRY_SIZE - 4);
            final long offsetValue = Integer.toUnsignedLong(off);
            final boolean inBounds = len > 0 && offsetValue >= HEADER_SIZE && offsetValue + len <= size;
            final boolean overlaps = inBounds && overlaps(used, offsetValue, len);
            if ((int) crc.getValue() != storedCrc || !inBounds || overlaps) {
                this.damagedEntries++;
                continue;
            }
            this.setEntry(i, off, len, raw, stamp, Short.toUnsignedInt(dict), Byte.toUnsignedInt(cdc), Byte.toUnsignedInt(lvl));
            used.put(offsetValue, (long) len);
        }

        // Everything between live extents is free; trailing space is dropped by the next close.
        long cursor = HEADER_SIZE;
        for (Map.Entry<Long, Long> extent : used.entrySet()) {
            if (extent.getKey() > cursor) {
                this.gaps.put(cursor, extent.getKey() - cursor);
            }
            cursor = extent.getKey() + extent.getValue();
        }
        this.end = cursor;
    }

    private static boolean overlaps(final TreeMap<Long, Long> used, final long start, final long len) {
        final Map.Entry<Long, Long> before = used.floorEntry(start);
        if (before != null && before.getKey() + before.getValue() > start) {
            return true;
        }
        final Map.Entry<Long, Long> after = used.ceilingEntry(start);
        return after != null && after.getKey() < start + len;
    }

    private void setEntry(final int i, final int off, final int len, final int raw, final int stamp,
                          final int dict, final int cdc, final int lvl) {
        this.offset[i] = off;
        this.length[i] = len;
        this.rawLength[i] = raw;
        this.timestamp[i] = stamp;
        this.dictionary[i] = (short) dict;
        this.codec[i] = (byte) cdc;
        this.level[i] = (byte) lvl;
        this.generation[i]++;
    }

    private void writeEntry(final int i) throws IOException {
        writeFully(this.encodeEntry(i), 16L + (long) i * ENTRY_SIZE);
    }

    private ByteBuffer encodeEntry(final int i) {
        final ByteBuffer entry = ByteBuffer.allocate(ENTRY_SIZE).order(ByteOrder.LITTLE_ENDIAN);
        if (this.length[i] != 0) {
            entry.putInt(this.offset[i]).putInt(this.length[i]).putInt(this.rawLength[i]).putInt(this.timestamp[i])
                .putShort(this.dictionary[i]).put(this.codec[i]).put(this.level[i]);
            final CRC32C crc = new CRC32C();
            crc.update(entry.array(), 0, ENTRY_SIZE - 4);
            entry.putInt((int) crc.getValue());
        }
        return entry.clear();
    }

    private ByteBuffer encodeHeaderStart() {
        return ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
            .putLong(MAGIC).putShort((short) VERSION).putShort((short) this.kind.ordinal()).putInt(0)
            .flip();
    }

    // ---- space management ------------------------------------------------------------------

    /** Best-fit gap, else the end of the file. */
    private long allocate(final int size) throws IOException {
        Map.Entry<Long, Long> best = null;
        for (Map.Entry<Long, Long> gap : this.gaps.entrySet()) {
            if (gap.getValue() >= size && (best == null || gap.getValue() < best.getValue())) {
                best = gap;
            }
        }
        if (best == null) {
            final long position = this.end;
            if (position + size > 0xFFFF_FFFFL) {
                throw new IOException(this.path + " would exceed 4 GiB");
            }
            this.end += size;
            return position;
        }
        this.gaps.remove(best.getKey());
        if (best.getValue() > size) {
            this.gaps.put(best.getKey() + size, best.getValue() - size);
        }
        return best.getKey();
    }

    private void release(final long start, final long len) {
        long gapStart = start;
        long gapLen = len;
        final Map.Entry<Long, Long> before = this.gaps.floorEntry(start);
        if (before != null && before.getKey() + before.getValue() == start) {
            gapStart = before.getKey();
            gapLen += before.getValue();
            this.gaps.remove(before.getKey());
        }
        final Long afterLen = this.gaps.remove(start + len);
        if (afterLen != null) {
            gapLen += afterLen;
        }
        if (gapStart + gapLen == this.end) {
            this.end = gapStart; // free space at the end just shortens the file
        } else {
            this.gaps.put(gapStart, gapLen);
        }
    }

    /** Writes live chunks back to back into a sibling file; the caller swaps it in. */
    private Path writeCompacted() throws IOException {
        final Path temp = this.path.resolveSibling(this.path.getFileName() + ".compact");
        try (FileChannel out = FileChannel.open(temp, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            long cursor = HEADER_SIZE;
            for (int i = 0; i < CHUNKS; i++) {
                if (this.length[i] == 0) {
                    continue;
                }
                final ByteBuffer data = ByteBuffer.allocate(this.length[i]);
                readFully(data, Integer.toUnsignedLong(this.offset[i]));
                data.flip();
                this.offset[i] = (int) cursor;
                while (data.hasRemaining()) {
                    cursor += out.write(data, cursor);
                }
            }
            out.write(this.encodeHeaderStart(), 0);
            for (int i = 0; i < CHUNKS; i++) {
                out.write(this.encodeEntry(i), 16L + (long) i * ENTRY_SIZE);
            }
            out.force(true);
        }
        return temp;
    }

    // ---- io --------------------------------------------------------------------------------

    private void readFully(final ByteBuffer buffer, final long position) throws IOException {
        long pos = position;
        while (buffer.hasRemaining()) {
            final int read = this.channel.read(buffer, pos);
            if (read < 0) {
                throw new IOException(this.path + ": unexpected end of file");
            }
            pos += read;
        }
    }

    private void writeFully(final ByteBuffer buffer, final long position) throws IOException {
        long pos = position;
        while (buffer.hasRemaining()) {
            pos += this.channel.write(buffer, pos);
        }
    }

    private void ensureOpen() throws IOException {
        if (this.closed) {
            throw new IOException(this.path + " is closed");
        }
    }
}
