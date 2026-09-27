package dev.shulker.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.BitSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CpuTopologyTest {

    @TempDir
    Path sys;

    /** Writes a fake /sys/devices/system/cpu/cpuN. */
    private void cpu(final int id, final int core, final long capacity, final int l3, final String l3Size) throws IOException {
        final Path dir = Files.createDirectories(this.sys.resolve("cpu" + id));
        Files.createDirectories(dir.resolve("topology"));
        Files.createDirectories(dir.resolve("cache/index3"));
        Files.writeString(dir.resolve("topology/physical_package_id"), "0\n");
        Files.writeString(dir.resolve("topology/core_id"), core + "\n");
        Files.writeString(dir.resolve("cpu_capacity"), capacity + "\n");
        Files.writeString(dir.resolve("cache/index3/id"), l3 + "\n");
        Files.writeString(dir.resolve("cache/index3/size"), l3Size + "\n");
    }

    private CpuTopology read(final String allowed) {
        return CpuTopology.read(this.sys, CpuTopology.parseList(allowed)).orElseThrow();
    }

    @Test
    void homogeneousSixCoresWithSmt() throws IOException {
        for (int i = 0; i < 12; i++) {
            this.cpu(i, i / 2, 1024, 0, "18432K"); // cpu0/1 share core 0, like an i5-12400
        }
        final CpuTopology.Isolation plan = this.read("0-11").planIsolation(false).orElseThrow();
        assertEquals(0, plan.tickCpu());
        assertEquals("2-11", CpuTopology.formatList(plan.workerCpus())); // cpu1, the sibling, stays free
    }

    @Test
    void hybridPicksAPerformanceCore() throws IOException {
        for (int i = 0; i < 4; i++) {
            this.cpu(i, 100 + i, 400, 0, "30720K"); // E-cores listed first, no SMT
        }
        for (int i = 4; i < 16; i++) {
            this.cpu(i, (i - 4) / 2, 1024, 0, "30720K"); // P-cores with SMT
        }
        final CpuTopology.Isolation plan = this.read("0-15").planIsolation(false).orElseThrow();
        assertEquals(4, plan.tickCpu());
        assertFalse(plan.workerCpus().get(5), "P-core sibling kept free");
        assertTrue(plan.workerCpus().get(0), "E-cores do worker duty");
        assertTrue(plan.reason().contains("fastest core type"));
    }

    @Test
    void dualCcdPicksTheLargerCache() throws IOException {
        for (int i = 0; i < 8; i++) {
            this.cpu(i, i, 1024, 0, "32M"); // plain CCD first
        }
        for (int i = 8; i < 16; i++) {
            this.cpu(i, i, 1024, 1, "96M"); // X3D CCD
        }
        final CpuTopology.Isolation plan = this.read("0-15").planIsolation(false).orElseThrow();
        assertEquals(8, plan.tickCpu());
        assertTrue(plan.reason().contains("96 MiB"));
    }

    @Test
    void smallMachinesAreLeftAloneUnlessForced() throws IOException {
        for (int i = 0; i < 8; i++) {
            this.cpu(i, i / 2, 1024, 0, "8M"); // 4 cores, 8 threads
        }
        assertTrue(this.read("0-7").planIsolation(false).isEmpty());
        assertEquals(0, this.read("0-7").planIsolation(true).orElseThrow().tickCpu());
    }

    @Test
    void onlyAllowedCpusCount() throws IOException {
        for (int i = 0; i < 16; i++) {
            this.cpu(i, i / 2, 1024, 0, "16M");
        }
        // A container limited to CPUs 4-9: 3 physical cores, below the automatic threshold.
        assertTrue(this.read("4-9").planIsolation(false).isEmpty());
        final CpuTopology.Isolation forced = this.read("4-9").planIsolation(true).orElseThrow();
        assertEquals(4, forced.tickCpu());
        assertEquals("6-9", CpuTopology.formatList(forced.workerCpus()));
    }

    @Test
    void cpuListsRoundTrip() {
        final BitSet bits = CpuTopology.parseList("0-3,8,10-11\n");
        assertEquals("0-3,8,10-11", CpuTopology.formatList(bits));
    }
}
