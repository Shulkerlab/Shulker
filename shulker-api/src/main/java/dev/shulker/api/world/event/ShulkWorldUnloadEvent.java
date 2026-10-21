package dev.shulker.api.world.event;

import org.bukkit.World;
import org.bukkit.event.HandlerList;

/**
 * Called on the main thread when a Shulk World is about to be unloaded, after its players were
 * moved out and before it is (optionally) saved and removed.
 */
public class ShulkWorldUnloadEvent extends ShulkWorldEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    private final boolean save;

    /**
     * Creates the event.
     *
     * @param world the world about to be unloaded
     * @param save  whether it is saved first
     */
    public ShulkWorldUnloadEvent(final World world, final boolean save) {
        super(world);
        this.save = save;
    }

    /**
     * Whether the world is saved before it goes.
     *
     * @return whether it is saved
     */
    public boolean isSaving() {
        return this.save;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
