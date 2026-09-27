package dev.shulker.launcher;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Stream;

/**
 * Project Leyden ahead-of-time cache (JEP 483/514/515, JEP 516 on 26+).
 *
 * <p>We use the explicit two-step workflow instead of {@code -XX:AOTCacheOutput}: the latter
 * spawns a second JVM with the server's heap settings at shutdown, which would briefly double
 * memory use on a server running with a pre-touched heap.
 *
 * <ol>
 *   <li>No configuration yet: run the server with {@code -XX:AOTMode=record}. The JVM writes the
 *       observed class loading/linking profile at exit.</li>
 *   <li>Configuration present, no cache: before starting, build the cache in a small JVM with
 *       {@code -XX:AOTMode=create}, then start the server with it.</li>
 *   <li>Cache present: start with {@code -XX:AOTCache}. A stale cache is ignored by the JVM.</li>
 * </ol>
 */
final class AotCache {

    private final Path dir;
    private final String key;

    /**
     * @param keyArgs JVM arguments that must match between recording and use (GC, headers...)
     */
    AotCache(Path shulkerDir, HostInfo host, ServerBundle bundle, List<String> keyArgs) throws IOException {
        this.dir = shulkerDir.resolve("aot");
        this.key = hash(host.javaVersion() + "|" + bundle.jarFingerprint() + "|" + String.join(" ", keyArgs));
    }

    private Path configuration() {
        return dir.resolve(key + ".aotconf");
    }

    private Path cache() {
        return dir.resolve(key + ".aot");
    }

    /** Returns the arguments to add to the server JVM, creating the cache first if possible. */
    List<String> prepare(String java, List<String> cacheArgs, Path bootJar, Path workDir) {
        try {
            Files.createDirectories(dir);
            deleteStale();
            if (Files.isRegularFile(cache())) {
                Log.info("AOT cache: using " + workDir.relativize(cache()));
                return withLogging(List.of("-XX:AOTCache=" + cache()), workDir);
            }
            if (Files.isRegularFile(configuration())) {
                if (create(java, cacheArgs, bootJar, workDir)) {
                    return withLogging(List.of("-XX:AOTCache=" + cache()), workDir);
                }
                Files.deleteIfExists(configuration());
            }
            Log.info("AOT cache: recording this run; startup gets faster after the next restart");
            return withLogging(List.of("-XX:AOTMode=record", "-XX:AOTConfiguration=" + configuration()), workDir);
        } catch (IOException | InterruptedException e) {
            Log.warn("AOT cache disabled for this run: " + e.getMessage());
            return List.of();
        }
    }

    /**
     * The JVM reports every class it cannot cache (signed jars, proxies...) as a warning on the
     * console. Keep only errors there and send the rest to a log file.
     */
    private List<String> withLogging(List<String> args, Path workDir) {
        final List<String> all = new ArrayList<>(args);
        all.add("-Xlog:aot*=error,cds*=error");
        all.add("-Xlog:aot*=warning,cds*=warning:file=" + workDir.relativize(dir.resolve("aot.log")));
        return all;
    }

    private boolean create(String java, List<String> cacheArgs, Path bootJar, Path workDir)
        throws IOException, InterruptedException {
        Log.info("AOT cache: building from last run's profile...");
        final List<String> command = new ArrayList<>();
        command.add(java);
        command.add("-Xmx2G");
        command.addAll(cacheArgs);
        command.add("-XX:AOTMode=create");
        command.add("-XX:AOTConfiguration=" + configuration());
        command.add("-XX:AOTCache=" + cache());
        // Same class path as the server run: the boot jar and its manifest Class-Path.
        command.add("-cp");
        command.add(workDir.relativize(bootJar).toString());
        final Path log = dir.resolve("create.log");
        final Process process = new ProcessBuilder(command)
            .directory(workDir.toFile())
            .redirectErrorStream(true)
            .redirectOutput(log.toFile())
            .start();
        if (process.waitFor() != 0 || !Files.isRegularFile(cache())) {
            Log.warn("AOT cache creation failed, see " + workDir.relativize(log));
            return false;
        }
        Log.info("AOT cache: ready (" + Log.bytes(Files.size(cache())) + ")");
        return true;
    }

    /** Caches for another JVM, jar or flag set can never be used again. */
    private void deleteStale() throws IOException {
        try (Stream<Path> files = Files.list(dir)) {
            for (Path file : files.toList()) {
                final String name = file.getFileName().toString();
                if ((name.endsWith(".aot") || name.endsWith(".aotconf")) && !name.startsWith(key + ".")) {
                    Files.deleteIfExists(file);
                }
            }
        }
    }

    private static String hash(String value) {
        try {
            final byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 8);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
