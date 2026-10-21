package dev.shulker.api.world;

/**
 * A Shulk World held in memory, read-only, to create worlds from. Creating a world from a
 * template copies references to its compressed chunks, so it costs about the same for any size;
 * keep a template around and create as many worlds from it as needed.
 */
public interface ShulkWorldTemplate {

    /**
     * The name the template was read or captured under.
     *
     * @return template name
     */
    String name();

    /**
     * The number of block chunks the template holds.
     *
     * @return chunk count
     */
    int chunkCount();

    /**
     * Compressed size of the template's chunks and files, roughly what it costs in memory.
     *
     * @return size in bytes
     */
    long sizeBytes();
}
