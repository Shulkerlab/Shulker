package dev.shulker.launcher;

import dev.shulker.launcher.LauncherConfig.Collector;
import java.util.ArrayList;
import java.util.List;

/**
 * Turns host facts and launcher settings into a concrete set of JVM arguments.
 * Pure function of its inputs so every decision can be unit tested.
 */
final class JvmPlanner {

    static final long GiB = 1L << 30;
    static final long MiB = 1L << 20;

    /** Heap sizes above this lose compressed oops on G1/Shenandoah - 32 GiB is worse than 31. */
    static final long COMPRESSED_OOPS_LIMIT = 31 * GiB;
    /** Non-heap usage of a running server: metaspace, code cache, Netty buffers, thread stacks. */
    static final long OFF_HEAP_ALLOWANCE = 768 * MiB;

    record Plan(long heap, long initialHeap, Collector collector, List<String> jvmArgs, List<String> notes) {
    }

    private final HostInfo host;
    private final LauncherConfig config;
    private final VmCapabilities vm;
    private final List<String> notes = new ArrayList<>();

    JvmPlanner(HostInfo host, LauncherConfig config, VmCapabilities vm) {
        this.host = host;
        this.config = config;
        this.vm = vm;
    }

    Plan plan() {
        final long target = targetHeap();
        final Collector collector = collector(target);
        long heap = heapSize(target);
        if (collector != Collector.ZGC && heap > COMPRESSED_OOPS_LIMIT && config.heapOverride() == LauncherConfig.AUTO) {
            heap = COMPRESSED_OOPS_LIMIT;
            notes.add("heap capped at 31 GiB to keep compressed oops on " + collector.name().toLowerCase(java.util.Locale.ROOT));
        }
        final long initialHeap = config.profile().elasticHeap()
            ? Math.min(heap, Math.max(GiB, alignDown(heap / 4)))
            : heap;

        final List<String> args = new ArrayList<>();
        args.add("-Xms" + (initialHeap / MiB) + "M");
        args.add("-Xmx" + (heap / MiB) + "M");
        switch (collector) {
            case G1 -> g1(args, heap);
            case ZGC -> zgc(args, heap);
            case SHENANDOAH -> shenandoah(args);
            case AUTO -> throw new IllegalStateException("collector must be resolved");
        }
        common(args, initialHeap == heap);
        args.addAll(config.extraJvmArgs());
        return new Plan(heap, initialHeap, collector, List.copyOf(args), List.copyOf(notes));
    }

    // ---- heap ------------------------------------------------------------------------------

    /**
     * The heap this machine is meant to give the server, ignoring what other programs happen to
     * use right now. Stable across restarts, so the collector choice (and with it the AOT cache)
     * does not flip-flop with the desktop's memory use.
     */
    long targetHeap() {
        if (config.heapOverride() != LauncherConfig.AUTO) {
            return config.heapOverride();
        }
        final long total = host.totalMemory();
        long heap = host.container()
            // The limit is ours alone, but the JVM's off-heap memory counts against it too.
            ? total - Math.max(GiB, total * 15 / 100)
            : total - Math.max(2 * GiB, total * 20 / 100);
        final long cap = config.profile().autoHeapCap();
        if (cap > 0 && heap > cap) {
            heap = cap;
            notes.add("profile " + config.profile().id() + " caps automatic heap at " + Log.bytes(cap));
        }
        return alignDown(Math.max(heap, floor()));
    }

    long heapSize(long target) {
        if (config.heapOverride() != LauncherConfig.AUTO || host.container()) {
            return target;
        }
        // Do not reserve memory other programs are using right now; that ends in swapping.
        final long free = host.availableMemory() - OFF_HEAP_ALLOWANCE;
        if (free >= target) {
            return target;
        }
        notes.add("heap limited by currently available RAM (" + Log.bytes(host.availableMemory()) + " free)");
        if (free < floor()) {
            notes.add("very little memory available; server may struggle");
            return alignDown(floor());
        }
        return alignDown(free);
    }

    private long floor() {
        return Math.min(GiB, host.totalMemory() / 2);
    }

    private static long alignDown(long bytes) {
        final long unit = 256 * MiB;
        return Math.max(unit, bytes / unit * unit);
    }

    // ---- collector -------------------------------------------------------------------------

    Collector collector(long heap) {
        final Collector requested = config.collector();
        if (requested != Collector.AUTO) {
            if (available(requested)) {
                return requested;
            }
            notes.add(requested + " is not available in this JVM build, choosing automatically");
        }
        if (host.cpus() <= 2) {
            notes.add("2 or fewer CPUs: concurrent collectors would steal the tick thread's core, using G1");
            return Collector.G1;
        }
        // Concurrent collectors need heap headroom to run ahead of allocation.
        final long concurrentFrom = config.profile().latencySensitive() ? 4 * GiB : 6 * GiB;
        if (heap < concurrentFrom) {
            return Collector.G1;
        }
        if (heap >= 16 * GiB && available(Collector.ZGC)) {
            return Collector.ZGC;
        }
        if (available(Collector.SHENANDOAH)) {
            return Collector.SHENANDOAH;
        }
        if (available(Collector.ZGC)) {
            return Collector.ZGC;
        }
        return Collector.G1;
    }

