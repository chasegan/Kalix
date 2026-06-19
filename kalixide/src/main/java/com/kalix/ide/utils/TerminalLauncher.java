package com.kalix.ide.utils;

import com.kalix.ide.preferences.PreferenceKeys;
import com.kalix.ide.preferences.PreferenceManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Cross-platform utility for opening terminal/command prompt windows.
 * Handles platform-specific differences for launching terminals at specific directories.
 *
 * <p>This class is intentionally free of any Swing/UI dependencies and holds no
 * application state: it is a pure function of its {@code directory} argument, so it
 * can be driven from any call site (the System menu, the file-tree context menu, …).
 * UI concerns — running off the EDT, status updates, error dialogs — live in
 * {@link TerminalActions}.
 */
public class TerminalLauncher {

    private static final Logger logger = LoggerFactory.getLogger(TerminalLauncher.class);

    /**
     * Opens a terminal window at the folder for the given path.
     *
     * <p>The argument may be either a folder or a file: a file is resolved to its
     * containing folder (see {@link #resolveFolder(File)}), so callers can pass
     * whatever the user clicked. A null or invalid path falls back to the user's
     * home directory.
     *
     * @param directory The folder (or a file within it) to open the terminal at, or null for user home
     * @return The folder the terminal was actually opened at (after resolution)
     * @throws IOException if the terminal cannot be launched
     */
    public static File openTerminalAt(File directory) throws IOException {
        // Resolve the path to the folder we should open
        File targetDir = resolveFolder(directory);

        try {
            switch (PlatformUtils.getCurrentPlatform()) {
                case MACOS -> openMacTerminal(targetDir);
                case WINDOWS -> openWindowsTerminal(targetDir);
                // Treat Linux and any unknown Unix-like as Linux (best effort).
                default -> openLinuxTerminal(targetDir);
            }
        } catch (IOException e) {
            logger.error("Failed to open terminal at directory: {}", targetDir.getAbsolutePath(), e);
            throw new IOException("Failed to open terminal: " + e.getMessage(), e);
        }

        return targetDir;
    }

