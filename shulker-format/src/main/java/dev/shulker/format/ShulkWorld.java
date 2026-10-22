package dev.shulker.format;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.CRC32C;
import org.jspecify.annotations.Nullable;

/**
 * A whole world in one {@code .shulk} file: the "world" layout of the format, next to the
 * {@code r.X.Z.shulk} "region" layout ({@link ShulkRegion}). Meant for small, bounded worlds -
 * islands, arenas, lobbies - that are loaded whole, kept in memory and saved whole.
 *
 * <pre>
 * [ magic "SHULKWD1" u64 ][ version u32 ][ flags u32 ][ chunk count u32 ][ file count u32 ][ property count u32 ]
 * [ chunks:     kind u8, x i32, z i32, compressed length u32, raw length u32, timestamp u32,
 *               dictionary u16, codec u8, level u8, data ]
 * [ files:      path length u16, path (UTF-8), raw length u32, compressed length u32, codec u8, data ]
 * [ properties: key length u16, key (UTF-8), value length u32, value (UTF-8) ]
 * [ CRC32C of everything before it, u32 ]
 * </pre>
 *
 * <p>Chunks are kept compressed in memory as immutable byte arrays, exactly as they are stored.
 * Reading a chunk decompresses it; writing one compresses it and swaps the entry. That makes a
 * {@link #copy()} a copy of references - no chunk is decompressed or recompressed - so cloning a
 * template world costs next to nothing, and an unchanged chunk is never recompressed on save.
 *
 * <p>Files are the world's small non-chunk files ({@code level.dat}, {@code data/*.dat}, ...)
 * under their path relative to the world folder. Properties are free-form string metadata.
 *
 * <p>Integers are little-endian. Thread-safe: chunk, file and property maps are concurrent, and
 * a chunk entry is replaced atomically.
 */
public final class ShulkWorld {

    public static final String EXTENSION = ShulkRegion.EXTENSION;

    static final long MAGIC = 0x3144574B4C554853L; // "SHULKWD1" read as little-endian bytes
    static final int VERSION = 1;
    /** Level used for files and for chunks written without an explicit level. */
    static final int DEFAULT_LEVEL = 6;

    /** Room for every header before the first chunk, checked on read. */
    private static final int FIXED_HEADER = 8 + 4 * 5;
    private static final int MAX_PATH = 4096;

    /** One stored chunk, exactly as it is on disk. Never mutated once created. */
    record Stored(byte[] data, int rawLength, int timestamp, int dictionary, int codec, int level) {
    }

    private final Map<RegionKind, Map<Long, Stored>> chunks = new EnumMap<>(RegionKind.class);
    private final Map<String, byte[]> files = new ConcurrentHashMap<>();
    private final Map<String, String> properties = new ConcurrentHashMap<>();
    /** Bumped on every change, so a saver can tell whether anything happened since the last save. */
    private final AtomicLong modifications = new AtomicLong();

    public ShulkWorld() {
        for (RegionKind kind : RegionKind.values()) {
            this.chunks.put(kind, new ConcurrentHashMap<>());
        }
    }

    private static long key(final int chunkX, final int chunkZ) {
        return ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
    }

    public static int keyX(final long key) {
        return (int) (key >> 32);
    }

    public static int keyZ(final long key) {
        return (int) key;
    }

    // ---- chunks ----------------------------------------------------------------------------

    public boolean has(final RegionKind kind, final int chunkX, final int chunkZ) {
        return this.chunks.get(kind).containsKey(key(chunkX, chunkZ));
    }

    /** Uncompressed chunk bytes, or null when the chunk is absent. */
    public byte @Nullable [] read(final RegionKind kind, final int chunkX, final int chunkZ) {
        final Stored stored = this.chunks.get(kind).get(key(chunkX, chunkZ));
        return stored == null ? null : Codec.decompress(stored.codec(), stored.data(), stored.rawLength(), stored.dictionary());
    }

    /** Seconds since the epoch at which the chunk was written, 0 when absent. */
    public int timestamp(final RegionKind kind, final int chunkX, final int chunkZ) {
        final Stored stored = this.chunks.get(kind).get(key(chunkX, chunkZ));
        return stored == null ? 0 : stored.timestamp();
    }

    /** Compresses and stores a chunk with the kind's current dictionary. */
    public void write(final RegionKind kind, final int chunkX, final int chunkZ, final byte[] raw, final int timestamp, final int level) {
        final int dictionary = Dictionaries.current(kind);
        final byte[] compressed = Codec.compress(raw, level, dictionary); // outside any lock
        this.chunks.get(kind).put(key(chunkX, chunkZ), new Stored(compressed, raw.length, timestamp, dictionary, Codec.ZSTD, level));
        this.modifications.incrementAndGet();
    }

