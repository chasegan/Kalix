package com.kalix.ide.managers;

import com.kalix.ide.constants.AppConstants;

import javax.swing.*;
import java.awt.*;
import java.io.File;

/**
 * Manages dynamic title bar updates for the main application window.
 * Handles file path display with intelligent truncation and dirty state indicators.
 * Provides responsive updates when window is resized.
 */
public class TitleBarManager {
    
    private final JFrame window;
    private static final double TITLE_BAR_WIDTH_RATIO = 0.7; // Use 70% of title bar width
    
    /**
     * Creates a new TitleBarManager for the specified window.
     * 
     * @param window The main application window whose title bar will be managed
     */
    public TitleBarManager(JFrame window) {
        this.window = window;
    }
    
    /**
     * Updates the window title with current file and dirty state.
     * Shows "Kalix" when no file is loaded, "Kalix - .../path/to/file.ini **" when file is dirty.
     *
     * @param isDirty true if the file has unsaved changes
     * @param currentFile the currently loaded file, or null if none
     */
    public void updateTitle(boolean isDirty, File currentFile) {
        String baseTitle = AppConstants.APP_TITLE;
        String title = baseTitle;

        // Add file path if a file is loaded
        if (currentFile != null) {
            String filePath = currentFile.getAbsolutePath();
            String truncatedPath = truncatePathForTitle(filePath, baseTitle, isDirty);
            title = baseTitle + " - " + truncatedPath;

            // Add dirty indicator after filename
            if (isDirty) {
                title += " **";
            }
        }

        window.setTitle(title);
    }
    
    /**
     * Updates the window title using only dirty state (gets current file from callback).
     * 
     * @param isDirty true if the file has unsaved changes
     * @param currentFileSupplier function that provides the current file
     */
    public void updateTitle(boolean isDirty, java.util.function.Supplier<File> currentFileSupplier) {
        updateTitle(isDirty, currentFileSupplier.get());
    }
    
    /**
     * Truncates a file path to fit within the title bar width constraints.
     * Uses progressive truncation, removing directories one at a time from the left.
     *
     * @param fullPath the complete file path
     * @param baseTitle the base title (e.g., "Kalix")
     * @param isDirty whether the file is dirty (affects space calculation)
     * @return truncated path that fits within title bar constraints
     */
    private String truncatePathForTitle(String fullPath, String baseTitle, boolean isDirty) {
        // Calculate available width
        FontMetrics fm = window.getFontMetrics(window.getFont());
        int titleBarWidth = window.getWidth();
        int maxWidth = (int) (titleBarWidth * TITLE_BAR_WIDTH_RATIO);

        // Account for base title, separator, and potential dirty indicator
        String baseWithSeparator = baseTitle + " - ";
        String dirtyIndicator = isDirty ? " **" : "";
        int baseWidth = fm.stringWidth(baseWithSeparator);
        int dirtyWidth = fm.stringWidth(dirtyIndicator);
        int availablePathWidth = maxWidth - baseWidth - dirtyWidth;
        
        // If title bar width is not yet available (during initialization), use a reasonable default
        if (titleBarWidth <= 0) {
            availablePathWidth = isDirty ? 380 : 400; // Account for dirty indicator space
        }
        
        // If the full path fits, use it
        if (fm.stringWidth(fullPath) <= availablePathWidth) {
            return fullPath;
        }
        
        // Split the path into components
        String[] pathParts = fullPath.split(java.util.regex.Pattern.quote(File.separator));
        
        // Progressive truncation: start by removing directories from the left, one at a time
        for (int dirsToRemove = 1; dirsToRemove < pathParts.length; dirsToRemove++) {
            StringBuilder truncatedPath = new StringBuilder();
            truncatedPath.append("...");
            
            // Add the remaining path parts (keeping the last N directories + filename)
            for (int i = dirsToRemove; i < pathParts.length; i++) {
                truncatedPath.append(File.separator);
                truncatedPath.append(pathParts[i]);
            }
            
            String candidate = truncatedPath.toString();
            if (fm.stringWidth(candidate) <= availablePathWidth) {
                return candidate;
            }
        }
        
        // If even filename alone with ".../" prefix doesn't fit, try just the filename
        String filename = pathParts[pathParts.length - 1];
        if (fm.stringWidth(filename) <= availablePathWidth) {
            return filename;
        }
        
        // If filename is too long, truncate it progressively from the end
        String ellipsis = "...";
        int maxFilenameWidth = availablePathWidth - fm.stringWidth(ellipsis);
        
        if (maxFilenameWidth > 0) {
            for (int i = filename.length() - 1; i > 0; i--) {
                String truncatedFilename = filename.substring(0, i) + ellipsis;
                if (fm.stringWidth(truncatedFilename) <= availablePathWidth) {
                    return truncatedFilename;
                }
            }
        }
        
        // Fallback: just show "..."
        return "...";
    }
}