package com.kalix.ide.utils;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the pure, platform-independent logic in {@link TerminalLauncher}:
 * folder resolution, PATH scanning, and the legacy activation-command shim.
 * The actual terminal-spawning paths are exercised manually (they open windows).
 */
class TerminalLauncherTest {

    private static final File HOME = new File(System.getProperty("user.home"));

    // ---- resolveFolder ----

    @Test
    void resolveFolderNullReturnsHome() {
        assertEquals(HOME, TerminalLauncher.resolveFolder(null));
    }

    @Test
    void resolveFolderExistingDirectoryReturnsItself(@TempDir Path dir) {
        File d = dir.toFile();
        assertEquals(d, TerminalLauncher.resolveFolder(d));
    }

    @Test
    void resolveFolderFileReturnsParent(@TempDir Path dir) throws IOException {
        File file = Files.createFile(dir.resolve("model.ini")).toFile();
        assertEquals(dir.toFile(), TerminalLauncher.resolveFolder(file));
    }

    @Test
    void resolveFolderNonexistentPathReturnsValidParent(@TempDir Path dir) {
        File missing = dir.resolve("does-not-exist.ini").toFile();
        assertEquals(dir.toFile(), TerminalLauncher.resolveFolder(missing));
    }

    @Test
    void resolveFolderNonexistentWithNoValidParentReturnsHome() {
        // A path whose parent also does not exist falls back to home.
        File missing = new File("/no/such/parent/at/all/file.ini");
        assertEquals(HOME, TerminalLauncher.resolveFolder(missing));
    }

    // ---- extractLegacyActivation ----

    @Test
    void extractLegacyActivationPullsCommandAfterKToken() {
        String legacy = "%windir%\\System32\\cmd.exe \"/K\" %USERPROFILE%\\anaconda3\\Scripts\\activate.bat";
        assertEquals("%USERPROFILE%\\anaconda3\\Scripts\\activate.bat",
                TerminalLauncher.extractLegacyActivation(legacy));
    }

    @Test
    void extractLegacyActivationDefaultWindowsCommand() {
        assertEquals("%USERPROFILE%\\anaconda3\\Scripts\\activate.bat",
                TerminalLauncher.extractLegacyActivation(
                        com.kalix.ide.preferences.PreferenceKeys.DEFAULT_PYTHON_TERMINAL_COMMAND_WINDOWS));
    }

    @Test
    void extractLegacyActivationWithoutKTokenReturnsEmpty() {
        assertEquals("", TerminalLauncher.extractLegacyActivation("cmd.exe activate.bat"));
    }

    @Test
    void extractLegacyActivationNullOrBlankReturnsEmpty() {
        assertEquals("", TerminalLauncher.extractLegacyActivation(null));
        assertEquals("", TerminalLauncher.extractLegacyActivation("   "));
    }

    // ---- isOnPath (pure overload) ----

    @Test
    void isOnPathFindsExecutableOnPath(@TempDir Path dir) throws IOException {
        File exe = makeExecutable(dir, "kalix-fake-tool");
        String path = dir.toString();
        assertTrue(TerminalLauncher.isOnPath(exe.getName(), path, Platform.LINUX));
    }

    @Test
    void isOnPathReturnsFalseForMissingExecutable(@TempDir Path dir) {
        assertFalse(TerminalLauncher.isOnPath("definitely-not-here", dir.toString(), Platform.LINUX));
    }

    @Test
    void isOnPathReturnsFalseForNullOrEmptyPath() {
        assertFalse(TerminalLauncher.isOnPath("sh", null, Platform.LINUX));
        assertFalse(TerminalLauncher.isOnPath("sh", "", Platform.LINUX));
    }

    @Test
    void isOnPathSkipsEmptyPathEntriesAndScansMultipleDirs(@TempDir Path dir) throws IOException {
        File exe = makeExecutable(dir, "kalix-fake-tool");
        // Leading empty entry + a bogus dir, then the real dir.
        String path = File.pathSeparator + "/no/such/dir" + File.pathSeparator + dir;
        assertTrue(TerminalLauncher.isOnPath(exe.getName(), path, Platform.LINUX));
    }

    @Test
    void isOnPathRequiresExecutableBit(@TempDir Path dir) throws IOException {
        // A plain, non-executable file must not count as on-PATH (Unix semantics).
        File plain = Files.createFile(dir.resolve("not-exec")).toFile();
        plain.setExecutable(false);
        // Skip if the filesystem ignores the executable bit (e.g. some mounts).
        if (!plain.canExecute()) {
            assertFalse(TerminalLauncher.isOnPath(plain.getName(), dir.toString(), Platform.LINUX));
        }
    }

    private static File makeExecutable(Path dir, String name) throws IOException {
        File exe = Files.createFile(dir.resolve(name)).toFile();
        assertTrue(exe.setExecutable(true), "could not mark test file executable");
        return exe;
    }
}
