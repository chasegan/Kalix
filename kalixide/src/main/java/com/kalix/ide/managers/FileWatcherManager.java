package com.kalix.ide.managers;

import com.kalix.ide.io.FsWatcher;
import com.kalix.ide.preferences.PreferenceKeys;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Watches every open document's file for external changes and triggers an auto-reload when a
 * file is changed on disk (and auto-reload is enabled). Backed by {@link FsWatcher} (native
 * FSEvents on macOS), so it does not suffer the multi-second latency of the JDK's polling
 * watch service.
 *
 * <p>All open files are watched — not just the active tab — so a change to a background tab's
 * file is picked up too. The set of files is always remembered (via {@link #setWatchedFiles})
 * independent of the auto-reload preference, and the actual watch is purely a function of that
 * set plus the preference (see {@link #restartWatch()}). So toggling the preference on/off
 * after files are open correctly starts/stops watching them.
 *
 * <p>{@link FsWatcher} watches directories, so this watches the deduped set of parent
 * directories of the open files and filters events to the files of interest. Reload events are
 * delivered on the EDT.
 */
public class FileWatcherManager {

    /**
     * After a save we briefly ignore changes so the IDE doesn't reload the file it just wrote.
     * A short time window absorbs the burst of events a single write can produce.
     */
    private static final long SELF_SAVE_IGNORE_WINDOW_MS = 2000;

    /**
     * How long after a DELETE event to wait before declaring the file missing. External
     * renames and editors' safe-write saves produce delete+create bursts; the delay lets the
     * file "come back" (same path) before we alarm the user.
     */
    private static final int MISSING_FILE_DEBOUNCE_MS = 600;

    private final Consumer<File> fileReloadCallback;
    /** Notified when an open file has verifiably vanished from disk (may be null). */
    private final Consumer<File> fileMissingCallback;
    private final FsWatcher fsWatcher;

    /**
     * Open document files, keyed by canonical (symlink-resolved) path → the original file.
     * OS change events arrive symlink-resolved (macOS /tmp → /private/tmp), so we match on the
     * canonical key but hand the original file back to the reload callback (documents are keyed
     * by the path the user opened). Remembered regardless of the auto-reload preference.
     */
    private final Map<File, File> watchedByCanonical = new LinkedHashMap<>();
    /** Per-file "ignore changes until" timestamps (keyed by canonical path), set on self-save. */
    private final Map<File, Long> ignoreUntil = new ConcurrentHashMap<>();

    /**
     * Creates a new FileWatcherManager.
     *
     * @param fileReloadCallback  Callback (invoked on the EDT) when a file should be reloaded
     * @param fileMissingCallback Callback (invoked on the EDT) when an open file has vanished
     *                            from disk — an external delete, or a rename the watcher only
     *                            sees as an uncorrelatable delete+create pair. May be null.
     */
    public FileWatcherManager(Consumer<File> fileReloadCallback, Consumer<File> fileMissingCallback) {
        this.fileReloadCallback = fileReloadCallback;
        this.fileMissingCallback = fileMissingCallback;
        this.fsWatcher = new FsWatcher(this::onFsEvent);
    }

    /**
     * Records the set of files to watch (the open documents' files) and (re)starts watching
     * their parent directories if auto-reload is enabled. Null entries are ignored.
     *
     * @param files The files of all open documents
     */
    public void setWatchedFiles(Collection<File> files) {
        watchedByCanonical.clear();
        for (File file : files) {
            if (file != null) {
                watchedByCanonical.put(canonical(file), file);
            }
        }
        restartWatch();
    }

    /**
     * Tells the watcher to ignore changes to the given file for a short window. Used when
     * saving a file to prevent the IDE from immediately reloading the file it just wrote —
     * scoped per file, so saving one document does not suppress an external change to another.
     *
     * @param file the file about to be written
     */
    public void ignoreNextChange(File file) {
        if (file != null) {
            ignoreUntil.put(canonical(file), System.currentTimeMillis() + SELF_SAVE_IGNORE_WINDOW_MS);
        }
    }

    /**
     * Checks if auto-reload is currently enabled in preferences.
     *
     * @return true if auto-reload is enabled
     */
    public boolean isAutoReloadEnabled() {
        return PreferenceKeys.FILE_AUTO_RELOAD.get();
    }

    /**
     * Enables or disables auto-reload and (re)starts or stops watching the current file
     * accordingly. Works whether or not a file was already open when toggled.
     *
     * @param enabled true to enable auto-reload
     */
    public void setAutoReloadEnabled(boolean enabled) {
        PreferenceKeys.FILE_AUTO_RELOAD.set(enabled);
        restartWatch();
    }

    /**
     * Shuts down the file watcher manager and releases resources.
     */
    public void shutdown() {
        watchedByCanonical.clear();
        ignoreUntil.clear();
        fsWatcher.stop();
    }

    /**
     * (Re)derives the watch from the set of open files and the auto-reload preference: watches
     * the deduped set of their parent directories.
     */
    private void restartWatch() {
        if (!isAutoReloadEnabled() || watchedByCanonical.isEmpty()) {
            fsWatcher.stop();
            return;
        }
        // Watch the canonical parent directories so they align with the symlink-resolved paths
        // the OS reports in events.
        Set<Path> directories = new LinkedHashSet<>();
        for (File canonicalFile : watchedByCanonical.keySet()) {
            File directory = canonicalFile.getParentFile();
            if (directory != null) {
                directories.add(directory.toPath());
            }
        }
        fsWatcher.watch(directories);
    }

    /**
     * Handles a filesystem event (on the EDT): reloads if it concerns one of the open files,
     * is a content change (create/modify), and isn't within the self-save ignore window.
     */
    private void onFsEvent(FsWatcher.Event event) {
        if (event.kind() == FsWatcher.Kind.DELETE) {
            onFileDeleted(event);
            return;
        }
        if (event.kind() != FsWatcher.Kind.MODIFY && event.kind() != FsWatcher.Kind.CREATE) {
            return; // other structural events don't change an open file's content
        }
        File changed = canonical(event.path().toFile());
        File original = watchedByCanonical.get(changed);
        if (original == null) {
            return; // a sibling in a watched directory; not one of our files
        }
        Long until = ignoreUntil.get(changed);
        if (until != null) {
            if (System.currentTimeMillis() < until) {
                return; // our own save of this file
            }
            ignoreUntil.remove(changed); // window elapsed; drop the entry
        }
        // Hand back the original (as-opened) file, not the canonical form: documents are keyed
        // by the path the user opened, so the reload lookup must use that.
        fileReloadCallback.accept(original);
    }

    /**
     * A DELETE arrived for one of the open files. Debounce before alarming: external renames
     * and safe-write saves show up as delete+create bursts, so only report the file missing
     * if it still doesn't exist after the wait (and we didn't just save it ourselves).
     */
    private void onFileDeleted(FsWatcher.Event event) {
        if (fileMissingCallback == null) {
            return;
        }
        // The path no longer exists, so canonical() falls back to the absolute form; look up
        // by both to be safe.
        File deleted = event.path().toFile();
        File original = watchedByCanonical.get(canonical(deleted));
        if (original == null) {
            original = watchedByCanonical.get(deleted.getAbsoluteFile());
        }
        if (original == null) {
            return; // a sibling in a watched directory; not one of our files
        }
        Long until = ignoreUntil.get(canonical(deleted));
        if (until != null && System.currentTimeMillis() < until) {
            return; // our own (safe-)save of this file
        }
        File finalOriginal = original;
        javax.swing.Timer timer = new javax.swing.Timer(MISSING_FILE_DEBOUNCE_MS, e -> {
            if (!finalOriginal.exists() && watchedByCanonical.containsValue(finalOriginal)) {
                fileMissingCallback.accept(finalOriginal);
            }
        });
        timer.setRepeats(false);
        timer.start();
    }

    /**
     * Resolves a file to its canonical (symlink-resolved) form so OS change events — which arrive
     * symlink-resolved — match the files we opened. Falls back to the absolute file if the path
     * can't be resolved (e.g. it no longer exists).
     */
    private static File canonical(File file) {
        try {
            return file.getCanonicalFile();
        } catch (IOException e) {
            return file.getAbsoluteFile();
        }
    }
}
