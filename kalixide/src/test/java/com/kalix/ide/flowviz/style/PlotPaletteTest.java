package com.kalix.ide.flowviz.style;

import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link PlotPalette} — the named, fixed-size, immutable set of
 * {@link LineStyle}s applied globally to plots.
 */
class PlotPaletteTest {

    private static List<LineStyle> tenStyles() {
        List<LineStyle> styles = new ArrayList<>();
        for (int i = 0; i < PlotPalette.SLOT_COUNT; i++) {
            styles.add(new LineStyle(Color.BLACK, StrokeStyle.SOLID));
        }
        return styles;
    }

    @Test
    void builtInOriginalHasTenSlotsAndIsReadOnly() {
        PlotPalette def = PlotPalette.builtInOriginal();
        assertEquals(PlotPalette.ORIGINAL_NAME, def.name());
        assertTrue(def.builtIn());
        assertEquals(PlotPalette.SLOT_COUNT, def.entries().size());
    }

    @Test
    void builtInOriginalReproducesTheHistoricalTab10Appearance() {
        PlotPalette def = PlotPalette.builtInOriginal();
        // First and last of the classic tab10 colours, at the historical solid stroke.
        assertEquals(new Color(0x1f77b4), def.entryAt(0).color());
        assertEquals(new Color(0x17becf), def.entryAt(9).color());
        for (LineStyle style : def.entries()) {
            assertEquals(StrokeStyle.DEFAULT, style.stroke());
        }
    }

    @Test
    void constructorRejectsWrongSlotCount() {
        List<LineStyle> tooFew = tenStyles().subList(0, 9);
        assertThrows(IllegalArgumentException.class,
            () -> new PlotPalette("X", false, tooFew));

        List<LineStyle> tooMany = tenStyles();
        tooMany.add(new LineStyle(Color.BLACK, StrokeStyle.SOLID));
        assertThrows(IllegalArgumentException.class,
            () -> new PlotPalette("X", false, tooMany));
    }

    @Test
    void constructorRejectsBlankName() {
        assertThrows(IllegalArgumentException.class,
            () -> new PlotPalette("  ", false, tenStyles()));
        assertThrows(IllegalArgumentException.class,
            () -> new PlotPalette(null, false, tenStyles()));
    }

    @Test
    void entriesListIsUnmodifiable() {
        PlotPalette palette = new PlotPalette("X", false, tenStyles());
        assertThrows(UnsupportedOperationException.class,
            () -> palette.entries().set(0, new LineStyle(Color.RED, StrokeStyle.BOLD)));
    }

    @Test
    void constructorDefensivelyCopiesEntries() {
        List<LineStyle> source = tenStyles();
        PlotPalette palette = new PlotPalette("X", false, source);
        source.set(0, new LineStyle(Color.RED, StrokeStyle.BOLD));
        assertEquals(Color.BLACK, palette.entryAt(0).color(),
            "Mutating the source list must not affect the palette");
    }

    @Test
    void entryAtWrapsModuloSlotCount() {
        PlotPalette def = PlotPalette.builtInOriginal();
        assertEquals(def.entryAt(0), def.entryAt(PlotPalette.SLOT_COUNT));
        assertEquals(def.entryAt(0), def.entryAt(2 * PlotPalette.SLOT_COUNT));
        assertEquals(def.entryAt(9), def.entryAt(-1), "negative indices wrap too (floorMod)");
    }

    @Test
    void withEntryReturnsNewPaletteAndLeavesOriginalUntouched() {
        PlotPalette original = new PlotPalette("X", false, tenStyles());
        LineStyle replacement = new LineStyle(Color.RED, StrokeStyle.BOLD_DASHED);
        PlotPalette edited = original.withEntry(3, replacement);

        assertEquals(replacement, edited.entryAt(3));
        assertEquals(Color.BLACK, original.entryAt(3).color(), "original unchanged");
        assertNotSame(original, edited);
    }

    @Test
    void withEntrySlotWraps() {
        PlotPalette original = new PlotPalette("X", false, tenStyles());
        LineStyle replacement = new LineStyle(Color.RED, StrokeStyle.BOLD);
        PlotPalette edited = original.withEntry(PlotPalette.SLOT_COUNT, replacement);
        assertEquals(replacement, edited.entryAt(0));
    }

    @Test
    void asUserCopyForksAnEditableCopyWithNewName() {
        PlotPalette def = PlotPalette.builtInOriginal();
        PlotPalette copy = def.asUserCopy("My Palette");

        assertEquals("My Palette", copy.name());
        assertFalse(copy.builtIn(), "a forked copy must be editable");
        assertEquals(def.entries(), copy.entries(), "styles are carried over verbatim");
        assertTrue(def.builtIn(), "the built-in original is untouched");
    }

    @Test
    void withNameKeepsEntriesAndBuiltInFlag() {
        PlotPalette def = PlotPalette.builtInOriginal();
        PlotPalette renamed = def.withName("Renamed");
        assertEquals("Renamed", renamed.name());
        assertTrue(renamed.builtIn());
        assertEquals(def.entries(), renamed.entries());
    }

    @Test
    void equalsIsValueBased() {
        assertEquals(
            new PlotPalette("X", false, tenStyles()),
            new PlotPalette("X", false, tenStyles()));
        assertNotEquals(
            new PlotPalette("X", false, tenStyles()),
            new PlotPalette("Y", false, tenStyles()));
    }
}
