package com.kalix.ide.rendering;

import com.kalix.ide.themes.NodeTheme;
import org.junit.jupiter.api.Test;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The shape renderer states how far a node's drawing can reach from its centre, so that
 * whatever must stay clear of a node (a cluster's box) takes the figure from the drawing code
 * rather than guessing it. The stated reach is checked against what is actually painted,
 * shape by shape, with the selected border.
 */
class NodeShapeRendererReachTest {

    private static final int CENTRE = 60;

    /** The furthest painted pixel from the centre along either axis, for {@code shape} with {@code border}. */
    private static double painted(NodeTheme.NodeShape shape, BasicStroke border) {
        BufferedImage image = new BufferedImage(2 * CENTRE, 2 * CENTRE, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2d = image.createGraphics();
        new NodeShapeRenderer().renderShape(g2d, shape, CENTRE, CENTRE, Color.BLACK, Color.BLACK, border);
        g2d.dispose();
        double reach = 0;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                if ((image.getRGB(x, y) >>> 24) != 0) {
                    // A painted pixel at x covers [x, x + 1): its far edge is what reaches.
                    reach = Math.max(reach, Math.max(Math.abs(x + 0.5 - CENTRE), Math.abs(y + 0.5 - CENTRE)) + 0.5);
                }
            }
        }
        return reach;
    }

    @Test
    void everyShapesStatedReachCoversWhatItPaintsWithoutMuchSlack() {
        for (NodeTheme.NodeShape shape : NodeTheme.NodeShape.values()) {
            double stated = NodeShapeRenderer.reach(shape, NodeShapeRenderer.SELECTED_BORDER);
            double painted = painted(shape, NodeShapeRenderer.SELECTED_BORDER);
            // Pixels are whole, geometry isn't, and the renderer draws some mitres a little
            // shorter than the geometry (the arrow's wings by about a pixel), so the stated
            // reach may exceed the paint by up to two pixels and never fall short of it.
            assertTrue(painted <= stated + 1.0, shape + " paints past its stated reach: " + painted + " > " + stated);
            assertTrue(painted >= stated - 2.0, shape + "'s stated reach is slack: " + stated + " for " + painted);
        }
    }

    @Test
    void theReachIsTheWidestShapesAndNothingPaintsPastIt() {
        double widest = 0;
        for (NodeTheme.NodeShape shape : NodeTheme.NodeShape.values()) {
            double painted = painted(shape, NodeShapeRenderer.SELECTED_BORDER);
            assertTrue(painted <= NodeShapeRenderer.REACH + 1.0, shape + " paints past the stated reach: " + painted);
            widest = Math.max(widest, painted);
        }
        assertEquals(widest, NodeShapeRenderer.REACH, 1.0, "the reach has no slack");
    }

    @Test
    void theWaterDropsMitredTipReachesFurthest() {
        // The drop's tip is at 16 px; its selected border, mitred at the tip's narrow angle,
        // carries on well past it. This is the figure a cluster's box has to allow for; it is
        // pinned so that a change to a shape or a border is noticed.
        NodeTheme.NodeShape furthest = null;
        double reach = 0;
        for (NodeTheme.NodeShape shape : NodeTheme.NodeShape.values()) {
            double r = NodeShapeRenderer.reach(shape, NodeShapeRenderer.SELECTED_BORDER);
            if (r > reach) {
                reach = r;
                furthest = shape;
            }
        }
        assertEquals(NodeTheme.NodeShape.WATER_DROP, furthest);
        assertEquals(20.7, NodeShapeRenderer.REACH, 0.2);
    }

    @Test
    void anUnselectedBorderReachesLess() {
        BasicStroke thin = new BasicStroke(1);
        assertTrue(NodeShapeRenderer.reach(thin) < NodeShapeRenderer.REACH);
    }
}
