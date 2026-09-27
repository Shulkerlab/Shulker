import com.github.luben.zstd.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/** Per-chunk compression: size and speed, zlib vs zstd levels, with and without a trained dictionary. */
public class ZBench {
    public static void main(String[] a) throws Exception {
        List<Path> files = new ArrayList<>();
        try (var s = Files.list(Path.of(a[0]))) { s.sorted().forEach(files::add); }
        // Train on chunks from regions whose name hashes even, test on the rest (no leakage).
        List<byte[]> train = new ArrayList<>(), test = new ArrayList<>();
        for (Path p : files) {
            String region = p.getFileName().toString().split("\\.nbt")[0].replaceAll("\\.\\d+$", "");
            (Math.floorMod(region.hashCode(), 2) == 0 ? train : test).add(Files.readAllBytes(p));
        }
        long raw = test.stream().mapToLong(b -> b.length).sum();
        System.out.printf("train %d chunks, test %d chunks (%.1f MiB raw)%n", train.size(), test.size(), raw / 1048576.0);
        ZstdDictTrainer trainer = new ZstdDictTrainer(train.stream().mapToInt(b -> b.length).sum(), 112 * 1024);
        for (byte[] b : train) trainer.addSample(b);
        byte[] dict = trainer.trainSamples();
        System.out.printf("dictionary %d KiB%n", dict.length / 1024);
        System.out.printf("%-14s %9s %8s %12s %12s%n", "codec", "MiB", "ratio", "comp MB/s", "decomp MB/s");
        bench("zlib-6", test, raw, b -> { Deflater d = new Deflater(6); d.setInput(b); d.finish(); byte[] o = new byte[b.length + 64]; int n = d.deflate(o); d.end(); return Arrays.copyOf(o, n); },
            (c, len) -> { Inflater i = new Inflater(); i.setInput(c); byte[] o = new byte[len]; try { i.inflate(o); } catch (Exception e) { throw new RuntimeException(e); } i.end(); return o; });
        for (int level : new int[] {1, 3, 6, 9, 12, 15, 19}) {
            bench("zstd-" + level, test, raw, b -> Zstd.compress(b, level), (c, len) -> Zstd.decompress(c, len));
            ZstdDictCompress dc = new ZstdDictCompress(dict, level);
            ZstdDictDecompress dd = new ZstdDictDecompress(dict);
            bench("zstd-" + level + "+dict", test, raw, b -> Zstd.compress(b, dc), (c, len) -> Zstd.decompress(c, dd, len));
        }
    }

    interface C { byte[] c(byte[] b); }
    interface D { byte[] d(byte[] c, int len); }

    static void bench(String name, List<byte[]> data, long raw, C c, D d) {
        for (int w = 0; w < 2; w++) for (byte[] b : data) d.d(c.c(b), b.length); // warm up
        long size = 0, t0 = System.nanoTime();
        List<byte[]> out = new ArrayList<>();
        for (byte[] b : data) { byte[] x = c.c(b); out.add(x); size += x.length; }
        long t1 = System.nanoTime();
        for (int i = 0; i < data.size(); i++) d.d(out.get(i), data.get(i).length);
        long t2 = System.nanoTime();
        System.out.printf("%-14s %9.2f %8.2f %12.0f %12.0f%n", name, size / 1048576.0, raw / (double) size,
            raw / 1e6 / ((t1 - t0) / 1e9), raw / 1e6 / ((t2 - t1) / 1e9));
    }
}
