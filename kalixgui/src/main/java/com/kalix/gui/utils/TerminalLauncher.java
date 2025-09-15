package com.kalix.gui.utils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Cross-platform utility for opening terminal/command prompt windows.
 * Handles platform-specific differences for launching terminals at specific directories.
 */
public class TerminalLauncher {

    private static final Logger logger = LoggerFactory.getLogger(TerminalLauncher.class);

    /**
     * Opens a terminal window at the specified directory.
     * Falls back to user home directory if the specified directory is null or doesn't exist.
     *
     * @param directory The directory to open the terminal in, or null for user home
     * @throws IOException if the terminal cannot be launched
     */
    public static void openTerminalAt(File directory) throws IOException {
        // Determine the target directory
        File targetDir = getValidDirectory(directory);

        logger.debug("Opening terminal at directory: {}", targetDir.getAbsolutePath());

        // Get the operating system
        String osName = System.getProperty("os.name").toLowerCase();

        try {
            if (osName.contains("mac")) {
                openMacTerminal(targetDir);
            } else if (osName.contains("win")) {
                openWindowsTerminal(targetDir);
            } else {
                // Assume Linux/Unix
                openLinuxTerminal(targetDir);
            }

            logger.info("Successfully opened terminal at: {}", targetDir.getAbsolutePath());

        } catch (IOException e) {
            logger.error("Failed to open terminal at directory: {}", targetDir.getAbsolutePath(), e);
            throw new IOException("Failed to open terminal: " + e.getMessage(), e);
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
     * Opens Terminal.app on macOS.
     */
    private static void openMacTerminal(File directory) throws IOException {
        // Use AppleScript to open Terminal at specific directory, clear screen, and bring it to foreground
        List<String> command = new ArrayList<>();
        command.add("osascript");
        command.add("-e");
        command.add("tell application \"Terminal\"");
        command.add("-e");
        command.add("do script \"cd '" + directory.getAbsolutePath().replace("'", "\\'") + "' && clear\"");
        command.add("-e");
        command.add("activate");
        command.add("-e");
        command.add("end tell");

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.start();
    }

    /**
     * Opens PowerShell on Windows.
     * Falls back to Command Prompt if PowerShell is not available.
     */
    private static void openWindowsTerminal(File directory) throws IOException {
        List<String> command = new ArrayList<>();

        // Try Windows Terminal first (modern Windows 10/11)
        if (isWindowsTerminalAvailable()) {
            command.add("wt");
            command.add("-d");
            command.add(directory.getAbsolutePath());
            command.add("--focus");  // Bring to foreground
            command.add("powershell");
            command.add("-NoExit");
            command.add("-Command");
            command.add("Clear-Host");  // Clear screen after opening
        } else {
            // Fall back to PowerShell - simpler approach without complex activation
            command.add("powershell");
            command.add("-Command");
            command.add("Start-Process");
            command.add("powershell");
            command.add("-ArgumentList");
            command.add("'-NoExit', '-Command', 'Set-Location '''" + directory.getAbsolutePath() + "'''; Clear-Host'");
            command.add("-WindowStyle");
            command.add("Normal");
        }

        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.start();
        } catch (IOException e) {
            // Final fallback to cmd.exe with activation
            logger.warn("PowerShell/Windows Terminal failed, falling back to cmd.exe");
            List<String> cmdCommand = new ArrayList<>();
            cmdCommand.add("cmd");
            cmdCommand.add("/c");
            cmdCommand.add("start");
            cmdCommand.add("/max");  // Start maximized to ensure visibility
            cmdCommand.add("cmd");
            cmdCommand.add("/k");
            cmdCommand.add("cd /d \"" + directory.getAbsolutePath() + "\" && cls");  // Clear screen after cd

            ProcessBuilder pb = new ProcessBuilder(cmdCommand);
            pb.start();
        }
    }

    /**
     * Opens a terminal on Linux/Unix systems.
     * Tries several common terminal emulators.
     */
    private static void openLinuxTerminal(File directory) throws IOException {
        // List of common terminal emulators to try, in order of preference
        String[] terminals = {
            "gnome-terminal",    // GNOME
            "konsole",           // KDE
            "xfce4-terminal",    // XFCE
            "mate-terminal",     // MATE
            "lxterminal",        // LXDE
            "terminator",        // Popular terminal emulator
            "xterm"              // Fallback - should be available on most systems
        };

        IOException lastException = null;

        for (String terminal : terminals) {
            try {
                if (isCommandAvailable(terminal)) {
                    List<String> command = new ArrayList<>();
                    command.add(terminal);

                    // Add directory argument and clear command based on terminal type
                    if (terminal.equals("gnome-terminal") || terminal.equals("mate-terminal")) {
                        command.add("--working-directory=" + directory.getAbsolutePath());
                        command.add("--");
                        command.add("bash");
                        command.add("-c");
                        command.add("clear; exec bash");
                    } else if (terminal.equals("konsole")) {
                        command.add("--workdir");
                        command.add(directory.getAbsolutePath());
                        command.add("-e");
                        command.add("bash");
                        command.add("-c");
                        command.add("clear; exec bash");
                    } else if (terminal.equals("xfce4-terminal") || terminal.equals("lxterminal")) {
                        command.add("--working-directory=" + directory.getAbsolutePath());
                        command.add("--command=bash -c 'clear; exec bash'");
                    } else if (terminal.equals("terminator")) {
                        command.add("--working-directory=" + directory.getAbsolutePath());
                        command.add("--command=bash -c 'clear; exec bash'");
                    } else {
                        // For xterm and others, we'll change directory and clear after opening
                        List<String> xtermCommand = new ArrayList<>();
                        xtermCommand.add(terminal);
                        xtermCommand.add("-e");
                        xtermCommand.add("bash");
                        xtermCommand.add("-c");
                        xtermCommand.add("cd '" + directory.getAbsolutePath() + "' && clear && exec bash");

                        ProcessBuilder pb = new ProcessBuilder(xtermCommand);
                        pb.start();
                        return;
                    }

                    ProcessBuilder pb = new ProcessBuilder(command);
                    pb.start();
                    return; // Success!

                } else {
                    logger.debug("Terminal '{}' not available", terminal);
                }
            } catch (IOException e) {
                logger.debug("Failed to launch terminal '{}': {}", terminal, e.getMessage());
                lastException = e;
            }
        }

        // If we get here, none of the terminals worked
        throw new IOException("No suitable terminal emulator found. Tried: " +
                            String.join(", ", terminals), lastException);
    }

    /**
     * Checks if Windows Terminal (wt.exe) is available.
     */
    private static boolean isWindowsTerminalAvailable() {
        try {
            ProcessBuilder pb = new ProcessBuilder("wt", "--help");
            Process process = pb.start();
            process.waitFor();
            return process.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Checks if a command is available on the system PATH.
     */
    private static boolean isCommandAvailable(String command) {
        try {
            ProcessBuilder pb = new ProcessBuilder("which", command);
            Process process = pb.start();
            process.waitFor();
            return process.exitValue() == 0;
        } catch (Exception e) {
            // 'which' might not be available on all systems
            try {
                // Try running the command with --help
                ProcessBuilder pb = new ProcessBuilder(command, "--help");
                Process process = pb.start();
                process.waitFor();
                return true;
            } catch (Exception e2) {
                return false;
            }
        }
    }
}