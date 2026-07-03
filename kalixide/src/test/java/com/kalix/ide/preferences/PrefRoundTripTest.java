package com.kalix.ide.preferences;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * File-tier {@link Pref} behaviour: defaults, set/get round-trips through the
 * JSON file, and the guarantee that reads never write.
 *
 * <p>Only the file tier is exercised — OS-tier prefs write to the developer's
 * real {@code java.util.prefs} store, so tests must not touch them.
 */
class PrefRoundTripTest {

    // Deliberately test-only keys so no production constant's stored data is disturbed.
    private static final Pref<Boolean> BOOL = Pref.fileBoolean("test.pref.bool", true);
    private static final Pref<Integer> INT = Pref.fileInt("test.pref.int", 42);
    private static final Pref<Double> DOUBLE = Pref.fileDouble("test.pref.double", 1.5);
    private static final Pref<String> STRING = Pref.fileString("test.pref.string", "fallback");
    private static final Pref<List<String>> LIST = Pref.fileStringList("test.pref.list", List.of("a", "b"));

    @Test
    void missingKeysYieldDefaults(@TempDir Path tempDir) {
        PreferenceManager.redirectForTesting(tempDir.resolve("prefs.json").toFile());

        assertEquals(true, BOOL.get());
        assertEquals(42, INT.get());
        assertEquals(1.5, DOUBLE.get());
        assertEquals("fallback", STRING.get());
        assertEquals(List.of("a", "b"), LIST.get());
    }

    @Test
    void readsDoNotWriteTheFile(@TempDir Path tempDir) {
        File file = tempDir.resolve("prefs.json").toFile();
        PreferenceManager.redirectForTesting(file);

        BOOL.get();
        INT.get();
        DOUBLE.get();
        STRING.get();
        LIST.get();

        assertFalse(file.exists(),
            "reading missing preferences must not create/rewrite the preference file");
    }

    @Test
    void setThenGetRoundTripsThroughTheFile(@TempDir Path tempDir) {
        File file = tempDir.resolve("prefs.json").toFile();
        PreferenceManager.redirectForTesting(file);

        BOOL.set(false);
        INT.set(-7);
        DOUBLE.set(0.001);
        STRING.set("chosen \"value\" with\nnewline and C:\\path");
        LIST.set(List.of("one", "two, with comma"));

        assertTrue(file.exists(), "set must persist immediately");

        // Same cache
        assertEquals(false, BOOL.get());
        assertEquals(-7, INT.get());
        assertEquals(0.001, DOUBLE.get());
        assertEquals("chosen \"value\" with\nnewline and C:\\path", STRING.get());
        assertEquals(List.of("one", "two, with comma"), LIST.get());

        // Fresh load from disk (redirect drops the in-memory cache)
        PreferenceManager.redirectForTesting(file);
        assertEquals(false, BOOL.get());
        assertEquals(-7, INT.get());
        assertEquals(0.001, DOUBLE.get());
        assertEquals("chosen \"value\" with\nnewline and C:\\path", STRING.get());
        assertEquals(List.of("one", "two, with comma"), LIST.get());
    }

    @Test
    void typeInvalidStoredValueYieldsDefaultWithoutRewriting(@TempDir Path tempDir) throws Exception {
        File file = tempDir.resolve("prefs.json").toFile();
        java.nio.file.Files.writeString(file.toPath(),
            "{\n  \"test.pref.int\": \"not a number\"\n}");
        PreferenceManager.redirectForTesting(file);

        long before = file.lastModified();
        assertEquals(42, INT.get(), "invalid stored type falls back to the default");
        assertEquals(before, file.lastModified(), "the fallback must not be written back");
    }
}
