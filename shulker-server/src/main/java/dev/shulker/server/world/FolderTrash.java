package dev.shulker.server.world;

import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.stream.Stream;
import org.slf4j.Logger;

/**
 * Removes the folders of discarded Shulk Worlds without competing with the server for the disk.
 *
 * <p>Deleting a world folder right away is a burst of file operations; with many worlds
 * discarded at once (a pool shrinking, islands unloading) they slowed the file opens of worlds
 * being created on the main thread by seconds on Windows. A folder is instead renamed into
 * {@code .shulker/trash/} - one cheap operation - and a low-priority thread deletes the trash a
 * file at a time. Trash left by a previous run is deleted the same way.
 */
final class FolderTrash {

    private static final Logger LOGGER = LogUtils.getClassLogger();
    private static final Path TRASH = Path.of(".shulker", "trash");
    /** Pause between two deletions, so the trash never takes the disk for long. */
    private static final long PAUSE_MILLIS = 5;

    private static final LinkedBlockingQueue<Path> QUEUE = new LinkedBlockingQueue<>();

    static {
        final Thread worker = new Thread(FolderTrash::run, "Shulker World Trash");
        worker.setDaemon(true);
        worker.setPriority(Thread.MIN_PRIORITY);
        worker.start();
        try {
            if (Files.isDirectory(TRASH)) {
                try (Stream<Path> leftovers = Files.list(TRASH)) {
                    leftovers.forEach(QUEUE::add);
                }
            }
        } catch (IOException e) {
            LOGGER.warn("Could not list {}", TRASH, e);
        }
    }

    private FolderTrash() {
    }

    /** Moves a folder out of the way now and deletes it later; deletes it right away if it cannot be moved. */
    static void discard(final Path folder) {
        if (!Files.exists(folder)) {
            return;
        }
        final Path target = TRASH.resolve(UUID.randomUUID().toString()).toAbsolutePath();
        try {
            Files.createDirectories(TRASH);
            try {
                Files.move(folder, target, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(folder, target);
            }
            QUEUE.add(target);
        } catch (IOException e) {
            try {
                ShulkWorldManager.deleteRecursively(folder);
            } catch (IOException again) {
                LOGGER.warn("Could not delete {}", folder, again);
            }
        }
    }

    private static void run() {
        while (true) {
            try {
                final Path folder = QUEUE.take();
                try (Stream<Path> walk = Files.walk(folder)) {
                    for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
                        Files.deleteIfExists(path);
                        Thread.sleep(PAUSE_MILLIS);
                    }
                } catch (IOException e) {
                    LOGGER.warn("Could not delete {}", folder, e);
                }
            } catch (InterruptedException e) {
                return;
            }
        }
    }
}
