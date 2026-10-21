package dev.shulker.api.world.event;

import org.bukkit.World;
import org.bukkit.event.HandlerList;

/**
 * Called before a Shulk World is captured for saving, on the main thread: the last moment to
 * write data that should be part of the save, such as the world's persistent data container.
 */
public class ShulkWorldPreSaveEvent extends ShulkWorldEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    /**
     * Creates the event.
     *
     * @param world the world about to be saved
     */
    public ShulkWorldPreSaveEvent(final World world) {
        super(world);
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
