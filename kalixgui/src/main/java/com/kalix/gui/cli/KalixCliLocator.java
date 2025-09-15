package com.kalix.gui.cli;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.prefs.Preferences;

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
     * Attempts to locate kalixcli using various strategies.
     * 
     * @return Optional containing CliLocation if found, empty otherwise
     */
    public static Optional<CliLocation> findKalixCli() {
        return findKalixCli(null);
    }
    
    /**
     * Attempts to locate kalixcli using various strategies, with optional user-configured path.
     * 
     * @param userConfiguredPath Optional user-configured path to check first
     * @return Optional containing CliLocation if found, empty otherwise
     */
    public static Optional<CliLocation> findKalixCli(String userConfiguredPath) {
        // Strategy 0: Check user-configured path first
        if (userConfiguredPath != null && !userConfiguredPath.trim().isEmpty()) {
            Path configuredPath = Paths.get(userConfiguredPath.trim());
            if (validateKalixCli(configuredPath)) {
                String version = getVersion(configuredPath);
                return Optional.of(new CliLocation(configuredPath, version, false));
            }
        }
        
        // Strategy 1: Check if it's in PATH
        Optional<CliLocation> pathLocation = findInPath();
        if (pathLocation.isPresent()) {
            return pathLocation;
        }
        
        // Strategy 2: Check common installation directories
        Optional<CliLocation> commonLocation = findInCommonLocations();
        if (commonLocation.isPresent()) {
            return commonLocation;
        }
        
        // Strategy 3: Check relative to GUI application (development scenario)
        Optional<CliLocation> relativeLocation = findRelativeToApplication();
        if (relativeLocation.isPresent()) {
            return relativeLocation;
        }
        
        return Optional.empty();
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
            String configuredPath = com.kalix.gui.preferences.PreferenceManager.getFileString(
                com.kalix.gui.preferences.PreferenceKeys.CLI_BINARY_PATH, "");
            return findKalixCli(configuredPath);
        } catch (Exception e) {
            // Fall back to standard discovery if preferences fail
            return findKalixCli();
        }
    }
    
    /**
     * Checks if kalixcli is available in the system PATH.
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
                
                // Try to find the actual path using 'which' (Unix) or 'where' (Windows)
                Path actualPath = findExecutablePath(cliName);
                if (actualPath != null) {
                    return Optional.of(new CliLocation(actualPath, version, true));
                } else {
                    // Fallback: just use the command name
                    return Optional.of(new CliLocation(Paths.get(cliName), version, true));
                }
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
     * Checks common installation directories for kalixcli.
     */
    private static Optional<CliLocation> findInCommonLocations() {
        List<Path> searchPaths = getCommonInstallationPaths();
        
        for (Path searchPath : searchPaths) {
            Optional<CliLocation> location = checkDirectory(searchPath, false);
            if (location.isPresent()) {
                return location;
            }
        }
        
        return Optional.empty();
    }
    
    /**
     * Gets a list of common installation paths based on the operating system.
     */
    private static List<Path> getCommonInstallationPaths() {
        List<Path> paths = new ArrayList<>();
        String userHome = System.getProperty("user.home");
        
        if (isWindows()) {
            // Windows common paths
            paths.add(Paths.get("C:\\Program Files\\Kalix"));
            paths.add(Paths.get("C:\\Program Files (x86)\\Kalix"));
            paths.add(Paths.get(userHome, "AppData", "Local", "Kalix"));
            paths.add(Paths.get(userHome, "AppData", "Roaming", "Kalix"));
            
            // Scoop installation
            paths.add(Paths.get(userHome, "scoop", "apps", "kalixcli", "current"));
            
            // Chocolatey installation
            paths.add(Paths.get("C:\\ProgramData\\chocolatey\\lib\\kalixcli\\tools"));
            
        } else if (isMac()) {
            // macOS common paths
            paths.add(Paths.get("/usr/local/bin"));
            paths.add(Paths.get("/opt/homebrew/bin"));
            paths.add(Paths.get("/Applications/Kalix.app/Contents/MacOS"));
            paths.add(Paths.get(userHome, ".local", "bin"));
            paths.add(Paths.get(userHome, "Applications", "Kalix.app", "Contents", "MacOS"));
            
            // Homebrew installations
            paths.add(Paths.get("/usr/local/Cellar/kalixcli"));
            paths.add(Paths.get("/opt/homebrew/Cellar/kalixcli"));
            
        } else {
            // Linux common paths
            paths.add(Paths.get("/usr/bin"));
            paths.add(Paths.get("/usr/local/bin"));
            paths.add(Paths.get("/opt/kalix/bin"));
            paths.add(Paths.get(userHome, ".local", "bin"));
            paths.add(Paths.get(userHome, "bin"));
            
            // Snap installation
            paths.add(Paths.get("/snap/bin"));
            
            // Flatpak installation
            paths.add(Paths.get("/var/lib/flatpak/exports/bin"));
            paths.add(Paths.get(userHome, ".local", "share", "flatpak", "exports", "bin"));
        }
        
        return paths;
    }
    
    /**
     * Checks for kalixcli relative to the GUI application (development scenario).
     */
    private static Optional<CliLocation> findRelativeToApplication() {
        try {
            // Get the directory where the GUI is running from
            Path currentDir = Paths.get(System.getProperty("user.dir"));
            
            // Check various relative paths
            List<Path> relativePaths = List.of(
                currentDir.resolve("kalixcli"),                    // Same directory
                currentDir.resolve("../kalixcli"),                 // Parent directory
                currentDir.resolve("cli"),                         // cli subdirectory
                currentDir.resolve("target/release"),              // Rust target directory
                currentDir.resolve("../target/release"),           // Parent's target directory
                currentDir.resolve("build/release"),               // Alternative build directory
                currentDir.resolve("../build/release")             // Parent's build directory
            );
            
            for (Path path : relativePaths) {
                Optional<CliLocation> location = checkDirectory(path, false);
                if (location.isPresent()) {
                    return location;
                }
            }
        } catch (Exception e) {
            // Ignore and continue
        }
        
        return Optional.empty();
    }
    
    /**
     * Checks a directory for kalixcli executable.
     */
    private static Optional<CliLocation> checkDirectory(Path directory, boolean inPath) {
        if (!Files.exists(directory) || !Files.isDirectory(directory)) {
            return Optional.empty();
        }
        
        String cliName = isWindows() ? CLI_NAME_WINDOWS : CLI_NAME_BASE;
        Path cliPath = directory.resolve(cliName);
        
        if (Files.exists(cliPath) && Files.isExecutable(cliPath)) {
            String version = getVersion(cliPath);
            return Optional.of(new CliLocation(cliPath, version, inPath));
        }
        
        return Optional.empty();
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
     * Gets the appropriate executable name for the current platform.
     */
    public static String getExecutableName() {
        return isWindows() ? CLI_NAME_WINDOWS : CLI_NAME_BASE;
    }
    
    /**
     * Searches for all available kalixcli installations (for selection dialogs).
     */
    public static List<CliLocation> findAllInstallations() {
        List<CliLocation> installations = new ArrayList<>();
        
        // Check PATH first
        findInPath().ifPresent(installations::add);
        
        // Check all common locations
        List<Path> searchPaths = getCommonInstallationPaths();
        for (Path searchPath : searchPaths) {
            checkDirectory(searchPath, false).ifPresent(location -> {
                // Avoid duplicates
                if (installations.stream().noneMatch(existing -> 
                        existing.getPath().equals(location.getPath()))) {
                    installations.add(location);
                }
            });
        }
        
        // Check relative paths
        findRelativeToApplication().ifPresent(location -> {
            if (installations.stream().noneMatch(existing -> 
                    existing.getPath().equals(location.getPath()))) {
                installations.add(location);
            }
        });
        
        return installations;
    }
}