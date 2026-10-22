package dev.shulker.server.world;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.storage.SerializableChunkData;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.phys.AABB;
import org.jspecify.annotations.Nullable;

/**
 * Puts a template's chunk back into a loaded chunk, the way pasting does: every block that
 * differs is set through {@link ServerLevel#setBlock}, so light, heightmaps, points of interest
 * and clients are updated as for any block change, then the template's block entities and
 * entities replace the chunk's. The chunk stays loaded, and so do its neighbours.
 *
 * <p>The template chunk is only parsed (sections and block entity tags), not read into a
 * ProtoChunk, which would register points of interest and light data for the position.
 */
final class ChunkRestorer {

    /** Tell clients; no drops, no neighbour shape updates, no block entity side effects. */
    private static final int FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_SKIP_ALL_SIDEEFFECTS;

    private ChunkRestorer() {
    }

    /**
     * Restores a loaded chunk to the template's.
     *
     * @param chunk    the loaded chunk
     * @param blocks   the template's chunk data at the position, or null for an empty chunk
     * @param entities the template's entity chunk data at the position, or null for none
     * @return how many blocks were changed
     */
    static int restore(final ServerLevel level, final LevelChunk chunk, final byte @Nullable [] blocks, final byte @Nullable [] entities) throws IOException {
        final LevelChunkSection[] target = new LevelChunkSection[level.getSectionsCount()];
        List<CompoundTag> blockEntities = List.of();
        if (blocks != null) {
            final CompoundTag tag = level.getChunkSource().chunkMap.upgradeChunkTag(read(blocks));
            final SerializableChunkData data = SerializableChunkData.parse(level, level.palettedContainerFactory(), tag);
            if (data != null) {
                for (SerializableChunkData.SectionData section : data.sectionData()) {
                    final int index = level.getSectionIndexFromSectionY(section.y());
                    if (index >= 0 && index < target.length) {
                        target[index] = section.chunkSection();
                    }
                }
                blockEntities = data.blockEntities();
            }
        }

        final int minX = chunk.getPos().getMinBlockX();
        final int minZ = chunk.getPos().getMinBlockZ();
        final AABB bounds = new AABB(minX, level.getMinY(), minZ, minX + 16, level.getMaxY() + 1, minZ + 16);
        for (Entity entity : level.getEntities((Entity) null, bounds, entity -> !(entity instanceof Player))) {
            entity.discard();
        }

        final LevelChunkSection[] live = chunk.getSections();
        final BlockState air = Blocks.AIR.defaultBlockState();
        final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int changed = 0;
        for (int index = 0; index < live.length; index++) {
            final LevelChunkSection mine = live[index];
            final LevelChunkSection theirs = target[index];
            final boolean mineEmpty = mine == null || mine.hasOnlyAir();
            final boolean theirsEmpty = theirs == null || theirs.hasOnlyAir();
            if (mineEmpty && theirsEmpty) {
                continue;
            }
            final int minY = level.getSectionYFromSectionIndex(index) << 4;
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        final BlockState want = theirsEmpty ? air : theirs.getBlockState(x, y, z);
                        final BlockState have = mineEmpty ? air : mine.getBlockState(x, y, z);
                        if (want != have) {
                            level.setBlock(pos.set(minX + x, minY + y, minZ + z), want, FLAGS);
                            changed++;
                        }
                    }
                }
            }
        }

        // The template's block entities replace whatever the game left in them (a chest's items).
        for (CompoundTag tag : blockEntities) {
            final BlockPos at = BlockEntity.getPosFromTag(chunk.getPos(), tag);
            final BlockEntity blockEntity = BlockEntity.loadStatic(at, level.getBlockState(at), tag, level.registryAccess());
            if (blockEntity != null) {
                level.setBlockEntity(blockEntity);
            }
        }

        if (entities != null) {
            final CompoundTag tag = read(entities);
            EntityType.loadEntitiesRecursive(
                TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(), tag).childrenListOrEmpty("Entities"),
                level, EntitySpawnReason.LOAD
            ).forEach(level::addFreshEntity);
        }
        return changed;
    }

    private static CompoundTag read(final byte[] raw) throws IOException {
        return NbtIo.read(new DataInputStream(new ByteArrayInputStream(raw)));
    }
}
