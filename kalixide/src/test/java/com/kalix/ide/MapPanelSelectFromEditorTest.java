package com.kalix.ide;

import com.kalix.ide.document.ModelDocument;
import com.kalix.ide.model.HydrologicalModel;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Show on map and Find… select a node from the text side through
 * {@link MapPanel#selectNodeFromEditor}. A member of a collapsed cluster is hidden, so its
 * cluster is expanded first and the node is seen selected; nothing else's collapse state
 * changes (clusters.md rule 10).
 *
 * <p>The model: loose x; n1 and n2 in north; s1 in south.</p>
 */
class MapPanelSelectFromEditorTest {

    private static final String MODEL = """
            [node.x]
            type = gauge
            loc = 0, 0

            #@cluster = north
            [node.n1]
            type = gauge
            loc = 100, 0

            #@cluster = north
            [node.n2]
            type = gauge
            loc = 200, 0

            #@cluster = south
            [node.s1]
            type = gauge
            loc = 300, 0
            """;

    private final ModelDocument document = new ModelDocument();
    private final HydrologicalModel model = document.getModel();
    private final MapPanel panel = document.getMapPanel();

    MapPanelSelectFromEditorTest() {
        model.parseFromIniText(MODEL);
    }

    @Test
    void aHiddenMemberExpandsItsClusterAndIsSelected() {
        panel.selectNodeFromEditor("n1");

        assertFalse(panel.getClusterCollapse().isCollapsed("north"), "expanded so the node can be seen");
        assertTrue(panel.getClusterCollapse().isCollapsed("south"), "the other cluster is left alone");
        assertEquals(Set.of("n1"), model.getSelectedNodes());
    }

    @Test
    void aLooseNodeChangesNoCollapseState() {
        panel.selectNodeFromEditor("x");

        assertTrue(panel.getClusterCollapse().isCollapsed("north"));
        assertTrue(panel.getClusterCollapse().isCollapsed("south"));
        assertEquals(Set.of("x"), model.getSelectedNodes());
    }

    @Test
    void aVisibleMemberLeavesItsClusterExpanded() {
        panel.getCollapseManager().expand(List.of("north"));

        panel.selectNodeFromEditor("n2");

        assertFalse(panel.getClusterCollapse().isCollapsed("north"));
        assertEquals(Set.of("n2"), model.getSelectedNodes());
    }

    @Test
    void anUnknownNodeDoesNothing() {
        panel.selectNodeFromEditor("no_such_node");

        assertTrue(panel.getClusterCollapse().isCollapsed("north"));
        assertTrue(model.getSelectedNodes().isEmpty());
    }
}
