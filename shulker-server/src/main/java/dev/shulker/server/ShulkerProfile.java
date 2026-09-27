package dev.shulker.server;

import com.mojang.logging.LogUtils;
import io.papermc.paper.configuration.GlobalConfiguration;
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
 * <p>For the performance profiles, changes are chosen to be invisible to normal gameplay;
 * anything that changes game mechanics noticeably stays a manual choice. {@link #VANILLA} goes
 * the other way: it turns off every Paper/Spigot/Shulker deviation from vanilla, including the
 * exploit fixes, so technical players get dupers, bedrock breaking and vanilla farm rates.
 */
public enum ShulkerProfile {
    SURVIVAL,
    SKYBLOCK,
    MINIGAME,
    LOBBY,
    /** Vanilla mechanics: all exploits and dupes allowed, no behaviour-changing optimisations. */
    VANILLA;

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
            case VANILLA -> {
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

    // ---- spigot.yml -------------------------------------------------------------------------

    /**
     * Seeds a not-yet-existing spigot.yml. Spigot fills in every other key when it loads the
     * file, so a partial file is fine.
     */
    public static void prepareSpigotConfig(final File file) {
        if (file == null || file.exists() || current() != VANILLA) {
            return;
        }
        final String yaml = """
            # Seeded by the Shulker 'vanilla' profile.
            world-settings:
              default:
                # Every entity ticks every tick, like vanilla: farm rates match vanilla.
                entity-activation-range:
                  animals: 0
                  monsters: 0
                  raiders: 0
                  misc: 0
                  water: 0
                  villagers: 0
                  flying-monsters: 0
                  tick-inactive-villagers: true
                # No cap on TNT explosions per tick.
                max-tnt-per-tick: 1000000
                # Vanilla item and XP merging.
                merge-radius:
                  item: 0.5
                  exp: -1
                nerf-spawner-mobs: false
            """;
        try {
            Files.writeString(file.toPath(), yaml, StandardCharsets.UTF_8);
        } catch (IOException e) {
            System.err.println("[Shulker] Could not write profile defaults to " + file + ": " + e.getMessage());
        }
    }

    // ---- paper-global.yml ------------------------------------------------------------------

    /** Called once, when paper-global.yml is first created. */
    public static void applyGlobalDefaults(final GlobalConfiguration config) {
        if (current() != VANILLA) {
            return;
        }
        LOGGER.info("Applying Shulker 'vanilla' profile: exploits and dupes are allowed");
        config.unsupportedSettings.allowPistonDuplication = true;
        config.unsupportedSettings.allowHeadlessPistons = true;
        config.unsupportedSettings.allowPermanentBlockBreakExploits = true;
        config.unsupportedSettings.allowUnsafeEndPortalTeleportation = true;
        config.unsupportedSettings.skipTripwireHookPlacementValidation = true;
    }

    // ---- paper-world-defaults.yml ----------------------------------------------------------

    /** Called once, when paper-world-defaults.yml is first created. */
    public static void applyWorldDefaults(final WorldConfiguration config) {
        final ShulkerProfile profile = current();
        LOGGER.info("Applying Shulker '{}' profile defaults to the new world configuration", profile.id());

        if (profile == VANILLA) {
            // Undo Paper's gameplay changes and exploit fixes.
            config.unsupportedSettings.fixInvulnerableEndCrystalExploit = false;
            config.fixes.splitOverstackedLoot = false;
            config.collisions.maxEntityCollisions = Integer.MAX_VALUE; // vanilla pushes every overlapping entity
            config.entities.spawning.perPlayerMobSpawns = false;
            return;
        }

        // Every other profile: same results, less work.
        config.environment.optimizeExplosions = true;
        config.chunks.preventMovingIntoUnloadedChunks = true;
        capProjectiles(config.chunks.entityPerChunkSaveLimit);

        switch (profile) {
            case SURVIVAL, VANILLA -> {
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
