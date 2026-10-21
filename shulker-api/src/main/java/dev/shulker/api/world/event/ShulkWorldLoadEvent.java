package dev.shulker.api.world.event;

import org.bukkit.World;
import org.bukkit.event.HandlerList;
import org.jspecify.annotations.Nullable;

/**
 * Called once a Shulk World has been loaded or created and is ready for players. Bukkit's own
 * {@link org.bukkit.event.world.WorldLoadEvent} fires before it.
 */
public class ShulkWorldLoadEvent extends ShulkWorldEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    private final @Nullable String template;

    /**
     * Creates the event.
     *
     * @param world    the loaded world
     * @param template the template it was created from, or null
     */
    public ShulkWorldLoadEvent(final World world, final @Nullable String template) {
        super(world);
        this.template = template;
    }

    /**
     * The template the world was created from.
     *
     * @return template name, or null when the world was loaded from storage
     */
    public @Nullable String getTemplate() {
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
