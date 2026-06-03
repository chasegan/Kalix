package com.kalix.ide.managers;

import com.kalix.ide.io.FsWatcher;
import com.kalix.ide.preferences.PreferenceKeys;
import com.kalix.ide.preferences.PreferenceManager;

import java.io.File;
import java.util.function.Consumer;

/**
 * Watches the currently loaded file for external changes and triggers an auto-reload when the
 * file is changed on disk (and auto-reload is enabled). Backed by {@link FsWatcher} (native
 * FSEvents on macOS), so it does not suffer the multi-second latency of the JDK's polling
 * watch service.
 *
 * <p>The active file is always remembered (via {@link #watchFile}) independent of the
 * auto-reload preference, and the actual watch is purely a function of that file plus the
 * preference (see {@link #restartWatch()}). So toggling the preference on/off after a file is
 * open correctly starts/stops watching it.
 *
 * <p>{@link FsWatcher} watches directories, so this watches the file's parent directory and
 * filters events to the one file of interest. Reload events are delivered on the EDT.
 */
public class FileWatcherManager {

    /**
     * After a save we briefly ignore changes so the IDE doesn't reload the file it just wrote.
     * A short time window absorbs the burst of events a single write can produce.
     */
    private static final long SELF_SAVE_IGNORE_WINDOW_MS = 2000;

    private final Consumer<File> fileReloadCallback;
    private final FsWatcher fsWatcher;

    /** The active document's file — remembered regardless of the auto-reload preference. */
    private File currentFile;
    private volatile long ignoreChangesUntil = 0;

    /**
     * Creates a new FileWatcherManager.
     *
     * @param fileReloadCallback Callback (invoked on the EDT) when a file should be reloaded
     */
    public FileWatcherManager(Consumer<File> fileReloadCallback) {
        this.fileReloadCallback = fileReloadCallback;
        this.fsWatcher = new FsWatcher(this::onFsEvent);
    }

    /**
     * Records the file to watch (typically the active document's file) and (re)starts watching
     * it if auto-reload is enabled. Pass {@code null} for an untitled document.
     *
     * @param file The file to watch, or null
     */
    public void watchFile(File file) {
        currentFile = file;
        restartWatch();
    }

    /**
     * Tells the watcher to ignore changes for a short window. Used when saving a file to
     * prevent the IDE from immediately reloading the file it just wrote.
     */
    public void ignoreNextChange() {
        ignoreChangesUntil = System.currentTimeMillis() + SELF_SAVE_IGNORE_WINDOW_MS;
    }

    /**
     * Checks if auto-reload is currently enabled in preferences.
     *
     * @return true if auto-reload is enabled
     */
    public boolean isAutoReloadEnabled() {
        return PreferenceManager.getFileBoolean(PreferenceKeys.FILE_AUTO_RELOAD, false);
    }

    /**
     * Enables or disables auto-reload and (re)starts or stops watching the current file
     * accordingly. Works whether or not a file was already open when toggled.
     *
     * @param enabled true to enable auto-reload
     */
    public void setAutoReloadEnabled(boolean enabled) {
        PreferenceManager.setFileBoolean(PreferenceKeys.FILE_AUTO_RELOAD, enabled);
        restartWatch();
    }

    /**
     * Shuts down the file watcher manager and releases resources.
     */
    public void shutdown() {
        currentFile = null;
        fsWatcher.stop();
    }

    /**
     * (Re)derives the active watch from the current file and the auto-reload preference.
     */
    private void restartWatch() {
        fsWatcher.stop();
        if (!isAutoReloadEnabled() || currentFile == null) {
            return;
        }
        File directory = currentFile.getParentFile();
        if (directory != null) {
            fsWatcher.watch(directory.toPath());
        }
    }

    /**
     * Handles a filesystem event (on the EDT): reloads if it concerns the current file,
     * is a content change (create/modify), and isn't within the self-save ignore window.
     */
    private void onFsEvent(FsWatcher.Event event) {
        if (currentFile == null) {
            return;
        }
        if (event.kind() != FsWatcher.Kind.MODIFY && event.kind() != FsWatcher.Kind.CREATE) {
            return; // structural events don't change the open file's content
        }
        if (!event.path().toFile().equals(currentFile)) {
            return; // a sibling in the same directory; not our file
        }
        if (System.currentTimeMillis() < ignoreChangesUntil) {
            return; // our own save
        }
        fileReloadCallback.accept(currentFile);
    }
}
