package com.kalix.ide.io;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KalixPathTest {

    // ---- Parse tests (no filesystem) ----

    @Test
    void parseTrailhead() {
        KalixPath kp = KalixPath.parse("^/data/climate/evap.csv");
        assertEquals(KalixPath.PathKind.TRAILHEAD, kp.kind());
        assertEquals("data/climate/evap.csv", kp.target());
        assertEquals("^/data/climate/evap.csv", kp.raw());
    }

    @Test
    void parseRelative() {
        KalixPath kp = KalixPath.parse("../data.csv");
        assertEquals(KalixPath.PathKind.RELATIVE, kp.kind());
        assertNull(kp.target());
    }

    @Test
    void parseAbsolute() {
        KalixPath kp = KalixPath.parse("/home/user/data.csv");
        assertEquals(KalixPath.PathKind.ABSOLUTE, kp.kind());
        assertNull(kp.target());
    }

    @Test
    void parseBareCaretIsError() {
        assertThrows(IllegalArgumentException.class, () -> KalixPath.parse("^"));
    }

    @Test
    void parseCaretSlashOnlyIsError() {
        assertThrows(IllegalArgumentException.class, () -> KalixPath.parse("^/"));
    }

    @Test
    void parseCaretNoSlashIsRelative() {
        KalixPath kp = KalixPath.parse("^something");
        assertEquals(KalixPath.PathKind.RELATIVE, kp.kind());
    }

    @Test
    void parseNullIsError() {
        assertThrows(IllegalArgumentException.class, () -> KalixPath.parse(null));
    }

    @Test
    void parseEmptyIsError() {
        assertThrows(IllegalArgumentException.class, () -> KalixPath.parse(""));
    }

    @Test
    void roundTrip() {
        String raw = "^/data/climate/evap.csv";
        assertEquals(raw, KalixPath.parse(raw).raw());
    }

    // ---- Resolution tests ----

    @Test
    void resolveRelativePath(@TempDir Path dir) throws Exception {
        Path dataFile = dir.resolve("rain.csv");
        Files.writeString(dataFile, "test");

        KalixPath kp = KalixPath.parse("rain.csv");
        Path resolved = kp.resolve(dir);
        assertEquals(dataFile.toAbsolutePath().normalize(), resolved);
    }

    @Test
    void resolveAbsolutePath(@TempDir Path dir) throws Exception {
        Path dataFile = dir.resolve("rain.csv");
        Files.writeString(dataFile, "test");

        KalixPath kp = KalixPath.parse(dataFile.toAbsolutePath().toString());
        Path resolved = kp.resolve(dir);
        assertEquals(dataFile.toAbsolutePath().normalize(), resolved);
    }

    @Test
    void resolveTrailheadFoundInAncestor(@TempDir Path project) throws Exception {
        // project/data/evap.csv
        // project/models/deep/nested/  <-- context dir
        Path dataDir = project.resolve("data");
        Files.createDirectories(dataDir);
        Path dataFile = dataDir.resolve("evap.csv");
        Files.writeString(dataFile, "test");

        Path context = project.resolve("models").resolve("deep").resolve("nested");
        Files.createDirectories(context);

        KalixPath kp = KalixPath.parse("^/data/evap.csv");
        Path resolved = kp.resolve(context);
        assertEquals(dataFile.toAbsolutePath().normalize(), resolved);
    }

    @Test
    void resolveTrailheadNearestWins(@TempDir Path project) throws Exception {
        // Far copy
        Path farData = project.resolve("data");
        Files.createDirectories(farData);
        Files.writeString(farData.resolve("evap.csv"), "far");

        // Near copy
        Path nearData = project.resolve("models").resolve("data");
        Files.createDirectories(nearData);
        Path nearFile = nearData.resolve("evap.csv");
        Files.writeString(nearFile, "near");

        Path context = project.resolve("models").resolve("deep");
        Files.createDirectories(context);

        KalixPath kp = KalixPath.parse("^/data/evap.csv");
        Path resolved = kp.resolve(context);
        assertEquals(nearFile.toAbsolutePath().normalize(), resolved);
    }

    @Test
    void resolveTrailheadFoundInContextDir(@TempDir Path dir) throws Exception {
        Path dataFile = dir.resolve("evap.csv");
        Files.writeString(dataFile, "test");

        KalixPath kp = KalixPath.parse("^/evap.csv");
        Path resolved = kp.resolve(dir);
        assertEquals(dataFile.toAbsolutePath().normalize(), resolved);
    }

    @Test
    void resolveTrailheadNotFound(@TempDir Path dir) {
        KalixPath kp = KalixPath.parse("^/nonexistent.csv");
        KalixPathResolutionException ex = assertThrows(
                KalixPathResolutionException.class,
                () -> kp.resolve(dir));
        assertTrue(ex.getMessage().contains("not found in any ancestor"));
        assertEquals("^/nonexistent.csv", ex.getRaw());
    }

    @Test
    void resolveNullContextDirThrows() {
        KalixPath kp = KalixPath.parse("data.csv");
        assertThrows(KalixPathResolutionException.class, () -> kp.resolve(null));
    }

    @Test
    void resolveTrailheadSingleFile(@TempDir Path project) throws Exception {
        // project/my_file.csv
        // project/models/sub/  <-- context dir
        Files.writeString(project.resolve("my_file.csv"), "test");

        Path context = project.resolve("models").resolve("sub");
        Files.createDirectories(context);

        KalixPath kp = KalixPath.parse("^/my_file.csv");
        Path resolved = kp.resolve(context);
        assertEquals(project.resolve("my_file.csv").toAbsolutePath().normalize(), resolved);
    }
}
