package dev.shulker.server.world;

import ca.spottedleaf.moonrise.common.util.TickThread;
import java.util.concurrent.ThreadFactory;

/**
 * One tick thread per world for parallel world ticking. From SparklyPaper
 * (https://github.com/SparklyPower/SparklyPaper) by MrPowerGamerBR, translated from Kotlin;
 * used under GPL-3.0 (see CREDITS.md).
 */
public final class ServerLevelTickExecutorThreadFactory implements ThreadFactory {

    private final String worldName;

    public ServerLevelTickExecutorThreadFactory(final String worldName) {
        this.worldName = worldName;
    }

    @Override
    public Thread newThread(final Runnable runnable) {
        final Thread thread = new TickThread.ServerLevelTickThread(runnable, "serverlevel-tick-worker [" + this.worldName + "]");
        thread.setDaemon(false);
        thread.setPriority(Thread.NORM_PRIORITY);
        return thread;
    }
}
