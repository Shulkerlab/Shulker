/*
 * Async pathfinding from Petal (https://github.com/Bloom-host/Petal) by peaches94,
 * updated by Kaiiju (https://github.com/KaiijuMC/Kaiiju) and Leaf
 * (https://github.com/Winds-Studio/Leaf). GPL-3.0-only.
 */
package dev.shulker.server.async.path;

import net.minecraft.world.level.pathfinder.NodeEvaluator;

public interface NodeEvaluatorGenerator {
    NodeEvaluator generate(NodeEvaluatorFeatures nodeEvaluatorFeatures);
}
