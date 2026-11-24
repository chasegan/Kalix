package com.kalix.ide.interaction;

import com.kalix.ide.MapPanel;
import com.kalix.ide.document.ModelDocument;
import com.kalix.ide.model.HydrologicalModel;
import com.kalix.ide.rendering.MapRenderer;
import org.junit.jupiter.api.Test;

import java.awt.Point;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * A link dragged onto a collapsed cluster's icon targets nothing, not a node hidden under the
 * icon (clusters.md rule 12: no link to an icon). Through the manager, since writing the link
 * needs the IDE's frame; at the starting zoom and pan a screen point is a map point.
 *
 * <p>The model: loose z at (0, 200), the source; loose y at (120, 100); north's n1 and n2 at
 * (100, 100) and (140, 100), so its icon is at (120, 100), over y.</p>
 */
class LinkDragManagerIconTest {

    private static final String MODEL = """
            [node.z]
            type = gauge
            loc = 0, 200

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

    private static final Point ON_Y = new Point(120, 100);

    private final ModelDocument document = new ModelDocument();
    private final HydrologicalModel model = document.getModel();
    private final MapPanel panel = document.getMapPanel();
    private final LinkDragManager links = new LinkDragManager(panel, model, document.getEditor(), new MapRenderer());

    LinkDragManagerIconTest() {
        model.parseFromIniText(MODEL);
    }

    private String targetAt(Point point) {
        links.startDrag("z", new Point(0, 200));
        links.updateDrag(point);
        return links.dragTargetForTests();
    }

    @Test
    void aLinkDraggedOntoAnIconTargetsNothing() {
        panel.getClusterCollapse().setCollapsed(List.of("north"), true);

        assertNull(targetAt(ON_Y), "y is hidden under the icon");
    }

    @Test
    void withTheClusterExpandedTheSamePointTargetsTheNode() {
        panel.getClusterCollapse().setCollapsed(List.of("north"), false);

        assertEquals("y", targetAt(ON_Y));
    }
}
