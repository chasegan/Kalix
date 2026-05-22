package com.kalix.ide.flowviz.style;

import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link PaletteCodec} — the flat-string (de)serialization of a
 * {@link PlotPalette} used to persist palettes inside {@code kalix_prefs.json}.
 */
class PaletteCodecTest {

    /** A palette whose ten slots all differ, exercising every field of the codec. */
    private static PlotPalette variedPalette(String name) {
        StrokeStyle[] strokes = StrokeStyle.values();
        List<LineStyle> styles = new ArrayList<>();
        for (int i = 0; i < PlotPalette.SLOT_COUNT; i++) {
            Color color = new Color(i * 20, i * 10 + 5, 255 - i * 20, i * 25 + 5);
            styles.add(new LineStyle(color, strokes[i % strokes.length]));
        }
        return new PlotPalette(name, false, styles);
    }

    @Test
    void roundTripsAllSlotsIncludingColourAlphaAndStroke() {
        PlotPalette original = variedPalette("Round Trip");
        PlotPalette decoded = PaletteCodec.decode(PaletteCodec.encode(original)).orElseThrow();

        assertEquals(original.name(), decoded.name());
        assertEquals(original.entries(), decoded.entries());
    }

    @Test
    void decodedPaletteIsAlwaysNonBuiltIn() {
        // Even encoding a built-in yields a user palette on decode — built-ins are never persisted.
        PlotPalette decoded = PaletteCodec.decode(
            PaletteCodec.encode(PlotPalette.builtInOriginal())).orElseThrow();
        assertFalse(decoded.builtIn());
    }

    @Test
    void roundTripsNamesContainingReservedCharacters() {
        for (String name : List.of("Reds, blues", "a\"b", "pipe|name", "semi;colon", "100%")) {
            PlotPalette original = variedPalette(name);
            PlotPalette decoded = PaletteCodec.decode(PaletteCodec.encode(original)).orElseThrow();
            assertEquals(name, decoded.name(), "name must survive a round trip: " + name);
        }
    }

    @Test
    void encodedFormHasNoCharactersThatBreakThePreferenceLayer() {
        // PreferenceManager's JSON string-list writer is comma/quote-sensitive.
        String encoded = PaletteCodec.encode(variedPalette("Reds, \"special\""));
        assertFalse(encoded.contains(","), "encoded palette must not contain commas");
        assertFalse(encoded.contains("\""), "encoded palette must not contain quotes");
    }

    @Test
    void unknownStrokeNameDegradesToDefault() {
        String encoded = PaletteCodec.encode(variedPalette("X"));
        String tampered = encoded.replace(StrokeStyle.values()[0].name(), "FUTURE_STROKE");

        PlotPalette decoded = PaletteCodec.decode(tampered).orElseThrow();
        assertEquals(StrokeStyle.DEFAULT, decoded.entryAt(0).stroke());
    }

    @Test
    void malformedInputDecodesToEmptyRatherThanThrowing() {
        assertEquals(Optional.empty(), PaletteCodec.decode(null));
        assertEquals(Optional.empty(), PaletteCodec.decode("no-separator-here"));
        assertEquals(Optional.empty(), PaletteCodec.decode("TooFewSlots|1f77b4ff-SOLID"));

        // Valid shape (10 slots) but one slot has a non-hex colour.
        String badColour = PaletteCodec.encode(variedPalette("X"))
            .replaceFirst("\\|[0-9a-f]{8}", "|zzzzzzzz");
        assertEquals(Optional.empty(), PaletteCodec.decode(badColour));
    }

    @Test
    void hex8RoundTripsAColour() {
        Color color = new Color(1, 254, 128, 64);
        assertEquals(color, PaletteCodec.fromHex8(PaletteCodec.toHex8(color)));
    }

    @Test
    void fromHex8RejectsBadInput() {
        assertNull(PaletteCodec.fromHex8(null));
        assertNull(PaletteCodec.fromHex8("abc"));          // wrong length
        assertNull(PaletteCodec.fromHex8("gggggggg"));     // not hex
    }

    @Test
    void escapeUnescapeRoundTripsReservedAndPlainText() {
        for (String s : List.of("", "plain", "%,\"|;", "mix 100% of, the \"things\"")) {
            assertEquals(s, PaletteCodec.unescape(PaletteCodec.escape(s)));
        }
    }

    @Test
    void unescapeLeavesAStrayPercentLiteral() {
        assertEquals("50% off", PaletteCodec.unescape("50% off"));
    }
}
