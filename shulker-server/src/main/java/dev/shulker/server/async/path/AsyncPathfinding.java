package dev.shulker.server.async.path;

import dev.shulker.server.ShulkerConfig;

/**
 * Async pathfinding settings, resolved from {@code shulker.yml}. The ported code reads these
 * static fields directly, as it did with Leaf's config module.
 */
public final class AsyncPathfinding {

    public static boolean enabled;
    public static int asyncPathfindingMaxThreads;
    public static int asyncPathfindingKeepalive;
    public static int asyncPathfindingQueueSize;
    public static PathfindTaskRejectPolicy asyncPathfindingRejectPolicy = PathfindTaskRejectPolicy.CALLER_RUNS;

    private AsyncPathfinding() {
    }

    /** Called once when shulker.yml is loaded; the executor cannot be resized afterwards. */
    public static void configure() {
        enabled = ShulkerConfig.asyncPathfinding;
        asyncPathfindingMaxThreads = enabled ? ShulkerConfig.threads(ShulkerConfig.asyncPathfindingThreads) : 0;
        asyncPathfindingKeepalive = ShulkerConfig.asyncPathfindingKeepAliveSeconds;
        asyncPathfindingQueueSize = ShulkerConfig.asyncPathfindingQueueSize > 0
            ? ShulkerConfig.asyncPathfindingQueueSize
            : Math.max(1, asyncPathfindingMaxThreads) * 256;
        asyncPathfindingRejectPolicy = PathfindTaskRejectPolicy.fromString(ShulkerConfig.asyncPathfindingRejectPolicy);
        if (enabled) {
            AsyncPathProcessor.LOGGER.info("Using {} threads for async pathfinding", asyncPathfindingMaxThreads);
            AsyncPathProcessor.init();
        }
    }
}
