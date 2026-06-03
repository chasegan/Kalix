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

    private File watchedFile;
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
     * Starts watching the specified file for changes. Only watches if auto-reload is enabled
     * and the file (and its parent directory) are non-null.
     *
     * @param file The file to watch, or null to stop watching
     */
    public void watchFile(File file) {
        stopWatching();

        if (!isAutoReloadEnabled() || file == null) {
            return;
        }
        File directory = file.getParentFile();
        if (directory == null) {
            return;
        }
        watchedFile = file;
        fsWatcher.watch(directory.toPath());
    }

    /**
     * Stops watching the current file.
     */
    public void stopWatching() {
        watchedFile = null;
        fsWatcher.stop();
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
     * Enables or disables auto-reload preference and updates file watching accordingly.
     *
     * @param enabled true to enable auto-reload
     */
    public void setAutoReloadEnabled(boolean enabled) {
        PreferenceManager.setFileBoolean(PreferenceKeys.FILE_AUTO_RELOAD, enabled);

        if (!enabled) {
            stopWatching();
        } else if (watchedFile != null) {
            watchFile(watchedFile);
        }
    }

    /**
     * Shuts down the file watcher manager and releases resources.
     */
    public void shutdown() {
        stopWatching();
    }

    /**
     * Handles a filesystem event (on the EDT): reloads if it concerns the watched file,
     * is a content change (create/modify), and isn't within the self-save ignore window.
     */
    private void onFsEvent(FsWatcher.Event event) {
        if (watchedFile == null) {
            return;
        }
        if (event.kind() != FsWatcher.Kind.MODIFY && event.kind() != FsWatcher.Kind.CREATE) {
            return; // structural events don't change the open file's content
        }
        if (!event.path().toFile().equals(watchedFile)) {
            return; // a sibling in the same directory; not our file
        }
        if (System.currentTimeMillis() < ignoreChangesUntil) {
            return; // our own save
        }
        fileReloadCallback.accept(watchedFile);
    }
}
