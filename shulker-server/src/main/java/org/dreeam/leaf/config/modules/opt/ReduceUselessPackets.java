package org.dreeam.leaf.config.modules.opt;

import org.dreeam.leaf.config.ConfigModule;
import org.dreeam.leaf.config.ConfigCategory;

public class ReduceUselessPackets extends ConfigModule {

    public String basePath() {
        return ConfigCategory.PERF.basePath() + ".reduce-packets";
    }

    // Shulker - the move and motion packet filters are Shulker's own ports, configured in shulker.yml (entity-tracker.*)
    public static boolean disableUselessParticles = false;

    @Override
    public void onLoaded() {
        disableUselessParticles = globalConfig.getBoolean(basePath() + ".disable-useless-particles", disableUselessParticles);
    }
}
