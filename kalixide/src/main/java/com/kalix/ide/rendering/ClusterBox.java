package com.kalix.ide.rendering;

import com.kalix.ide.constants.UIConstants;

import java.awt.Color;
import java.awt.Rectangle;
import java.awt.geom.Rectangle2D;
import java.util.List;
import java.util.OptionalInt;

/**
 * The geometry and colours of the box under an expanded cluster (clusters.md, Architecture,
 * "Box bounds"), as pure functions of screen positions and measured text widths, so the
 * renderer only draws what it is given and the rules can be tested without a display.
 *
 * <p>Nodes and labels draw at a fixed screen size while only positions scale with zoom, so
 * the box is computed in screen pixels on every repaint. It is also the one place that says
 * where a node's label strips lie ({@link #labelStrip}), which the renderer draws from, so
 * the box can't drift away from the labels it must cover.</p>
 */
public final class ClusterBox {

    /**
     * The link ring drawn on hover: half a node plus half the ring, plus half the ring's 2 px
     * stroke ({@code MapRenderer.renderLinkHandle}).
     */
    static final double LINK_RING_REACH =
            UIConstants.Map.NODE_SIZE / 2.0 + UIConstants.Map.LINK_HANDLE_RING_PX / 2.0 + 1;

    /**
     * How far a member's drawing can reach from its centre, in every direction: the furthest
     * any shape's selected border reaches, which the shape renderer states, or the link ring
     * drawn on hover, whichever is more, rounded up to whole pixels. Reserving all of it keeps
     * the box the same size whether or not a member is selected or hovered.
     */
    static final double SHAPE_CLEARANCE = Math.ceil(Math.max(NodeShapeRenderer.REACH, LINK_RING_REACH));

    /** The clear space between the box's contents and its border. */
    static final double GAP = 4;

    /**
     * Whether a box shows the proxy before its name. On by default. It is a switch, not a
     * setting: no menu, toolbar or preference sets it, so turning the proxy off to compare
     * means changing this line. With it off, nothing uses the proxy: the band holds only
     * the name, and the name sits {@link #GAP} in from the corner. Tests flip it, and put it
     * back.
     */
    static boolean showsProxy = true;

    /**
     * The radius of the box's rounded corners. A corner of radius r cuts in about 0.3 r
     * (r(1 - 1/√2)) at the diagonal, under 2 px here, so {@link #GAP} keeps it clear of the
     * contents.
     */
    static final double CORNER_RADIUS = 6;

    /** The fill: grey, 72% transparent, on every theme, with no theme key (clusters.md, settled decision 7). */
    static final Color FILL = new Color(0x80, 0x80, 0x80, Math.round(0.28f * 255));

    /** The padding around a label's text on its strip. */
    static final int TEXT_PADDING = UIConstants.Text.BACKGROUND_PADDING;

    /** How far the border is moved from the fill's colour towards black or white. */
    private static final double BORDER_SHIFT = 0.35;

    private ClusterBox() {
    }

    /**
     * The measurements label strips are laid out from.
     *
     * @param ascent the font's ascent
     * @param height the font's line height
     * @param firstBaseline how far below a node's centre its first line's baseline sits
     */
    public record TextMetrics(int ascent, int height, double firstBaseline) {
    }

    /**
     * A member as drawn: its centre in screen pixels, and the width of each label line it
     * shows without the mouse over it, top first (empty when it shows none).
     */
    public record Member(double x, double y, List<Integer> lineWidths) {
    }

    /**
     * The strip behind line {@code line} (0 for the top) of the label of the node centred at
     * ({@code centreX}, {@code centreY}), {@code width} being that line's text width; the
     * text is drawn centred on it, on {@link #baseline}.
     */
    public static Rectangle labelStrip(double centreX, double centreY, int line, int width,
                                       TextMetrics metrics) {
        double textX = centreX - width / 2.0;
        double textY = baseline(centreY, line, metrics);
        return new Rectangle((int) (textX - TEXT_PADDING), (int) (textY - metrics.ascent() - TEXT_PADDING),
                width + TEXT_PADDING * 2, metrics.height() + TEXT_PADDING);
    }

    /** The baseline of line {@code line} (0 for the top) of a label under a node centred at {@code centreY}. */
    public static double baseline(double centreY, int line, TextMetrics metrics) {
        return centreY + metrics.firstBaseline() + line * (metrics.height() + TEXT_PADDING);
    }

