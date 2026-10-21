package dev.shulker.bench;

import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/** Shared measuring steps of the three approaches. */
abstract class Scenario {

    /** Ticks of idle main-thread CPU measured before each operation. */
    private static final int BASELINE_TICKS = 5;

    /** The measurement {@link WorldBench#sequence} reports each step to, if any. */
    static Scenario measuring;

    protected final WorldBench bench;
    protected final String name;
    protected Probe probe;
    private double cpu;
    private double wall;

    Scenario(final WorldBench bench, final String name) {
        this.bench = bench;
        this.name = name;
    }

    abstract CompletableFuture<Void> run(int islands, int resets);

    protected CompletableFuture<Void> prepareProbe() {
        return this.bench.sync(() -> Thread.currentThread().threadId()).thenAccept(id -> this.probe = new Probe(this.bench.ticks, id));
    }

    /**
     * Measures {@code operations} steps run through {@link WorldBench#sequence}: before each step
     * the main thread's idle CPU is measured for a few ticks (it grows as worlds pile up), and
     * only what the step adds on top counts.
     */
    protected CompletableFuture<Void> measure(final String scenario, final int operations, final Supplier<CompletableFuture<?>> work) {
        return this.bench.settle(20).thenCompose(v -> {
            this.cpu = 0;
            this.wall = 0;
            this.bench.ticks.start();
            measuring = this;
            return work.get();
        }).thenRun(() -> {
            measuring = null;
            this.bench.record(this.name, scenario, "main_cpu_ms_per_op", this.cpu / operations, "ms");
            this.bench.record(this.name, scenario, "wall_ms_per_op", this.wall / operations, "ms");
            this.bench.record(this.name, scenario, "worst_tick_gap_ms", this.bench.ticks.worstGapMillis(), "ms");
            this.bench.record(this.name, scenario, "ticks_seen", this.bench.ticks.ticks(), "ticks");
        });
    }

    /** Wraps one measured step: idle baseline, the step, one tick for work it queued. */
    CompletableFuture<?> step(final Supplier<CompletableFuture<?>> step) {
        final double[] wallAtEnd = new double[1];
        return this.bench.sync(() -> {
            this.probe.baselineStart();
            return null;
        }).thenCompose(v -> this.bench.settle(BASELINE_TICKS)).thenCompose(v -> {
            this.probe.baselineEnd();
            this.probe.start();
            return step.get();
        }).thenCompose(v -> {
            wallAtEnd[0] = this.probe.wallMillis();
            return this.bench.settle(1);
        }).thenRun(() -> {
            this.cpu += this.probe.mainCpuMillis();
            this.wall += wallAtEnd[0];
        });
    }

    protected void heapPerIsland(final long before, final int islands) {
        final long after = WorldBench.usedHeapAfterGc();
        this.bench.record(this.name, "skyblock", "heap_kib_per_island", Math.max(0, after - before) / 1024.0 / islands, "KiB");
    }
}
