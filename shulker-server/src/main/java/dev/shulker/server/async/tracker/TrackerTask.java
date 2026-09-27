/*
 * Multithreaded entity tracker from Leaf (https://github.com/Winds-Studio/Leaf), refactored from
 * Petal (https://github.com/Bloom-host/Petal) and Airplane (https://github.com/TECHNOVE/Airplane-Experimental)
 * by peaches94, Paul Sauve and Kevin Raneri, with HaHaWTH and hayanesuru. GPL-3.0-only.
 */
package dev.shulker.server.async.tracker;

import ca.spottedleaf.moonrise.patches.chunk_system.entity.ChunkSystemEntity;
import ca.spottedleaf.moonrise.patches.chunk_system.level.chunk.ChunkData;
import ca.spottedleaf.moonrise.patches.entity_tracker.EntityTrackerEntity;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.FullChunkStatus;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import dev.shulker.server.util.EntitySlice;

import java.util.concurrent.Callable;

public record TrackerTask(ServerLevel world, EntitySlice entities) implements Callable<TrackerCtx> {

    @Override
    public TrackerCtx call() throws Exception {
        final TrackerCtx ctx = new TrackerCtx(this.world);
        final Entity[] raw = entities.array();
        for (int i = entities.start(); i < entities.end(); i++) {
            final Entity entity = raw[i];
            final ChunkMap.TrackedEntity tracker = ((EntityTrackerEntity) entity).moonrise$getTrackedEntity();
            // removed in world if null
            if (tracker == null) {
                continue;
            }
            if (tracker.getClass() != ChunkMap.TrackedEntity.class) {
                ctx.citizensEntity(tracker);
                continue;
            }
            ChunkData chunkData = ((ChunkSystemEntity) entity).moonrise$getChunkData();
            boolean flag = tracker.shulker$tick(ctx, chunkData == null ? null : chunkData.nearbyPlayers);
            if (!flag) {
                FullChunkStatus status = ((ChunkSystemEntity) entity).moonrise$getChunkStatus();
                // removed in world if null
                flag = status != null && status.isOrAfter(FullChunkStatus.ENTITY_TICKING);
            }
            if (flag || entity.needsSync) {
                tracker.serverEntity.shulker$sendChanges(ctx, tracker, false);
            }
        }
        return ctx;
    }
}
