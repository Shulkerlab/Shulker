package dev.shulker.api.world.event;

import org.bukkit.World;
import org.bukkit.event.HandlerList;

/** Called on the main thread after a Shulk World was stored by its loader. */
public class ShulkWorldSaveEvent extends ShulkWorldEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    private final long sizeBytes;

    /**
     * Creates the event.
     *
     * @param world     the saved world
     * @param sizeBytes size of the stored file
     */
    public ShulkWorldSaveEvent(final World world, final long sizeBytes) {
        super(world);
        this.sizeBytes = sizeBytes;
    }

    /**
     * Size of the stored world-layout {@code .shulk} file.
     *
     * @return size in bytes
     */
    public long getSizeBytes() {
        return this.sizeBytes;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
