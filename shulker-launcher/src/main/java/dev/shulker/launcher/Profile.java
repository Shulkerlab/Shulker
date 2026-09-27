package dev.shulker.launcher;

import java.util.Locale;
import java.util.Optional;

/**
 * Server workload profile. Drives JVM tuning in the launcher and is passed to the
 * server as {@code -Dshulker.profile} so server-side defaults can follow it too.
 */
public enum Profile {
    /** Large open worlds: chunk generation, many entities. Favors throughput and a big heap. */
    SURVIVAL(0, false, false),
    /** Many small island worlds loaded and unloaded on demand. Favors low pauses. */
    SKYBLOCK(0, true, false),
    /** Short-lived arenas cloned from templates. Pause-sensitive combat. */
    MINIGAME(0, true, false),
    /** Hub servers: few chunks, many players. Heap is capped and allowed to shrink. */
    LOBBY(6L << 30, true, true),
    /** Vanilla mechanics for technical players: exploits and dupes allowed. Tuned like survival. */
    VANILLA(0, false, false);

    private final long autoHeapCap;
    private final boolean latencySensitive;
    private final boolean elasticHeap;

    Profile(long autoHeapCap, boolean latencySensitive, boolean elasticHeap) {
        this.autoHeapCap = autoHeapCap;
        this.latencySensitive = latencySensitive;
        this.elasticHeap = elasticHeap;
    }

    /** Upper bound for an automatically sized heap, 0 for none. */
    public long autoHeapCap() {
        return autoHeapCap;
    }

    /** Prefer a concurrent (low-pause) collector at smaller heap sizes than usual. */
    public boolean latencySensitive() {
        return latencySensitive;
    }

    /** Start with a small heap and let the GC grow/uncommit it instead of reserving everything. */
    public boolean elasticHeap() {
        return elasticHeap;
    }

    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static Optional<Profile> parse(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(valueOf(value.trim().toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
