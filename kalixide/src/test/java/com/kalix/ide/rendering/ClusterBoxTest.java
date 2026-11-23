package com.kalix.ide.rendering;

import com.kalix.ide.constants.UIConstants;
import com.kalix.ide.themes.NodeTheme;
import com.kalix.ide.model.HydrologicalModel;
import com.kalix.ide.themes.NodeTheme;
import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The box under an expanded cluster (clusters.md, Architecture, "Box bounds"): it keeps
 * selection halos and its rounded corners clear of its contents, covers each member's drawn
 * label with a small gap, is symmetric about its members, holds its name in a band along
 * the top, and isn't widened by labels shown only on hover.
 */
class ClusterBoxTest {

    /** Round numbers, close to a real theme's: an 11 pt font, baseline 25 px below the centre. */
    private static final ClusterBox.TextMetrics METRICS = new ClusterBox.TextMetrics(11, 14, 25);

    private static ClusterBox.Member member(double x, double y, Integer... lineWidths) {
        return new ClusterBox.Member(x, y, List.of(lineWidths));
    }

    private static Rectangle2D box(OptionalInt nameWidth, ClusterBox.Member... members) {
        return ClusterBox.bounds(List.of(members), METRICS, nameWidth);
    }

    private static Rectangle2D box(ClusterBox.Member... members) {
        return box(OptionalInt.empty(), members);
    }

    /** {@code r} grown by {@code by} on every side. */
    private static Rectangle2D grown(Rectangle2D r, double by) {
        return new Rectangle2D.Double(r.getX() - by, r.getY() - by,
                r.getWidth() + 2 * by, r.getHeight() + 2 * by);
    }

    /** The square a member's shape, halo and link ring can reach. */
    private static Rectangle2D shapeReach(ClusterBox.Member m) {
        double c = ClusterBox.SHAPE_CLEARANCE;
        return new Rectangle2D.Double(m.x() - c, m.y() - c, 2 * c, 2 * c);
    }

    private static List<Rectangle2D> strips(ClusterBox.Member m) {
        List<Rectangle2D> strips = new ArrayList<>();
        for (int line = 0; line < m.lineWidths().size(); line++) {
            strips.add(ClusterBox.labelStrip(m.x(), m.y(), line, m.lineWidths().get(line), METRICS));
        }
        return strips;
    }

    private static void assertCentredOn(double x, double y, Rectangle2D box) {
        assertEquals(x, box.getCenterX(), 1e-9, "not centred across");
        assertEquals(y, box.getCenterY(), 1e-9, "not centred down");
    }

    @Test
    void aLoneMemberWithNoLabelGetsItsShapeClearancePlusTheGap() {
        Rectangle2D box = box(member(100, 50));
        double half = ClusterBox.SHAPE_CLEARANCE + ClusterBox.GAP;
        assertEquals(new Rectangle2D.Double(100 - half, 50 - half, 2 * half, 2 * half), box);
    }

    @Test
    void theAgreedGapAndCornerAreFourAndSix() {
        // Agreed before coding (a 4 px gap, 6 px corners), so pinned as numbers, not computed
        // from the constants they check. The 15 px once agreed for shapes was short (the
        // review of #531): the clearance is now the shape renderer's stated reach.
        assertEquals(4, ClusterBox.GAP);
        assertEquals(6, ClusterBox.CORNER_RADIUS);
    }

    @Test
    void theShapeClearanceIsTheWidestOfEveryShapesSelectedBorderAndTheLinkRing() {
        double widest = ClusterBox.LINK_RING_REACH;
        for (NodeTheme.NodeShape shape : NodeTheme.NodeShape.values()) {
            widest = Math.max(widest, NodeShapeRenderer.reach(shape, NodeShapeRenderer.SELECTED_BORDER));
        }
        assertEquals(Math.ceil(widest), ClusterBox.SHAPE_CLEARANCE, 1e-9,
                "covers every shape and the ring to the next whole pixel, with no slack");
        // The hover ring is drawn at half a node plus half its ring width with a 2 px stroke
        // (MapRenderer.renderLinkHandle, private to MapRenderer).
        assertEquals(UIConstants.Map.NODE_SIZE / 2.0 + UIConstants.Map.LINK_HANDLE_RING_PX / 2.0 + 1,
                ClusterBox.LINK_RING_REACH, 1e-9);
    }

