package dev.shulker.bench;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;

/**
 * Main-thread CPU time of one step beyond the idle rate measured right before it, so work the
 * server runs between ticks counts as much as work inside a tick, and the growing cost of
 * ticking the worlds created so far does not.
 */
final class Probe {

    private static final ThreadMXBean THREADS = ManagementFactory.getThreadMXBean();

    private final WorldBench.TickGaps ticks;
    private final long mainThread;
    /** Idle main-thread CPU per wall-clock nanosecond. */
    private double idleRate;
    private long cpuStart;
    private long wallStart;

    Probe(final WorldBench.TickGaps ticks, final long mainThread) {
        this.ticks = ticks;
        this.mainThread = mainThread;
    }

    void baselineStart() {
        this.cpuStart = THREADS.getThreadCpuTime(this.mainThread);
        this.wallStart = System.nanoTime();
    }

    void baselineEnd() {
        this.idleRate = (double) (THREADS.getThreadCpuTime(this.mainThread) - this.cpuStart) / (System.nanoTime() - this.wallStart);
    }

    /** Idle main-thread CPU per 50 ms tick, from the last baseline. */
    double idleMillisPerTick() {
        return this.idleRate * 50.0;
    }

    void start() {
        this.cpuStart = THREADS.getThreadCpuTime(this.mainThread);
        this.wallStart = System.nanoTime();
    }

    /** Main-thread CPU beyond idle since {@link #start()}, in ms. */
    double mainCpuMillis() {
        final long wall = System.nanoTime() - this.wallStart;
        final long cpu = THREADS.getThreadCpuTime(this.mainThread) - this.cpuStart;
        return Math.max(0, cpu - this.idleRate * wall) / 1e6;
    }

    double wallMillis() {
        return (System.nanoTime() - this.wallStart) / 1e6;
    }
}
