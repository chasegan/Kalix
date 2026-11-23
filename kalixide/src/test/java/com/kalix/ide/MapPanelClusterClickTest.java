package com.kalix.ide;

import com.kalix.ide.document.ModelDocument;
import com.kalix.ide.model.HydrologicalModel;
import com.kalix.ide.rendering.ClusterHandles;
import com.kalix.ide.rendering.NodeLabelMode;
import com.kalix.ide.themes.NodeTheme;
import org.junit.jupiter.api.Test;

import java.awt.Point;
import java.awt.event.InputEvent;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.awt.geom.Rectangle2D;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A click on a cluster's icon, box name or proxy (clusters.md rule 11): it selects all the
 * members and takes the editor to the first member's section; Shift adds them, or removes
 * them when all are selected, in one change, so a collapsed cluster stays collapsed
 * (rule 10). Through the panel's own mouse listeners; at the starting zoom and pan a screen
 * point is a map point.
 *
 * <p>The model: loose x at (0, 100); north's n1 and n2 at (100, 100) and (140, 100), so its
 * icon is at (120, 100), over loose y there.</p>
 */
class MapPanelClusterClickTest {

    private static final String MODEL = """
            [node.x]
            type = gauge
            loc = 0, 100

            [node.y]
            type = gauge
            loc = 120, 100

            #@cluster = north
            [node.n1]
            type = gauge
            loc = 100, 100

            #@cluster = north
            [node.n2]
            type = gauge
            loc = 140, 100
            """;

    private final ModelDocument document = new ModelDocument();
    private final HydrologicalModel model = document.getModel();
    private final MapPanel panel = document.getMapPanel();

    MapPanelClusterClickTest() {
        document.getEditor().getTextArea().setText(MODEL);
        model.parseFromIniText(MODEL);
    }

    private void collapse(boolean collapsed) {
        panel.getClusterCollapse().setCollapsed(List.of("north"), collapsed);
    }

    private MouseEvent event(int id, Point point, boolean shift) {
        int modifiers = InputEvent.BUTTON1_DOWN_MASK | (shift ? InputEvent.SHIFT_DOWN_MASK : 0);
        return new MouseEvent(panel, id, 0, modifiers, point.x, point.y, 1, false, MouseEvent.BUTTON1);
    }

    private void click(Point point, boolean shift) {
        for (MouseListener l : panel.getMouseListeners()) l.mousePressed(event(MouseEvent.MOUSE_PRESSED, point, shift));
        for (MouseListener l : panel.getMouseListeners()) l.mouseReleased(event(MouseEvent.MOUSE_RELEASED, point, shift));
    }

    /** The centre of north's box handle shape {@code index}: 0 is the proxy, last the name. */
    private Point boxHandle(int index) {
        NodeTheme.TextStyle style = new NodeTheme().getCurrentTextStyle();
        List<Rectangle2D> shapes = ClusterHandles.boxNames(model, panel.getClusterCollapse(),
                panel.getFontMetrics(style.getFont()), style, NodeLabelMode.NAMES, 1, 0, 0).get(0).shapes();
        Rectangle2D shape = shapes.get(index < 0 ? shapes.size() - 1 : index);
        return new Point((int) shape.getCenterX(), (int) shape.getCenterY());
    }

    private int caret() {
        return document.getEditor().getTextArea().getCaretPosition();
    }

    private static final Point ICON = new Point(120, 100);
    private static final int NORTH_SECTION = MODEL.indexOf("#@cluster = north");

    @Test
    void aClickOnAnIconSelectsItWholeAndGoesToItsFirstMember() {
        collapse(true);

        click(ICON, false);

        assertEquals(Set.of("n1", "n2"), model.getSelectedNodes(), "the icon, over y");
        assertTrue(panel.getClusterCollapse().isCollapsed("north"));
        assertEquals(NORTH_SECTION, caret());
    }

    @Test
    void aClickOnAnIconReplacesTheSelection() {
        collapse(true);
        model.selectNode("x", false);

        click(ICON, false);

        assertEquals(Set.of("n1", "n2"), model.getSelectedNodes());
    }

    @Test
    void aClickOnASelectedIconKeepsTheSelection() {
        collapse(true);
        model.selectNodes(List.of("x", "n1", "n2"));

        click(ICON, false);

        assertEquals(Set.of("x", "n1", "n2"), model.getSelectedNodes(), "kept for a drag");
    }

    @Test
    void shiftClickAddsAnIconThenTakesItAwayInOneChange() {
        collapse(true);
        model.selectNode("x", false);

        click(ICON, true);
        assertEquals(Set.of("x", "n1", "n2"), model.getSelectedNodes());

        click(ICON, true);
        assertEquals(Set.of("x"), model.getSelectedNodes());
        assertTrue(panel.getClusterCollapse().isCollapsed("north"), "never half selected, so not expanded");
    }

    @Test
    void aClickOnABoxNameSelectsTheExpandedClusterAndGoesToItsFirstMember() {
        collapse(false);

        click(boxHandle(-1), false);

        assertEquals(Set.of("n1", "n2"), model.getSelectedNodes());
        assertFalse(panel.getClusterCollapse().isCollapsed("north"));
        assertEquals(NORTH_SECTION, caret());
    }

    @Test
    void aClickOnAProxySelectsTheExpandedCluster() {
        collapse(false);

        click(boxHandle(0), false);

        assertEquals(Set.of("n1", "n2"), model.getSelectedNodes());
    }

    @Test
    void shiftClickOnABoxNameAddsThenTakesAway() {
        collapse(false);
        model.selectNode("x", false);

        click(boxHandle(-1), true);
        assertEquals(Set.of("x", "n1", "n2"), model.getSelectedNodes());

        click(boxHandle(-1), true);
        assertEquals(Set.of("x"), model.getSelectedNodes());
    }

    @Test
    void inHiddenLabelModeABoxHasNoNameToClick() {
        collapse(false);
        Point name = boxHandle(-1);
        panel.setLabelMode(NodeLabelMode.HIDDEN);
        model.selectNode("x", false);

        click(name, false);

        assertEquals(Set.of(), model.getSelectedNodes(), "empty map: the selection is cleared");
    }
}