    /**
     * The box around {@code members}, in screen pixels. It holds every member's shape out
     * to {@link #SHAPE_CLEARANCE}, every label strip the member shows without hover, and,
     * when {@code nameWidth} is present, a band along the top for the box name; all of it
     * {@link #GAP} clear of the border. The box is symmetric about the centre of the
     * members' positions: it reaches as far left as right and as far up as down, each pair
     * set by whichever side needs more room. So the band for the name is mirrored below the
     * members, where their labels already take most of that room.
     *
     * <p>Labels shown only on hover aren't passed in, so they never move the box.</p>
     *
     * <p>The band holds the proxy (a small copy of the collapsed icon; clusters.md, Terms)
     * and then the name, {@link #GAP} apart: its height is the taller of the two, and the
     * box is at least as wide as both. The band exists only when the name does (none in
     * hidden label mode), and so does the proxy.</p>
     *
     * @param members at least one
     * @param nameWidth the box name's text width, or empty when the label mode hides it
     */
    public static Rectangle2D.Double bounds(List<Member> members, TextMetrics metrics,
                                            OptionalInt nameWidth) {
        if (members.isEmpty()) {
            throw new IllegalArgumentException("a box needs at least one member");
        }

        // The contents: every shape and label strip, and the centre of the positions.
        double minX = Double.POSITIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;
        double minCentreX = Double.POSITIVE_INFINITY;
        double minCentreY = Double.POSITIVE_INFINITY;
        double maxCentreX = Double.NEGATIVE_INFINITY;
        double maxCentreY = Double.NEGATIVE_INFINITY;
        for (Member member : members) {
            minCentreX = Math.min(minCentreX, member.x());
            maxCentreX = Math.max(maxCentreX, member.x());
            minCentreY = Math.min(minCentreY, member.y());
            maxCentreY = Math.max(maxCentreY, member.y());

            minX = Math.min(minX, member.x() - SHAPE_CLEARANCE);
            maxX = Math.max(maxX, member.x() + SHAPE_CLEARANCE);
            minY = Math.min(minY, member.y() - SHAPE_CLEARANCE);
            maxY = Math.max(maxY, member.y() + SHAPE_CLEARANCE);

            List<Integer> widths = member.lineWidths();
            for (int line = 0; line < widths.size(); line++) {
                Rectangle strip = labelStrip(member.x(), member.y(), line, widths.get(line), metrics);
                minX = Math.min(minX, strip.getMinX());
                maxX = Math.max(maxX, strip.getMaxX());
                minY = Math.min(minY, strip.getMinY());
                maxY = Math.max(maxY, strip.getMaxY());
            }
        }
        double centreX = (minCentreX + maxCentreX) / 2;
        double centreY = (minCentreY + maxCentreY) / 2;

        double halfWidth = Math.max(centreX - minX, maxX - centreX) + GAP;
        double up = centreY - minY + GAP;
        double down = maxY - centreY + GAP;

        if (nameWidth.isPresent()) {
            // The band: the proxy and the name strip, GAP below the border and GAP above
            // the contents.
            Rectangle strip = nameStrip(0, 0, nameWidth.getAsInt(), metrics);
            up += bandHeight(metrics) + GAP;
            halfWidth = Math.max(halfWidth, (strip.getMaxX() + GAP) / 2);
        }
        double halfHeight = Math.max(up, down);

        return new Rectangle2D.Double(centreX - halfWidth, centreY - halfHeight,
                2 * halfWidth, 2 * halfHeight);
    }

    /**
     * The height of the band along the top of a box: the taller of the proxy, if shown, and
     * the name strip.
     */
    static double bandHeight(TextMetrics metrics) {
        double name = metrics.height() + TEXT_PADDING;
        return showsProxy ? Math.max(ClusterIcon.PROXY_HEIGHT + ClusterIcon.PROXY_STACK_OFFSET, name) : name;
    }

    /** Whether a box shows the proxy before its name ({@link #showsProxy}). */
    public static boolean showsProxy() {
        return showsProxy;
    }

    /**
     * The proxy's front card in the band of a box whose top left corner is ({@code boxX},
     * {@code boxY}): {@link #GAP} in from the corner, centred on the band's height. Only
     * meaningful while {@link #showsProxy}.
     */
    public static Rectangle2D.Double proxyFace(double boxX, double boxY, TextMetrics metrics) {
        double stack = ClusterIcon.PROXY_HEIGHT + ClusterIcon.PROXY_STACK_OFFSET;
        return ClusterIcon.proxyFace(boxX + GAP, boxY + GAP + (bandHeight(metrics) - stack) / 2);
    }

    /**
     * The strip behind the box name of a box whose top left corner is ({@code boxX},
     * {@code boxY}): after the proxy, {@link #GAP} from it, or {@link #GAP} in from the
     * corner when there is none; centred on the band's height, and sized like a node
     * label's strip. The name's baseline is {@link #nameBaseline}.
     */
    public static Rectangle nameStrip(double boxX, double boxY, int nameWidth, TextMetrics metrics) {
        int height = metrics.height() + TEXT_PADDING;
        double x = boxX + GAP
                + (showsProxy ? ClusterIcon.PROXY_WIDTH + ClusterIcon.PROXY_STACK_OFFSET + GAP : 0);
        double y = boxY + GAP + (bandHeight(metrics) - height) / 2;
        return new Rectangle((int) x, (int) y, nameWidth + TEXT_PADDING * 2, height);
    }

    /** The baseline of the box name on {@code strip}, placed as a node label's is on its strip. */
    public static int nameBaseline(Rectangle strip, TextMetrics metrics) {
        return strip.y + TEXT_PADDING + metrics.ascent();
    }

    /**
     * The border colour: the fill's grey moved {@link #BORDER_SHIFT} of the way towards
     * black on a light theme and towards white on a dark one, opaque, so the box keeps an
     * edge on either (clusters.md, settled decision 7).
     */
    static Color border(boolean lightTheme) {
        int target = lightTheme ? 0 : 255;
        int grey = (int) Math.round(FILL.getRed() + (target - FILL.getRed()) * BORDER_SHIFT);
        return new Color(grey, grey, grey);
    }
}
