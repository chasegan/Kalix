package com.kalix.ide.rendering;

import com.kalix.ide.model.HydrologicalModel;
import com.kalix.ide.themes.NodeTheme;
import org.junit.jupiter.api.Test;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * The map draws a box under an expanded cluster's members, and the members over it. Like
 * {@code MapRendererLabelOrderTest}, this reads pixels and asserts only "changed or not",
 * never a colour: a node drawn loose and the same node drawn as a member must differ just
 * outside its shape, where only the box can reach, and must not differ at its centre,
 * where its own shape covers the box.
 */
class MapRendererClusterBoxTest {

    private static final int SIZE = 120;
    private static final int X = 60;
    private static final int Y = 60;

    private static String node(boolean member) {
        return (member ? "#@cluster = farm\n" : "")
                + "[node.a]\ntype = storage\nloc = " + X + ", " + Y + "\n";
    }

    private static int pixel(String text, int x, int y) {
        HydrologicalModel model = new HydrologicalModel();
        model.parseFromIniText(text);
        BufferedImage image = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2d = image.createGraphics();
        new MapRenderer().renderMap(g2d, SIZE, SIZE, 1.0, 0, 0, false, model, new NodeTheme(),
                null, null, 0, 0, false, NodeLabelMode.HIDDEN, null);
        g2d.dispose();
        return image.getRGB(x, y);
    }

    @Test
    void aClustersBoxIsDrawnAroundItsMember() {
        // Inside the box's clearance, outside any shape.
        int x = X + (int) ClusterBox.SHAPE_CLEARANCE;
        assertNotEquals(pixel(node(false), x, Y), pixel(node(true), x, Y));
    }

    @Test
    void aMembersShapeIsDrawnOverItsBox() {
        // Left of centre, clear of the type text drawn inside the shape.
        assertEquals(pixel(node(false), X - 6, Y), pixel(node(true), X - 6, Y));
    }

    @Test
    void nothingIsDrawnBeyondTheBox() {
        int x = X + (int) (ClusterBox.SHAPE_CLEARANCE + ClusterBox.GAP) + 2;
        assertEquals(pixel(node(false), x, Y), pixel(node(true), x, Y));
    }
}
