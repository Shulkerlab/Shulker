package dev.shulker.server.world;

import com.mojang.logging.LogUtils;
import dev.shulker.format.ShulkRegion;
import dev.shulker.server.ShulkerConfig;
import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.bukkit.Bukkit;
import org.slf4j.Logger;

/**
 * Recompresses chunks nobody has saved for a while at a high zstd level, in the background.
 *
 * <p>zstd-19 with the dictionary is about 13% smaller than the level-6 writes but ~25x slower
 * to compress (decompression speed is the same), so it only runs on one low-priority thread,
 * a limited number of chunks per second, and only while the server has tick time to spare.
 *
 * <p>Only regions the server already has open are touched, through the same objects the
 * server uses: opening a region file a second time would give it two independent headers.
 */
final class ColdStorage {

    private static final Logger LOGGER = LogUtils.getClassLogger();
    private static final Set<ShulkRegionFile> OPEN = ConcurrentHashMap.newKeySet();
    private static final AtomicBoolean STARTED = new AtomicBoolean();
    private static final long REPORT_INTERVAL_MILLIS = 30 * 60 * 1000L;

    private ColdStorage() {
    }

    static void opened(final ShulkRegionFile region) {
        OPEN.add(region);
        if (ShulkerConfig.coldRecompression && STARTED.compareAndSet(false, true)) {
            final Thread worker = new Thread(ColdStorage::run, "Shulker Cold Storage");
            worker.setDaemon(true);
            worker.setPriority(Thread.MIN_PRIORITY);
            worker.start();
        }
    }

    static void closed(final ShulkRegionFile region) {
        OPEN.remove(region);
    }

    private static void run() {
        long recompressed = 0;
        long lastReport = System.currentTimeMillis();
        while (true) {
            try {
                Thread.sleep(1000);
                if (!hasTickTimeToSpare()) {
                    continue;
                }
                final int writtenBefore = (int) (System.currentTimeMillis() / 1000L) - ShulkerConfig.coldRecompressionAfterHours * 3600;
                int budget = ShulkerConfig.coldRecompressionChunksPerSecond;
                for (ShulkRegionFile file : List.copyOf(OPEN)) {
                    final ShulkRegion region = file.shulkRegion();
                    if (region == null) {
                        continue;
                    }
                    for (int index : region.coldChunks(ShulkerConfig.coldRecompressionLevel, writtenBefore)) {
                        if (budget-- <= 0) {
                            break;
                        }
                        try {
                            if (region.recompress(index, ShulkerConfig.coldRecompressionLevel)) {
                                recompressed++;
                            }
                        } catch (IOException e) {
                            break; // region closed underneath us (evicted or shutting down)
                        }
                    }
                    if (budget <= 0) {
                        break;
                    }
                }
                if (recompressed > 0 && System.currentTimeMillis() - lastReport > REPORT_INTERVAL_MILLIS) {
                    LOGGER.info("Recompressed {} cold chunks at zstd level {}", recompressed, ShulkerConfig.coldRecompressionLevel);
                    recompressed = 0;
                    lastReport = System.currentTimeMillis();
                }
            } catch (InterruptedException e) {
                return;
            } catch (RuntimeException e) {
                LOGGER.warn("Cold storage recompression failed, will retry", e);
            }
        }
    }

    private static boolean hasTickTimeToSpare() {
        try {
            return Bukkit.getServer() != null && Bukkit.getServer().getAverageTickTime() < ShulkerConfig.coldRecompressionMaxMspt;
        } catch (RuntimeException e) {
            return false; // server not fully started
        }
    }
}
