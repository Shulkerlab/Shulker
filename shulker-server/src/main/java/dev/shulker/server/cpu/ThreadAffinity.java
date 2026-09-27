package dev.shulker.server.cpu;

import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.BitSet;
import java.util.stream.Stream;
import org.slf4j.Logger;

/**
 * Gives the tick thread a physical core of its own, as planned by the launcher
 * ({@code -Dshulker.tick-cpu}, {@code -Dshulker.worker-cpus}).
 *
 * <p>Linux only, through {@code sched_setaffinity(2)} via the FFM API. New threads inherit the
 * mask of the thread that creates them - often the tick thread - so a background sweeper moves
 * every other thread of the process back to the worker CPUs every few seconds.
 */
public final class ThreadAffinity {

    private static final Logger LOGGER = LogUtils.getClassLogger();
    private static final long SWEEP_MILLIS = 10_000;
    private static final int MASK_BYTES = 128; // cpu_set_t: 1024 CPUs

    private ThreadAffinity() {
    }

    /** Call on the tick thread, before worlds load. */
    public static void applyToTickThread() {
        final String tick = System.getProperty("shulker.tick-cpu");
        final String workers = System.getProperty("shulker.worker-cpus");
        if (tick == null || workers == null) {
            return;
        }
        try {
            final MethodHandle setAffinity = Linker.nativeLinker().downcallHandle(
                Linker.nativeLinker().defaultLookup().find("sched_setaffinity").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG, ValueLayout.ADDRESS));
            final BitSet tickMask = new BitSet();
            tickMask.set(Integer.parseInt(tick.trim()));
            final BitSet workerMask = parse(workers);
            final int tickTid = currentTid();
            if (!set(setAffinity, tickTid, tickMask)) {
                LOGGER.warn("Could not pin the tick thread to CPU {}; continuing without CPU isolation", tick);
                return;
            }
            final Thread sweeper = new Thread(() -> sweep(setAffinity, tickTid, workerMask), "Shulker Thread Affinity");
            sweeper.setDaemon(true);
            sweeper.start();
            LOGGER.info("Tick thread pinned to CPU {}, other server threads use CPUs {}", tick, workers);
        } catch (Throwable e) { // FFM linkage errors, missing symbol on non-glibc systems
            LOGGER.warn("CPU isolation unavailable: {}", e.toString());
        }
    }

    private static void sweep(final MethodHandle setAffinity, final int tickTid, final BitSet workers) {
        final Path tasks = Path.of("/proc/self/task");
        while (true) {
            try (Stream<Path> threads = Files.list(tasks)) {
                threads.map(p -> p.getFileName().toString())
                    .mapToInt(Integer::parseInt)
                    .filter(tid -> tid != tickTid)
                    .forEach(tid -> set(setAffinity, tid, workers)); // a thread may exit meanwhile: ignored
                Thread.sleep(SWEEP_MILLIS);
            } catch (InterruptedException e) {
                return;
            } catch (IOException | RuntimeException e) {
                LOGGER.warn("CPU isolation sweep failed, stopping it: {}", e.toString());
                return;
            }
        }
    }

    private static boolean set(final MethodHandle setAffinity, final int tid, final BitSet cpus) {
        try (Arena arena = Arena.ofConfined()) {
            final MemorySegment mask = arena.allocate(MASK_BYTES);
            final long[] words = cpus.toLongArray();
            for (int i = 0; i < words.length && i < MASK_BYTES / 8; i++) {
                mask.setAtIndex(ValueLayout.JAVA_LONG, i, words[i]); // little-endian hosts: bit n = CPU n
            }
            return (int) setAffinity.invokeExact(tid, (long) MASK_BYTES, mask) == 0;
        } catch (Throwable e) {
            return false;
        }
    }

    /** The kernel thread id of the calling thread, from the /proc/thread-self link ("pid/task/tid"). */
    private static int currentTid() throws IOException {
        final String link = Files.readSymbolicLink(Path.of("/proc/thread-self")).toString();
        return Integer.parseInt(link.substring(link.lastIndexOf('/') + 1));
    }

    private static BitSet parse(final String list) {
        final BitSet bits = new BitSet();
        for (String part : list.trim().split(",")) {
            final String[] range = part.trim().split("-");
            final int from = Integer.parseInt(range[0]);
            final int to = range.length > 1 ? Integer.parseInt(range[1]) : from;
            bits.set(from, to + 1);
        }
        return bits;
    }
}
