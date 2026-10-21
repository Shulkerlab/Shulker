package dev.shulker.server.world;

import dev.shulker.api.world.ShulkWorldLoader;
import dev.shulker.format.ShulkWorld;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.stream.Stream;

/**
 * The built-in loader: one world-layout {@code <name>.shulk} file per world in a folder
 * ({@code shulker-worlds/} by default). Writes are atomic (temporary file, sync, rename).
 */
final class ShulkWorldFileLoader implements ShulkWorldLoader {

    private final Path folder;
    private final Executor executor;

    ShulkWorldFileLoader(final Path folder, final Executor executor) {
        this.folder = folder;
        this.executor = executor;
    }

    Path folder() {
        return this.folder;
    }

    Path file(final String name) {
        ShulkWorldManager.checkName(name);
        return this.folder.resolve(name + ShulkWorld.EXTENSION);
    }

    @Override
    public CompletableFuture<byte[]> read(final String name) {
        return this.io(() -> {
            try {
                return Files.readAllBytes(this.file(name));
            } catch (NoSuchFileException e) {
                throw new IOException("No Shulk World named " + name + " in " + this.folder, e);
            }
        });
    }

    @Override
    public CompletableFuture<Void> write(final String name, final byte[] data) {
        return this.io(() -> {
            ShulkWorld.writeAtomically(this.file(name), data);
            return null;
        });
    }

    @Override
    public CompletableFuture<Boolean> exists(final String name) {
        return this.io(() -> Files.isRegularFile(this.file(name)));
    }

    @Override
    public CompletableFuture<Void> delete(final String name) {
        return this.io(() -> {
            Files.deleteIfExists(this.file(name));
            return null;
        });
    }

    @Override
    public CompletableFuture<List<String>> list() {
        return this.io(() -> {
            if (!Files.isDirectory(this.folder)) {
                return List.of();
            }
            try (Stream<Path> files = Files.list(this.folder)) {
                return files.map(path -> path.getFileName().toString())
                    .filter(name -> name.endsWith(ShulkWorld.EXTENSION))
                    .map(name -> name.substring(0, name.length() - ShulkWorld.EXTENSION.length()))
                    .sorted()
                    .toList();
            }
        });
    }

    private <T> CompletableFuture<T> io(final IoTask<T> task) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return task.run();
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        }, this.executor);
    }

    @FunctionalInterface
    private interface IoTask<T> {
        T run() throws IOException;
    }
}