    @Test
    void everyShapeAndLabelIsCoveredWithTheGapToSpare() {
        ClusterBox.Member[] members = {
            member(0, 0, 160), member(40, 90, 30, 120), member(-30, 200), member(200, 20, 8)};
        Rectangle2D box = box(members);
        for (ClusterBox.Member m : members) {
            assertTrue(box.contains(grown(shapeReach(m), ClusterBox.GAP)), "shape of " + m);
            for (Rectangle2D strip : strips(m)) {
                assertTrue(box.contains(grown(strip, ClusterBox.GAP)), "label strip of " + m);
            }
        }
    }

    @Test
    void theRoundedCornersCutIntoNothing() {
        // A member in each corner, each with a label, so contents reach every corner.
        ClusterBox.Member[] members = {
            member(0, 0, 90), member(300, 0, 90), member(0, 200, 90), member(300, 200, 90)};
        Rectangle2D box = box(OptionalInt.of(60), members);
        double arc = 2 * ClusterBox.CORNER_RADIUS;
        RoundRectangle2D rounded = new RoundRectangle2D.Double(box.getX(), box.getY(),
                box.getWidth(), box.getHeight(), arc, arc);
        for (ClusterBox.Member m : members) {
            assertTrue(rounded.contains(shapeReach(m)), "a corner cuts the shape of " + m);
            for (Rectangle2D strip : strips(m)) {
                assertTrue(rounded.contains(strip), "a corner cuts the label of " + m);
            }
        }
        Rectangle name = ClusterBox.nameStrip(box.getX(), box.getY(), 60, METRICS);
        assertTrue(rounded.contains(name), "a corner cuts the box name");
        assertTrue(rounded.contains(proxyStack(box)), "a corner cuts the proxy");
    }

    @Test
    void aLongLabelOnOneSideIsMirroredOnTheOther() {
        // The left member's label reaches far left; nothing reaches as far right.
        Rectangle2D box = box(member(0, 0, 300), member(100, 0));
        assertCentredOn(50, 0, box);
        assertTrue(box.getMinX() <= -150 - 2 - ClusterBox.GAP);
    }

    @Test
    void labelsBelowAreMirroredAbove() {
        Rectangle2D box = box(member(0, 0, 40));
        assertCentredOn(0, 0, box);
        Rectangle2D strip = strips(member(0, 0, 40)).get(0);
        assertEquals(strip.getMaxY() + ClusterBox.GAP, box.getMaxY(), 1e-9);
    }

    @Test
    void theBoxIsCentredOnTheMiddleOfItsMembersNotTheirCentroid() {
        // Three members bunched at the left pull the centroid left; the box stays centred
        // on the span of positions.
        Rectangle2D box = box(member(0, 0), member(2, 0), member(4, 0), member(400, 0));
        assertCentredOn(200, 0, box);
    }

    /** The proxy's two cards, front and back, in the band of {@code box}. */
    private static Rectangle2D proxyStack(Rectangle2D box) {
        Rectangle2D face = ClusterBox.proxyFace(box.getX(), box.getY(), METRICS);
        return face.createUnion(ClusterIcon.proxyBack(face.getX(), face.getY()));
    }

    @Test
    void theProxyThenTheNameSitInABandAboveEveryMembersContents() {
        ClusterBox.Member m = member(0, 0, 40);
        Rectangle2D box = box(OptionalInt.of(50), m);
        Rectangle name = ClusterBox.nameStrip(box.getX(), box.getY(), 50, METRICS);
        Rectangle2D proxy = proxyStack(box);

        assertEquals(box.getX() + ClusterBox.GAP, proxy.getX(), 1e-9);
        assertEquals(box.getY() + ClusterBox.GAP, Math.min(proxy.getY(), name.getY()), 1.0);
        assertEquals(proxy.getMaxX() + ClusterBox.GAP, name.getX(), 1.0);
        assertEquals(proxy.getCenterY(), name.getCenterY(), 1.0, "the two share the band's middle");
        double contentsTop = shapeReach(m).getMinY();
        assertTrue(Math.max(proxy.getMaxY(), name.getMaxY()) + ClusterBox.GAP <= contentsTop,
                "the band runs into the members");
        assertCentredOn(0, 0, box);
    }

