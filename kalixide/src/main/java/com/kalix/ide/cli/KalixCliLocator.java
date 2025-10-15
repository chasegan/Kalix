package com.kalix.ide.cli;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Cross-platform utility for locating the kalixcli executable.
 * Handles different installation patterns and path conventions across Windows, Mac, and Linux.
 */
public class KalixCliLocator {
    
    private static final String CLI_NAME_BASE = "kalixcli";
    private static final String CLI_NAME_WINDOWS = "kalixcli.exe";
    
    /**
     * Information about a located kalixcli installation.
     */
    public static class CliLocation {
        private final Path path;
        private final String version;
        private final boolean inPath;
        
        public CliLocation(Path path, String version, boolean inPath) {
            this.path = path;
            this.version = version;
            this.inPath = inPath;
        }
        
        public Path getPath() { return path; }
        public String getVersion() { return version; }
        public boolean isInPath() { return inPath; }
        
        @Override
        public String toString() {
            return String.format("KalixCli[path=%s, version=%s, inPath=%s]", 
                path, version, inPath);
        }
    }
    
    /**
     * Attempts to locate kalixcli using a simplified strategy.
     *
     * If userConfiguredPath is provided:
     *   - ONLY uses that path (no fallback)
     *   - Returns empty if the path is invalid
     *
     * If no userConfiguredPath:
     *   - Uses unqualified "kalixcli" command (relies on system PATH)
     *
     * @param userConfiguredPath Optional user-configured path
     * @return Optional containing CliLocation if found, empty otherwise
     */
    public static Optional<CliLocation> findKalixCli(String userConfiguredPath) {
        // If user specified a path, ONLY use that path (no fallback)
        if (userConfiguredPath != null && !userConfiguredPath.trim().isEmpty()) {
            Path configuredPath = Paths.get(userConfiguredPath.trim());
            if (validateKalixCli(configuredPath)) {
                String version = getVersion(configuredPath);
                return Optional.of(new CliLocation(configuredPath, version, false));
            }
            // User-specified path is invalid - fail (don't fallback)
            return Optional.empty();
        }

        // No user path configured - use unqualified "kalixcli" from system PATH
        return findInPath();
    }
    
    /**
     * Finds kalixcli using application preferences.
     * This method checks user settings and falls back to auto-discovery.
     * 
     * @return Optional containing CliLocation if found, empty otherwise
     */
    public static Optional<CliLocation> findKalixCliWithPreferences() {
        try {
            // Use the file-based preference system instead of OS preferences
            String configuredPath = com.kalix.ide.preferences.PreferenceManager.getFileString(
                com.kalix.ide.preferences.PreferenceKeys.CLI_BINARY_PATH, "");
            return findKalixCli(configuredPath);
        } catch (Exception e) {
            // Fall back to standard discovery if preferences fail
            return findKalixCli(null);
        }
    }
    
    /**
     * Checks if kalixcli is available in the system PATH.
     * Uses unqualified command name and relies on system PATH resolution.
     */
    private static Optional<CliLocation> findInPath() {
        String cliName = isWindows() ? CLI_NAME_WINDOWS : CLI_NAME_BASE;

        try {
            // Try to run the command with --version to check if it exists and get version
            ProcessBuilder pb = new ProcessBuilder(cliName, "--version");
            Process process = pb.start();

            // Wait for completion with timeout
            boolean finished = process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
            if (finished && process.exitValue() == 0) {
                // Read version output
                String version = new String(process.getInputStream().readAllBytes()).trim();

                // Try to find the actual absolute path using 'which' (Unix) or 'where' (Windows)
                Path actualPath = findExecutablePath(cliName);
                if (actualPath != null) {
                    return Optional.of(new CliLocation(actualPath, version, true));
                }

                // If we can't determine absolute path, don't return a location
                // (avoids creating invalid Paths.get("kalixcli") that won't work)
                return Optional.empty();
            }
        } catch (Exception e) {
            // Command not found or failed to execute
        }

        return Optional.empty();
    }
    
    /**
     * Finds the full path of an executable using system commands.
     */
    private static Path findExecutablePath(String executableName) {
        try {
            String command = isWindows() ? "where" : "which";
            ProcessBuilder pb = new ProcessBuilder(command, executableName);
            Process process = pb.start();
            
            boolean finished = process.waitFor(3, java.util.concurrent.TimeUnit.SECONDS);
            if (finished && process.exitValue() == 0) {
                String output = new String(process.getInputStream().readAllBytes()).trim();
                String firstLine = output.split("\\r?\\n")[0]; // Take first result
                Path path = Paths.get(firstLine);
                if (Files.exists(path) && Files.isExecutable(path)) {
                    return path;
                }
            }
        } catch (Exception e) {
            // Ignore and return null
        }
        return null;
    }
    
    /**
     * Gets the version of a kalixcli executable.
     */
    private static String getVersion(Path cliPath) {
        try {
            ProcessBuilder pb = new ProcessBuilder(cliPath.toString(), "--version");
            Process process = pb.start();
            
            boolean finished = process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
            if (finished && process.exitValue() == 0) {
                return new String(process.getInputStream().readAllBytes()).trim();
            }
        } catch (Exception e) {
            // Ignore and return unknown
        }
        
        return "unknown";
    }
    
    /**
     * Validates that a given path points to a working kalixcli executable.
     */
    public static boolean validateKalixCli(Path cliPath) {
        if (!Files.exists(cliPath) || !Files.isExecutable(cliPath)) {
            return false;
        }
        
        try {
            ProcessBuilder pb = new ProcessBuilder(cliPath.toString(), "--help");
            Process process = pb.start();
            
            boolean finished = process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
            return finished && process.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }
    
    /**
     * Checks if the current OS is Windows.
     */
    private static boolean isWindows() {
        return System.getProperty("os.name").toLowerCase().contains("win");
    }
    
    /**
     * Checks if the current OS is macOS.
     */
    private static boolean isMac() {
        return System.getProperty("os.name").toLowerCase().contains("mac");
    }

    /**
     * Searches for all available kalixcli installations (for selection dialogs).
     * Now simplified to only check the system PATH.
     */
    public static List<CliLocation> findAllInstallations() {
        List<CliLocation> installations = new ArrayList<>();

        // Only check PATH - consistent with simplified discovery strategy
        findInPath().ifPresent(installations::add);

        return installations;
    }
}