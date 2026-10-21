package dev.shulker.server.world;

import dev.shulker.format.RegionKind;
import dev.shulker.format.ShulkWorld;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.Set;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;

/**
 * Recognises chunks that hold nothing: only air, no block entities, no scheduled ticks, no
 * plugin data, or an entity chunk without entities. In a world whose generator is void such a
 * chunk comes back identical when it is needed again, so a Shulk World does not store it - a
 * template made from a regular world would otherwise carry every empty chunk around spawn.
 */
final class EmptyChunks {

    private static final Set<String> AIR = Set.of("minecraft:air", "minecraft:cave_air", "minecraft:void_air");

    private EmptyChunks() {
    }

    static boolean isEmpty(final RegionKind kind, final CompoundTag tag) {
        return switch (kind) {
            case CHUNK -> isEmptyChunk(tag);
            case ENTITIES -> tag.getListOrEmpty("Entities").isEmpty();
            default -> false;
        };
    }

    private static boolean isEmptyChunk(final CompoundTag tag) {
        if (!tag.getListOrEmpty("block_entities").isEmpty() || !tag.getListOrEmpty("block_ticks").isEmpty()
            || !tag.getListOrEmpty("fluid_ticks").isEmpty() || !tag.getCompoundOrEmpty("ChunkBukkitValues").isEmpty()) {
            return false;
        }
        final ListTag sections = tag.getListOrEmpty("sections");
        for (int i = 0; i < sections.size(); i++) {
            final ListTag palette = sections.getCompoundOrEmpty(i).getCompoundOrEmpty("block_states").getListOrEmpty("palette");
            for (int j = 0; j < palette.size(); j++) {
                if (!AIR.contains(palette.getCompoundOrEmpty(j).getStringOr("Name", ""))) {
                    return false;
                }
            }
        }
        return true;
    }

    /** Drops every empty chunk and entity chunk of a world. Returns how many were dropped. */
    static int prune(final ShulkWorld world) throws IOException {
        int dropped = 0;
        for (RegionKind kind : new RegionKind[] {RegionKind.CHUNK, RegionKind.ENTITIES}) {
            for (long key : world.chunkKeys(kind)) {
                final int x = ShulkWorld.keyX(key);
                final int z = ShulkWorld.keyZ(key);
                final byte[] raw = world.read(kind, x, z);
                if (raw != null && isEmpty(kind, NbtIo.read(new DataInputStream(new ByteArrayInputStream(raw))))) {
                    world.delete(kind, x, z);
                    dropped++;
                }
            }
        }
        return dropped;
    }
}
