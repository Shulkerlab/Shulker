package dev.shulker.launcher;

import static dev.shulker.launcher.JvmPlanner.GiB;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.shulker.launcher.JvmPlanner.Plan;
import dev.shulker.launcher.LauncherConfig.Collector;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class JvmPlannerTest {

    /** A Temurin-like JDK 25: every collector, compact headers available but off. */
    private static final VmCapabilities TEMURIN_25 = new FakeVm(Set.of(), Set.of());
    /** Oracle JDK builds ship without Shenandoah. */
    private static final VmCapabilities ORACLE = new FakeVm(Set.of("UseShenandoahGC", "ShenandoahGCMode"), Set.of());
    /** JDK 27 turns compact headers on by default. */
    private static final VmCapabilities TEMURIN_27 = new FakeVm(Set.of(), Set.of("UseCompactObjectHeaders"));

    private static HostInfo container(long memory, int cpus, int java) {
        return new HostInfo(cpus, memory, memory, true, null, java, java + "", "OpenJDK 64-Bit Server VM", true, false, 1);
    }

    private static HostInfo bareMetal(long memory, long available) {
        return new HostInfo(8, memory, available, false, null, 25, "25", "OpenJDK 64-Bit Server VM", true, true, 1);
    }

    private static LauncherConfig config(Profile profile) {
        return new LauncherConfig(profile, LauncherConfig.AUTO, Collector.AUTO, true, true, false, List.of(), List.of());
    }

    private static Plan plan(HostInfo host, Profile profile, VmCapabilities vm) {
        return new JvmPlanner(host, config(profile), vm).plan();
    }

    @Test
    void containerHeapLeavesRoomForOffHeapMemory() {
        final Plan plan = plan(container(8 * GiB, 4, 25), Profile.SURVIVAL, TEMURIN_25);
        assertTrue(plan.heap() <= 8 * GiB - GiB, "heap " + plan.heap());
        assertTrue(plan.heap() >= 6 * GiB, "heap " + plan.heap());
        assertEquals(plan.heap(), plan.initialHeap());
    }

    @Test
    void bareMetalDoesNotTakeMemoryInUse() {
        final Plan plan = plan(bareMetal(16 * GiB, 4 * GiB), Profile.SURVIVAL, TEMURIN_25);
        assertTrue(plan.heap() <= 4 * GiB - JvmPlanner.OFF_HEAP_ALLOWANCE, "heap " + plan.heap());
        assertFalse(plan.notes().isEmpty());
    }

    @Test
    void smallHeapsUseG1() {
        final Plan plan = plan(container(4 * GiB, 4, 25), Profile.SURVIVAL, TEMURIN_25);
        assertEquals(Collector.G1, plan.collector());
        assertTrue(plan.jvmArgs().contains("-XX:+UseG1GC"));
    }

    @Test
    void midHeapsUseGenerationalShenandoah() {
        final Plan plan = plan(container(12 * GiB, 8, 25), Profile.SURVIVAL, TEMURIN_25);
        assertEquals(Collector.SHENANDOAH, plan.collector());
        assertTrue(plan.jvmArgs().contains("-XX:ShenandoahGCMode=generational"));
    }

    @Test
    void midHeapsFallBackToZgcWithoutShenandoah() {
        assertEquals(Collector.ZGC, plan(container(12 * GiB, 8, 25), Profile.SURVIVAL, ORACLE).collector());
    }

    @Test
    void largeHeapsUseZgc() {
        final Plan plan = plan(container(32 * GiB, 16, 25), Profile.SURVIVAL, TEMURIN_25);
        assertEquals(Collector.ZGC, plan.collector());
        assertTrue(plan.jvmArgs().stream().anyMatch(a -> a.startsWith("-XX:SoftMaxHeapSize=")));
    }

    @Test
    void fewCpusStayOnG1() {
        assertEquals(Collector.G1, plan(container(12 * GiB, 2, 25), Profile.SURVIVAL, TEMURIN_25).collector());
    }

    @Test
    void lobbyHeapIsCappedAndElastic() {
        final Plan plan = plan(container(32 * GiB, 8, 25), Profile.LOBBY, TEMURIN_25);
        assertEquals(Profile.LOBBY.autoHeapCap(), plan.heap());
        assertTrue(plan.initialHeap() < plan.heap());
        assertFalse(plan.jvmArgs().contains("-XX:+AlwaysPreTouch"));
    }

    @Test
    void compactHeadersEnabledOnlyWhenNotDefault() {
        assertTrue(plan(container(8 * GiB, 4, 25), Profile.SURVIVAL, TEMURIN_25).jvmArgs()
            .contains("-XX:+UseCompactObjectHeaders"));
        assertFalse(plan(container(8 * GiB, 4, 27), Profile.SURVIVAL, TEMURIN_27).jvmArgs()
            .contains("-XX:+UseCompactObjectHeaders"));
    }

    @Test
    void noOptionsThatDisableTheArchivedModuleGraph() {
        // --enable-native-access / --enable-final-field-mutation would make the AOT cache
        // drop its heap data; the boot jar manifest grants these instead.
        final List<String> args = plan(container(8 * GiB, 4, 26), Profile.SURVIVAL, TEMURIN_25).jvmArgs();
        assertTrue(args.stream().noneMatch(a -> a.startsWith("--enable-")), args.toString());
    }

    @Test
    void unknownFlagsAreDropped() {
        final VmCapabilities noRSet = new FakeVm(Set.of("G1RSetUpdatingPauseTimePercent"), Set.of());
        final Plan plan = plan(container(4 * GiB, 4, 25), Profile.SURVIVAL, noRSet);
        assertTrue(plan.jvmArgs().stream().noneMatch(a -> a.contains("G1RSetUpdatingPauseTimePercent")));
    }

    @Test
    void explicitHeapWins() {
        final LauncherConfig config = new LauncherConfig(Profile.SURVIVAL, 3 * GiB, Collector.AUTO, true, true,
            false, List.of(), List.of());
        assertEquals(3 * GiB, new JvmPlanner(container(64 * GiB, 16, 25), config, TEMURIN_25).plan().heap());
    }

    @Test
    void parsesSizes() {
        assertEquals(8 * GiB, LauncherConfig.parseSize("8G"));
        assertEquals(8 * GiB, LauncherConfig.parseSize("8gb"));
        assertEquals(512L << 20, LauncherConfig.parseSize("512M"));
        assertEquals(4096L << 20, LauncherConfig.parseSize("4096"));
        assertEquals(LauncherConfig.AUTO, LauncherConfig.parseSize("auto"));
    }

    @Test
    void optionNames() {
        assertEquals("UseZGC", JvmPlanner.optionName("-XX:+UseZGC"));
        assertEquals("G1HeapRegionSize", JvmPlanner.optionName("-XX:G1HeapRegionSize=8M"));
    }

    private record FakeVm(Set<String> missing, Set<String> onByDefault) implements VmCapabilities {
        @Override
        public boolean supports(String option) {
            return !missing.contains(option);
        }

        @Override
        public boolean isEnabledByDefault(String booleanOption) {
            return onByDefault.contains(booleanOption);
        }
    }
}
