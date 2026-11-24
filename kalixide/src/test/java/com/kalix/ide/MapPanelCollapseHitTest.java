package com.kalix.ide;

import com.kalix.ide.document.ModelDocument;
import com.kalix.ide.model.HydrologicalModel;
import org.junit.jupiter.api.Test;

import java.awt.Point;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The map's hit-tests with clusters (clusters.md, "Hit-testing"): a hidden member and its
 * links are never found, and of two overlapping shapes the one drawn on top is. At the
 * panel's starting zoom and pan, a screen point is the same as a map point.
 */
class MapPanelCollapseHitTest {

    /** Loose x at (0, 100), linked to n1 at (100, 100) in north; loose y at (300, 50) under m. */
    private static final String MODEL = """
            [node.x]
            type = gauge
            loc = 0, 100
            ds_1 = n1

            #@cluster = north
            [node.n1]
            type = gauge
            loc = 100, 100

            [node.y]
            type = gauge
            loc = 300, 50

            #@cluster = south
            [node.m]
            type = gauge
            loc = 305, 50
            """;

    private static MapPanel panel(boolean collapsed) {
        ModelDocument document = new ModelDocument();
        HydrologicalModel model = document.getModel();
        model.parseFromIniText(MODEL);
        MapPanel panel = document.getMapPanel();
        panel.getClusterCollapse().setCollapsed(List.of("north", "south"), collapsed);
        return panel;
    }

    @Test
    void aHiddenMemberIsNotFound() {
        assertNull(panel(true).getNodeAtPoint(new Point(100, 100)));
        assertEquals("n1", panel(false).getNodeAtPoint(new Point(100, 100)));
    }

    @Test
    void aLinkToAHiddenMemberIsNotFound() {
        assertNull(panel(true).getLinkAtPoint(new Point(50, 100)));
        assertNotNull(panel(false).getLinkAtPoint(new Point(50, 100)));
    }

    @Test
    void ofTwoOverlappingShapesTheOneOnTopIsFound() {
        // m is in a cluster, so its layer is drawn after loose y's; both reach (302, 50).
        assertEquals("m", panel(false).getNodeAtPoint(new Point(302, 50)));
        assertEquals("y", panel(true).getNodeAtPoint(new Point(302, 50)), "with m hidden, y");
    }
}
