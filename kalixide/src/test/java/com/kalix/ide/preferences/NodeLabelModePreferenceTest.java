package com.kalix.ide.preferences;

import com.kalix.ide.rendering.NodeLabelMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The saved label mode, and the one-time migration of the legacy on/off toggle. Lives
 * here, not in {@code rendering}, because the preference test hook is package-private.
 */
class NodeLabelModePreferenceTest {

    private static void freshPreferences(Path tempDir) {
        PreferenceManager.redirectForTesting(tempDir.resolve("prefs.json").toFile());
    }

    @Test
    void nothingSavedMeansNames(@TempDir Path tempDir) {
        freshPreferences(tempDir);
        assertEquals(NodeLabelMode.NAMES, NodeLabelMode.saved());
    }

    @Test
    void savedModeRoundTrips(@TempDir Path tempDir) {
        freshPreferences(tempDir);
        NodeLabelMode.AKA.save();
        assertEquals(NodeLabelMode.AKA, NodeLabelMode.saved());
    }

    @Test
    void legacyOffToggleIsReadOnceAndRewrittenAsHidden(@TempDir Path tempDir) {
        freshPreferences(tempDir);
        PreferenceKeys.MAP_SHOW_LABELS.set(false);
        assertEquals(NodeLabelMode.HIDDEN, NodeLabelMode.saved());
        assertEquals("hidden", PreferenceKeys.MAP_LABEL_MODE.get());
    }

    @Test
    void unknownSavedModeFallsBackToTheLegacyToggle(@TempDir Path tempDir) {
        freshPreferences(tempDir);
        PreferenceKeys.MAP_LABEL_MODE.set("purple");
        assertEquals(NodeLabelMode.NAMES, NodeLabelMode.saved());
        assertEquals("names", PreferenceKeys.MAP_LABEL_MODE.get());
    }
}
