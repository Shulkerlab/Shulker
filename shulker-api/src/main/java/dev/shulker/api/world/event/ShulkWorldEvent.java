package dev.shulker.api.world.event;

import org.bukkit.World;
import org.bukkit.event.world.WorldEvent;

/** An event about a Shulk World. Fired on the main thread. */
public abstract class ShulkWorldEvent extends WorldEvent {

    protected ShulkWorldEvent(final World world) {
        super(world);
    }
}