    /**
     * Resolves an arbitrary path to the folder a terminal should open at.
     * <ul>
     *   <li>null → the user's home directory</li>
     *   <li>an existing directory → that directory</li>
     *   <li>a file (or non-existent path) → its parent directory, if valid</li>
     *   <li>otherwise → the user's home directory</li>
     * </ul>
     * This is the single source of truth for "where will the terminal open", shared
     * by every call site so reported paths always match what actually happens.
     *
     * @param directory The path to resolve (file, folder, or null)
     * @return A valid directory (never null)
     */
    public static File resolveFolder(File directory) {
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
     * Returns the preference key holding the activation command for the current platform.
     * Exposed so the preferences UI writes to the same key the launcher reads.
     *
     * @return the per-platform activation preference key
     */
    public static String activationPreferenceKey() {
        return switch (PlatformUtils.getCurrentPlatform()) {
            case WINDOWS -> PreferenceKeys.FILE_TERMINAL_ACTIVATION_WINDOWS;
            case MACOS -> PreferenceKeys.FILE_TERMINAL_ACTIVATION_MACOS;
            default -> PreferenceKeys.FILE_TERMINAL_ACTIVATION_LINUX;
        };
    }

    /**
     * Resolves the effective activation command for the current platform — shell command(s)
     * to run after entering the working directory (e.g. to activate a Python/conda
     * environment), or "" for a plain shell.
     *
     * <p>On Windows, if the new per-platform key is unset we fall back to the legacy
     * {@link PreferenceKeys#FILE_PYTHON_TERMINAL_COMMAND} (extracting the portion after its
     * {@code "/K"} token), preserving the historical Anaconda-activation default. This is the
     * one remaining use of that brittle format — isolated here as a migration shim.
     *
     * @return the activation command, trimmed; never null
     */
    public static String getActivationCommand() {
        String configured = PreferenceManager.getFileString(activationPreferenceKey(), "");
        if (configured != null && !configured.isBlank()) {
            return configured.trim();
        }
        if (PlatformUtils.getCurrentPlatform() == Platform.WINDOWS) {
            String legacy = PreferenceManager.getFileString(
                    PreferenceKeys.FILE_PYTHON_TERMINAL_COMMAND,
                    PreferenceKeys.DEFAULT_PYTHON_TERMINAL_COMMAND_WINDOWS);
            return extractLegacyActivation(legacy);
        }
        return "";
    }

    /**
     * Extracts the activation portion from the legacy combined command, i.e. everything
     * after the {@code "/K"} token. Returns "" if the token is absent or there is nothing
     * after it. (Environment variables like {@code %USERPROFILE%} are left intact — cmd.exe
     * expands them natively when the activation runs.)
     */
    static String extractLegacyActivation(String legacy) {
        if (legacy == null || legacy.isBlank()) {
            return "";
        }
        String marker = "\"/K\"";
        int index = legacy.indexOf(marker);
        return index >= 0 ? legacy.substring(index + marker.length()).trim() : "";
    }

    /**
     * Opens a terminal on macOS, honouring the user's preferred terminal application
     * ({@link PreferenceKeys#FILE_MACOS_TERMINAL_APP}, default Terminal.app).
     *
     * <p>For the two AppleScript-scriptable terminals — Terminal.app and iTerm2 — we drive
     * them via {@code osascript} so we can {@code cd} into the directory and clear the
     * screen. Crucially, the directory is passed as a real process argument and read back
     * inside the script via {@code item 1 of argv}, then shell-quoted with AppleScript's
     * {@code quoted form of}. Nothing is interpolated into the script text, so paths
     * containing apostrophes, spaces or quotes are handled correctly — the old
     * manual-escaping bug is gone by construction.
     *
     * <p>An activation command, if configured, is passed as a second argv item and read back
     * via {@code item 2 of argv}, so it too stays out of the script text. Unlike the path it
     * is left unquoted — it is a shell command meant to be interpreted, not a literal.
     *
     * <p>Any other app name (Warp, Ghostty, kitty, Alacritty, …) is opened best-effort via
     * {@code open -a <app> <dir>}, passing the path as a literal argument. Those terminals
     * are not scriptable here, so an activation command cannot be injected; the terminal
     * simply opens at the directory (a warning is logged if one was configured).
     */
    private static void openMacTerminal(File directory) throws IOException {
        String app = PreferenceManager.getFileString(
                PreferenceKeys.FILE_MACOS_TERMINAL_APP, PreferenceKeys.DEFAULT_MACOS_TERMINAL_APP);
        if (app == null || app.isBlank()) {
            app = PreferenceKeys.DEFAULT_MACOS_TERMINAL_APP;
        }
        app = app.trim();

        String path = directory.getAbsolutePath();
        String activation = getActivationCommand();
        String normalized = app.toLowerCase();

        // Shell run inside the terminal: "cd <quoted-path> [&& <activation>] && clear".
        // The path is item 1 of argv (shell-quoted by AppleScript); the activation, if any,
        // is item 2 of argv, inserted raw.
        String shellCommand = activation.isEmpty()
                ? "\"cd \" & quoted form of (item 1 of argv) & \" && clear\""
                : "\"cd \" & quoted form of (item 1 of argv) & \" && \" & (item 2 of argv) & \" && clear\"";

        ProcessBuilder pb;
        if (normalized.equals("terminal") || normalized.equals("terminal.app")) {
            pb = new ProcessBuilder(
                    "osascript",
                    "-e", "on run argv",
                    "-e", "tell application \"Terminal\"",
                    "-e", "do script " + shellCommand,
                    "-e", "activate",
                    "-e", "end tell",
                    "-e", "end run");
            appendMacArgs(pb, path, activation);
        } else if (normalized.equals("iterm") || normalized.equals("iterm2") || normalized.equals("iterm.app")) {
            pb = new ProcessBuilder(
                    "osascript",
                    "-e", "on run argv",
                    "-e", "tell application \"iTerm\"",
                    "-e", "set newWindow to (create window with default profile)",
                    "-e", "tell current session of newWindow to write text " + shellCommand,
                    "-e", "activate",
                    "-e", "end tell",
                    "-e", "end run");
            appendMacArgs(pb, path, activation);
        } else {
            // Non-scriptable / unknown app: best-effort. Path is a literal argument (no escaping needed).
            if (!activation.isEmpty()) {
                logger.warn("Terminal app '{}' is not scriptable; activation command will not run.", app);
            }
            pb = new ProcessBuilder("open", "-a", app, path);
        }

        pb.start();
    }

    /**
     * Appends the trailing osascript {@code argv} items: the directory path (always) and the
     * activation command (only when non-empty, so {@code item 2 of argv} exists iff used).
     */
    private static void appendMacArgs(ProcessBuilder pb, String path, String activation) {
        pb.command().add(path);
        if (!activation.isEmpty()) {
            pb.command().add(activation);
        }
    }

    /**
     * Opens a terminal on Windows.
     *
     * <p>If an activation command is configured (see {@link #getActivationCommand()}), the
     * user is dropped into a Command Prompt with that command run after {@code cd}-ing into
     * the directory — typically to activate a Python/conda environment. Otherwise it falls
     * back to the standard Windows Terminal → PowerShell → cmd chain.
     */
    private static void openWindowsTerminal(File directory) throws IOException {
        String activation = getActivationCommand();
        if (!activation.isEmpty()) {
            openWindowsTerminalWithActivation(directory, activation);
            return;
        }

        List<String> command = new ArrayList<>();

        // Try Windows Terminal first (modern Windows 10/11)
        if (isOnPath("wt")) {
            command.add("wt");
            command.add("-d");
            command.add(directory.getAbsolutePath());
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
     * Opens a Command Prompt on Windows and runs the given activation command after
     * changing into the target directory (e.g. activating a Python/conda environment).
     * Environment variables such as {@code %USERPROFILE%} are expanded by cmd.exe itself
     * when the command runs, so no manual expansion is needed.
     */
    private static void openWindowsTerminalWithActivation(File directory, String activation) throws IOException {
        // cmd.exe /c start "Kalix Terminal" cmd.exe /K "cd /d <dir> && <activation>"
        String commandToExecute = "cd /d \"" + directory.getAbsolutePath() + "\" && " + activation;
        ProcessBuilder pb = new ProcessBuilder(
                "cmd.exe", "/c", "start", "Kalix Terminal", "cmd.exe", "/K", commandToExecute);
        pb.start();
    }

    /**
     * Opens a terminal on Linux/Unix systems, trying several common emulators in order.
     *
     * <p>The working directory is set via {@link ProcessBuilder#directory(File)} (and, where
     * supported, the emulator's own working-directory flag), so the path is never placed in a
     * shell string — no quoting of the path is needed. The inner shell command runs the
     * configured activation (if any), clears the screen, and then {@code exec}s the user's
     * login shell ({@code $SHELL}, falling back to bash) so they keep their preferred shell.
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

        String activation = getActivationCommand();
        // Runs in the working dir (set on the ProcessBuilder), so no cd / path quoting here.
        String inner = (activation.isEmpty() ? "" : activation + "; ")
                + "clear; exec \"${SHELL:-bash}\"";
        String dir = directory.getAbsolutePath();

        IOException lastException = null;

        for (String terminal : terminals) {
            try {
                if (isOnPath(terminal)) {
                    List<String> command = new ArrayList<>();
                    command.add(terminal);

                    // Most emulators take the inner command as separate argv tokens (no quoting
                    // needed). The string-based --command forms keep their historical shape.
                    if (terminal.equals("gnome-terminal") || terminal.equals("mate-terminal")) {
                        command.add("--working-directory=" + dir);
                        command.add("--");
                        command.add("bash");
                        command.add("-c");
                        command.add(inner);
                    } else if (terminal.equals("konsole")) {
                        command.add("--workdir");
                        command.add(dir);
                        command.add("-e");
                        command.add("bash");
                        command.add("-c");
                        command.add(inner);
                    } else if (terminal.equals("xfce4-terminal") || terminal.equals("lxterminal")
                            || terminal.equals("terminator")) {
                        command.add("--working-directory=" + dir);
                        command.add("--command=bash -c '" + inner + "'");
                    } else {
                        // xterm and others: inner as separate argv; cwd comes from ProcessBuilder.
                        command.add("-e");
                        command.add("bash");
                        command.add("-c");
                        command.add(inner);
                    }

                    ProcessBuilder pb = new ProcessBuilder(command);
                    pb.directory(directory);  // robust cwd, independent of any emulator flag
                    pb.start();
                    return; // Success!

                }
            } catch (IOException e) {
                lastException = e;
            }
        }

        // If we get here, none of the terminals worked
        throw new IOException("No suitable terminal emulator found. Tried: " +
                            String.join(", ", terminals), lastException);
    }

    /**
     * Checks whether an executable is available on the system {@code PATH}, by scanning
     * PATH directly rather than spawning a probe process.
     *
     * <p>The old approach shelled out to {@code which}/{@code where} and, as a fallback,
     * ran {@code <cmd> --help}. That had two hazards this method removes by construction:
     * <ul>
     *   <li><b>Deadlock:</b> the probes called {@code waitFor()} without draining the
     *       child's stdout/stderr, so a chatty {@code --help} could fill the pipe buffer
     *       and hang forever.</li>
     *   <li><b>Side effects:</b> {@code --help} could actually launch a GUI terminal.</li>
     * </ul>
     * Scanning PATH spawns nothing, so neither hazard exists, and it is faster.
     *
     * @param executable the executable name to look for (without extension)
     * @return true if a matching executable is found on PATH
     */
    private static boolean isOnPath(String executable) {
        return isOnPath(executable, System.getenv("PATH"), PlatformUtils.getCurrentPlatform());
    }

    /**
     * Pure implementation of {@link #isOnPath(String)}, with the PATH string and platform
     * injected so it can be unit-tested without touching the real environment.
     *
     * @param executable the executable name to look for (without extension)
     * @param pathEnv    the PATH value to scan (entries separated by {@link File#pathSeparator})
     * @param platform   the platform (controls Windows PATHEXT handling)
     * @return true if a matching executable is found
     */
    static boolean isOnPath(String executable, String pathEnv, Platform platform) {
        if (pathEnv == null || pathEnv.isEmpty()) {
            return false;
        }

        // Build the set of filenames to look for. On Windows, executables resolve via
        // PATHEXT (.EXE, .BAT, .CMD, …) so we try each extension as well as the bare name.
        List<String> candidates = new ArrayList<>();
        candidates.add(executable);
        if (platform == Platform.WINDOWS) {
            String pathExt = System.getenv("PATHEXT");
            if (pathExt == null || pathExt.isEmpty()) {
                pathExt = ".EXE;.BAT;.CMD;.COM";
            }
            for (String ext : pathExt.split(";")) {
                ext = ext.trim();
                if (!ext.isEmpty()) {
                    candidates.add(executable + ext);
                }
            }
        }

        for (String dir : pathEnv.split(File.pathSeparator)) {
            if (dir.isEmpty()) {
                continue;
            }
            File dirFile = new File(dir);
            for (String candidate : candidates) {
                File file = new File(dirFile, candidate);
                if (file.isFile() && file.canExecute()) {
                    return true;
                }
            }
        }
        return false;
    }
}