package dev.shulker.server.world;

import com.mojang.logging.LogUtils;
import dev.shulker.server.ShulkerConfig;
import org.slf4j.Logger;

/**
 * Switch for SparklyPaper's parallel world ticking (https://github.com/SparklyPower/SparklyPaper,
 * by MrPowerGamerBR; used under GPL-3.0, see CREDITS.md).
 *
 * <p>Off by default. SparklyPaper adds thread checks to many Bukkit and Minecraft methods that
 * Paper does not check; Shulker only runs those checks while parallel ticking is on, so plugins
 * that read blocks or chunks from async threads keep working on a default server.
 */
public final class ParallelWorldTicking {

    private static final Logger LOGGER = LogUtils.getClassLogger();

    private ParallelWorldTicking() {
    }

    /** Whether worlds tick in parallel, and SparklyPaper's extra thread checks apply. */
    public static boolean enabled() {
        return ShulkerConfig.parallelWorldTicking;
    }

    public static void announce(final int threads) {
        LOGGER.warn("*************************************************************************************");
        LOGGER.warn("Parallel world ticking is ON ({} threads). This is experimental.", threads);
        LOGGER.warn("Plugins that change another world from an event (teleports, block or entity edits)");
        LOGGER.warn("fail their thread checks and can crash the server; NoCheatPlus movement checks are known");
        LOGGER.warn("to. Non-player entities cannot use portals. Turn it off in shulker.yml");
        LOGGER.warn("(parallel-world-ticking.enabled) if anything misbehaves.");
        LOGGER.warn("*************************************************************************************");
    }
}
