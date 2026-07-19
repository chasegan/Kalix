package com.kalix.ide.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KalixCliLocatorTest {

    /**
     * A relative configured directory (e.g. "../target/release") must resolve to an
     * absolute executable path. Sessions are spawned with the model's folder as the
     * child working directory, and ProcessBuilder re-resolves a relative command
     * against that directory — so a relative CliLocation launches the wrong path
     * (or nothing) at run time even though Test/version checks succeed.
     */
    @Test
    @DisabledOnOs(OS.WINDOWS) // uses a shell script as the fake executable
    void relativeConfiguredPathYieldsAbsoluteExecutablePath(@TempDir Path tempDir) throws Exception {
        Path fakeKalix = tempDir.resolve("kalix");
        Files.writeString(fakeKalix, "#!/bin/sh\necho \"kalix 0.0.0-test\"\nexit 0\n");
        Files.setPosixFilePermissions(fakeKalix, EnumSet.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE));

        Path cwd = Paths.get("").toAbsolutePath();
        String relativeDir = cwd.relativize(tempDir.toRealPath()).toString();

        Optional<KalixCliLocator.CliLocation> location = KalixCliLocator.findKalixCli(relativeDir);

        assertTrue(location.isPresent(), "locator should find the fake kalix via the relative dir");
        Path found = location.get().getPath();
        assertTrue(found.isAbsolute(),
            "located path must be absolute so it survives a different child working directory, got: " + found);
        assertTrue(Files.isSameFile(found, fakeKalix), "absolute path should point at the same executable");
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void absoluteConfiguredPathStillWorks(@TempDir Path tempDir) throws Exception {
        Path fakeKalix = tempDir.resolve("kalix");
        Files.writeString(fakeKalix, "#!/bin/sh\nexit 0\n");
        Files.setPosixFilePermissions(fakeKalix, EnumSet.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE));

        Optional<KalixCliLocator.CliLocation> location =
            KalixCliLocator.findKalixCli(tempDir.toString());

        assertTrue(location.isPresent());
        assertTrue(location.get().getPath().isAbsolute());
        assertEquals(fakeKalix.toRealPath(), location.get().getPath().toRealPath());
    }
}
