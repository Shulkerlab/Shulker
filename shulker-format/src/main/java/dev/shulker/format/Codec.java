package dev.shulker.format;

import com.github.luben.zstd.ZstdCompressCtx;
import com.github.luben.zstd.ZstdDecompressCtx;

/** Chunk payload compression. Codec ids are stored on disk: never renumber. */
final class Codec {

    static final int NONE = 0;
    static final int ZSTD = 1;

    private static final ThreadLocal<ZstdCompressCtx> COMPRESS = ThreadLocal.withInitial(ZstdCompressCtx::new);
    private static final ThreadLocal<ZstdDecompressCtx> DECOMPRESS = ThreadLocal.withInitial(ZstdDecompressCtx::new);

    private Codec() {
    }

    /** zstd frame with a content checksum, so corrupted data is detected on read. */
    static byte[] compress(final byte[] raw, final int level, final int dictionary) {
        final ZstdCompressCtx ctx = COMPRESS.get();
        ctx.reset();
        if (dictionary != Dictionaries.NONE) {
            ctx.loadDict(Dictionaries.compressor(dictionary, level)); // level comes with the dictionary
        } else {
            ctx.setLevel(level);
        }
        ctx.setChecksum(true);
        return ctx.compress(raw);
    }

    static byte[] decompress(final int codec, final byte[] data, final int rawLength, final int dictionary) {
        return switch (codec) {
            case NONE -> data;
            case ZSTD -> {
                final ZstdDecompressCtx ctx = DECOMPRESS.get();
                ctx.reset();
                if (dictionary != Dictionaries.NONE) {
                    ctx.loadDict(Dictionaries.decompressor(dictionary));
                }
                yield ctx.decompress(data, rawLength);
            }
            default -> throw new IllegalStateException("Unknown .shulk codec " + codec);
        };
    }
}
