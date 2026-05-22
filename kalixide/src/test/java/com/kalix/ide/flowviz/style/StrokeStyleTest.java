package com.kalix.ide.flowviz.style;

import org.junit.jupiter.api.Test;

import java.awt.BasicStroke;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link StrokeStyle} and the {@link DashStyle} it embeds — the
 * thickness + dash half of a {@link LineStyle}.
 */
class StrokeStyleTest {

    @Test
    void defaultIsSolidAt1_5px() {
        assertEquals(StrokeStyle.SOLID, StrokeStyle.DEFAULT,
            "Default stroke must match the renderer's historical 1.5px solid line");
        assertEquals(1.5f, StrokeStyle.DEFAULT.getThickness());
        assertEquals(DashStyle.SOLID, StrokeStyle.DEFAULT.getDash());
    }

    @Test
    void solidStrokeHasNoDashArray() {
        BasicStroke stroke = StrokeStyle.SOLID.toBasicStroke();
        assertEquals(1.5f, stroke.getLineWidth());
        assertNull(stroke.getDashArray(), "Solid strokes must not carry a dash array");
        assertEquals(BasicStroke.CAP_ROUND, stroke.getEndCap());
        assertEquals(BasicStroke.JOIN_ROUND, stroke.getLineJoin());
    }

    @Test
    void dashedStrokeCarriesDashArray() {
        BasicStroke stroke = StrokeStyle.DASHED.toBasicStroke();
        assertNotNull(stroke.getDashArray());
        assertTrue(stroke.getDashArray().length > 0);
    }

    @Test
    void boldPresetsAreThickerThanTheirThinCounterparts() {
        assertTrue(StrokeStyle.BOLD.getThickness() > StrokeStyle.THIN.getThickness());
        assertTrue(StrokeStyle.BOLD_DASHED.getThickness() > StrokeStyle.DASHED.getThickness());
    }

    @Test
    void toBasicStrokeReturnsFreshInstances() {
        assertNotSame(StrokeStyle.DASHED.toBasicStroke(), StrokeStyle.DASHED.toBasicStroke());
    }

    @Test
    void fromDisplayNameRoundTrips() {
        for (StrokeStyle style : StrokeStyle.values()) {
            assertEquals(style, StrokeStyle.fromDisplayName(style.getDisplayName()));
        }
    }

    @Test
    void fromDisplayNameFallsBackToDefaultForUnknownName() {
        assertEquals(StrokeStyle.DEFAULT, StrokeStyle.fromDisplayName("nonsense"));
    }

    @Test
    void dashStyleSolidHasNoArrayOthersDo() {
        assertNull(DashStyle.SOLID.dashArray());
        assertNotNull(DashStyle.DASHED.dashArray());
        assertNotNull(DashStyle.DOTTED.dashArray());
    }

    @Test
    void dashArrayReturnsDefensiveCopies() {
        float[] first = DashStyle.DASHED.dashArray();
        first[0] = 999f;
        assertNotEquals(999f, DashStyle.DASHED.dashArray()[0],
            "Mutating a returned dash array must not affect the enum's shared state");
    }
}
