package dev.shulker.server;

import com.mojang.logging.LogUtils;
import java.io.File;
import java.io.IOException;
import java.util.List;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.slf4j.Logger;

/**
 * {@code shulker.yml}: switches and limits for Shulker's own optimisations.
 *
 * <p>Values written as {@code 0} for thread counts mean "pick from the CPU count". Missing keys
 * are added with their defaults, so the file documents every option after the first start.
 */
public final class ShulkerConfig {

    private static final Logger LOGGER = LogUtils.getClassLogger();
    private static final int VERSION = 1;
    private static final String HEADER = """
        Shulker configuration.
        Thread counts of 0 are chosen automatically from the number of CPU cores.
        Changes need a restart.""";

    private static YamlConfiguration config;

    // ---- network ---------------------------------------------------------------------------
    /** Use Linux io_uring for player connections when the kernel supports it. */
    public static boolean ioUring = true;

    // ---- async pathfinding -----------------------------------------------------------------
    public static boolean asyncPathfinding = true;
    public static int asyncPathfindingThreads = 0;
    public static int asyncPathfindingKeepAliveSeconds = 60;
    public static String asyncPathfindingRejectPolicy = "caller_runs";
    public static int asyncPathfindingQueueSize = 0;

    // ---- entity tracker --------------------------------------------------------------------
    public static boolean multithreadedTracker = true;
    public static int multithreadedTrackerThreads = 0;

    private ShulkerConfig() {
    }

    public static void load(final File file) {
        config = new YamlConfiguration();
        if (file.exists()) {
            try {
                config.load(file);
            } catch (IOException | InvalidConfigurationException e) {
                LOGGER.error("Could not load {}, using defaults for this run", file, e);
            }
        }
        config.options().setHeader(List.of(HEADER.split("\n")));
        config.set("config-version", VERSION);

        ioUring = bool("network.io-uring", ioUring,
            "Use io_uring instead of epoll on Linux 5.10+ for fewer syscalls per packet.");

        asyncPathfinding = bool("async-pathfinding.enabled", asyncPathfinding,
            "Compute mob paths on worker threads instead of the tick thread.");
        asyncPathfindingThreads = integer("async-pathfinding.threads", asyncPathfindingThreads,
            "Worker threads (0 = a quarter of the CPU cores).");
        asyncPathfindingKeepAliveSeconds = integer("async-pathfinding.keep-alive-seconds", asyncPathfindingKeepAliveSeconds,
            "Idle worker threads are stopped after this many seconds.");
        asyncPathfindingQueueSize = integer("async-pathfinding.queue-size", asyncPathfindingQueueSize,
            "Pending path requests before the queue is full (0 = threads x 256).");
        asyncPathfindingRejectPolicy = string("async-pathfinding.reject-policy", asyncPathfindingRejectPolicy,
            "When the queue is full: caller_runs computes the new path on the tick thread, flush_all computes every queued path there.");

        multithreadedTracker = bool("entity-tracker.multithreaded", multithreadedTracker,
            "Update which players see which entities on worker threads.");
        multithreadedTrackerThreads = integer("entity-tracker.threads", multithreadedTrackerThreads,
            "Worker threads (0 = a quarter of the CPU cores).");

        dev.shulker.server.async.path.AsyncPathfinding.configure();

        try {
            config.save(file);
        } catch (IOException e) {
            LOGGER.error("Could not save {}", file, e);
        }
    }

    /** Threads for a worker pool: configured value, or a quarter of the cores (at least one). */
    public static int threads(final int configured) {
        if (configured > 0) {
            return configured;
        }
        return Math.max(1, Runtime.getRuntime().availableProcessors() / 4);
    }

    private static boolean bool(final String path, final boolean def, final String comment) {
        if (!config.isSet(path)) {
            config.set(path, def);
        }
        config.setComments(path, List.of(comment));
        return config.getBoolean(path, def);
    }

    private static String string(final String path, final String def, final String comment) {
        if (!config.isSet(path)) {
            config.set(path, def);
        }
        config.setComments(path, List.of(comment));
        return config.getString(path, def);
    }

    private static int integer(final String path, final int def, final String comment) {
        if (!config.isSet(path)) {
            config.set(path, def);
        }
        config.setComments(path, List.of(comment));
        return config.getInt(path, def);
    }
}