    public void delete(final RegionKind kind, final int chunkX, final int chunkZ) {
        if (this.chunks.get(kind).remove(key(chunkX, chunkZ)) != null) {
            this.modifications.incrementAndGet();
        }
    }

    /** Positions of the stored chunks of a kind, as keys for {@link #keyX} and {@link #keyZ}. */
    public Set<Long> chunkKeys(final RegionKind kind) {
        return Set.copyOf(this.chunks.get(kind).keySet());
    }

    /** Whether any chunk of the kind lies in the 32x32 region at the given region coordinates. */
    public boolean hasRegion(final RegionKind kind, final int regionX, final int regionZ) {
        for (long key : this.chunks.get(kind).keySet()) {
            if (keyX(key) >> 5 == regionX && keyZ(key) >> 5 == regionZ) {
                return true;
            }
        }
        return false;
    }

    public int chunkCount(final RegionKind kind) {
        return this.chunks.get(kind).size();
    }

    /** Compressed size of every chunk and file: roughly what the world costs in memory. */
    public long compressedBytes() {
        long total = 0;
        for (Map<Long, Stored> map : this.chunks.values()) {
            for (Stored stored : map.values()) {
                total += stored.data().length;
            }
        }
        for (byte[] file : this.files.values()) {
            total += file.length;
        }
        return total;
    }

    // ---- files and properties --------------------------------------------------------------

    /** A small file of the world by its path relative to the world folder, '/'-separated. */
    public byte @Nullable [] file(final String path) {
        return this.files.get(normalize(path));
    }

    public void putFile(final String path, final byte[] contents) {
        this.files.put(normalize(path), contents.clone());
        this.modifications.incrementAndGet();
    }

    public void removeFile(final String path) {
        if (this.files.remove(normalize(path)) != null) {
            this.modifications.incrementAndGet();
        }
    }

    public Set<String> filePaths() {
        return Set.copyOf(this.files.keySet());
    }

    public @Nullable String property(final String key) {
        return this.properties.get(key);
    }

    public void setProperty(final String key, final @Nullable String value) {
        if (value == null) {
            this.properties.remove(key);
        } else {
            this.properties.put(key, value);
        }
        this.modifications.incrementAndGet();
    }

    public Map<String, String> properties() {
        return Map.copyOf(this.properties);
    }

    /** Paths stay inside the world folder: no absolute paths, no "..", forward slashes only. */
    static String normalize(final String path) {
        final String unix = path.replace('\\', '/');
        if (unix.isEmpty() || unix.startsWith("/") || unix.contains(":") || unix.length() > MAX_PATH) {
            throw new IllegalArgumentException("Not a relative world file path: " + path);
        }
        for (String part : unix.split("/")) {
            if (part.isEmpty() || part.equals(".") || part.equals("..")) {
                throw new IllegalArgumentException("Not a relative world file path: " + path);
            }
        }
        return unix;
    }

    // ---- copies and change tracking --------------------------------------------------------

    /** An independent world sharing this one's (immutable) chunk data: cheap, whatever the size. */
    public ShulkWorld copy() {
        final ShulkWorld copy = new ShulkWorld();
        for (RegionKind kind : RegionKind.values()) {
            copy.chunks.get(kind).putAll(this.chunks.get(kind));
        }
        copy.files.putAll(this.files); // byte arrays are never mutated in place either
        copy.properties.putAll(this.properties);
        return copy;
    }

    /**
     * Positions, as keys for {@link #keyX} and {@link #keyZ}, where this world and {@code other}
     * do not hold the same chunk of the kind: present in only one, or stored differently. Worlds
     * copied from one another share their unchanged chunks, so this compares references and
     * decompresses nothing; a chunk rewritten with identical content still counts as different.
     */
    public Set<Long> differentChunks(final ShulkWorld other, final RegionKind kind) {
        final Map<Long, Stored> mine = this.chunks.get(kind);
        final Map<Long, Stored> theirs = other.chunks.get(kind);
        final Set<Long> different = new java.util.HashSet<>();
        for (Map.Entry<Long, Stored> entry : mine.entrySet()) {
            if (theirs.get(entry.getKey()) != entry.getValue()) {
                different.add(entry.getKey());
            }
        }
        for (Long key : theirs.keySet()) {
            if (!mine.containsKey(key)) {
                different.add(key);
            }
        }
        return different;
    }

    /** Makes this world hold exactly {@code other}'s chunk of the kind at a position: the same stored data, or none. */
    public void copyChunkFrom(final ShulkWorld other, final RegionKind kind, final int chunkX, final int chunkZ) {
        final long key = key(chunkX, chunkZ);
        final Stored stored = other.chunks.get(kind).get(key);
        final Stored previous = stored == null ? this.chunks.get(kind).remove(key) : this.chunks.get(kind).put(key, stored);
        if (previous != stored) {
            this.modifications.incrementAndGet();
        }
    }

