package com.kalix.ide;

import com.kalix.ide.document.ModelDocument;
import com.kalix.ide.model.HydrologicalModel;
import org.junit.jupiter.api.Test;

import java.awt.event.InputEvent;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.awt.event.MouseMotionListener;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Rectangle selection with clusters (clusters.md, "Hit-testing"): it picks an icon when the
 * point the icon is drawn at lies inside, selecting all its members in one batch, so the
 * selection rule keeps the cluster collapsed and selected (rule 10). A Shift-drag on empty
 * map, through the panel's own mouse listeners; at the starting zoom and pan a screen point
 * is a map point.
 */
class MapPanelRectangleSelectionTest {

    /** Loose x at (0, 100); north's n1 and n2 at (100, 100), (140, 100), so its icon is at (120, 100). */
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

    private static MapPanel panel(HydrologicalModel[] model) {
        ModelDocument document = new ModelDocument();
        model[0] = document.getModel();
        model[0].parseFromIniText(MODEL);
        MapPanel panel = document.getMapPanel();
        panel.getClusterCollapse().setCollapsed(List.of("north"), true);
        return panel;
    }

    private static MouseEvent shiftEvent(MapPanel panel, int id, int x, int y) {
        return new MouseEvent(panel, id, 0, InputEvent.BUTTON1_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK,
                x, y, 1, false, MouseEvent.BUTTON1);
    }

    /** Shift-drags a rectangle from (x1, y1) to (x2, y2). */
    private static void drag(MapPanel panel, int x1, int y1, int x2, int y2) {
        for (MouseListener l : panel.getMouseListeners()) l.mousePressed(shiftEvent(panel, MouseEvent.MOUSE_PRESSED, x1, y1));
        for (MouseMotionListener l : panel.getMouseMotionListeners()) l.mouseDragged(shiftEvent(panel, MouseEvent.MOUSE_DRAGGED, x2, y2));
        for (MouseListener l : panel.getMouseListeners()) l.mouseReleased(shiftEvent(panel, MouseEvent.MOUSE_RELEASED, x2, y2));
    }

    @Test
    void anIconWhosePointIsInsideIsSelectedWholeAndStaysCollapsed() {
        HydrologicalModel[] model = new HydrologicalModel[1];
        MapPanel panel = panel(model);

        // Around the icon's point only: neither member's point is inside.
        drag(panel, 110, 60, 130, 140);

        assertEquals(Set.of("n1", "n2"), model[0].getSelectedNodes());
        assertTrue(panel.getClusterCollapse().isCollapsed("north"), "a selected icon, not expanded");
    }

    @Test
    void aHiddenMembersPointAloneSelectsNothing() {
        HydrologicalModel[] model = new HydrologicalModel[1];
        MapPanel panel = panel(model);

        // Around n1's point (100, 100), but not the icon's (120, 100).
        drag(panel, 90, 60, 110, 140);

        assertEquals(Set.of(), model[0].getSelectedNodes());
    }

    @Test
    void looseNodesAndIconsAreSelectedTogether() {
        HydrologicalModel[] model = new HydrologicalModel[1];
        MapPanel panel = panel(model);

        drag(panel, -20, 60, 130, 140);

        assertEquals(Set.of("x", "n1", "n2"), model[0].getSelectedNodes());
        assertTrue(panel.getClusterCollapse().isCollapsed("north"));
    }
}
