package dev.shulker.launcher;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * The CPUs this process may run on, grouped into physical cores, read from Linux sysfs.
 *
 * @param cpus one entry per usable logical CPU
 */
record CpuTopology(List<Cpu> cpus) {

    /**
     * @param id        logical CPU number
     * @param core      identifies the physical core (SMT siblings share it)
     * @param capacity  relative speed; P-cores are higher than E-cores on hybrid CPUs
     * @param l3        identifies the last-level cache domain (a CCD on multi-CCD Ryzen)
     * @param l3SizeKib size of that cache; X3D CCDs have more
     */
    record Cpu(int id, String core, long capacity, String l3, long l3SizeKib) {
    }

    /**
     * Where to run the tick thread.
     *
     * @param tickCpu    the logical CPU for the tick thread
     * @param workerCpus every other allowed CPU, minus the tick core's SMT siblings
     */
    record Isolation(int tickCpu, BitSet workerCpus, String reason) {
    }

    /** Below this many physical cores, taking one away from the worker threads costs too much. */
    static final int MIN_PHYSICAL_CORES = 6;

    int physicalCores() {
        return (int) this.cpus.stream().map(Cpu::core).distinct().count();
    }

    /**
     * Picks a physical core for the tick thread: the fastest kind (P-core on hybrid CPUs), in the
     * largest last-level cache (the X3D die on dual-CCD Ryzen), lowest number first. Its SMT
     * siblings are kept free so nothing shares the core with the tick thread.
     */
    Optional<Isolation> planIsolation(final boolean force) {
        if (this.physicalCores() < (force ? 2 : MIN_PHYSICAL_CORES)) {
            return Optional.empty();
        }
        final Cpu best = this.cpus.stream()
            .min(Comparator.comparingLong(Cpu::capacity).reversed()
                .thenComparing(Comparator.comparingLong(Cpu::l3SizeKib).reversed())
                .thenComparingInt(Cpu::id))
            .orElseThrow();
        final BitSet workers = new BitSet();
        for (Cpu cpu : this.cpus) {
            if (!cpu.core().equals(best.core())) {
                workers.set(cpu.id());
            }
        }
        final List<String> why = new ArrayList<>();
        if (this.cpus.stream().mapToLong(Cpu::capacity).distinct().count() > 1) {
            why.add("fastest core type");
        }
        if (this.cpus.stream().map(Cpu::l3).distinct().count() > 1) {
            why.add(best.l3SizeKib() / 1024 + " MiB L3 domain");
        }
        why.add("SMT sibling kept free");
        return Optional.of(new Isolation(best.id(), workers, String.join(", ", why)));
    }

    /** Reads sysfs for the CPUs in {@code allowed}; empty when the layout cannot be read. */
    static Optional<CpuTopology> read(final Path sysCpu, final BitSet allowed) {
        final List<Cpu> cpus = new ArrayList<>();
        for (int id = allowed.nextSetBit(0); id >= 0; id = allowed.nextSetBit(id + 1)) {
            final Path dir = sysCpu.resolve("cpu" + id);
            try {
                final String pkg = readTrim(dir.resolve("topology/physical_package_id")).orElse("0");
                final String coreId = readTrim(dir.resolve("topology/core_id")).orElseThrow();
                final long capacity = readTrim(dir.resolve("cpu_capacity")).map(Long::parseLong)
                    .or(() -> readTrim(dir.resolve("cpufreq/cpuinfo_max_freq")).map(Long::parseLong))
                    .orElse(0L);
                final String l3 = readTrim(dir.resolve("cache/index3/id")).orElse("0");
                final long l3Size = readTrim(dir.resolve("cache/index3/size")).map(CpuTopology::parseKib).orElse(0L);
                cpus.add(new Cpu(id, pkg + ":" + coreId, capacity, pkg + ":" + l3, l3Size));
            } catch (RuntimeException e) {
                return Optional.empty();
            }
        }
        return cpus.isEmpty() ? Optional.empty() : Optional.of(new CpuTopology(List.copyOf(cpus)));
    }

    /** CPUs this process may use, from /proc/self/status (honours taskset and cgroup cpusets). */
    static Optional<BitSet> allowedCpus(final Path procStatus) {
        try {
            for (String line : Files.readAllLines(procStatus)) {
                if (line.startsWith("Cpus_allowed_list:")) {
                    return Optional.of(parseList(line.substring("Cpus_allowed_list:".length())));
                }
            }
        } catch (IOException | RuntimeException ignored) {
        }
        return Optional.empty();
    }

    /** "0-3,8,10-11" -> bits. */
    static BitSet parseList(final String list) {
        final BitSet bits = new BitSet();
        for (String part : list.trim().split(",")) {
            if (part.isBlank()) {
                continue;
            }
            final String[] range = part.trim().split("-");
            final int from = Integer.parseInt(range[0]);
            final int to = range.length > 1 ? Integer.parseInt(range[1]) : from;
            bits.set(from, to + 1);
        }
        return bits;
    }

    /** bits -> "0-3,8,10-11". */
    static String formatList(final BitSet bits) {
        final StringBuilder out = new StringBuilder();
        for (int from = bits.nextSetBit(0); from >= 0; ) {
            final int to = bits.nextClearBit(from) - 1;
            if (!out.isEmpty()) {
                out.append(',');
            }
            out.append(from);
            if (to > from) {
                out.append('-').append(to);
            }
            from = bits.nextSetBit(to + 1);
        }
        return out.toString();
    }

    private static long parseKib(final String size) {
        final String s = size.trim().toUpperCase(java.util.Locale.ROOT);
        if (s.endsWith("K")) {
            return Long.parseLong(s.substring(0, s.length() - 1));
        }
        if (s.endsWith("M")) {
            return Long.parseLong(s.substring(0, s.length() - 1)) * 1024;
        }
        return Long.parseLong(s) / 1024;
    }

    private static Optional<String> readTrim(final Path path) {
        try {
            return Optional.of(Files.readString(path).trim());
        } catch (IOException e) {
            return Optional.empty();
        }
    }
}
