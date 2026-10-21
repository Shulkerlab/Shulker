package dev.shulker.format;

import java.util.Locale;

/** What a region file stores. Each kind has its own compression dictionary. */
public enum RegionKind {
    CHUNK,
    ENTITIES,
    POI,
    OTHER;

    /** Maps Minecraft's RegionStorageInfo type ("chunk", "entities", "poi") to a kind. */
    public static RegionKind fromStorageType(final String type) {
        return switch (type.toLowerCase(Locale.ROOT)) {
            case "chunk" -> CHUNK;
            case "entities" -> ENTITIES;
            case "poi" -> POI;
            default -> OTHER;
        };
    }

    /** Maps a region folder name ("region", "entities", "poi") to a kind. */
    public static RegionKind fromFolderName(final String folder) {
        return switch (folder) {
            case "region" -> CHUNK;
            case "entities" -> ENTITIES;
            case "poi" -> POI;
            default -> OTHER;
        };
    }

    String id() {
        return this.name().toLowerCase(Locale.ROOT);
    }
}