    private boolean available(Collector collector) {
        return switch (collector) {
            case G1 -> vm.supports("UseG1GC");
            case ZGC -> vm.supports("UseZGC");
            // Generational mode is a product feature from JDK 25 (JEP 521).
            case SHENANDOAH -> vm.supports("UseShenandoahGC") && vm.supports("ShenandoahGCMode");
            case AUTO -> true;
        };
    }

    private void g1(List<String> args, long heap) {
        // Aikar's flags, still the best known baseline for Minecraft on G1.
        final boolean large = heap > 12 * GiB;
        args.add("-XX:+UseG1GC");
        xx(args, "-XX:+ParallelRefProcEnabled");
        xx(args, "-XX:MaxGCPauseMillis=200");
        xx(args, "-XX:+UnlockExperimentalVMOptions");
        experimental(args, "-XX:G1NewSizePercent=" + (large ? 40 : 30));
        experimental(args, "-XX:G1MaxNewSizePercent=" + (large ? 50 : 40));
        xx(args, "-XX:G1HeapRegionSize=" + (large ? "16M" : "8M"));
        xx(args, "-XX:G1ReservePercent=" + (large ? 15 : 20));
        xx(args, "-XX:G1HeapWastePercent=5");
        xx(args, "-XX:G1MixedGCCountTarget=4");
        if (!vm.supports("G1IHOP")) {
            // Deprecated in JDK 27 in favour of G1IHOP; adaptive IHOP is good enough there.
            xx(args, "-XX:InitiatingHeapOccupancyPercent=" + (large ? 20 : 15));
        }
        experimental(args, "-XX:G1MixedGCLiveThresholdPercent=90");
        xx(args, "-XX:G1RSetUpdatingPauseTimePercent=5");
        xx(args, "-XX:SurvivorRatio=32");
        xx(args, "-XX:MaxTenuringThreshold=1");
        if (config.profile().elasticHeap()) {
            // Return unused heap to the OS when the lobby is quiet.
            xx(args, "-XX:G1PeriodicGCInterval=60000");
        }
    }

    private void zgc(List<String> args, long heap) {
        args.add("-XX:+UseZGC"); // generational-only since JDK 24
        // Let ZGC aim below the hard limit so it collects before allocation stalls.
        xx(args, "-XX:SoftMaxHeapSize=" + (alignDown(heap * 90 / 100) / MiB) + "M");
    }

    private void shenandoah(List<String> args) {
        args.add("-XX:+UseShenandoahGC");
        args.add("-XX:ShenandoahGCMode=generational");
    }

    // ---- everything else -------------------------------------------------------------------

    private void common(List<String> args, boolean fixedHeap) {
        if (fixedHeap) {
            xx(args, "-XX:+AlwaysPreTouch");
        }
        xx(args, "-XX:+DisableExplicitGC");
        xx(args, "-XX:+PerfDisableSharedMem");
        xx(args, "-XX:+UseStringDeduplication");

        // JEP 519: 8-byte object headers, ~20% less heap for object-heavy workloads.
        // Product in 25, on by default from 27.
        if (vm.supports("UseCompactObjectHeaders") && !vm.isEnabledByDefault("UseCompactObjectHeaders")) {
            args.add("-XX:+UseCompactObjectHeaders");
        }
        if (host.transparentHugePages()) {
            xx(args, "-XX:+UseTransparentHugePages");
        }
        if (host.numaNodes() > 1) {
            xx(args, "-XX:+UseNUMA");
        }

        // JEP 471/498: sun.misc.Unsafe memory access warns now and will be denied later.
        // Libraries (joml) and many plugins still use it.
        args.add("--sun-misc-unsafe-memory-access=allow");
        // Native access and final field mutation (JEP 500) are granted through the boot jar's
        // manifest instead of --enable-* options: those options disable the archived module
        // graph, which the AOT cache needs. See ServerBundle#writeBootJar.

        args.add("-Dshulker.profile=" + config.profile().id());
        args.add("-Dshulker.launcher=managed");
        // Netty 4.2's default adaptive allocator contends on locks when many connections send at
        // once: the pooled one halved the Netty threads' CPU time with 40 players (same packets).
        // extra-jvm-args come later, so a -Dio.netty.allocator.type there still wins.
        args.add("-Dio.netty.allocator.type=pooled");
    }

    /** Adds an -XX option only when this JVM build knows it, so an unknown flag never blocks startup. */
    private void xx(List<String> args, String option) {
        final String name = optionName(option);
        if (vm.supports(name)) {
            args.add(option);
        }
    }

    /**
     * Experimental options are invisible to the MXBean probe until unlocked, so they cannot be
     * checked here. Only long-standing ones go through this path; {@link FlagValidator} still
     * catches a JVM that rejects them.
     */
    private void experimental(List<String> args, String option) {
        if (args.contains("-XX:+UnlockExperimentalVMOptions")) {
            args.add(option);
        }
    }

    static String optionName(String option) {
        String body = option.substring("-XX:".length());
        if (body.startsWith("+") || body.startsWith("-")) {
            body = body.substring(1);
        }
        final int eq = body.indexOf('=');
        return eq >= 0 ? body.substring(0, eq) : body;
    }
}
