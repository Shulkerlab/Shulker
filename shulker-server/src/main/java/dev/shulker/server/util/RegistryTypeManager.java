/*
 * From Leaf (https://github.com/Winds-Studio/Leaf), "Replace brain with optimized collection"
 * by HaHaWTH, co-authored by Taiyou06 and hayanesuru. MIT licensed.
 */
package dev.shulker.server.util;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.schedule.Activity;

/** Dense, id-indexed views of built-in registries for array-backed lookups. */
public final class RegistryTypeManager {

    public static final int ACTIVITY_SIZE;
    public static final Activity[] ACTIVITY_DIRECT;

    static {
        ACTIVITY_SIZE = BuiltInRegistries.ACTIVITY.size();
        if (ACTIVITY_SIZE == 0) {
            throw new ExceptionInInitializerError("RegistryTypeManager initialize before registries bootstrap");
        }
        if (ACTIVITY_SIZE > 32) {
            throw new ExceptionInInitializerError("minecraft:activity out of range int bitset (>32)");
        }
        ACTIVITY_DIRECT = new Activity[ACTIVITY_SIZE];
        for (int i = 0; i < ACTIVITY_SIZE; i++) {
            ACTIVITY_DIRECT[i] = BuiltInRegistries.ACTIVITY.byIdOrThrow(i);
            if (ACTIVITY_DIRECT[i].id != i) {
                throw new ExceptionInInitializerError();
            }
        }
    }

    private RegistryTypeManager() {
    }
}