    /** Increases on every change; compare two readings to see whether the world changed. */
    public long modifications() {
        return this.modifications.get();
    }

    Map<Long, Stored> chunksForTest(final RegionKind kind) {
        return this.chunks.get(kind);
    }

    // ---- serialization ---------------------------------------------------------------------

    /** True when the bytes start like a world-layout {@code .shulk} file. */
    public static boolean isWorldFile(final byte[] start) {
        return start.length >= 8 && ByteBuffer.wrap(start, 0, 8).order(ByteOrder.LITTLE_ENDIAN).getLong() == MAGIC;
    }

    /** True when the file starts like a world-layout {@code .shulk} file (and not a region). */
    public static boolean isWorldFile(final Path path) throws IOException {
        try (InputStream in = Files.newInputStream(path)) {
            return isWorldFile(in.readNBytes(8));
        }
    }

    public byte[] toBytes() {
        final Out out = new Out();
        // Snapshots, so a concurrent write cannot make the counts disagree with the entries.
        final Map<RegionKind, Map<Long, Stored>> chunks = new EnumMap<>(RegionKind.class);
        int chunkCount = 0;
        for (RegionKind kind : RegionKind.values()) {
            final Map<Long, Stored> sorted = new TreeMap<>(this.chunks.get(kind));
            chunks.put(kind, sorted);
            chunkCount += sorted.size();
        }
        final Map<String, byte[]> files = new TreeMap<>(this.files);
        final Map<String, String> properties = new TreeMap<>(this.properties);

        out.putLong(MAGIC);
        out.putInt(VERSION);
        out.putInt(0); // flags, none defined yet
        out.putInt(chunkCount);
        out.putInt(files.size());
        out.putInt(properties.size());
        for (Map.Entry<RegionKind, Map<Long, Stored>> byKind : chunks.entrySet()) {
            for (Map.Entry<Long, Stored> entry : byKind.getValue().entrySet()) {
                final Stored stored = entry.getValue();
                out.putByte(kindId(byKind.getKey()));
                out.putInt(keyX(entry.getKey()));
                out.putInt(keyZ(entry.getKey()));
                out.putInt(stored.data().length);
                out.putInt(stored.rawLength());
                out.putInt(stored.timestamp());
                out.putShort(stored.dictionary());
                out.putByte(stored.codec());
                out.putByte(stored.level());
                out.put(stored.data());
            }
        }
        for (Map.Entry<String, byte[]> file : files.entrySet()) {
            final byte[] raw = file.getValue();
            final byte[] compressed = Codec.compress(raw, DEFAULT_LEVEL, Dictionaries.NONE);
            out.putString16(file.getKey());
            out.putInt(raw.length);
            out.putInt(compressed.length);
            out.putByte(Codec.ZSTD);
            out.put(compressed);
        }
        for (Map.Entry<String, String> property : properties.entrySet()) {
            out.putString16(property.getKey());
            final byte[] value = property.getValue().getBytes(StandardCharsets.UTF_8);
            out.putInt(value.length);
            out.put(value);
        }
        final CRC32C crc = new CRC32C();
        crc.update(out.buffer(), 0, out.size());
        out.putInt((int) crc.getValue());
        return out.toByteArray();
    }

