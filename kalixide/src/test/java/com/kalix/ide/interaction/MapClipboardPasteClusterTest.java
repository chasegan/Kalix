package com.kalix.ide.interaction;

import com.kalix.ide.MapPanel;
import com.kalix.ide.document.ModelDocument;
import com.kalix.ide.model.HydrologicalModel;
import org.junit.jupiter.api.Test;

import javax.swing.SwingUtilities;
import java.lang.reflect.InvocationTargetException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A paste into a collapsed cluster expands it, so the pasted node can be seen (clusters.md
 * rule 16): through the map's Copy and Paste key actions, and the reading of the pasted
 * sections' {@code #@cluster} lines.
 */
class MapClipboardPasteClusterTest {

    private static final String MODEL = """
            [node.x]
            type = gauge
            loc = 0, 100

            #@cluster = north
            [node.n1]
            type = gauge
            loc = 100, 100

            #@cluster = north
            [node.n2]
            type = gauge
            loc = 140, 100
            """;

    /** Runs a test on the event thread, where the editor queues its reparse. */
    private static void onEdt(Runnable test) {
        try {
            SwingUtilities.invokeAndWait(test);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof Error error) throw error;
            throw new RuntimeException(e.getCause());
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    private static ClipboardEntry.NodeSectionData section(String text) {
        return new ClipboardEntry.NodeSectionData("a", text, 0, 0, 0);
    }

    @Test
    void aPasteIntoACollapsedClusterExpandsIt() {
        ModelDocument document = new ModelDocument();
        HydrologicalModel model = document.getModel();
        MapPanel panel = document.getMapPanel();
        document.getEditor().getTextArea().setText(MODEL);
        model.parseFromIniText(MODEL);
        onEdt(() -> {
            panel.getClusterCollapse().setCollapsed(List.of("north"), true);
            model.selectNodes(List.of("n1"));
            panel.getActionMap().get("map.copy").actionPerformed(null);
            model.clearSelection();
            panel.getClusterCollapse().setCollapsed(List.of("north"), true);

            panel.getActionMap().get("map.paste").actionPerformed(null);

            assertTrue(document.getEditor().getTextArea().getText().contains("[node.n1_"), "pasted");
            assertFalse(panel.getClusterCollapse().isCollapsed("north"), "expanded to show it");
        });
    }

    @Test
    void theClustersNamedAreReadOnceEachInOrder() {
        assertEquals(List.of("north", "south"), MapClipboardManager.pastedClusters(List.of(
                section("#@cluster = north\n[node.a]\ntype = gauge"),
                section("#@cluster = south\r\n[node.b]"),
                section("#@cluster = north\n[node.c]"))));
    }

    @Test
    void theLastClusterLineCountsAndALooseNodeNamesNone() {
        assertEquals(List.of("second"), MapClipboardManager.pastedClusters(List.of(
                section("#@cluster = first\n#@cluster = second\n[node.a]"),
                section("#@aka = A\n[node.b]"))));
    }
}
