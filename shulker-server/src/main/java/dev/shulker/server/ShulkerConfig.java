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
    /** Disconnect clients that stop reading instead of buffering for them without limit. */
    public static boolean slowClientProtection = true;
    public static int slowClientMaxPendingMegabytes = 32;

    // ---- entity tracking -------------------------------------------------------------------
    /** Skip entity move packets whose position and rotation deltas are all zero. */
    public static boolean skipEmptyMovePackets = true;
    /** Only send periodic entity velocity to entities whose client-side rendering needs it. */
    public static boolean filterMotionPackets = true;
    public static boolean multithreadedTracker = false;
    public static int multithreadedTrackerThreads = 0;

    // ---- world storage ---------------------------------------------------------------------
    /** auto: new worlds .shulk, existing .mca worlds unchanged; shulk: migrate; mca: vanilla. */
    public static String storageFormat = "auto";
    public static int storageCompressionLevel = 6;
    public static boolean coldRecompression = true;
    public static int coldRecompressionLevel = 19;
    public static int coldRecompressionAfterHours = 24;
    public static int coldRecompressionChunksPerSecond = 20;
    public static double coldRecompressionMaxMspt = 30.0;

    // ---- parallel world ticking ------------------------------------------------------------
    public static boolean parallelWorldTicking = false;
    public static int parallelWorldTickingThreadCount = 0;
    public static boolean parallelWorldTickingLogContainerCreation = false;

    // ---- async pathfinding -----------------------------------------------------------------
    public static boolean asyncPathfinding = true;
    public static int asyncPathfindingThreads = 0;
    public static int asyncPathfindingKeepAliveSeconds = 60;
    public static String asyncPathfindingRejectPolicy = "caller_runs";
    public static int asyncPathfindingQueueSize = 0;

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
        slowClientProtection = bool("network.slow-client-protection.enabled", slowClientProtection,
            "Disconnect players whose connection cannot keep up, instead of buffering their data until the server runs out of memory.");
        slowClientMaxPendingMegabytes = integer("network.slow-client-protection.max-pending-megabytes", slowClientMaxPendingMegabytes,
            "Disconnect when this much data is waiting to be sent to one player. Clients that stop reading entirely are already dropped by the 30 s keep-alive timeout.");

        skipEmptyMovePackets = ShulkerProfile.current() != ShulkerProfile.VANILLA;
        skipEmptyMovePackets = bool("entity-tracker.skip-empty-move-packets", skipEmptyMovePackets,
            "Do not send entity movement packets that move and rotate the entity by zero.");
        filterMotionPackets = ShulkerProfile.current() != ShulkerProfile.VANILLA;
        filterMotionPackets = bool("entity-tracker.filter-motion-packets", filterMotionPackets,
            "Send periodic velocity updates only for items, eyes of ender, squid, shulker bullets and fishing hooks. Knockback is always sent.");
        // Experimental upstream (Leaf); on by default here, except where vanilla timing matters.
        multithreadedTracker = ShulkerProfile.current() != ShulkerProfile.VANILLA;
        multithreadedTracker = bool("entity-tracker.multithreaded", multithreadedTracker,
            "Compute entity tracking updates on worker threads; packets and events still go out on the tick thread.");
        multithreadedTrackerThreads = integer("entity-tracker.threads", multithreadedTrackerThreads,
            "Worker threads (0 = CPU cores, at most 4).");

        storageFormat = ShulkerProfile.current() == ShulkerProfile.VANILLA ? "mca" : "auto"; // technical players use .mca tools
        storageFormat = string("storage.format", storageFormat,
            "Region file format. auto: new worlds use .shulk (about 45% smaller than .mca), existing .mca worlds are left alone. "
                + "shulk: also migrate existing worlds, chunk by chunk as they are saved. mca: vanilla format for new region files.");
        storageCompressionLevel = integer("storage.compression-level", storageCompressionLevel,
            "zstd level for chunks as they are saved (1-19). 6 is smaller and faster than .mca's zlib.");
        coldRecompression = bool("storage.cold-recompression.enabled", coldRecompression,
            "Recompress chunks that were not saved for a while at a higher level in the background (about 13% smaller). "
                + "Only regions the server has open are processed, one thread, low priority.");
        coldRecompressionLevel = integer("storage.cold-recompression.level", coldRecompressionLevel,
            "zstd level for cold chunks. Reading them is as fast as any other level.");
        coldRecompressionAfterHours = integer("storage.cold-recompression.after-hours", coldRecompressionAfterHours,
            "A chunk is cold when it has not been saved for this many hours.");
        coldRecompressionChunksPerSecond = integer("storage.cold-recompression.chunks-per-second", coldRecompressionChunksPerSecond,
            "Upper limit on background work; 20 uses roughly 15% of one CPU core.");
        coldRecompressionMaxMspt = dbl("storage.cold-recompression.max-mspt", coldRecompressionMaxMspt,
            "Pause while the server's average tick time is above this many milliseconds.");

        parallelWorldTicking = bool("parallel-world-ticking.enabled", parallelWorldTicking,
            "EXPERIMENTAL (SparklyPaper). Tick worlds in parallel. Only helps servers with several busy worlds. "
                + "Plugins that touch another world from an event can crash the server (e.g. NoCheatPlus movement checks), "
                + "and non-player entities cannot use portals.");
        parallelWorldTickingThreadCount = integer("parallel-world-ticking.threads", parallelWorldTickingThreadCount,
            "Worlds ticked at the same time (0 = half the CPU cores).");
        parallelWorldTickingLogContainerCreation = bool("parallel-world-ticking.log-container-creation-stacktraces", parallelWorldTickingLogContainerCreation,
            "Debugging aid: log where inventories are opened from while parallel ticking is on.");

        // Vanilla computes paths within the tick; the vanilla profile keeps that timing.
        asyncPathfinding = ShulkerProfile.current() != ShulkerProfile.VANILLA;
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

        dev.shulker.server.async.path.AsyncPathfinding.configure();

        try {
            config.save(file);
        } catch (IOException e) {
            LOGGER.error("Could not save {}", file, e);
        }
    }

    public static int parallelWorldTickingThreads() {
        return parallelWorldTickingThreadCount > 0 ? parallelWorldTickingThreadCount : Math.max(1, Runtime.getRuntime().availableProcessors() / 2);
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

    private static double dbl(final String path, final double def, final String comment) {
        if (!config.isSet(path)) {
            config.set(path, def);
        }
        config.setComments(path, List.of(comment));
        return config.getDouble(path, def);
    }

    private static int integer(final String path, final int def, final String comment) {
        if (!config.isSet(path)) {
            config.set(path, def);
        }
        config.setComments(path, List.of(comment));
        return config.getInt(path, def);
    }
}
