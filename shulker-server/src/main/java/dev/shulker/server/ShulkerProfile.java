package dev.shulker.server;

import com.mojang.logging.LogUtils;
import io.papermc.paper.configuration.WorldConfiguration;
import it.unimi.dsi.fastutil.objects.Reference2IntMap;
import java.io.File;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import org.slf4j.Logger;

/**
 * Server-side half of the Shulker profile system. The launcher picks JVM settings for the
 * profile; this class picks game-server defaults for it.
 *
 * <p>Profiles only shape the <em>initial</em> configuration: values are applied when
 * server.properties or paper-world-defaults.yml is created for the first time. From then on
 * the files belong to the server owner and are never touched again.
 *
 * <p>Changes here are performance-oriented and chosen to be invisible to normal gameplay;
 * anything that changes game mechanics noticeably stays a manual choice.
 */
public enum ShulkerProfile {
    SURVIVAL,
    SKYBLOCK,
    MINIGAME,
    LOBBY;

    public static final String PROPERTY = "shulker.profile";
    private static final Logger LOGGER = LogUtils.getClassLogger();
    private static ShulkerProfile current;

    public static ShulkerProfile current() {
        if (current == null) {
            final String value = System.getProperty(PROPERTY, "survival");
            try {
                current = valueOf(value.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                LOGGER.warn("Unknown Shulker profile '{}', using survival", value);
                current = SURVIVAL;
            }
        }
        return current;
    }

    public String id() {
        return this.name().toLowerCase(Locale.ROOT);
    }

    // ---- server.properties -----------------------------------------------------------------

    Map<String, String> serverPropertiesDefaults() {
        final Map<String, String> values = new LinkedHashMap<>();
        switch (this) {
            case SURVIVAL -> {
                // Vanilla distances; survival players expect them.
            }
            case SKYBLOCK, MINIGAME -> {
                values.put("view-distance", "8");
                values.put("simulation-distance", "6");
            }
            case LOBBY -> {
                values.put("view-distance", "6");
                values.put("simulation-distance", "4");
                values.put("allow-nether", "false"); // one less world to load and tick
            }
        }
        return values;
    }

    /**
     * Seeds a not-yet-existing server.properties with the profile's values. Minecraft fills in
     * every other key when it loads the file.
     */
    public static void prepareServerProperties(final File file) {
        if (file == null || file.exists()) {
            return;
        }
        final Map<String, String> defaults = current().serverPropertiesDefaults();
        if (defaults.isEmpty()) {
            return;
        }
        final Properties properties = new Properties();
        properties.putAll(defaults);
        try (Writer writer = Files.newBufferedWriter(file.toPath(), StandardCharsets.UTF_8)) {
            properties.store(writer, "Minecraft server properties (Shulker profile: " + current().id() + ")");
        } catch (IOException e) {
            System.err.println("[Shulker] Could not write profile defaults to " + file + ": " + e.getMessage());
        }
    }

    // ---- paper-world-defaults.yml ----------------------------------------------------------

    /** Called once, when paper-world-defaults.yml is first created. */
    public static void applyWorldDefaults(final WorldConfiguration config) {
        final ShulkerProfile profile = current();
        LOGGER.info("Applying Shulker '{}' profile defaults to the new world configuration", profile.id());

        // Every profile: same results, less work.
        config.environment.optimizeExplosions = true;
        config.chunks.preventMovingIntoUnloadedChunks = true;
        capProjectiles(config.chunks.entityPerChunkSaveLimit);

        switch (profile) {
            case SURVIVAL -> {
            }
            case SKYBLOCK -> {
                // Island mob farms pile entities into one block.
                config.collisions.maxEntityCollisions = 2;
                // Redstone-heavy farms; Alternate Current is behaviour-compatible and much cheaper.
                config.misc.redstoneImplementation = WorldConfiguration.Misc.RedstoneImplementation.ALTERNATE_CURRENT;
            }
            case MINIGAME -> {
                config.collisions.maxEntityCollisions = 2;
                config.misc.redstoneImplementation = WorldConfiguration.Misc.RedstoneImplementation.ALTERNATE_CURRENT;
                config.entities.armorStands.doCollisionEntityLookups = false;
            }
            case LOBBY -> {
                config.collisions.maxEntityCollisions = 2;
                config.misc.redstoneImplementation = WorldConfiguration.Misc.RedstoneImplementation.ALTERNATE_CURRENT;
                // Lobby armor stands are holograms and decorations.
                config.entities.armorStands.tick = false;
                config.entities.armorStands.doCollisionEntityLookups = false;
                config.tickRates.grassSpread = 4;
            }
        }
    }

    /** Stops chunks from carrying thousands of stray projectiles/orbs across saves. */
    private static void capProjectiles(final Reference2IntMap<EntityType<?>> limits) {
        limits.put(EntityTypes.EXPERIENCE_ORB, 16);
        limits.put(EntityTypes.ARROW, 16);
        limits.put(EntityTypes.SPECTRAL_ARROW, 16);
        limits.put(EntityTypes.SNOWBALL, 8);
        limits.put(EntityTypes.EGG, 8);
        limits.put(EntityTypes.ENDER_PEARL, 8);
        limits.put(EntityTypes.FIREBALL, 8);
        limits.put(EntityTypes.SMALL_FIREBALL, 8);
        limits.put(EntityTypes.WITHER_SKULL, 4);
    }
}