    /**
     * Reads a world written by {@link #toBytes()}. The whole-file checksum is checked first, so a
     * truncated or damaged file is rejected instead of half loaded.
     */
    public static ShulkWorld fromBytes(final byte[] bytes) throws IOException {
        if (bytes.length < FIXED_HEADER + 4) {
            throw new IOException("Not a world .shulk file (too short)");
        }
        final ByteBuffer in = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        if (in.getLong() != MAGIC) {
            if (in.getLong(0) == ShulkRegion.MAGIC) {
                throw new IOException("This is a region .shulk file (r.X.Z.shulk), not a world .shulk file");
            }
            throw new IOException("Not a world .shulk file (bad magic)");
        }
        final int version = in.getInt();
        if (version > VERSION) {
            throw new IOException("World .shulk version " + version + " was written by a newer Shulker (this one reads up to " + VERSION + ")");
        }
        final CRC32C crc = new CRC32C();
        crc.update(bytes, 0, bytes.length - 4);
        if ((int) crc.getValue() != in.getInt(bytes.length - 4)) {
            throw new IOException("World .shulk file is damaged (checksum mismatch)");
        }
        in.limit(bytes.length - 4);
        try {
            in.getInt(); // flags
            final int chunkCount = in.getInt();
            final int fileCount = in.getInt();
            final int propertyCount = in.getInt();
            final ShulkWorld world = new ShulkWorld();
            for (int i = 0; i < chunkCount; i++) {
                final RegionKind kind = kindById(in.get());
                final int x = in.getInt();
                final int z = in.getInt();
                final int length = in.getInt();
                final int rawLength = in.getInt();
                final int timestamp = in.getInt();
                final int dictionary = Short.toUnsignedInt(in.getShort());
                final int codec = Byte.toUnsignedInt(in.get());
                final int level = in.get();
                final byte[] data = take(in, length);
                world.chunks.get(kind).put(key(x, z), new Stored(data, rawLength, timestamp, dictionary, codec, level));
            }
            for (int i = 0; i < fileCount; i++) {
                final String path = normalize(string16(in));
                final int rawLength = in.getInt();
                final int length = in.getInt();
                final int codec = Byte.toUnsignedInt(in.get());
                world.files.put(path, Codec.decompress(codec, take(in, length), rawLength, Dictionaries.NONE));
            }
            for (int i = 0; i < propertyCount; i++) {
                final String key = string16(in);
                world.properties.put(key, new String(take(in, in.getInt()), StandardCharsets.UTF_8));
            }
            if (in.hasRemaining()) {
                throw new IOException("World .shulk file has " + in.remaining() + " unexpected trailing bytes");
            }
            return world;
        } catch (java.nio.BufferUnderflowException | IllegalArgumentException e) {
            throw new IOException("World .shulk file is malformed: " + e.getMessage(), e);
        }
    }

    public static ShulkWorld read(final Path path) throws IOException {
        return fromBytes(Files.readAllBytes(path));
    }

    /** Writes the world crash-safely: to a temporary sibling, synced, then moved over the target. */
    public void write(final Path path) throws IOException {
        writeAtomically(path, this.toBytes());
    }

    /**
     * Replaces {@code target} with {@code data} so that a crash leaves either the old file or the
     * new one, never a mix: write a sibling, force it to disk, then rename it over the target.
     */
    public static void writeAtomically(final Path target, final byte[] data) throws IOException {
        final Path absolute = target.toAbsolutePath();
        final Path parent = absolute.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        final Path temporary = absolute.resolveSibling(absolute.getFileName() + ".tmp");
        try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
            final ByteBuffer buffer = ByteBuffer.wrap(data);
            while (buffer.hasRemaining()) {
                channel.write(buffer);
            }
            channel.force(true);
        }
        try {
            Files.move(temporary, absolute, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temporary, absolute, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    // ---- encoding helpers ------------------------------------------------------------------

    /** Kind ids are stored on disk: never renumber. */
    private static int kindId(final RegionKind kind) {
        return switch (kind) {
            case CHUNK -> 0;
            case ENTITIES -> 1;
            case POI -> 2;
            case OTHER -> 3;
        };
    }

    private static RegionKind kindById(final int id) {
        return switch (id) {
            case 0 -> RegionKind.CHUNK;
            case 1 -> RegionKind.ENTITIES;
            case 2 -> RegionKind.POI;
            case 3 -> RegionKind.OTHER;
            default -> throw new IllegalArgumentException("unknown chunk kind " + id);
        };
    }

    private static byte[] take(final ByteBuffer in, final int length) {
        if (length < 0 || length > in.remaining()) {
            throw new IllegalArgumentException("length " + Integer.toUnsignedString(length) + " runs past the end");
        }
        final byte[] data = new byte[length];
        in.get(data);
        return data;
    }

    private static String string16(final ByteBuffer in) {
        return new String(take(in, Short.toUnsignedInt(in.getShort())), StandardCharsets.UTF_8);
    }

    /** A growable little-endian buffer. */
    private static final class Out extends ByteArrayOutputStream {
        private final ByteBuffer scratch = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);

        Out() {
            super(64 * 1024);
        }

        byte[] buffer() {
            return this.buf;
        }

        void putByte(final int value) {
            this.write(value);
        }

        void putShort(final int value) {
            this.scratch.clear();
            this.scratch.putShort((short) value);
            this.write(this.scratch.array(), 0, 2);
        }

        void putInt(final int value) {
            this.scratch.clear();
            this.scratch.putInt(value);
            this.write(this.scratch.array(), 0, 4);
        }

        void putLong(final long value) {
            this.scratch.clear();
            this.scratch.putLong(value);
            this.write(this.scratch.array(), 0, 8);
        }

        void put(final byte[] data) {
            this.write(data, 0, data.length);
        }

        void putString16(final String value) {
            final byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
            if (bytes.length > 0xFFFF) {
                throw new IllegalArgumentException("String too long for .shulk: " + bytes.length + " bytes");
            }
            this.putShort(bytes.length);
            this.put(bytes);
        }
    }
}
