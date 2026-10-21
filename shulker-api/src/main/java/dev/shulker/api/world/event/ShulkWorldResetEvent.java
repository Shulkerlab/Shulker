package dev.shulker.api.world.event;

import org.bukkit.World;
import org.bukkit.event.HandlerList;

/**
 * Called on the main thread after a Shulk World's chunks were replaced by a template's
 * ({@link dev.shulker.api.world.ShulkWorlds#reset}); the world can be played again.
 */
public class ShulkWorldResetEvent extends ShulkWorldEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    private final String template;

    /**
     * Creates the event.
     *
     * @param world    the reset world
     * @param template the template it was reset to
     */
    public ShulkWorldResetEvent(final World world, final String template) {
        super(world);
        this.template = template;
    }

    /**
     * The template the world was reset to.
     *
     * @return template name
     */
    public String getTemplate() {
        return this.template;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
