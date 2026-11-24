package com.kalix.ide.rendering;

import com.kalix.ide.model.HydrologicalModel;
import com.kalix.ide.themes.NodeTheme;
import org.junit.jupiter.api.Test;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * A node's label is drawn over the shape of another node in the same layer, never under
 * it (#495). This reads what is actually drawn, a pixel's colour, because the rule is
 * about what ends up on top: a test of the order of drawing calls would still pass if
 * a later change drew something else over the label.
 *
 * <p>Each check sets up a collision for certain: the upper node's label runs across the
 * lower node's shape. One pixel lies inside both. Drawn alone, the lower node gives that
 * pixel its own colour; drawn with the upper node, the label's translucent background
 * (or its text) must change it, which happens only if the label is on top. A guard draws
 * both nodes with labels hidden and expects the lower node's colour: it proves the label
 * is the only thing that can change the pixel, so the test can't pass on the upper
 * node's shape if node sizes or label offsets change. Only "changed or not" is asserted,
 * never a colour, so fonts and anti-aliasing on each platform don't matter.</p>
 *
 * <p>Both orders of the two names are drawn: the model's node order is a hash order, and
 * before the fix the outcome depended on which node came first.</p>
 */
class MapRendererLabelOrderTest {

    private static final int SIZE = 200;
    /** The upper node's centre; its label's baseline is 25 px below (half a node, plus the offset). */
    private static final int UPPER_X = 100;
    private static final int UPPER_Y = 100;
    /** The lower node sits on the upper node's label. */
    private static final int LOWER_Y = UPPER_Y + 20;

    private static String section(String name, int x, int y) {
        return "[node." + name + "]\ntype = storage\nloc = " + x + ", " + y + "\n\n";
    }

    /** The colour of a pixel inside the lower node and inside the upper node's label. */
    private static int pixel(String text, NodeLabelMode labelMode) {
        HydrologicalModel model = new HydrologicalModel();
        model.parseFromIniText(text);
        BufferedImage image = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2d = image.createGraphics();
        new MapRenderer().renderMap(g2d, SIZE, SIZE, 1.0, 0, 0, false, true, model, new NodeTheme(),
                null, null, 0, 0, false, labelMode, null, null, AllExpanded.of(model));
        g2d.dispose();
        return image.getRGB(UPPER_X + 4, LOWER_Y);
    }

    private static void assertUpperLabelOnTop(String upper, String lower) {
        String both = section(upper, UPPER_X, UPPER_Y) + section(lower, UPPER_X, LOWER_Y);
        int lowerAlone = pixel(section(lower, UPPER_X, LOWER_Y), NodeLabelMode.NAMES);
        assertEquals(lowerAlone, pixel(both, NodeLabelMode.HIDDEN),
                "with labels hidden, something other than " + upper + "'s label reaches the pixel");
        assertNotEquals(lowerAlone, pixel(both, NodeLabelMode.NAMES),
                upper + "'s label is under " + lower + "'s shape");
    }

    @Test
    void aLabelIsDrawnOverTheShapeOfANodeBelowIt() {
        assertUpperLabelOnTop("first_node_with_a_long_name", "second_node_with_a_long_name");
    }

    @Test
    void aLabelIsDrawnOverTheShapeOfANodeBelowItWithTheNamesSwapped() {
        assertUpperLabelOnTop("second_node_with_a_long_name", "first_node_with_a_long_name");
    }
}
