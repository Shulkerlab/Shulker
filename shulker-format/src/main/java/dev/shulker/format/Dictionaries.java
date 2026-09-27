package dev.shulker.format;

import com.github.luben.zstd.ZstdDictCompress;
import com.github.luben.zstd.ZstdDictDecompress;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;

/**
 * The zstd dictionaries built into Shulker, listed in {@code dict/index.properties}.
 * Dictionary id 0 means "no dictionary".
 */
public final class Dictionaries {

    public static final int NONE = 0;

    private static final Properties INDEX = loadIndex();
    private static final Map<Integer, byte[]> RAW = new ConcurrentHashMap<>();
    private static final Map<Long, ZstdDictCompress> COMPRESS = new ConcurrentHashMap<>();
    private static final Map<Integer, ZstdDictDecompress> DECOMPRESS = new ConcurrentHashMap<>();

    private Dictionaries() {
    }

    /** The dictionary new writes of this kind should use. */
    public static int current(final RegionKind kind) {
        return Integer.parseInt(INDEX.getProperty("current." + kind.id(), "0").trim());
    }

    static ZstdDictCompress compressor(final int id, final int level) {
        return COMPRESS.computeIfAbsent(((long) id << 32) | level, key -> new ZstdDictCompress(raw(id), level));
    }

    static ZstdDictDecompress decompressor(final int id) {
        return DECOMPRESS.computeIfAbsent(id, key -> new ZstdDictDecompress(raw(id)));
    }

    static byte[] raw(final int id) {
        return RAW.computeIfAbsent(id, key -> {
            final @Nullable String file = INDEX.getProperty(Integer.toString(id));
            if (file == null) {
                throw new IllegalArgumentException("Unknown .shulk dictionary id " + id + " (written by a newer Shulker?)");
            }
            try (InputStream in = Dictionaries.class.getResourceAsStream("dict/" + file.trim())) {
                if (in == null) {
                    throw new IllegalStateException("Missing dictionary resource " + file);
                }
                return in.readAllBytes();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
    }

    private static Properties loadIndex() {
        final Properties index = new Properties();
        try (InputStream in = Dictionaries.class.getResourceAsStream("dict/index.properties")) {
            if (in == null) {
                throw new IllegalStateException("Missing .shulk dictionary index");
            }
            index.load(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return index;
    }
}
