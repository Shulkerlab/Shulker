package dev.shulker.server.world;

import net.minecraft.server.MinecraftServer;
import org.jspecify.annotations.Nullable;

/**
 * Spigot and Purpur rewrite their whole global file (spigot.yml, purpur.yml) every time a world's
 * settings are read, which happens for every world that is loaded. On a server that creates
 * worlds while running - islands, arenas - that is a file write, and on Windows a virus scan, on
 * the main thread per world, for a file whose content does not change. The startup worlds still
 * write it, so new defaults reach the file as before.
 */
public final class WorldConfigSaves {

    private WorldConfigSaves() {
    }

    /**
     * Whether readConfig should save the global file.
     *
     * @param instance the world settings object being read, or null for the global settings
     */
    public static boolean shouldSave(final @Nullable Object instance) {
        final MinecraftServer server = MinecraftServer.getServer();
        return instance == null || server == null || !server.isReady();
    }
}
