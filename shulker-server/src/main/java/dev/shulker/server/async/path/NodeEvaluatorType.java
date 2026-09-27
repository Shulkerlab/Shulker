/*
 * Async pathfinding from Petal (https://github.com/Bloom-host/Petal) by peaches94,
 * updated by Kaiiju (https://github.com/KaiijuMC/Kaiiju) and Leaf
 * (https://github.com/Winds-Studio/Leaf). GPL-3.0-only.
 */
package dev.shulker.server.async.path;

import net.minecraft.world.level.pathfinder.AmphibiousNodeEvaluator;
import net.minecraft.world.level.pathfinder.FlyNodeEvaluator;
import net.minecraft.world.level.pathfinder.NodeEvaluator;
import net.minecraft.world.level.pathfinder.SwimNodeEvaluator;

public enum NodeEvaluatorType {
    WALK,
    SWIM,
    AMPHIBIOUS,
    FLY;

    public static NodeEvaluatorType fromNodeEvaluator(NodeEvaluator nodeEvaluator) {
        return switch (nodeEvaluator) {
            case SwimNodeEvaluator ignored -> SWIM;
            case FlyNodeEvaluator ignored -> FLY;
            case AmphibiousNodeEvaluator ignored -> AMPHIBIOUS;
            default -> WALK;
        };
    }
}
