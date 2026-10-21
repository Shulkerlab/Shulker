package dev.shulker.api.world;

import java.time.Duration;
import java.util.Objects;
import org.bukkit.World;
import org.bukkit.generator.ChunkGenerator;
import org.jspecify.annotations.Nullable;

/**
 * How a Shulk World runs once loaded. Immutable; build with {@link #builder()}.
 *
 * <p>Chunks the stored world does not contain are produced by the {@linkplain #generator()
 * generator}: empty (void) by default, so a world stays as small as what was built in it.
 */
public final class ShulkWorldProperties {

    private static final ShulkWorldProperties DEFAULTS = builder().build();

    private final World.Environment environment;
    private final boolean readOnly;
    private final Duration autosave;
    private final @Nullable ChunkGenerator generator;

    private ShulkWorldProperties(final Builder builder) {
        this.environment = builder.environment;
        this.readOnly = builder.readOnly;
        this.autosave = builder.autosave;
        this.generator = builder.generator;
    }

    /**
     * Normal environment, saved every five minutes, void outside the stored chunks.
     *
     * @return the default properties
     */
    public static ShulkWorldProperties defaults() {
        return DEFAULTS;
    }

    /**
     * Like {@link #defaults()}, but never saved: for arenas and lobbies reset by reloading.
     *
     * @return read-only properties
     */
    public static ShulkWorldProperties readOnlyWorld() {
        return builder().readOnly(true).build();
    }

    /**
     * Starts building properties.
     *
     * @return a builder with the default values
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * The dimension type.
     *
     * @return environment
     */
    public World.Environment environment() {
        return this.environment;
    }

    /**
     * Whether the world is never written back. Changes are lost on unload.
     *
     * @return read-only
     */
    public boolean readOnly() {
        return this.readOnly;
    }

    /**
     * How often the world is saved to its loader while loaded; {@link Duration#ZERO} saves only
     * on request and on unload.
     *
     * @return autosave interval
     */
    public Duration autosave() {
        return this.autosave;
    }

    /**
     * The generator for chunks the stored world does not contain; null for empty chunks.
     *
     * @return generator, or null for void
     */
    public @Nullable ChunkGenerator generator() {
        return this.generator;
    }

    /**
     * A builder starting from these properties.
     *
     * @return builder
     */
    public Builder toBuilder() {
        return new Builder().environment(this.environment).readOnly(this.readOnly).autosave(this.autosave).generator(this.generator);
    }

    /** Builds {@link ShulkWorldProperties}. */
    public static final class Builder {

        private World.Environment environment = World.Environment.NORMAL;
        private boolean readOnly;
        private Duration autosave = Duration.ofMinutes(5);
        private @Nullable ChunkGenerator generator;

        private Builder() {
        }

        /**
         * Sets the dimension type.
         *
         * @param environment environment
         * @return this builder
         */
        public Builder environment(final World.Environment environment) {
            this.environment = Objects.requireNonNull(environment, "environment");
            return this;
        }

        /**
         * Sets whether the world is never written back.
         *
         * @param readOnly read-only
         * @return this builder
         */
        public Builder readOnly(final boolean readOnly) {
            this.readOnly = readOnly;
            return this;
        }

        /**
         * Sets the autosave interval; {@link Duration#ZERO} turns autosave off.
         *
         * @param autosave interval, zero or positive
         * @return this builder
         * @throws IllegalArgumentException if the interval is negative
         */
        public Builder autosave(final Duration autosave) {
            if (autosave.isNegative()) {
                throw new IllegalArgumentException("autosave must not be negative");
            }
            this.autosave = autosave;
            return this;
        }

        /**
         * Sets the generator for chunks the stored world does not contain.
         *
         * @param generator generator, or null for empty chunks
         * @return this builder
         */
        public Builder generator(final @Nullable ChunkGenerator generator) {
            this.generator = generator;
            return this;
        }

        /**
         * Builds the properties.
         *
         * @return properties
         */
        public ShulkWorldProperties build() {
            return new ShulkWorldProperties(this);
        }
    }
}
