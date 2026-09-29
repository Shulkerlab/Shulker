/*
 * Adapted from DivineMC's ParallelSensorTicker (https://github.com/BX-Team/DivineMC, GPL-3.0),
 * by NONPLAYT. Shulker keeps vanilla sensor timing and gives worker threads their own sorter,
 * because Leaf's per-world FastBitRadixSort is not thread-safe.
 */
package dev.shulker.server.async.sensing;

import ca.spottedleaf.moonrise.common.util.TickThread;
import com.mojang.logging.LogUtils;
import dev.shulker.server.ShulkerConfig;
import dev.shulker.server.world.ParallelWorldTicking;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.TickRateManager;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.ai.sensing.Sensor;
import org.dreeam.leaf.util.FastBitRadixSort;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * Runs the AI sensors that are due this tick (read-only world scans) on worker threads right
 * before entity ticking, one task per mob. The world's tick thread waits for all of them, so
 * nothing changes the world meanwhile; a sensor that ran here has its timer reset, and the mob's
 * own brain tick then skips it.
 */
public final class ParallelSensors {

    private static final Logger LOGGER = LogUtils.getClassLogger();

    /**
     * Sensors checked to be safe off the tick thread while it waits: they read entities and
     * blocks, write only their own mob's brain and fire no plugin events.
     */
    private static final Set<Class<?>> SAFE_SENSORS = Set.of(
        net.minecraft.world.entity.ai.sensing.AdultSensor.class,
        net.minecraft.world.entity.ai.sensing.AdultSensorAnyType.class,
        net.minecraft.world.entity.ai.sensing.AxolotlAttackablesSensor.class,
        net.minecraft.world.entity.ai.sensing.BreezeAttackEntitySensor.class,
        net.minecraft.world.entity.ai.sensing.FrogAttackablesSensor.class,
        net.minecraft.world.entity.ai.sensing.GolemSensor.class,
        net.minecraft.world.entity.ai.sensing.HoglinSpecificSensor.class,
        net.minecraft.world.entity.ai.sensing.HurtBySensor.class,
        net.minecraft.world.entity.ai.sensing.IsInWaterSensor.class,
        net.minecraft.world.entity.ai.sensing.MobSensor.class,
        net.minecraft.world.entity.ai.sensing.NearestItemSensor.class,
        net.minecraft.world.entity.ai.sensing.NearestLivingEntitySensor.class,
        net.minecraft.world.entity.ai.sensing.PiglinBruteSpecificSensor.class,
        net.minecraft.world.entity.ai.sensing.PiglinSpecificSensor.class,
        net.minecraft.world.entity.ai.sensing.PlayerSensor.class,
        net.minecraft.world.entity.ai.sensing.SecondaryPoiSensor.class,
        net.minecraft.world.entity.ai.sensing.VillagerBabiesSensor.class,
        net.minecraft.world.entity.ai.sensing.VillagerHostilesSensor.class,
        net.minecraft.world.entity.ai.sensing.WardenEntitySensor.class
    );

    private static final ThreadLocal<FastBitRadixSort> WORKER_SORTER = ThreadLocal.withInitial(FastBitRadixSort::new);
    private static volatile @Nullable ExecutorService pool;

    private ParallelSensors() {
    }

    /** Whether the sensor phase runs: on in the config, and not combined with parallel world ticking. */
    public static boolean enabled() {
        return ShulkerConfig.asyncSensors && !ParallelWorldTicking.enabled();
    }

    /** The sorter a sensor must use: the world's own on its tick thread, a private one on a worker. */
    public static FastBitRadixSort sorter(final ServerLevel level) {
        return Thread.currentThread() instanceof Worker ? WORKER_SORTER.get() : level.fastBitRadixSort;
    }

    /**
     * Called on the world's tick thread before entities tick. Returns once every sensor task has
     * finished, so entity ticking never overlaps with sensing.
     */
    public static void preTick(final ServerLevel level) {
        if (!enabled()) {
            return;
        }
        final ExecutorService executor = pool();
        final List<CompletableFuture<Void>> tasks = new ArrayList<>();
        final TickRateManager tickRateManager = level.tickRateManager();
        level.entityTickList.forEach(entity -> collect(level, entity, tickRateManager, executor, tasks));
        for (int i = 0, size = tasks.size(); i < size; i++) {
            try {
                tasks.get(i).join();
            } catch (Throwable t) {
                LOGGER.error("Sensor task failed in world '{}'", level.getWorld().getName(), t);
            }
        }
    }

    private static void collect(final ServerLevel level, final Entity entity, final TickRateManager tickRateManager,
                                final ExecutorService executor, final List<CompletableFuture<Void>> tasks) {
        if (!(entity instanceof Mob mob) || entity.isRemoved()
            || entity.activatedTick < MinecraftServer.currentTick // inactive (activation range): the brain does not tick
            || tickRateManager.isEntityFrozen(entity)) {
            return;
        }
        final Brain<?> brain = mob.getBrain();
        if (brain.sensors.isEmpty()) {
            return;
        }
        List<Sensor<?>> due = null;
        for (final Sensor<?> sensor : brain.sensors.values()) {
            if (sensor.shulker$isDue() && SAFE_SENSORS.contains(sensor.getClass())) {
                if (due == null) {
                    due = new ArrayList<>(2);
                }
                due.add(sensor);
            }
        }
        if (due == null) {
            return;
        }
        final List<Sensor<?>> dueSensors = due;
        tasks.add(CompletableFuture.runAsync(() -> {
            for (int i = 0, size = dueSensors.size(); i < size; i++) {
                @SuppressWarnings("unchecked") final Sensor<? super Mob> sensor = (Sensor<? super Mob>) dueSensors.get(i);
                sensor.shulker$tickAhead(level, mob);
            }
        }, executor));
    }

    private static ExecutorService pool() {
        ExecutorService executor = pool;
        if (executor == null) {
            synchronized (ParallelSensors.class) {
                executor = pool;
                if (executor == null) {
                    final int threads = ShulkerConfig.threads(ShulkerConfig.asyncSensorThreads);
                    final AtomicInteger ids = new AtomicInteger();
                    final ThreadPoolExecutor created = new ThreadPoolExecutor(threads, threads, 60L, TimeUnit.SECONDS,
                        new LinkedBlockingQueue<>(), run -> {
                            final Thread thread = new Worker(run, "Shulker Sensor Thread - " + ids.getAndIncrement());
                            thread.setDaemon(true);
                            return thread;
                        });
                    created.allowCoreThreadTimeOut(true);
                    pool = executor = created;
                    LOGGER.info("Using {} threads for mob sensors", threads);
                }
            }
        }
        return executor;
    }

    /** A tick thread, so Paper's thread checks accept the world reads sensors make. */
    private static final class Worker extends TickThread {
        Worker(final Runnable run, final String name) {
            super(run, name);
        }
    }
}