    @Test
    void aLargerFontMakesTheNameTheTallerAndCentresTheProxyOnIt() {
        ClusterBox.TextMetrics large = new ClusterBox.TextMetrics(18, 24, 25);
        Rectangle name = ClusterBox.nameStrip(0, 0, 50, large);
        Rectangle2D proxy = ClusterBox.proxyFace(0, 0, large);
        double stack = ClusterIcon.PROXY_HEIGHT + ClusterIcon.PROXY_STACK_OFFSET;

        assertEquals(ClusterBox.GAP, name.getY(), 1e-9, "the name fills the band");
        assertEquals(name.getCenterY(), proxy.getY() + stack / 2, 1e-9);
    }

    @Test
    void aSmallerFontMakesTheProxyTheTallerAndCentresTheNameOnIt() {
        ClusterBox.TextMetrics small = new ClusterBox.TextMetrics(8, 10, 25);
        Rectangle name = ClusterBox.nameStrip(0, 0, 50, small);
        Rectangle2D proxy = ClusterBox.proxyFace(0, 0, small);
        double stack = ClusterIcon.PROXY_HEIGHT + ClusterIcon.PROXY_STACK_OFFSET;

        assertEquals(ClusterBox.GAP, proxy.getY(), 1e-9, "the proxy fills the band");
        assertEquals(proxy.getY() + stack / 2, name.getCenterY(), 1.0);
    }

    @Test
    void withTheProxySwitchedOffTheNameSitsAtTheCornerInABandOfItsOwnHeight() {
        ClusterBox.showsProxy = false;
        try {
            Rectangle2D without = box(member(0, 0));
            Rectangle2D with = box(OptionalInt.of(10), member(0, 0));
            Rectangle name = ClusterBox.nameStrip(with.getX(), with.getY(), 10, METRICS);
            double band = METRICS.height() + ClusterBox.TEXT_PADDING + ClusterBox.GAP;

            assertEquals(with.getX() + ClusterBox.GAP, name.getX(), 1.0);
            assertEquals(with.getY() + ClusterBox.GAP, name.getY(), 1.0);
            assertEquals(without.getHeight() + 2 * band, with.getHeight(), 1e-9);
        } finally {
            ClusterBox.showsProxy = true;
        }
    }

    @Test
    void aNameAddsTheBandAtTheTopAndTheSameAtTheBottom() {
        Rectangle2D without = box(member(0, 0));
        Rectangle2D with = box(OptionalInt.of(10), member(0, 0));
        double band = Math.max(METRICS.height() + ClusterBox.TEXT_PADDING,
                ClusterIcon.PROXY_HEIGHT + ClusterIcon.PROXY_STACK_OFFSET) + ClusterBox.GAP;
        assertEquals(without.getHeight() + 2 * band, with.getHeight(), 1e-9);
    }

    @Test
    void aNameLongerThanTheContentsWidensTheBox() {
        Rectangle2D box = box(OptionalInt.of(400), member(0, 0, 20));
        Rectangle name = ClusterBox.nameStrip(box.getX(), box.getY(), 400, METRICS);
        assertTrue(box.getMaxX() >= name.getMaxX() + ClusterBox.GAP, "the name overhangs the box");
        assertCentredOn(0, 0, box);
    }

    @Test
    void theNameIsWrittenOnItsStripAsANodeLabelIsOnIts() {
        Rectangle node = ClusterBox.labelStrip(100, 0, 0, 40, METRICS);
        double nodeBaseline = ClusterBox.baseline(0, 0, METRICS);
        Rectangle name = ClusterBox.nameStrip(0, 0, 40, METRICS);
        assertEquals(node.getSize(), name.getSize());
        assertEquals(nodeBaseline - node.y, ClusterBox.nameBaseline(name, METRICS) - name.y, 1.0);
    }

