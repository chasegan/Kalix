package com.kalix.ide.io;

import io.methvin.watcher.DirectoryChangeEvent;
import io.methvin.watcher.DirectoryWatcher;
import io.methvin.watcher.hashing.FileHasher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.SwingUtilities;
import java.io.IOException;
import java.nio.file.Path;
import java.util.function.Consumer;

/**
 * Watches a directory tree for filesystem changes and delivers them, on the EDT, to a single
 * listener. Wraps io.methvin's native watcher (FSEvents on macOS) with the performance and
 * lifecycle handling the UI needs:
 *
 * <ul>
 *   <li><b>Off-EDT init.</b> methvin's initial scan is synchronous and can take seconds on a
 *       large or network tree; building/starting on a background thread keeps the UI responsive.</li>
 *   <li><b>Stat-based hashing.</b> {@link FileHasher#LAST_MODIFIED_TIME} never reads file content
 *       — the default content hasher would read the entire tree (potentially gigabytes) on start.</li>
 *   <li><b>Generation token.</b> A slow init that has been superseded by a later
 *       {@link #watch}/{@link #stop} is discarded rather than leaked.</li>
 *   <li><b>Decoupled events.</b> Changes arrive as a small {@link Event} on the EDT, so consumers
 *       neither depend on the watch library nor need their own thread marshaling.</li>
 * </ul>
 *
 * <p>{@link #watch} and {@link #stop} must be called on the EDT.
 */
public class FsWatcher {

    private static final Logger logger = LoggerFactory.getLogger(FsWatcher.class);

    /** Kind of filesystem change. */
    public enum Kind { CREATE, MODIFY, DELETE, OVERFLOW }

    /** A filesystem change of {@code kind} at {@code path}. */
    public record Event(Kind kind, Path path) { }

    private final Consumer<Event> onEvent;
    private DirectoryWatcher watcher;
    private int generation = 0; // EDT-confined

    /**
     * @param onEvent invoked on the EDT for each change under the watched root
     */
    public FsWatcher(Consumer<Event> onEvent) {
        this.onEvent = onEvent;
    }

    /**
     * Watches the given directory tree, replacing any previous watch. Returns immediately;
     * the watcher initialises on a background thread.
     */
    public void watch(Path root) {
        stop();
        final int gen = ++generation;
        Thread init = new Thread(() -> {
            try {
                DirectoryWatcher built = DirectoryWatcher.builder()
                    .path(root)
                    .fileHasher(FileHasher.LAST_MODIFIED_TIME)
                    .listener(this::dispatch)
                    .build();
                built.watchAsync(); // synchronous initial scan happens here, off the EDT
                SwingUtilities.invokeLater(() -> {
                    if (gen != generation) {
                        closeQuietly(built); // superseded by a later watch()/stop(); discard
                    } else {
                        watcher = built;
                    }
                });
            } catch (IOException ex) {
                logger.warn("Failed to start filesystem watcher for {}: {}", root, ex.getMessage());
            }
        }, "FsWatcher-init");
        init.setDaemon(true);
        init.start();
    }

    /** Stops watching and releases resources, invalidating any in-flight init. */
    public void stop() {
        generation++;
        if (watcher != null) {
            closeQuietly(watcher);
            watcher = null;
        }
    }

    private void dispatch(DirectoryChangeEvent event) {
        Event e = new Event(toKind(event.eventType()), event.path());
        SwingUtilities.invokeLater(() -> onEvent.accept(e));
    }

    private static Kind toKind(DirectoryChangeEvent.EventType type) {
        switch (type) {
            case CREATE: return Kind.CREATE;
            case DELETE: return Kind.DELETE;
            case OVERFLOW: return Kind.OVERFLOW;
            case MODIFY:
            default: return Kind.MODIFY;
        }
    }

    private static void closeQuietly(DirectoryWatcher w) {
        try {
            w.close();
        } catch (IOException ignored) {
            // best-effort
        }
    }
}
