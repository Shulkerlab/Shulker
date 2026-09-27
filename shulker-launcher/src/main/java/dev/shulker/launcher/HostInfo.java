package dev.shulker.launcher;

import com.sun.management.OperatingSystemMXBean;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * What the launcher knows about the machine it is starting on.
 *
 * @param cpus              processors usable by this process (container-aware)
 * @param totalMemory       memory the server may use: container limit, panel limit or physical RAM
 * @param availableMemory   memory currently free for new allocations (bare metal only, else == totalMemory)
 * @param container         running under a cgroup-limited container or a game panel
 * @param panel             name of the detected game panel, or null
 * @param javaFeature       Java feature release, e.g. 25
 * @param javaVersion       full runtime version string
 * @param vmName            JVM name, used to detect GraalVM
 * @param linux             host OS is Linux
 * @param transparentHugePages THP is available in madvise or always mode
 * @param numaNodes         NUMA node count (1 when unknown)
 */
public record HostInfo(
    int cpus,
    long totalMemory,
    long availableMemory,
    boolean container,
    String panel,
    int javaFeature,
    String javaVersion,
    String vmName,
    boolean linux,
    boolean transparentHugePages,
    int numaNodes
) {

    public boolean graalVm() {
        return vmName.toLowerCase(Locale.ROOT).contains("graal");
    }

    public static HostInfo detect() {
        final OperatingSystemMXBean os = ManagementFactory.getPlatformMXBean(OperatingSystemMXBean.class);
        final boolean linux = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("linux");

        final String panel = detectPanel();
        final boolean container = panel != null || detectContainer(linux);

        // getTotalMemorySize() already honours cgroup limits on JDK 14+.
        long total = os.getTotalMemorySize();
        final OptionalLong panelLimit = panelMemoryLimit();
        if (panelLimit.isPresent()) {
            total = Math.min(total, panelLimit.getAsLong());
        }

        long available = total;
        if (!container) {
            available = linux ? memAvailable().orElse(os.getFreeMemorySize()) : os.getFreeMemorySize();
        }

        return new HostInfo(
            Runtime.getRuntime().availableProcessors(),
            total,
            Math.min(available, total),
            container,
            panel,
            Runtime.version().feature(),
            System.getProperty("java.runtime.version", Runtime.version().toString()),
            System.getProperty("java.vm.name", "unknown"),
            linux,
            linux && transparentHugePagesUsable(),
            linux ? numaNodeCount() : 1
        );
    }

    private static String detectPanel() {
        // Pterodactyl / Pelican export these to every server container.
        if (System.getenv("P_SERVER_UUID") != null || System.getenv("P_SERVER_LOCATION") != null) {
            return "pterodactyl";
        }
        return null;
    }

    /** Pterodactyl exposes the configured memory limit in MiB. */
    private static OptionalLong panelMemoryLimit() {
        final String value = System.getenv("SERVER_MEMORY");
        if (value == null) {
            return OptionalLong.empty();
        }
        try {
            final long mib = Long.parseLong(value.trim());
            return mib > 0 ? OptionalLong.of(mib << 20) : OptionalLong.empty();
        } catch (NumberFormatException e) {
            return OptionalLong.empty();
        }
    }

    private static boolean detectContainer(boolean linux) {
        if (!linux) {
            return false;
        }
        if (Files.exists(Path.of("/.dockerenv")) || Files.exists(Path.of("/run/.containerenv"))
            || System.getenv("KUBERNETES_SERVICE_HOST") != null) {
            return true;
        }
        // A finite cgroup v2 memory limit means we must size against it, container or not.
        return readFirstLine(Path.of("/sys/fs/cgroup/memory.max"))
            .map(v -> !v.equals("max"))
            .orElse(false);
    }

    private static OptionalLong memAvailable() {
        try {
            for (String line : Files.readAllLines(Path.of("/proc/meminfo"))) {
                if (line.startsWith("MemAvailable:")) {
                    final String kb = line.substring("MemAvailable:".length()).replace("kB", "").trim();
                    return OptionalLong.of(Long.parseLong(kb) * 1024L);
                }
            }
        } catch (IOException | NumberFormatException ignored) {
        }
        return OptionalLong.empty();
    }

    private static boolean transparentHugePagesUsable() {
        return readFirstLine(Path.of("/sys/kernel/mm/transparent_hugepage/enabled"))
            .map(v -> v.contains("[always]") || v.contains("[madvise]"))
            .orElse(false);
    }

    private static int numaNodeCount() {
        try (var nodes = Files.list(Path.of("/sys/devices/system/node"))) {
            final int count = (int) nodes.filter(p -> p.getFileName().toString().matches("node\\d+")).count();
            return Math.max(1, count);
        } catch (IOException e) {
            return 1;
        }
    }

    private static Optional<String> readFirstLine(Path path) {
        try {
            final List<String> lines = Files.readAllLines(path);
            return lines.isEmpty() ? Optional.empty() : Optional.of(lines.getFirst().trim());
        } catch (IOException | SecurityException e) {
            return Optional.empty();
        }
    }
}
