package com.kalix.ide.utils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Desktop;
import java.io.File;
import java.io.IOException;

/**
 * Cross-platform utility for opening file manager/explorer windows.
 * Handles platform-specific differences for launching file managers at specific directories.
 */
public class FileManagerLauncher {

    private static final Logger logger = LoggerFactory.getLogger(FileManagerLauncher.class);

    /**
     * Opens a file manager window at the specified directory.
     * Falls back to user home directory if the specified directory is null or doesn't exist.
     *
     * @param directory The directory to open in the file manager, or null for user home
     * @throws IOException if the file manager cannot be launched
     */
    public static void openFileManagerAt(File directory) throws IOException {
        // Determine the target directory
        File targetDir = getValidDirectory(directory);

        // Try using Desktop API first (cross-platform)
        if (Desktop.isDesktopSupported()) {
            Desktop desktop = Desktop.getDesktop();
            if (desktop.isSupported(Desktop.Action.OPEN)) {
                try {
                    desktop.open(targetDir);
                    return;
                } catch (IOException e) {
                    logger.warn("Desktop.open() failed, trying platform-specific approach", e);
                }
            }
        }

        // Fall back to platform-specific commands
        String osName = System.getProperty("os.name").toLowerCase();

        try {
            if (osName.contains("mac")) {
                openMacFinder(targetDir);
            } else if (osName.contains("win")) {
                openWindowsExplorer(targetDir);
            } else {
                // Assume Linux/Unix
                openLinuxFileManager(targetDir);
            }

        } catch (IOException e) {
            logger.error("Failed to open file manager at directory: {}", targetDir.getAbsolutePath(), e);
            throw new IOException("Failed to open file manager: " + e.getMessage(), e);
        }
    }

    /**
     * Opens the file manager with the given file selected ("reveal"), rather than merely opening
     * its containing folder. Mirrors the behaviour of VS Code's "Reveal in Finder/Explorer".
     *
     * <p>Uses the proven platform reveal commands first — {@code open -R} (macOS),
     * {@code explorer /select,} (Windows), and the freedesktop {@code FileManager1.ShowItems}
     * D-Bus call (Linux) — then the cross-platform {@link Desktop#browseFileDirectory(File)} where
     * available, and finally falls back to opening the containing folder if selection isn't
     * possible.</p>
     *
     * @param file the file to reveal and select
     * @throws IOException if neither selection nor the folder fallback can be launched
     */
    public static void revealFile(File file) throws IOException {
        if (file == null || !file.exists()) {
            // Nothing to select; open the closest existing folder instead.
            openFileManagerAt(file == null ? null : file.getParentFile());
            return;
        }

        String osName = System.getProperty("os.name").toLowerCase();
        try {
            if (osName.contains("mac")) {
                new ProcessBuilder("open", "-R", file.getAbsolutePath()).start();
                return;
            }
            if (osName.contains("win")) {
                // explorer exits non-zero even on success; we only need to launch it.
                new ProcessBuilder("explorer", "/select," + file.getAbsolutePath()).start();
                return;
            }
            if (revealLinux(file)) {
                return;
            }
        } catch (IOException e) {
            logger.warn("Reveal command failed; trying Desktop API / folder fallback", e);
        }

        // Cross-platform fallback: select via the Desktop API if the platform supports it.
        if (Desktop.isDesktopSupported()) {
            Desktop desktop = Desktop.getDesktop();
            if (desktop.isSupported(Desktop.Action.BROWSE_FILE_DIR)) {
                try {
                    desktop.browseFileDirectory(file);
                    return;
                } catch (UnsupportedOperationException | IllegalArgumentException e) {
                    logger.warn("Desktop.browseFileDirectory() failed, opening containing folder", e);
                }
            }
        }

        // Last resort: open the containing folder so the user at least gets close.
        openFileManagerAt(file.getParentFile());
    }

    /**
     * Attempts to select {@code file} in the user's Linux file manager via the freedesktop
     * {@code org.freedesktop.FileManager1.ShowItems} D-Bus method (honoured by Nautilus, Dolphin,
     * Nemo, Caja, and others). Returns {@code false} if the call could not be launched, so the
     * caller can fall back to opening the containing folder.
     */
    private static boolean revealLinux(File file) {
        try {
            new ProcessBuilder(
                "dbus-send", "--session", "--print-reply",
                "--dest=org.freedesktop.FileManager1",
                "/org/freedesktop/FileManager1",
                "org.freedesktop.FileManager1.ShowItems",
                "array:string:" + file.toURI().toString(),
                "string:"
            ).start();
            return true;
        } catch (IOException e) {
            logger.warn("Linux D-Bus reveal failed", e);
            return false;
        }
    }

    /**
     * Validates and returns a directory that exists.
     * Falls back to user home if the provided directory is invalid.
     *
     * @param directory The directory to validate
     * @return A valid directory (never null)
     */
    private static File getValidDirectory(File directory) {
        // If directory is null, use user home
        if (directory == null) {
            return new File(System.getProperty("user.home"));
        }

        // If directory doesn't exist or isn't a directory, use its parent
        if (!directory.exists() || !directory.isDirectory()) {
            File parent = directory.getParentFile();
            if (parent != null && parent.exists() && parent.isDirectory()) {
                return parent;
            } else {
                return new File(System.getProperty("user.home"));
            }
        }

        return directory;
    }

    /**
     * Opens Finder on macOS.
     */
    private static void openMacFinder(File directory) throws IOException {
        ProcessBuilder pb = new ProcessBuilder("open", directory.getAbsolutePath());
        pb.start();
    }

    /**
     * Opens Windows Explorer.
     */
    private static void openWindowsExplorer(File directory) throws IOException {
        ProcessBuilder pb = new ProcessBuilder("explorer", directory.getAbsolutePath());
        pb.start();
    }

    /**
     * Opens the default file manager on Linux.
     * Tries common file managers in order of preference.
     */
    private static void openLinuxFileManager(File directory) throws IOException {
        // List of common Linux file managers to try
        String[] fileManagers = {
            "xdg-open",      // Standard freedesktop.org tool (preferred)
            "nautilus",      // GNOME
            "dolphin",       // KDE
            "thunar",        // XFCE
            "pcmanfm",       // LXDE
            "nemo",          // Cinnamon
            "caja"           // MATE
        };

        IOException lastException = null;

        for (String fileManager : fileManagers) {
            try {
                ProcessBuilder pb = new ProcessBuilder(fileManager, directory.getAbsolutePath());
                pb.start();
                return; // Success
            } catch (IOException e) {
                lastException = e;
                // Try next file manager
            }
        }

        // If we get here, all file managers failed
        throw new IOException("Could not find a suitable file manager", lastException);
    }
}
