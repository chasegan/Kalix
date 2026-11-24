package com.kalix.ide.rendering;

import com.kalix.ide.model.ClusterCollapse;
import com.kalix.ide.model.HydrologicalModel;
import com.kalix.ide.themes.NodeTheme;
import org.junit.jupiter.api.Test;

import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the map draws for a collapsed cluster: an icon at its members' centroid in place of
 * the members, links to the icon, and the icon's name only where labels are drawn. Also
 * that the label under the mouse is drawn over everything. Like the other renderer tests,
 * this reads pixels and asserts only "changed or not", never a colour.
 *
 * <p>The cluster {@code farm} has members at (20, 60) and (100, 60), so its centroid is
 * (60, 60), where no node is.</p>
 */
class MapRendererCollapseTest {

    private static final int SIZE = 200;

    private static final String FARM = "#@cluster = farm\n[node.f1]\ntype = storage\nloc = 20, 60\n\n"
            + "#@cluster = farm\n[node.f2]\ntype = storage\nloc = 100, 60\n\n";
    private static final String LOOSE = FARM.replace("#@cluster = farm\n", "");
    /** A loose node below the centroid, linked to f1. */
    private static final String LINKED = "[node.l]\ntype = storage\nloc = 60, 180\nds_1 = f1\n\n";

    private static BufferedImage draw(String text, boolean collapsed, NodeLabelMode mode, String hovered) {
        return draw(text, collapsed, mode, hovered, null);
    }

    private static BufferedImage draw(String text, boolean collapsed, NodeLabelMode mode, String hovered,
                                      String hoveredCluster) {
        HydrologicalModel model = new HydrologicalModel();
        model.parseFromIniText(text);
        ClusterCollapse collapse = collapsed ? new ClusterCollapse() : AllExpanded.of(model);
        BufferedImage image = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2d = image.createGraphics();
        new MapRenderer().renderMap(g2d, SIZE, SIZE, 1.0, 0, 0, false, true, model, new NodeTheme(),
                null, null, 0, 0, false, mode, hovered, hoveredCluster, collapse);
        g2d.dispose();
        return image;
    }

    private static int pixel(String text, boolean collapsed, int x, int y) {
        return draw(text, collapsed, NodeLabelMode.HIDDEN, null).getRGB(x, y);
    }

    @Test
    void aCollapsedClusterIsDrawnAsAnIconAtItsCentroid() {
        // Inside the face, clear of the count at its centre.
        assertNotEquals(pixel(LOOSE, false, 48, 52), pixel(FARM, true, 48, 52));
    }

    @Test
    void theMembersOfACollapsedClusterAreNotDrawn() {
        int empty = pixel("", false, 20, 60);
        assertEquals(empty, pixel(FARM, true, 20, 60));
        assertNotEquals(empty, pixel(FARM, false, 20, 60), "an expanded member is drawn there");
    }

    @Test
    void aLinkToAHiddenMemberIsDrawnToTheIcon() {
        // On the straight line from l (60, 180) up to the icon (60, 60); the link to f1
        // itself runs well left of it.
        int noLink = pixel(LINKED.replace("ds_1 = f1\n", "") + FARM, true, 60, 140);
        assertNotEquals(noLink, pixel(LINKED + FARM, true, 60, 140));
        assertEquals(pixel(LINKED.replace("ds_1 = f1\n", "") + FARM, false, 60, 140),
                pixel(LINKED + FARM, false, 60, 140), "expanded, the link goes to f1");
    }

    /** The point inside the left padding of the icon's name strip. */
    private static int[] iconNameProbe() {
        BufferedImage scratch = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2d = scratch.createGraphics();
        NodeTheme.TextStyle style = new NodeTheme().getCurrentTextStyle();
        FontMetrics fm = g2d.getFontMetrics(style.getFont());
        Rectangle strip = ClusterBox.labelStrip(60, 60 + ClusterIcon.LABEL_DROP, 0, fm.stringWidth("farm"),
                MapRenderer.textMetrics(fm, style));
        g2d.dispose();
        return new int[] {strip.x + 1, strip.y + strip.height - 2};
    }

    @Test
    void anIconsNameIsDrawnWhereLabelsAreAndNotInHiddenMode() {
        int[] p = iconNameProbe();
        int hidden = draw(FARM, true, NodeLabelMode.HIDDEN, null).getRGB(p[0], p[1]);
        assertEquals(draw("", false, NodeLabelMode.HIDDEN, null).getRGB(p[0], p[1]), hidden,
                "nothing is drawn there in hidden mode");
        assertNotEquals(hidden, draw(FARM, true, NodeLabelMode.NAMES, null).getRGB(p[0], p[1]));
    }

    @Test
    void theLabelUnderTheMouseIsDrawnOverALaterLayersShape() {
        // Loose a's label lies over the shape of b, a member of an expanded cluster, whose
        // layer is drawn after the loose one.
        String text = "[node.a]\ntype = storage\nloc = 60, 30\n\n"
                + "#@cluster = farm\n[node.b]\ntype = storage\nloc = 60, 58\n\n";
        BufferedImage scratch = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2d = scratch.createGraphics();
        NodeTheme.TextStyle style = new NodeTheme().getCurrentTextStyle();
        FontMetrics fm = g2d.getFontMetrics(style.getFont());
        Rectangle strip = ClusterBox.labelStrip(60, 30, 0, fm.stringWidth("a"), MapRenderer.textMetrics(fm, style));
        g2d.dispose();
        int x = strip.x + 1;
        int y = strip.y + strip.height - 2;
        assertTrue(Math.hypot(x - 60, y - 58) < 8, "the probe must lie inside b's shape");

        int unhovered = draw(text, false, NodeLabelMode.HIDDEN, null).getRGB(x, y);
        assertNotEquals(unhovered, draw(text, false, NodeLabelMode.HIDDEN, "a").getRGB(x, y));
    }

    /** Whether two images have every pixel the same. */
    private static boolean samePixels(BufferedImage a, BufferedImage b) {
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                if (a.getRGB(x, y) != b.getRGB(x, y)) {
                    return false;
                }
            }
        }
        return true;
    }

    @Test
    void inHiddenModeAHoveredIconShowsItsNameAsInNamesMode() {
        BufferedImage named = draw(FARM, true, NodeLabelMode.NAMES, null);
        assertTrue(samePixels(named, draw(FARM, true, NodeLabelMode.HIDDEN, null, "farm")));
        assertFalse(samePixels(named, draw(FARM, true, NodeLabelMode.HIDDEN, null, null)), "not hovered");
    }
}
