package com.kalix.ide;

import com.kalix.ide.document.ModelDocument;
import com.kalix.ide.model.HydrologicalModel;
import com.kalix.ide.rendering.ClusterHandles;
import com.kalix.ide.rendering.NodeLabelMode;
import com.kalix.ide.themes.NodeTheme;
import org.junit.jupiter.api.Test;

import javax.swing.SwingUtilities;

import java.awt.Cursor;
import java.awt.Point;
import java.awt.event.InputEvent;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.awt.geom.Rectangle2D;
import java.lang.reflect.InvocationTargetException;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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

    /**
     * Runs a test on the event thread. The editor queues a reparse there, and after it a zoom
     * to fit; run from the test thread, they could land mid-test and move every point.
     */
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
        onEdt(() -> {
            collapse(true);

            click(ICON, false);

            assertEquals(Set.of("n1", "n2"), model.getSelectedNodes(), "the icon, over y");
            assertTrue(panel.getClusterCollapse().isCollapsed("north"));
            assertEquals(NORTH_SECTION, caret());
        });
    }

    @Test
    void aClickOnAnIconReplacesTheSelection() {
        onEdt(() -> {
            collapse(true);
            model.selectNode("x", false);

            click(ICON, false);

            assertEquals(Set.of("n1", "n2"), model.getSelectedNodes());
        });
    }

    @Test
    void aClickOnASelectedIconKeepsTheSelection() {
        onEdt(() -> {
            collapse(true);
            model.selectNodes(List.of("x", "n1", "n2"));

            click(ICON, false);

            assertEquals(Set.of("x", "n1", "n2"), model.getSelectedNodes(), "kept for a drag");
        });
    }

    @Test
    void shiftClickAddsAnIconThenTakesItAwayInOneChange() {
        onEdt(() -> {
            collapse(true);
            model.selectNode("x", false);

            click(ICON, true);
            assertEquals(Set.of("x", "n1", "n2"), model.getSelectedNodes());

            click(ICON, true);
            assertEquals(Set.of("x"), model.getSelectedNodes());
            assertTrue(panel.getClusterCollapse().isCollapsed("north"), "never half selected, so not expanded");
        });
    }

    @Test
    void aClickOnABoxNameSelectsTheExpandedClusterAndGoesToItsFirstMember() {
        onEdt(() -> {
            collapse(false);

            click(boxHandle(-1), false);

            assertEquals(Set.of("n1", "n2"), model.getSelectedNodes());
            assertFalse(panel.getClusterCollapse().isCollapsed("north"));
            assertEquals(NORTH_SECTION, caret());
        });
    }

    @Test
    void aClickOnAProxySelectsTheExpandedCluster() {
        onEdt(() -> {
            collapse(false);

            click(boxHandle(0), false);

            assertEquals(Set.of("n1", "n2"), model.getSelectedNodes());
        });
    }

    @Test
    void shiftClickOnABoxNameAddsThenTakesAway() {
        onEdt(() -> {
            collapse(false);
            model.selectNode("x", false);

            click(boxHandle(-1), true);
            assertEquals(Set.of("x", "n1", "n2"), model.getSelectedNodes());

            click(boxHandle(-1), true);
            assertEquals(Set.of("x"), model.getSelectedNodes());
        });
    }

    private void moveTo(Point point) {
        MouseEvent move = new MouseEvent(panel, MouseEvent.MOUSE_MOVED, 0, 0, point.x, point.y, 0, false);
        for (java.awt.event.MouseMotionListener l : panel.getMouseMotionListeners()) l.mouseMoved(move);
    }

    @Test
    void inHiddenLabelModeTheIconUnderTheMouseIsHovered() {
        onEdt(() -> {
            collapse(true);
            panel.setLabelMode(NodeLabelMode.HIDDEN);

            moveTo(ICON);
            assertEquals("north", panel.getHoveredClusterName(), "the icon, over y");

            moveTo(new Point(0, 100));
            assertEquals(null, panel.getHoveredClusterName(), "x");
        });
    }

    @Test
    void noLinkRingIsOfferedOverAnIcon() {
        onEdt(() -> {
            // 11 px below y's centre: in its link ring, clear of every node, on north's icon.
            Point nearY = new Point(120, 111);
            Cursor crosshair = Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR);

            collapse(false);
            moveTo(nearY);
            assertEquals(crosshair, panel.getCursor(), "y's ring, with no icon there");

            collapse(true);
            moveTo(nearY);
            assertNotEquals(crosshair, panel.getCursor(), "the icon is over the ring");
        });
    }

    @Test
    void aNodeDrawnOverABoxNameIsClickedFirst() {
        onEdt(() -> {
            collapse(false);
            Point name = boxHandle(-1);
            String withZ = MODEL + "\n[node.z]\ntype = gauge\nloc = " + name.x + ", " + name.y + "\n";
            model.parseFromIniText(withZ);

            click(name, false);

            assertEquals(Set.of("z"), model.getSelectedNodes());
        });
    }

    @Test
    void inNamesModeNoIconIsHovered() {
        onEdt(() -> {
            collapse(true);

            moveTo(ICON);

            assertEquals(null, panel.getHoveredClusterName(), "its name is always drawn");
        });
    }

    @Test
    void inHiddenLabelModeABoxHasNoNameToClick() {
        onEdt(() -> {
            collapse(false);
            Point name = boxHandle(-1);
            panel.setLabelMode(NodeLabelMode.HIDDEN);
            model.selectNode("x", false);

            click(name, false);

            assertEquals(Set.of(), model.getSelectedNodes(), "empty map: the selection is cleared");
        });
    }
}
