/*
 * From Lithium (https://github.com/CaffeineMC/lithium), LGPL-3.0-only,
 * via Leaf (https://github.com/Winds-Studio/Leaf).
 */
package net.caffeinemc.mods.lithium.common.ai.brain.memories;

public interface MemoryModificationCounter {

    long lithium$getMemoryValueModCount();

    void lithium$onMemoryModified();
}