    @Test
    void labelStripsStackDownwardsAsTheyAreDrawn() {
        Rectangle first = ClusterBox.labelStrip(0, 0, 0, 40, METRICS);
        Rectangle second = ClusterBox.labelStrip(0, 0, 1, 40, METRICS);
        // 25 below the centre, less the ascent and the padding.
        assertEquals(new Rectangle(-22, 25 - 11 - 2, 44, 16), first);
        assertEquals(first.y + METRICS.height() + ClusterBox.TEXT_PADDING, second.y);
    }

    @Test
    void theFillIsGreyAndSeventyTwoPercentTransparent() {
        assertEquals(new Color(128, 128, 128, 71), ClusterBox.FILL);
    }

    @Test
    void theBorderMovesTowardsBlackOnLightThemesAndWhiteOnDark() {
        assertEquals(new Color(0x53, 0x53, 0x53), ClusterBox.border(true));
        assertEquals(new Color(0xac, 0xac, 0xac), ClusterBox.border(false));
    }

    // What the renderer passes in for each label mode.

    private static String cluster(String... sections) {
        return String.join("", sections);
    }

    /**
     * A member of the cluster {@code f}. The name is one letter so that its band is
     * narrower than a lone member's shape: then only labels and the band's height can
     * change a box's size, and the width comparisons below test the labels alone.
     */
    private static String member(String name, String aka, int x) {
        return "#@cluster = f\n" + (aka == null ? "" : "#@aka = " + aka + "\n")
                + "[node." + name + "]\ntype = storage\nloc = " + x + ", 100\n\n";
    }

    private static Rectangle2D rendererBox(String text, NodeLabelMode mode) {
        HydrologicalModel model = new HydrologicalModel();
        model.parseFromIniText(text);
        BufferedImage image = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2d = image.createGraphics();
        try {
            NodeTheme.TextStyle style = new NodeTheme().getCurrentTextStyle();
            FontMetrics fm = g2d.getFontMetrics(style.getFont());
            MapLayers.Layer layer = MapLayers.of(model, AllExpanded.of(model)).get(0);
            return MapRenderer.clusterBox(layer, fm, MapRenderer.textMetrics(fm, style), mode, 1.0, 0, 0);
        } finally {
            g2d.dispose();
        }
    }

    @Test
    void aHoverOnlyLabelDoesntWidenTheBox() {
        // In hidden mode every label is hover-only; the box is as if there were none.
        String text = cluster(member("a_node_with_a_very_long_name_indeed", null, 100));
        double half = ClusterBox.SHAPE_CLEARANCE + ClusterBox.GAP;
        assertEquals(new Rectangle2D.Double(100 - half, 100 - half, 2 * half, 2 * half),
                rendererBox(text, NodeLabelMode.HIDDEN));
    }

    @Test
    void inAkasOnlyModeOnlyAnAkaWidensTheBox() {
        String withAka = cluster(member("a", "a_long_readable_name_for_it", 100));
        String withoutAka = cluster(member("a_node_with_a_very_long_name_indeed", null, 100));
        Rectangle2D shapeOnly = rendererBox(withoutAka, NodeLabelMode.HIDDEN);
        // A member with no aka draws no label, so only the name band (mirrored) is added.
        assertEquals(shapeOnly.getWidth(), rendererBox(withoutAka, NodeLabelMode.AKA).getWidth(), 1e-9);
        assertTrue(rendererBox(withAka, NodeLabelMode.AKA).getWidth() > shapeOnly.getWidth());
    }

    @Test
    void inNamesModeEveryNameWidensTheBox() {
        String text = cluster(member("a_node_with_a_very_long_name_indeed", null, 100));
        assertTrue(rendererBox(text, NodeLabelMode.NAMES).getWidth()
                > rendererBox(text, NodeLabelMode.HIDDEN).getWidth());
    }

    @Test
    void theNameBandIsThereInNamesAndAkasOnlyModesAndNotInHidden() {
        String text = cluster(member("a", null, 100));
        double hidden = rendererBox(text, NodeLabelMode.HIDDEN).getHeight();
        assertNotEquals(hidden, rendererBox(text, NodeLabelMode.AKA).getHeight());
        assertNotEquals(hidden, rendererBox(text, NodeLabelMode.NAMES).getHeight());
    }
}
