package com.kalix.ide.windows.optimisation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.function.ToIntFunction;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link ModelSelectorPanel#elideHead}, which shortens a label to fit the
 * closed combo.
 *
 * <p>Width is measured by an injected function, so these run without a font: one
 * character is one unit, which makes the expected results readable.</p>
 */
class ModelSelectorPanelTest {

    /** One unit per character — the ellipsis included, so it costs one too. */
    private static final ToIntFunction<String> WIDTH = String::length;

    @Test
    @DisplayName("A label that already fits is untouched")
    void testShortLabelIsUnchanged() {
        assertEquals("model.ini", ModelSelectorPanel.elideHead("model.ini", WIDTH, 20));
        // Exactly filling the space still counts as fitting.
        assertEquals("model.ini", ModelSelectorPanel.elideHead("model.ini", WIDTH, 9));
    }

    @Test
    @DisplayName("A long label loses leading folders, never the file name")
    void testElisionTrimsTheHead() {
        String label = "alpha/run/model.ini";  // 19 units

        String elided = ModelSelectorPanel.elideHead(label, WIDTH, 14);

        // Trimming the tail would give "alpha/run/mod…", hiding what identifies the model.
        assertTrue(elided.startsWith("…"), "expected a leading ellipsis, got: " + elided);
        assertTrue(elided.endsWith("model.ini"), "the file name must survive, got: " + elided);
        assertTrue(WIDTH.applyAsInt(elided) <= 14, "still too wide: " + elided);
    }

    @Test
    @DisplayName("Elision keeps as much of the path as fits")
    void testElisionKeepsAsMuchAsFits() {
        String label = "alpha/run/model.ini";

        // 14 units: "…" + the last 13 characters.
        assertEquals("…" + label.substring(label.length() - 13),
                ModelSelectorPanel.elideHead(label, WIDTH, 14));
        // One unit narrower drops exactly one more character.
        assertEquals("…" + label.substring(label.length() - 12),
                ModelSelectorPanel.elideHead(label, WIDTH, 13));
    }

    @Test
    @DisplayName("An unmeasured combo is left alone rather than elided to nothing")
    void testZeroWidthLeavesLabelIntact() {
        // Before the combo has been laid out its available width is 0; eliding then would
        // flash an ellipsis on first paint.
        assertEquals("alpha/run/model.ini",
                ModelSelectorPanel.elideHead("alpha/run/model.ini", WIDTH, 0));
        assertEquals("alpha/run/model.ini",
                ModelSelectorPanel.elideHead("alpha/run/model.ini", WIDTH, -5));
    }

    @Test
    @DisplayName("A space too small for any text degrades to the ellipsis alone")
    void testDegenerateWidth() {
        assertEquals("…", ModelSelectorPanel.elideHead("model.ini", WIDTH, 1));
    }

    @Test
    @DisplayName("Null and empty labels are returned unchanged")
    void testNullAndEmpty() {
        assertNull(ModelSelectorPanel.elideHead(null, WIDTH, 10));
        assertEquals("", ModelSelectorPanel.elideHead("", WIDTH, 10));
    }
}
