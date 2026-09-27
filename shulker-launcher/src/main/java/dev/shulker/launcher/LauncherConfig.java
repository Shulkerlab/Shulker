package dev.shulker.launcher;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

/**
 * Launcher settings. Precedence: command line, then environment ({@code SHULKER_*}),
 * then {@code shulker-launcher.properties}, then defaults.
 */
public record LauncherConfig(
    Profile profile,
    long heapOverride,
    Collector collector,
    boolean aotCache,
    boolean managed,
    boolean dryRun,
    List<String> extraJvmArgs,
    List<String> serverArgs,
    String tickIsolation
) {

    public static final String FILE_NAME = "shulker-launcher.properties";

    /** Heap size is picked by the launcher. */
    public static final long AUTO = 0;

    public enum Collector {
        AUTO, G1, ZGC, SHENANDOAH;

        static Collector parse(String value) {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        }
    }

    private static final String DEFAULT_FILE = """
        # Shulker launcher settings. Command line (--profile=, --memory=, --gc=) and
        # SHULKER_PROFILE / SHULKER_MEMORY / SHULKER_GC environment variables override these.

        # Workload preset: survival, skyblock, minigame, lobby, vanilla
        # (vanilla: all exploits/dupes allowed, no behaviour-changing optimisations)
        profile=survival

        # Max heap. 'auto' sizes it from the machine / container limit, or e.g. 8G, 12288M
        memory=auto

        # Garbage collector: auto, g1, zgc, shenandoah
        gc=auto

        # Cache loaded classes between restarts for faster startup (Project Leyden AOT cache)
        aot-cache=true

        # When true the launcher restarts the JVM with tuned flags. Set to false to use
        # exactly the flags you pass to 'java' yourself.
        managed=true

        # Give the server's tick thread a physical CPU core of its own (Linux).
        # auto: on machines with 6+ physical cores; on; off
        tick-thread-isolation=auto

        # Extra JVM arguments appended to the tuned set, space separated
        extra-jvm-args=
        """;

    public static LauncherConfig load(Path workDir, String[] args) throws IOException {
        final Properties file = new Properties();
        final Path path = workDir.resolve(FILE_NAME);
        if (Files.notExists(path)) {
            try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
                writer.write(DEFAULT_FILE);
            } catch (IOException e) {
                Log.warn("Could not create " + FILE_NAME + ": " + e.getMessage());
            }
        }
        if (Files.exists(path)) {
            try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                file.load(reader);
            }
        }

        String profile = setting(file, "profile", "SHULKER_PROFILE", "survival");
        String memory = setting(file, "memory", "SHULKER_MEMORY", "auto");
        String gc = setting(file, "gc", "SHULKER_GC", "auto");
        boolean dryRun = false;
        boolean managed = Boolean.parseBoolean(file.getProperty("managed", "true").trim());

        final List<String> serverArgs = new ArrayList<>();
        for (String arg : args) {
            if (arg.startsWith("--profile=")) {
                profile = arg.substring("--profile=".length());
            } else if (arg.startsWith("--memory=")) {
                memory = arg.substring("--memory=".length());
            } else if (arg.startsWith("--gc=")) {
                gc = arg.substring("--gc=".length());
            } else if (arg.equals("--dry-run")) {
                dryRun = true;
            } else if (arg.equals("--no-relaunch")) {
                managed = false;
            } else {
                serverArgs.add(arg);
            }
        }

        final String profileValue = profile;
        final Profile parsedProfile = Profile.parse(profileValue).orElseGet(() -> {
            Log.warn("Unknown profile '" + profileValue + "', using survival");
            return Profile.SURVIVAL;
        });

        Collector collector;
        try {
            collector = Collector.parse(gc);
        } catch (IllegalArgumentException e) {
            Log.warn("Unknown gc '" + gc + "', using auto");
            collector = Collector.AUTO;
        }

        final String extra = file.getProperty("extra-jvm-args", "").trim();
        return new LauncherConfig(
            parsedProfile,
            parseSize(memory),
            collector,
            Boolean.parseBoolean(file.getProperty("aot-cache", "true").trim()),
            managed,
            dryRun,
            extra.isEmpty() ? List.of() : Arrays.asList(extra.split("\\s+")),
            List.copyOf(serverArgs),
            setting(file, "tick-thread-isolation", "SHULKER_TICK_THREAD_ISOLATION", "auto").toLowerCase(Locale.ROOT)
        );
    }

    private static String setting(Properties file, String key, String env, String fallback) {
        final String fromEnv = System.getenv(env);
        if (fromEnv != null && !fromEnv.isBlank()) {
            return fromEnv.trim();
        }
        return file.getProperty(key, fallback).trim();
    }

    /** Parses "auto", "8G", "8192M", "8192m" etc. Returns {@link #AUTO} for auto or garbage. */
    static long parseSize(String value) {
        String v = value.trim().toUpperCase(Locale.ROOT);
        if (v.isEmpty() || v.equals("AUTO")) {
            return AUTO;
        }
        if (v.endsWith("B") && v.length() > 1 && !Character.isDigit(v.charAt(v.length() - 2))) {
            v = v.substring(0, v.length() - 1); // 8GB -> 8G
        }
        try {
            final char unit = v.charAt(v.length() - 1);
            final String digits = Character.isDigit(unit) ? v : v.substring(0, v.length() - 1);
            final long number = Long.parseLong(digits.trim());
            return switch (unit) {
                case 'G' -> number << 30;
                case 'M' -> number << 20;
                case 'K' -> number << 10;
                default -> number << 20; // bare numbers are MiB, like panels use
            };
        } catch (NumberFormatException e) {
            Log.warn("Could not parse memory '" + value + "', using auto");
            return AUTO;
        }
    }
}
