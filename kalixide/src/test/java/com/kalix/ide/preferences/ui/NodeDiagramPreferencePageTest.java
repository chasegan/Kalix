package com.kalix.ide.preferences.ui;

import com.kalix.ide.MapPanel;
import com.kalix.ide.document.ModelDocument;
import com.kalix.ide.preferences.PreferencesForTests;
import com.kalix.ide.rendering.NodeLabelMode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JList;
import java.awt.Component;
import java.awt.Container;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/** Pins the label-mode default: it starts from, and saves to, the saved mode, and leaves open maps alone. */
class NodeDiagramPreferencePageTest {

    @BeforeEach
    void freshPreferences(@TempDir Path tempDir) {
        PreferencesForTests.redirect(tempDir.resolve("prefs.json").toFile());
    }

    private static NodeDiagramPreferencePage page() {
        return new NodeDiagramPreferencePage(enabled -> { });
    }

    @SuppressWarnings("unchecked")
    private static JComboBox<NodeLabelMode> labelModeComboBox(Container container) {
        for (Component child : container.getComponents()) {
            if (child instanceof JComboBox<?> combo && combo.getItemAt(0) instanceof NodeLabelMode) {
                return (JComboBox<NodeLabelMode>) combo;
            }
            if (child instanceof Container inner) {
                JComboBox<NodeLabelMode> found = labelModeComboBox(inner);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    @Test
    void comboStartsAtTheSavedMode() {
        NodeLabelMode.AKA.save();
        JComboBox<NodeLabelMode> combo = labelModeComboBox(page());
        assertNotNull(combo);
        assertEquals(NodeLabelMode.AKA, combo.getSelectedItem());
    }

    @Test
    void comboListsEveryModeByItsDisplayName() {
        JComboBox<NodeLabelMode> combo = labelModeComboBox(page());
        assertNotNull(combo);
        assertEquals(NodeLabelMode.values().length, combo.getItemCount());
        JList<NodeLabelMode> list = new JList<>();
        for (NodeLabelMode mode : NodeLabelMode.values()) {
            Component cell = combo.getRenderer().getListCellRendererComponent(list, mode, 0, false, false);
            assertEquals(mode.displayName(), ((JLabel) cell).getText());
        }
    }

    @Test
    void selectingAModeSavesItForNewMapsAndLeavesOpenMapsAlone() {
        MapPanel openMap = new ModelDocument().getMapPanel();
        openMap.setLabelMode(NodeLabelMode.NAMES);

        labelModeComboBox(page()).setSelectedItem(NodeLabelMode.HIDDEN);

        assertEquals(NodeLabelMode.HIDDEN, NodeLabelMode.saved());
        MapPanel newMap = new ModelDocument().getMapPanel();
        newMap.setLabelMode(NodeLabelMode.saved()); // as KalixIDE.configureDocument does
        assertEquals(NodeLabelMode.HIDDEN, newMap.getLabelMode());
        assertEquals(NodeLabelMode.NAMES, openMap.getLabelMode());
    }
}
