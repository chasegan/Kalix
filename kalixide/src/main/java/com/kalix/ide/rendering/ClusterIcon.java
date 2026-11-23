package com.kalix.ide.rendering;

import java.awt.Color;
import java.awt.geom.Rectangle2D;

/**
 * The geometry and colours of a collapsed cluster's icon (clusters.md, Terms): two stacked
 * cards with the member count on the front one and the cluster's name below, centred on
 * its members' centroid. Pure functions of screen positions, like {@link ClusterBox}, so
 * the renderer draws what it is given and the shape can be tested without a display.
 *
 * <p>Sizes are in screen pixels and taken from the agreed demo
 * ({@code cluster-ui-spike.html}): a 34 × 24 face with 4 px corners and a back card 3 px
 * down and to the right, the pair centred on the centroid.</p>
 */
public final class ClusterIcon {

    static final double WIDTH = 34;
    static final double HEIGHT = 24;
    static final double CORNER_RADIUS = 4;

    /** How far the back card sits below and to the right of the face. */
    static final double STACK_OFFSET = 3;

    /**
     * How much lower the icon's name sits than a node label would: the cards reach about
     * 14 px below the centre where a node reaches 10.
     */
    static final double LABEL_DROP = 4;

    /**
     * The proxy (clusters.md, Terms): a smaller copy of the cards, with the count, drawn
     * before the box name of an expanded cluster. As tall as a name strip, near enough, so
     * the band holding both barely grows.
     */
    static final double PROXY_WIDTH = 22;
    static final double PROXY_HEIGHT = 15;
    static final double PROXY_CORNER_RADIUS = 3;
    static final double PROXY_STACK_OFFSET = 2;
    static final float PROXY_COUNT_SIZE = 9f;

    /** The cards' colours, the same on every theme, as the box's are (clusters.md, settled decision 7). */
    static final Color BACK = new Color(0xbd, 0xbd, 0xbd);
    static final Color FACE = new Color(0xd9, 0xd9, 0xd9);
    static final Color BORDER = new Color(0x55, 0x55, 0x55);
    static final Color COUNT = new Color(0x33, 0x33, 0x33);

    private ClusterIcon() {
    }

    /** The front card of an icon centred at ({@code x}, {@code y}). */
    public static Rectangle2D.Double face(double x, double y) {
        return new Rectangle2D.Double(x - (WIDTH + STACK_OFFSET) / 2, y - (HEIGHT + STACK_OFFSET) / 2,
                WIDTH, HEIGHT);
    }

    /** The proxy's front card, with its top left corner at ({@code x}, {@code y}). */
    public static Rectangle2D.Double proxyFace(double x, double y) {
        return new Rectangle2D.Double(x, y, PROXY_WIDTH, PROXY_HEIGHT);
    }

    /** The proxy's back card, for a front card at ({@code x}, {@code y}). */
    public static Rectangle2D.Double proxyBack(double x, double y) {
        return new Rectangle2D.Double(x + PROXY_STACK_OFFSET, y + PROXY_STACK_OFFSET, PROXY_WIDTH, PROXY_HEIGHT);
    }

    /** The back card of an icon centred at ({@code x}, {@code y}): the face, moved down and right. */
    public static Rectangle2D.Double back(double x, double y) {
        Rectangle2D.Double face = face(x, y);
        return new Rectangle2D.Double(face.x + STACK_OFFSET, face.y + STACK_OFFSET, WIDTH, HEIGHT);
    }
}
