package com.kalix.ide.flowviz.style;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Serializes a {@link PlotPalette} to and from a single flat string.
 *
 * <h2>Why a flat string</h2>
 * Palettes are persisted inside {@code kalix_prefs.json}, whose hand-rolled JSON
 * layer ({@code PreferenceManager}) supports only flat values — no nested objects.
 * Each palette is therefore collapsed to one line, and the whole library is stored
 * as a string-list preference.
 *
 * <h2>Format</h2>
 * <pre>{@code  <escaped-name>|<slot0>;<slot1>;...;<slot9> }</pre>
 * where each slot is {@code <rrggbbaa>-<STROKE_ENUM_NAME>} — eight hex digits of
 * RGBA colour followed by a {@link StrokeStyle} constant name.
 *
 * <h2>Robustness</h2>
 * The name is percent-escaped so the reserved characters {@code % , " | ;} survive
 * a round trip (the comma and quote also matter because {@code PreferenceManager}'s
 * JSON writer is comma/quote-sensitive). {@link #decode} never throws: a malformed
 * line yields {@link Optional#empty()} and is logged, so one bad entry cannot
 * corrupt the rest of the library. An unknown stroke name degrades to
 * {@link StrokeStyle#DEFAULT} — forward compatibility with palettes written by a
 * newer build.
 */
public final class PaletteCodec {

    private static final Logger logger = LoggerFactory.getLogger(PaletteCodec.class);

    private static final char FIELD_SEP = '|';
    private static final char SLOT_SEP = ';';
    private static final char STYLE_SEP = '-';

    /** Characters percent-escaped within a palette name. */
    private static final String RESERVED = "%,\"|;";

    private PaletteCodec() {
    }

    /** Encodes a palette to its single-line string form. */
    public static String encode(PlotPalette palette) {
        StringBuilder sb = new StringBuilder();
        sb.append(escape(palette.name())).append(FIELD_SEP);

        List<LineStyle> entries = palette.entries();
        for (int i = 0; i < entries.size(); i++) {
            if (i > 0) {
                sb.append(SLOT_SEP);
            }
            LineStyle style = entries.get(i);
            sb.append(toHex8(style.color())).append(STYLE_SEP).append(style.stroke().name());
        }
        return sb.toString();
    }

    /**
     * Decodes a palette string. Returns {@link Optional#empty()} (and logs) for any
     * structurally invalid input; the result is always a non-built-in palette, since
     * built-ins live in code and are never persisted.
     */
    public static Optional<PlotPalette> decode(String encoded) {
        if (encoded == null) {
            return Optional.empty();
        }
        int sep = encoded.indexOf(FIELD_SEP);
        if (sep < 0) {
            logger.warn("Discarding malformed palette (no name separator): {}", encoded);
            return Optional.empty();
        }

        String name = unescape(encoded.substring(0, sep));
        String[] slots = encoded.substring(sep + 1).split(String.valueOf(SLOT_SEP), -1);
        if (slots.length != PlotPalette.SLOT_COUNT) {
            logger.warn("Discarding malformed palette '{}': expected {} slots, got {}",
                name, PlotPalette.SLOT_COUNT, slots.length);
            return Optional.empty();
        }

        List<LineStyle> entries = new ArrayList<>(PlotPalette.SLOT_COUNT);
        for (String slot : slots) {
            LineStyle style = decodeSlot(slot, name);
            if (style == null) {
                return Optional.empty();
            }
            entries.add(style);
        }

        try {
            return Optional.of(new PlotPalette(name, false, entries));
        } catch (IllegalArgumentException e) {
            logger.warn("Discarding malformed palette: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /** Decodes one {@code rrggbbaa-STROKE} slot, or {@code null} if malformed. */
    private static LineStyle decodeSlot(String slot, String paletteName) {
        int sep = slot.indexOf(STYLE_SEP);
        if (sep < 0) {
            logger.warn("Discarding palette '{}': malformed slot '{}'", paletteName, slot);
            return null;
        }

        Color color = fromHex8(slot.substring(0, sep));
        if (color == null) {
            logger.warn("Discarding palette '{}': bad colour in slot '{}'", paletteName, slot);
            return null;
        }

        String strokeName = slot.substring(sep + 1);
        StrokeStyle stroke;
        try {
            stroke = StrokeStyle.valueOf(strokeName);
        } catch (IllegalArgumentException e) {
            logger.warn("Palette '{}': unknown stroke '{}', falling back to default",
                paletteName, strokeName);
            stroke = StrokeStyle.DEFAULT;
        }
        return new LineStyle(color, stroke);
    }

    /** Formats a colour as eight lowercase hex digits, RGBA order. */
    static String toHex8(Color c) {
        return String.format("%02x%02x%02x%02x",
            c.getRed(), c.getGreen(), c.getBlue(), c.getAlpha());
    }

    /** Parses eight RGBA hex digits, or {@code null} if not exactly eight valid digits. */
    static Color fromHex8(String hex) {
        if (hex == null || hex.length() != 8) {
            return null;
        }
        try {
            return new Color(
                Integer.parseInt(hex.substring(0, 2), 16),
                Integer.parseInt(hex.substring(2, 4), 16),
                Integer.parseInt(hex.substring(4, 6), 16),
                Integer.parseInt(hex.substring(6, 8), 16));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Percent-escapes the {@link #RESERVED} characters; all others pass through. */
    static String escape(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (RESERVED.indexOf(ch) >= 0) {
                sb.append('%').append(String.format("%02X", (int) ch));
            } else {
                sb.append(ch);
            }
        }
        return sb.toString();
    }

    /** Reverses {@link #escape}; a stray {@code %} not followed by two hex digits is literal. */
    static String unescape(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '%' && i + 2 < s.length()) {
                try {
                    sb.append((char) Integer.parseInt(s.substring(i + 1, i + 3), 16));
                    i += 2;
                    continue;
                } catch (NumberFormatException e) {
                    // Not a valid escape sequence — fall through and treat '%' literally.
                }
            }
            sb.append(ch);
        }
        return sb.toString();
    }
}
