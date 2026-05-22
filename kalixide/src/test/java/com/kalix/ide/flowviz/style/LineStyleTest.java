package com.kalix.ide.flowviz.style;

import org.junit.jupiter.api.Test;

import java.awt.Color;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link LineStyle} — the immutable colour + stroke value type.
 */
class LineStyleTest {

    @Test
    void rejectsNullColourAndStroke() {
        assertThrows(IllegalArgumentException.class,
            () -> new LineStyle(null, StrokeStyle.SOLID));
        assertThrows(IllegalArgumentException.class,
            () -> new LineStyle(Color.RED, null));
    }

    @Test
    void withColorReplacesColourAndKeepsStroke() {
        LineStyle original = new LineStyle(Color.RED, StrokeStyle.DASHED);
        LineStyle changed = original.withColor(Color.BLUE);

        assertEquals(Color.BLUE, changed.color());
        assertEquals(StrokeStyle.DASHED, changed.stroke());
        assertEquals(Color.RED, original.color(), "original must be unchanged");
    }

    @Test
    void withStrokeReplacesStrokeAndKeepsColour() {
        LineStyle original = new LineStyle(Color.RED, StrokeStyle.SOLID);
        LineStyle changed = original.withStroke(StrokeStyle.BOLD);

        assertEquals(StrokeStyle.BOLD, changed.stroke());
        assertEquals(Color.RED, changed.color());
        assertEquals(StrokeStyle.SOLID, original.stroke(), "original must be unchanged");
    }

    @Test
    void opacityIsCarriedInTheColourAlphaChannel() {
        LineStyle translucent = new LineStyle(new Color(255, 0, 0, 128), StrokeStyle.SOLID);
        assertEquals(128, translucent.color().getAlpha());
    }

    @Test
    void equalsIsValueBased() {
        assertEquals(
            new LineStyle(Color.RED, StrokeStyle.SOLID),
            new LineStyle(Color.RED, StrokeStyle.SOLID));
        assertNotEquals(
            new LineStyle(Color.RED, StrokeStyle.SOLID),
            new LineStyle(Color.RED, StrokeStyle.BOLD));
    }
}
