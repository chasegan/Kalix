package com.kalix.ide.rendering;

import com.kalix.ide.model.ClusterCollapse;
import com.kalix.ide.model.HydrologicalModel;
import com.kalix.ide.themes.NodeTheme;
import org.junit.jupiter.api.Test;

import java.awt.Canvas;
import java.awt.FontMetrics;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What stands for a cluster, where the map draws it (clusters.md rule 11): an icon's two
 * cards; an expanded box's proxy, when boxes show one, and its name; none of the box's in
 * hidden label mode. At zoom 1 and no pan a screen point is a map point.
 *
 * <p>The model: north (n1, n2) and south (s1, at the same place as n2, so south's box is
 * drawn over north's).</p>
 */
class ClusterHandlesTest {

    private static final String MODEL = """
            #@cluster = north
            [node.n1]
            type = gauge
            loc = 100, 100

            #@cluster = north
            [node.n2]
            type = gauge
            loc = 140, 100

            #@cluster = south
            [node.s1]
            type = gauge
            loc = 140, 100
            """;

    private static final NodeTheme.TextStyle STYLE = new NodeTheme().getCurrentTextStyle();
    private static final FontMetrics FM = new Canvas().getFontMetrics(STYLE.getFont());

    private static HydrologicalModel model() {
        HydrologicalModel model = new HydrologicalModel();
        model.parseFromIniText(MODEL);
        return model;
    }

    private static ClusterCollapse collapsed(boolean collapsed) {
        ClusterCollapse collapse = new ClusterCollapse();
        collapse.setCollapsed(List.of("north", "south"), collapsed);
        return collapse;
    }

    private static List<ClusterHandles.Handle> boxNames(NodeLabelMode mode) {
        return ClusterHandles.boxNames(model(), collapsed(false), FM, STYLE, mode, true, 1, 0, 0);
    }

    @Test
    void anIconIsItsTwoCardsAtItsPoint() {
        List<ClusterHandles.Handle> icons = ClusterHandles.icons(model(), collapsed(true), 1, 0, 0);
        assertEquals("north", icons.get(0).clusterName());
        assertEquals(List.of(ClusterIcon.face(120, 100), ClusterIcon.back(120, 100)), icons.get(0).shapes());
        assertEquals("north", ClusterHandles.at(icons, new Point2D.Double(120, 100)));
    }

    @Test
    void anIconFollowsZoomAndPan() {
        List<ClusterHandles.Handle> icons = ClusterHandles.icons(model(), collapsed(true), 2, 10, 20);
        // north's point (120, 100) is drawn at (2 × 120 + 10, 2 × 100 + 20).
        assertEquals(ClusterIcon.face(250, 220), icons.get(0).shapes().get(0));
    }

    @Test
    void aBoxNameFollowsZoomAndPan() {
        ClusterCollapse expanded = collapsed(false);
        Rectangle2D at1 = ClusterHandles.boxNames(model(), expanded, FM, STYLE, NodeLabelMode.NAMES, true, 1, 0, 0)
                .get(0).shapes().get(0);
        Rectangle2D at2 = ClusterHandles.boxNames(model(), expanded, FM, STYLE, NodeLabelMode.NAMES, true, 2, 10, 20)
                .get(0).shapes().get(0);
        // The members' screen points move from (100..140, 100) to (210..290, 220): the box's
        // top left moves by (2 × 100 + 10 − 100, 2 × 100 + 20 − 100); shapes keep their size.
        assertEquals(at1.getX() + 110, at2.getX(), 1e-9);
        assertEquals(at1.getY() + 120, at2.getY(), 1e-9);
    }

    @Test
    void aBoxHasItsProxyAndItsName() {
        List<Rectangle2D> shapes = boxNames(NodeLabelMode.NAMES).get(0).shapes();
        assertEquals(3, shapes.size(), "the proxy's two cards and the name strip");
    }

    @Test
    void withTheProxySwitchedOffABoxHasOnlyItsName() {
        boolean was = ClusterBox.showsProxy;
        try {
            ClusterBox.showsProxy = false;
            List<Rectangle2D> shapes = boxNames(NodeLabelMode.NAMES).get(0).shapes();
            assertEquals(1, shapes.size());
            Rectangle2D name = shapes.get(0);
            assertTrue(name.getWidth() > ClusterIcon.PROXY_WIDTH, "the name strip, not a proxy card");
        } finally {
            ClusterBox.showsProxy = was;
        }
    }

    @Test
    void withBoxesHiddenThereAreNoBoxNames() {
        assertEquals(0, ClusterHandles.boxNames(model(), collapsed(false), FM, STYLE,
                NodeLabelMode.NAMES, false, 1, 0, 0).size());
    }

    @Test
    void aModeWithoutLabelsHasNoBoxNames() {
        for (NodeLabelMode mode : NodeLabelMode.values()) {
            assertEquals(mode.drawsLabels() ? 2 : 0, boxNames(mode).size(), mode.name());
        }
    }

    @Test
    void ofOverlappingHandlesTheOneDrawnLastIsFound() {
        List<ClusterHandles.Handle> icons = ClusterHandles.icons(model(), collapsed(true), 1, 0, 0);
        // north's icon at (120, 100) and south's at (140, 100) overlap at (130, 100).
        assertEquals("south", ClusterHandles.at(icons, new Point2D.Double(130, 100)));
        assertNull(ClusterHandles.at(icons, new Point2D.Double(0, 0)));
    }
}
