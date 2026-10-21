package dev.shulker.server.world;

import org.bukkit.generator.ChunkGenerator;

/**
 * The default generator of Shulk Worlds: chunks the stored world does not contain stay empty,
 * so a world never grows beyond what was built in it.
 */
final class VoidChunkGenerator extends ChunkGenerator {

    static final VoidChunkGenerator INSTANCE = new VoidChunkGenerator();

    private VoidChunkGenerator() {
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
