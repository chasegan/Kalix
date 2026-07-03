package com.kalix.ide.diff;

import com.github.difflib.text.DiffRow;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the diff view's inline-change marking survives literal '~' in the
 * compared texts (review #26): '~' used to be the in-band marker, so tildes in
 * model text were stripped and highlight ranges shifted.
 */
class DiffEngineInlineMarkerTest {

    /** Reassembles the cleaned text of one side of the diff, collecting highlight ranges. */
    private static String cleanSide(List<DiffRow> rows, boolean left, List<DiffEngine.InlineChange> changes) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < rows.size(); i++) {
            String line = left ? rows.get(i).getOldLine() : rows.get(i).getNewLine();
            if (line == null) line = "";
            sb.append(DiffEngine.stripInlineMarkers(line, sb.length(), changes));
            if (i < rows.size() - 1) sb.append('\n');
        }
        return sb.toString();
    }

    @Test
    void literalTildesSurviveTheDiffView() {
        String original = "flow = a ~ b\nsame = 1";
        String modified = "flow = a ~ c\nsame = 1";

        DiffResult result = DiffEngine.computeDiff(original, modified);
        List<DiffEngine.InlineChange> leftChanges = new ArrayList<>();
        List<DiffEngine.InlineChange> rightChanges = new ArrayList<>();

        assertEquals(original, cleanSide(result.getRows(), true, leftChanges));
        assertEquals(modified, cleanSide(result.getRows(), false, rightChanges));
    }

    @Test
    void highlightRangesAddressTheChangedSegment() {
        String original = "name = old_value";
        String modified = "name = new_value";

        DiffResult result = DiffEngine.computeDiff(original, modified);
        List<DiffEngine.InlineChange> rightChanges = new ArrayList<>();
        String cleaned = cleanSide(result.getRows(), false, rightChanges);

        assertEquals(modified, cleaned);
        assertFalse(rightChanges.isEmpty());
        // Every reported range must lie within the cleaned text and cover changed text.
        StringBuilder highlighted = new StringBuilder();
        for (DiffEngine.InlineChange change : rightChanges) {
            assertTrue(change.startOffset() >= 0 && change.endOffset() <= cleaned.length());
            highlighted.append(cleaned, change.startOffset(), change.endOffset());
        }
        assertTrue(highlighted.toString().contains("new_value"),
            "highlighted text was: " + highlighted);
    }

    @Test
    void tildesInsideAChangedSegmentDoNotShiftRanges() {
        String original = "expr = x";
        String modified = "expr = ~x~"; // literal tildes in the changed segment itself

        DiffResult result = DiffEngine.computeDiff(original, modified);
        List<DiffEngine.InlineChange> rightChanges = new ArrayList<>();
        String cleaned = cleanSide(result.getRows(), false, rightChanges);

        assertEquals(modified, cleaned);
        assertFalse(rightChanges.isEmpty());
        for (DiffEngine.InlineChange change : rightChanges) {
            assertTrue(change.endOffset() <= cleaned.length());
        }
    }

    @Test
    void stripInlineMarkersHandlesMarkersAndOffsets() {
        List<DiffEngine.InlineChange> changes = new ArrayList<>();
        String line = "abc " + DiffEngine.INLINE_MARKER_OPEN + "XY" + DiffEngine.INLINE_MARKER_CLOSE + " tail";
        String cleaned = DiffEngine.stripInlineMarkers(line, 10, changes);

        assertEquals("abc XY tail", cleaned);
        assertEquals(1, changes.size());
        assertEquals(14, changes.get(0).startOffset()); // 10 + "abc ".length()
        assertEquals(16, changes.get(0).endOffset());
    }
}
