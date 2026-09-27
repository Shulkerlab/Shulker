/*
 * Async pathfinding from Petal (https://github.com/Bloom-host/Petal) by peaches94,
 * updated by Kaiiju (https://github.com/KaiijuMC/Kaiiju) and Leaf
 * (https://github.com/Winds-Studio/Leaf). GPL-3.0-only.
 */
package dev.shulker.server.async.path;



import java.util.Locale;

public enum PathfindTaskRejectPolicy {
    FLUSH_ALL,
    CALLER_RUNS;

    public static PathfindTaskRejectPolicy fromString(String policy) {
        try {
            return PathfindTaskRejectPolicy.valueOf(policy.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            AsyncPathProcessor.LOGGER.warn("Invalid pathfind task reject policy: {}, falling back to {}.", policy, FLUSH_ALL.toString());
            return FLUSH_ALL;
        }
    }
}
