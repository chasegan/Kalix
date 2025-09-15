package com.kalix.gui.managers;

import com.kalix.gui.preferences.PreferenceKeys;
import com.kalix.gui.preferences.PreferenceManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Manages file watching functionality for automatic reloading of clean model files.
 * Watches the currently loaded file for external changes and triggers reload when appropriate.
 */
public class FileWatcherManager {

    private static final Logger logger = LoggerFactory.getLogger(FileWatcherManager.class);

    private final Consumer<File> fileReloadCallback;
    private final ExecutorService watchService;

    private WatchService watchService_;
    private File currentWatchedFile;
    private Path currentWatchedDirectory;
    private boolean isWatching = false;
    private volatile boolean shouldStop = false;

    /**
     * Creates a new FileWatcherManager.
     *
     * @param fileReloadCallback Callback to trigger when a file should be reloaded
     */
    public FileWatcherManager(Consumer<File> fileReloadCallback) {
        this.fileReloadCallback = fileReloadCallback;
        this.watchService = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "FileWatcher");
            t.setDaemon(true);
            return t;
        });

        try {
            this.watchService_ = FileSystems.getDefault().newWatchService();
        } catch (IOException e) {
            logger.error("Failed to create file watch service", e);
        }
    }

    /**
     * Starts watching the specified file for changes.
     * Only watches if auto-reload preference is enabled and file is not null.
     *
     * @param file The file to watch, or null to stop watching
     */
    public void watchFile(File file) {
        // Stop any existing watch
        stopWatching();

        // Don't start watching if disabled or no file
        if (!isAutoReloadEnabled() || file == null || watchService_ == null) {
            return;
        }

        try {
            Path filePath = file.toPath();
            Path directory = filePath.getParent();

            if (directory == null) {
                logger.warn("Cannot watch file without parent directory: {}", file);
                return;
            }

            // Register directory for watching
            directory.register(watchService_, StandardWatchEventKinds.ENTRY_MODIFY);

            currentWatchedFile = file;
            currentWatchedDirectory = directory;
            isWatching = true;
            shouldStop = false;

            // Start watching in background thread
            watchService.submit(this::watchLoop);

            logger.debug("Started watching file: {}", file.getAbsolutePath());

        } catch (IOException e) {
            logger.error("Failed to start watching file: {}", file, e);
        }
    }

    /**
     * Stops watching the current file.
     */
    public void stopWatching() {
        shouldStop = true;
        isWatching = false;
        currentWatchedFile = null;
        currentWatchedDirectory = null;

        logger.debug("Stopped file watching");
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
        } else if (currentWatchedFile != null) {
            // Restart watching with current file
            File fileToWatch = currentWatchedFile;
            watchFile(fileToWatch);
        }

        logger.debug("Auto-reload preference changed to: {}", enabled);
    }

    /**
     * Main watch loop that monitors for file changes.
     */
    private void watchLoop() {
        while (!shouldStop && isWatching) {
            try {
                WatchKey key = watchService_.take(); // Blocks until an event occurs

                if (shouldStop) {
                    break;
                }

                for (WatchEvent<?> event : key.pollEvents()) {
                    WatchEvent.Kind<?> kind = event.kind();

                    if (kind == StandardWatchEventKinds.OVERFLOW) {
                        continue;
                    }

                    if (kind == StandardWatchEventKinds.ENTRY_MODIFY) {
                        Path modifiedFile = currentWatchedDirectory.resolve((Path) event.context());

                        // Check if this is our watched file
                        if (currentWatchedFile != null &&
                            modifiedFile.equals(currentWatchedFile.toPath())) {

                            logger.debug("Detected change in watched file: {}", currentWatchedFile);

                            // Trigger reload on EDT
                            javax.swing.SwingUtilities.invokeLater(() -> {
                                if (currentWatchedFile != null && isWatching) {
                                    fileReloadCallback.accept(currentWatchedFile);
                                }
                            });
                        }
                    }
                }

                // Reset the key
                boolean valid = key.reset();
                if (!valid) {
                    logger.warn("Watch key no longer valid, stopping file watch");
                    break;
                }

            } catch (InterruptedException e) {
                logger.debug("File watch interrupted");
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                logger.error("Error in file watch loop", e);
                break;
            }
        }
    }

    /**
     * Shuts down the file watcher manager and releases resources.
     */
    public void shutdown() {
        stopWatching();

        if (watchService_ != null) {
            try {
                watchService_.close();
            } catch (IOException e) {
                logger.error("Error closing watch service", e);
            }
        }

        watchService.shutdown();
        logger.debug("FileWatcherManager shut down");
    }
}