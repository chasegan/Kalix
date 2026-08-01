package com.kalix.ide.filedialog;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.SwingUtilities;
import java.io.IOException;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Asynchronous directory enumeration for the file dialogs — the anti-{@code JFileChooser}.
 *
 * <p>Performance doctrine (why this class exists): filesystems — network ones especially —
 * stream a directory's entries <em>with their attributes</em> in batched wire operations, and
 * {@link Files#walkFileTree} surfaces exactly that: each entry arrives with its
 * {@link BasicFileAttributes} from the enumeration itself. One listing pass, no per-entry
 * follow-up stats, no shell icon lookups. {@code JFileChooser}'s N+1 pattern (list names,
 * then re-stat every file, on the EDT) is precisely what this replaces.
 *
 * <p>Threading: {@link #list} may be called from any thread (the dialogs call it on the EDT).
 * Enumeration runs on a single background daemon thread — requests queue and complete in
 * order, which suits the dialogs (a Miller-columns trail fills left to right). Results are
 * delivered to the callbacks <em>on the EDT</em> in incremental sorted batches, so a huge or
 * slow directory paints progressively. Each request returns a {@link Handle}; cancelling it
 * stops the walk and silences every not-yet-delivered callback, so views never receive stale
 * results after navigating away.
 */
final class DirectoryLister {

    private static final Logger logger = LoggerFactory.getLogger(DirectoryLister.class);

    /** Entries per incremental batch delivered to the EDT. */
    private static final int BATCH_SIZE = 128;

    /** A cancellable in-flight listing. */
    static final class Handle {
        private volatile boolean cancelled;

        void cancel() {
            cancelled = true;
        }
    }

    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "kalix-file-dialog-lister");
        t.setDaemon(true);
        return t;
    });

    /**
     * Lists {@code dir} in the background, delivering sorted snapshots on the EDT.
     *
     * @param dir     the directory to enumerate
     * @param onBatch receives the full, freshly sorted entry list after each batch and once
     *                on completion (sorting per batch is cheap at dialog scale and keeps the
     *                view consistently ordered while streaming)
     * @param onDone  called once when the listing completes: null on success, else a
     *                user-presentable error message
     * @return a handle that cancels the listing and silences its remaining callbacks
     */
    Handle list(Path dir, Consumer<List<FsEntry>> onBatch, Consumer<String> onDone) {
        Handle handle = new Handle();
        executor.execute(() -> {
            List<FsEntry> all = new ArrayList<>();
            List<FsEntry> pending = new ArrayList<>();
            try {
                Files.walkFileTree(dir, EnumSet.noneOf(FileVisitOption.class), 1,
                    new SimpleFileVisitor<>() {
                        @Override
                        public FileVisitResult preVisitDirectory(Path entry, BasicFileAttributes attrs) {
                            if (entry.equals(dir)) {
                                return continueOrStop(handle);
                            }
                            add(entry, attrs);
                            // Depth 1: record the subdirectory itself, never descend.
                            return FileVisitResult.SKIP_SUBTREE;
                        }

                        @Override
                        public FileVisitResult visitFile(Path entry, BasicFileAttributes attrs) {
                            add(entry, attrs);
                            return continueOrStop(handle);
                        }

                        @Override
                        public FileVisitResult visitFileFailed(Path entry, IOException exc) throws IOException {
                            if (entry.equals(dir)) {
                                // The directory itself is unreadable/missing: that IS the failure.
                                throw exc;
                            }
                            // A single unreadable entry shouldn't kill the listing.
                            return continueOrStop(handle);
                        }

                        private void add(Path entry, BasicFileAttributes attrs) {
                            Path fileName = entry.getFileName();
                            if (fileName == null) {
                                return;
                            }
                            pending.add(new FsEntry(entry, fileName.toString(), attrs.isDirectory(),
                                attrs.isDirectory() ? 0 : attrs.size(),
                                attrs.lastModifiedTime().toMillis()));
                            if (pending.size() >= BATCH_SIZE) {
                                publish();
                            }
                        }

                        private void publish() {
                            all.addAll(pending);
                            pending.clear();
                            deliver(handle, sortedSnapshot(all), onBatch, null, null);
                        }
                    });
                all.addAll(pending);
                deliver(handle, sortedSnapshot(all), onBatch, onDone, null);
            } catch (IOException ex) {
                logger.warn("Failed to list {}: {}", dir, ex.toString());
                deliver(handle, null, null, onDone,
                    "Could not read folder: " + (ex.getMessage() != null ? ex.getMessage() : dir));
            }
        });
        return handle;
    }

    private static List<FsEntry> sortedSnapshot(List<FsEntry> all) {
        List<FsEntry> snapshot = new ArrayList<>(all);
        snapshot.sort(FsEntry.ENTRY_ORDER);
        return snapshot;
    }

    private static FileVisitResult continueOrStop(Handle handle) {
        return handle.cancelled ? FileVisitResult.TERMINATE : FileVisitResult.CONTINUE;
    }

    /**
     * Delivers a batch and/or completion on the EDT, unless the handle was cancelled.
     *
     * <p>The cancellation check is on the EDT, and only there. A second check before
     * {@code invokeLater} would look like defence in depth but cannot be authoritative —
     * it can pass and have {@code cancel()} land immediately afterwards, so the EDT-side
     * check is required regardless. All the earlier one bought was skipping the post of a
     * Runnable that then does nothing, which the walk's own {@code TERMINATE} already
     * bounds to a handful per cancelled listing. It cost more than it saved: with two
     * checks, no test could distinguish which one was doing the work, and the load-bearing
     * one could be deleted with the suite still green.
     */
    private static void deliver(Handle handle, List<FsEntry> entries,
                                Consumer<List<FsEntry>> onBatch,
                                Consumer<String> onDone, String error) {
        SwingUtilities.invokeLater(() -> {
            if (handle.cancelled) {
                return;
            }
            if (onBatch != null) {
                onBatch.accept(entries);
            }
            if (onDone != null) {
                onDone.accept(error);
            }
        });
    }

    /** Stops the background thread. Call when the owning dialog is disposed. */
    void dispose() {
        executor.shutdownNow();
    }
}
