package com.kalix.ide;

import com.kalix.ide.document.ModelDocument;
import com.kalix.ide.interaction.MapInteractionManager;
import com.kalix.ide.model.HydrologicalModel;
import com.kalix.ide.model.ModelNode;
import com.kalix.ide.rendering.ClusterHandles;
import com.kalix.ide.rendering.NodeLabelMode;
import com.kalix.ide.themes.NodeTheme;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.junit.jupiter.api.Test;

import javax.swing.SwingUtilities;

import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.event.InputEvent;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.awt.event.MouseMotionListener;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.lang.reflect.InvocationTargetException;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Dragging, rotating and deleting a cluster from its icon, box name or proxy (clusters.md
 * rules 11, 14, 17): a drag moves every selected node as one edit, shows ghosts of the
 * hidden members, and never sends the editor to the cluster; a collapsed cluster counts as
 * one thing towards rotation. Through the panel's own mouse listeners; at the starting zoom
 * and pan a screen point is a map point.
 *
 * <p>The model: loose x at (0, 100), linked to n1; loose y at (120, 100); north's n1 and n2
 * at (100, 100) and (140, 100), so its icon is at (120, 100), over y.</p>
 */
class MapPanelClusterDragTest {

    private static final String MODEL = """
            [node.x]
            type = gauge
            loc = 0, 100
            ds_1 = n1

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

    private static final Point ICON = new Point(120, 100);

    private final ModelDocument document = new ModelDocument();
    private final HydrologicalModel model = document.getModel();
    private final MapPanel panel = document.getMapPanel();
    private final RSyntaxTextArea text = document.getEditor().getTextArea();

    MapPanelClusterDragTest() {
        text.setText(MODEL);
        text.discardAllEdits();
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

    private MouseEvent event(int id, Point point) {
        return new MouseEvent(panel, id, 0, InputEvent.BUTTON1_DOWN_MASK, point.x, point.y, 1, false, MouseEvent.BUTTON1);
    }

    private void press(Point point) {
        for (MouseListener l : panel.getMouseListeners()) l.mousePressed(event(MouseEvent.MOUSE_PRESSED, point));
    }

    private void moveTo(Point point) {
        for (MouseMotionListener l : panel.getMouseMotionListeners()) l.mouseDragged(event(MouseEvent.MOUSE_DRAGGED, point));
    }

    private void release(Point point) {
        for (MouseListener l : panel.getMouseListeners()) l.mouseReleased(event(MouseEvent.MOUSE_RELEASED, point));
    }

    private void drag(Point from, Point to) {
        press(from);
        moveTo(to);
        release(to);
    }

    private static Point offset(Point p, int dx, int dy) {
        return new Point(p.x + dx, p.y + dy);
    }

    /** The centre of north's box name, the last of its box handle shapes. */
    private Point boxName() {
        NodeTheme.TextStyle style = new NodeTheme().getCurrentTextStyle();
        List<Rectangle2D> shapes = ClusterHandles.boxNames(model, panel.getClusterCollapse(),
                panel.getFontMetrics(style.getFont()), style, NodeLabelMode.NAMES, 1, 0, 0).get(0).shapes();
        Rectangle2D shape = shapes.get(shapes.size() - 1);
        return new Point((int) shape.getCenterX(), (int) shape.getCenterY());
    }

    private double x(String node) {
        return model.getNode(node).getX();
    }

    private Set<String> ghostNames() {
        return Set.copyOf(panel.ghostNodes().stream().map(ModelNode::getName).toList());
    }

    // --- Drag ---

    @Test
    void aDragFromAnIconMovesEveryMemberAsOneEdit() {
        onEdt(() -> {
            collapse(true);

            drag(ICON, offset(ICON, 30, 0));

            assertEquals(130, x("n1"), 1e-9);
            assertEquals(170, x("n2"), 1e-9);
            assertEquals(120, x("y"), 1e-9, "y under the icon stays put");
            assertTrue(text.getText().contains("loc = 130"), text.getText());
            assertTrue(text.getText().contains("loc = 170"), text.getText());
            text.undoLastAction();
            assertEquals(MODEL, text.getText(), "one undo puts both back");
        });
    }

    @Test
    void aDragFromASelectedIconTakesTheRestOfTheSelectionAlong() {
        onEdt(() -> {
            collapse(true);
            model.selectNodes(List.of("x", "n1", "n2"));

            drag(ICON, offset(ICON, 0, 20));

            assertEquals(120, model.getNode("x").getY(), 1e-9);
            assertEquals(120, model.getNode("n1").getY(), 1e-9);
            assertEquals(120, model.getNode("n2").getY(), 1e-9);
        });
    }

    @Test
    void aDragFromABoxNameMovesTheExpandedCluster() {
        onEdt(() -> {
            collapse(false);
            Point name = boxName();

            drag(name, offset(name, -20, 0));

            assertEquals(80, x("n1"), 1e-9);
            assertEquals(120, x("n2"), 1e-9);
            assertEquals(0, x("x"), 1e-9);
        });
    }

    @Test
    void aReleaseAfterADragFromAClusterLeavesTheEditorWhereItWas() {
        onEdt(() -> {
            collapse(true);
            text.setCaretPosition(0);

            drag(ICON, offset(ICON, 30, 0));

            assertEquals(0, text.getCaretPosition());
        });
    }

    // --- Ghosts ---

    @Test
    void aDraggedCollapsedClusterShowsItsMembersAsGhostsUntilTheRelease() {
        onEdt(() -> {
            collapse(true);
            model.selectNodes(List.of("x", "n1", "n2"));

            press(ICON);
            moveTo(offset(ICON, 30, 0));
            assertEquals(Set.of("n1", "n2"), ghostNames(), "hidden members only, not x");

            release(offset(ICON, 30, 0));
            assertEquals(Set.of(), ghostNames());
        });
    }

    @Test
    void thePanelPaintsTheGhosts() {
        onEdt(() -> {
            collapse(true);
            panel.setSize(300, 250); // the first size applies the editor's pending zoom to fit
            Point icon = new Point((int) panel.toScreenX(120), (int) panel.toScreenY(100));
            Point to = offset(icon, 0, 60);

            press(icon);
            moveTo(to);
            BufferedImage during = paint();
            release(to);
            BufferedImage after = paint();

            // Same positions, same icon; only the ghosts differ.
            assertTrue(differ(during, after), "the drag painted no ghosts");
        });
    }

    private BufferedImage paint() {
        BufferedImage image = new BufferedImage(panel.getWidth(), panel.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        panel.paint(g);
        g.dispose();
        return image;
    }

    private static boolean differ(BufferedImage a, BufferedImage b) {
        for (int y = 0; y < a.getHeight(); y++) {
            for (int x = 0; x < a.getWidth(); x++) {
                if (a.getRGB(x, y) != b.getRGB(x, y)) {
                    return true;
                }
            }
        }
        return false;
    }

    @Test
    void anExpandedClusterHasNoGhosts() {
        onEdt(() -> {
            collapse(false);
            Point name = boxName();

            press(name);
            moveTo(offset(name, 30, 0));

            assertEquals(Set.of(), ghostNames(), "its members are visible");
        });
    }

    @Test
    void aRotatedCollapsedClusterShowsGhosts() {
        onEdt(() -> {
            collapse(true);
            model.selectNodes(List.of("x", "n1", "n2"));

            rotation().startDrag(new Point(0, 0), true);
            rotation().updateDrag(new Point(10, 40));

            assertEquals(Set.of("n1", "n2"), ghostNames());
        });
    }

    // --- Rotate (through the interaction manager: the panel's rotate cursor needs a display) ---

    private MapInteractionManager rotation() {
        return panel.interactionManagerForTests();
    }

    private void rotate(Point from, Point to) {
        rotation().startDrag(from, true);
        rotation().updateDrag(to);
        rotation().endDrag(to);
    }

    @Test
    void aCollapsedClusterAloneDoesNotRotate() {
        onEdt(() -> {
            collapse(true);
            model.selectNodes(List.of("n1", "n2"));

            assertFalse(rotation().canStartRotation(), "a collapsed cluster counts as one");
            rotate(new Point(0, 0), new Point(10, 40));
            assertEquals(100, x("n1"), 1e-9);
            assertEquals(140, x("n2"), 1e-9);
        });
    }

    @Test
    void anExpandedClusterOfTwoRotatesAboutItsMembers() {
        onEdt(() -> {
            collapse(false);
            model.selectNodes(List.of("n1", "n2"));

            assertTrue(rotation().canStartRotation());
            rotate(new Point(200, 100), new Point(120, 180));
            assertEquals(120, x("n1"), 1e-6, "a quarter turn about (120, 100)");
            assertEquals(120, x("n2"), 1e-6);
        });
    }

    @Test
    void aCollapsedClusterAndANodeRotate() {
        onEdt(() -> {
            collapse(true);
            model.selectNodes(List.of("x", "n1", "n2"));

            assertTrue(rotation().canStartRotation(), "two things selected");
        });
    }

    // --- Delete ---

    @Test
    void deleteOnASelectedCollapsedClusterRemovesItsMembersAndTheirLinksAsOneEdit() {
        onEdt(() -> {
            collapse(true);
            press(ICON);
            release(ICON);

            panel.getActionMap().get("map.delete").actionPerformed(null);

            String after = text.getText();
            assertFalse(after.contains("[node.n1]"), after);
            assertFalse(after.contains("[node.n2]"), after);
            assertFalse(after.contains("ds_1 = n1"), "x's link to n1 goes too");
            assertTrue(after.contains("[node.x]") && after.contains("[node.y]"), after);
            text.undoLastAction();
            assertEquals(MODEL, text.getText());
        });
    }
}
