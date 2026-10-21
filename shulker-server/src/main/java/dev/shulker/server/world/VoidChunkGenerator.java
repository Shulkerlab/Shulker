package dev.shulker.server.world;

import java.util.Random;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.generator.ChunkGenerator;

/**
 * The default generator of Shulk Worlds: chunks the stored world does not contain stay empty,
 * so a world never grows beyond what was built in it.
 *
 * <p>A world without a stored spawn (a pool world, or a template made without level data) spawns
 * at 0.5, 64, 0.5. Without a fixed spawn the server would search for solid ground and, in a void
 * world, load chunk after chunk on the main thread without finding any.
 */
final class VoidChunkGenerator extends ChunkGenerator {

    static final VoidChunkGenerator INSTANCE = new VoidChunkGenerator();

    private VoidChunkGenerator() {
    }

    @Override
    public Location getFixedSpawnLocation(final World world, final Random random) {
        return new Location(world, 0.5, 64, 0.5);
    }

    @Override
    public boolean shouldGenerateNoise() {
        return false;
    }

    @Override
    public boolean shouldGenerateSurface() {
        return false;
    }

    @Override
    public boolean shouldGenerateCaves() {
        return false;
    }

    @Override
    public boolean shouldGenerateDecorations() {
        return false;
    }

    @Override
    public boolean shouldGenerateMobs() {
        return false;
    }

    @Override
    public boolean shouldGenerateStructures() {
        return false;
    }
}
