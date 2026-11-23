package com.kalix.ide.rendering;

import com.kalix.ide.model.ClusterCollapse;
import com.kalix.ide.model.HydrologicalModel;
import com.kalix.ide.themes.NodeTheme;
import org.junit.jupiter.api.Test;

import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The map draws a box under an expanded cluster's members, and the members over it. Like
 * {@code MapRendererLabelOrderTest}, this reads pixels and asserts mostly "changed or not":
 * a node drawn loose and the same node drawn as a member must differ just outside its shape,
 * where only the box can reach, and must not differ at its centre, where its own shape covers
 * the box. The proxy's tests look for the cards' face colour, which nothing else uses.
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
                null, null, 0, 0, false, NodeLabelMode.HIDDEN, null, AllExpanded.of(model));
        g2d.dispose();
        return image.getRGB(x, y);
    }

    @Test
    void aClustersBoxIsDrawnAroundItsMember() {
        // Inside the box's clearance, outside any shape.
        int x = X + (int) ClusterBox.SHAPE_CLEARANCE;
        assertNotEquals(pixel(node(false), x, Y), pixel(node(true), x, Y));
    }

    /** The pixel on the top edge of the face of a collapsed cluster of one at (X, Y). */
    private static int iconEdge(boolean selected) {
        HydrologicalModel model = new HydrologicalModel();
        model.parseFromIniText(node(true));
        if (selected) {
            model.selectNode("a", false);
        }
        BufferedImage image = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2d = image.createGraphics();
        // A new ClusterCollapse holds every cluster collapsed.
        new MapRenderer().renderMap(g2d, SIZE, SIZE, 1.0, 0, 0, false, model, new NodeTheme(),
                null, null, 0, 0, false, NodeLabelMode.HIDDEN, null, new ClusterCollapse());
        g2d.dispose();
        Rectangle2D face = ClusterIcon.face(X, Y);
        return image.getRGB((int) face.getCenterX(), (int) face.getY());
    }

    @Test
    void aSelectedIconsFaceHasTheSelectedBorder() {
        assertNotEquals(iconEdge(false), iconEdge(true));
    }

    @Test
    void aMembersShapeIsDrawnOverItsBox() {
        // Left of centre, clear of the type text drawn inside the shape.
        assertEquals(pixel(node(false), X - 6, Y), pixel(node(true), X - 6, Y));
    }

    /**
     * A one-letter cluster around a node with a long name, so the box is wider than the
     * box name's strip and its top band has room beside the strip.
     */
    private static final String NAMED = "#@cluster = f\n[node.a_long_node_name]\ntype = storage\nloc = 150, 100\n";

    /** The image of {@link #NAMED} in {@code mode}, and the box and name strip it is drawn with. */
    private record Drawn(BufferedImage image, Rectangle2D box, Rectangle strip, Rectangle2D proxy) {
        int at(int x, int y) {
            return image.getRGB(x, y);
        }
    }

    private static Drawn drawNamed(NodeLabelMode mode) {
        HydrologicalModel model = new HydrologicalModel();
        model.parseFromIniText(NAMED);
        BufferedImage image = new BufferedImage(300, 200, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2d = image.createGraphics();
        NodeTheme theme = new NodeTheme();
        new MapRenderer().renderMap(g2d, 300, 200, 1.0, 0, 0, false, model, theme,
                null, null, 0, 0, false, mode, null, AllExpanded.of(model));
        // The geometry as the renderer computes it, from the same font.
        FontMetrics fm = g2d.getFontMetrics(theme.getCurrentTextStyle().getFont());
        ClusterBox.TextMetrics metrics = MapRenderer.textMetrics(fm, theme.getCurrentTextStyle());
        Rectangle2D box = MapRenderer.clusterBox(MapLayers.of(model, AllExpanded.of(model)).get(0), fm, metrics, mode, 1.0, 0, 0);
        Rectangle strip = ClusterBox.nameStrip(box.getX(), box.getY(), fm.stringWidth("f"), metrics);
        Rectangle2D proxy = ClusterBox.proxyFace(box.getX(), box.getY(), metrics);
        g2d.dispose();
        return new Drawn(image, box, strip, proxy);
    }

    /** A point in the top band, beside the name strip, where only the box is drawn. */
    private static int besideTheStrip(Drawn d) {
        int x = (int) (d.box().getMaxX() - ClusterBox.GAP - 2);
        assertTrue(x > d.strip().getMaxX(), "the box is too narrow to probe beside the strip");
        return d.at(x, d.strip().y + 1);
    }

    @Test
    void theBoxNameIsDrawnWhenLabelsAre() {
        Drawn d = drawNamed(NodeLabelMode.NAMES);
        // Inside the strip's padding, clear of the letter itself.
        assertNotEquals(besideTheStrip(d), d.at(d.strip().x + 1, d.strip().y + 1));
    }

    @Test
    void theBoxNameIsNotDrawnInHiddenMode() {
        Drawn d = drawNamed(NodeLabelMode.HIDDEN);
        assertEquals(besideTheStrip(d), d.at(d.strip().x + 1, d.strip().y + 1));
    }

    /** A point just inside the proxy's front card, clear of its border and its count. */
    private static int inTheProxy(Drawn d) {
        return d.at((int) d.proxy().getX() + 3, (int) d.proxy().getY() + 3);
    }

    @Test
    void theProxyIsDrawnBeforeTheNameWhenLabelsAre() {
        Drawn d = drawNamed(NodeLabelMode.NAMES);
        assertEquals(ClusterIcon.FACE.getRGB(), inTheProxy(d));
        assertTrue(d.proxy().getMaxX() < d.strip().x, "the proxy comes before the name");
    }

    @Test
    void theProxyShowsItsCount() {
        Drawn d = drawNamed(NodeLabelMode.NAMES);
        Rectangle2D proxy = d.proxy();
        int marks = 0;
        // Inside the face, clear of its border: anything not the face colour is the count.
        for (int x = (int) proxy.getX() + 3; x < proxy.getMaxX() - 3; x++) {
            for (int y = (int) proxy.getY() + 3; y < proxy.getMaxY() - 3; y++) {
                if (d.at(x, y) != ClusterIcon.FACE.getRGB()) {
                    marks++;
                }
            }
        }
        assertTrue(marks > 0, "no count on the proxy");
    }

    @Test
    void theProxyIsNotDrawnInHiddenMode() {
        Drawn d = drawNamed(NodeLabelMode.HIDDEN);
        assertEquals(besideTheStrip(d), inTheProxy(d));
    }

    @Test
    void theProxyIsNotDrawnWhenSwitchedOff() {
        assertTrue(facePixels(drawNamed(NodeLabelMode.NAMES)) > 0, "the probe finds the proxy when on");
        ClusterBox.showsProxy = false;
        try {
            Drawn off = drawNamed(NodeLabelMode.NAMES);
            assertEquals(0, facePixels(off), "no card is drawn anywhere");
            assertEquals(off.box().getX() + ClusterBox.GAP, off.strip().getX(), 1.0, "the name takes the corner");
        } finally {
            ClusterBox.showsProxy = true;
        }
    }

    /** How many pixels have the cards' face colour: only a proxy has it, every cluster being expanded. */
    private static int facePixels(Drawn d) {
        int count = 0;
        for (int x = 0; x < d.image().getWidth(); x++) {
            for (int y = 0; y < d.image().getHeight(); y++) {
                if (d.at(x, y) == ClusterIcon.FACE.getRGB()) {
                    count++;
                }
            }
        }
        return count;
    }

    @Test
    void nothingIsDrawnBeyondTheBox() {
        int x = X + (int) (ClusterBox.SHAPE_CLEARANCE + ClusterBox.GAP) + 2;
        assertEquals(pixel(node(false), x, Y), pixel(node(true), x, Y));
    }
}
