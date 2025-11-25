package com.kalix.ide.managers;

import com.kalix.ide.builders.MenuBarBuilder;
import com.kalix.ide.constants.AppConstants;

import javax.swing.JOptionPane;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.prefs.Preferences;
import java.util.function.Consumer;

/**
 * Manages recent files functionality including loading, saving, and updating the recent files menu.
 * Maintains a list of recently opened files and provides menu management capabilities.
 */
public class RecentFilesManager {

    private final Preferences prefs;
    private final List<String> recentFiles;
    private final Consumer<String> fileOpenCallback;
    private final Runnable statusUpdateCallback;
    private MenuBarBuilder menuBarBuilder;

    /**
     * Creates a new RecentFilesManager instance.
     *
     * @param prefs The preferences object for storing recent files
     * @param fileOpenCallback Callback function to open a file
     * @param statusUpdateCallback Callback to update status when files are cleared
     */
    public RecentFilesManager(Preferences prefs, Consumer<String> fileOpenCallback, Runnable statusUpdateCallback) {
        this.prefs = prefs;
        this.recentFiles = new ArrayList<>();
        this.fileOpenCallback = fileOpenCallback;
        this.statusUpdateCallback = statusUpdateCallback;
        loadRecentFiles();
    }
    
    /**
     * Loads recent files from preferences.
     */
    private void loadRecentFiles() {
        recentFiles.clear();
        for (int i = 0; i < AppConstants.MAX_RECENT_FILES; i++) {
            String filePath = prefs.get(AppConstants.RECENT_FILE_PREF_PREFIX + i, null);
            if (filePath != null && !filePath.isEmpty()) {
                recentFiles.add(filePath);
            }
        }
    }
    
    /**
     * Saves current recent files to preferences.
     */
    private void saveRecentFiles() {
        // Clear all existing recent file preferences
        for (int i = 0; i < AppConstants.MAX_RECENT_FILES; i++) {
            prefs.remove(AppConstants.RECENT_FILE_PREF_PREFIX + i);
        }
        
        // Save current recent files
        for (int i = 0; i < recentFiles.size(); i++) {
            prefs.put(AppConstants.RECENT_FILE_PREF_PREFIX + i, recentFiles.get(i));
        }
    }
    
    /**
     * Adds a file to the recent files list and updates the menu immediately.
     *
     * @param filePath The absolute path of the file to add
     */
    public void addRecentFile(String filePath) {
        // Remove if already exists to avoid duplicates
        recentFiles.remove(filePath);

        // Add to front of list
        recentFiles.add(0, filePath);

        // Limit size to maximum allowed
        while (recentFiles.size() > AppConstants.MAX_RECENT_FILES) {
            recentFiles.remove(recentFiles.size() - 1);
        }

        saveRecentFiles();

        // Update menu immediately
        rebuildMenu();
    }
    
    /**
     * Clears all recent files and updates the menu immediately.
     */
    public void clearRecentFiles() {
        recentFiles.clear();
        saveRecentFiles();
        rebuildMenu();
        statusUpdateCallback.run();
    }
    
    /**
     * Sets the MenuBarBuilder reference for menu updates.
     *
     * @param menuBarBuilder The menu bar builder to use for updates
     */
    public void setMenuBarBuilder(MenuBarBuilder menuBarBuilder) {
        this.menuBarBuilder = menuBarBuilder;
        rebuildMenu();
    }

    /**
     * Rebuilds the recent files section in the File menu.
     */
    private void rebuildMenu() {
        if (menuBarBuilder == null) {
            return;
        }
        menuBarBuilder.rebuildRecentFilesSection(
            Collections.unmodifiableList(recentFiles),
            this::openRecentFile
        );
    }
    
    /**
     * Attempts to open a recent file.
     *
     * @param filePath The path of the file to open
     */
    private void openRecentFile(String filePath) {
        File file = new File(filePath);
        if (file.exists()) {
            fileOpenCallback.accept(filePath);
        } else {
            // File no longer exists, remove from recent files
            recentFiles.remove(filePath);
            saveRecentFiles();
            rebuildMenu();

            // Show error dialog
            JOptionPane.showMessageDialog(
                null,
                AppConstants.ERROR_FILE_NOT_EXISTS + filePath,
                AppConstants.ERROR_FILE_NOT_FOUND_TITLE,
                JOptionPane.WARNING_MESSAGE
            );
        }
    }
}