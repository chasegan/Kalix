package com.kalix.ide.icons;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.kordamp.ikonli.fontawesome6.FontAwesomeSolid;
import org.kordamp.ikonli.swing.FontIcon;

import javax.swing.Icon;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The hollow form of a glyph stands in for the solid {@link FontIcon}: the same box, the
 * glyph in the same place, only its outline drawn. A different box resizes the button that
 * swaps between them.
 */
class HollowIconTest {

    private static final int SIZE = 16;
    private static final int CANVAS = 32;

    /** Painted pixels and their bounding box, the icon drawn at the canvas origin. */
    private record Ink(int pixels, Rectangle bounds) {
    }

    private static Ink ink(Icon icon) {
        BufferedImage image = new BufferedImage(CANVAS, CANVAS, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        icon.paintIcon(null, g, 0, 0);
        g.dispose();
        int pixels = 0;
        Rectangle bounds = null;
        for (int y = 0; y < CANVAS; y++) {
            for (int x = 0; x < CANVAS; x++) {
                if ((image.getRGB(x, y) >>> 24) > 0x80) {
                    pixels++;
                    if (bounds == null) {
                        bounds = new Rectangle(x, y, 1, 1);
                    } else {
                        bounds.add(new Rectangle(x, y, 1, 1));
                    }
                }
            }
        }
        return new Ink(pixels, bounds);
    }

    @ParameterizedTest
    @EnumSource(value = FontAwesomeSolid.class, names = {"TAG", "FONT"})
    void hasTheSolidIconsBox(FontAwesomeSolid glyph) {
        FontIcon solid = FontIcon.of(glyph, SIZE, Color.BLACK);
        HollowIcon hollow = HollowIcon.of(glyph, SIZE, Color.BLACK);
        assertEquals(solid.getIconWidth(), hollow.getIconWidth(), "width");
        assertEquals(solid.getIconHeight(), hollow.getIconHeight(), "height");
    }

    @ParameterizedTest
    @EnumSource(value = FontAwesomeSolid.class, names = {"TAG", "FONT"})
    void drawsTheGlyphWhereTheSolidIconDoes(FontAwesomeSolid glyph) {
        Rectangle solid = ink(FontIcon.of(glyph, SIZE, Color.BLACK)).bounds();
        Rectangle hollow = ink(HollowIcon.of(glyph, SIZE, Color.BLACK)).bounds();
        // The outline's stroke may reach a pixel past the filled glyph's edge.
        assertTrue(Math.abs(solid.x - hollow.x) <= 1, "left " + solid + " vs " + hollow);
        assertTrue(Math.abs(solid.y - hollow.y) <= 1, "top " + solid + " vs " + hollow);
        assertTrue(Math.abs(solid.getMaxX() - hollow.getMaxX()) <= 1, "right " + solid + " vs " + hollow);
        assertTrue(Math.abs(solid.getMaxY() - hollow.getMaxY()) <= 1, "bottom " + solid + " vs " + hollow);
    }

    /** The tag, which the map toolbar hollows; a thin glyph like the "A" is mostly outline anyway. */
    @Test
    void drawsTheOutlineOnly() {
        int solid = ink(FontIcon.of(FontAwesomeSolid.TAG, SIZE, Color.BLACK)).pixels();
        int hollow = ink(HollowIcon.of(FontAwesomeSolid.TAG, SIZE, Color.BLACK)).pixels();
        assertTrue(hollow > 0, "the outline is drawn");
        assertTrue(hollow < solid * 3 / 4, "hollow paints " + hollow + " pixels, solid " + solid);
    }
}
