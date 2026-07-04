package com.kalix.ide.interaction;

import com.kalix.ide.interaction.TextCoordinateUpdater.TextSpan;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the pure deletion-span computation behind node/link deletion,
 * especially review finding #23: deleting a node must also delete dangling
 * {@code ds_N = <node>} references in surviving sections.
 */
class TextCoordinateUpdaterDeletionTest {

    /** Applies spans (already merged and ascending) to the text, like deleteSelectedElements does. */
    private static String apply(String text, List<TextSpan> spans) {
        StringBuilder sb = new StringBuilder(text);
        for (int i = spans.size() - 1; i >= 0; i--) {
            sb.delete(spans.get(i).start(), spans.get(i).end());
        }
        return sb.toString();
    }

    @Test
    void deletingNodeRemovesItsSection() {
        String text = "[node.a]\nloc = 1, 1\n\n[node.b]\nloc = 2, 2\n";
        String result = apply(text, TextCoordinateUpdater.computeDeletionSpans(text, Set.of("a"), Set.of()));
        assertEquals("[node.b]\nloc = 2, 2\n", result);
    }

    @Test
    void deletingNodeRemovesDanglingReferencesInSurvivingSections() {
        String text = "[node.a]\nloc = 1, 1\nds_1 = b\n\n[node.b]\nloc = 2, 2\n\n[node.c]\nds_1 = b\nds_2 = a\nloc = 3, 3\n";
        String result = apply(text, TextCoordinateUpdater.computeDeletionSpans(text, Set.of("b"), Set.of()));
        assertFalse(result.contains("[node.b]"));
        assertFalse(result.contains("ds_1 = b"), "dangling references must be removed");
        assertTrue(result.contains("ds_2 = a"), "references to surviving nodes must remain");
        assertTrue(result.contains("[node.a]"));
        assertTrue(result.contains("[node.c]"));
    }

    @Test
    void danglingReferenceWithInlineCommentIsRemoved() {
        String text = "[node.a]\nds_1 = b # main channel\nloc = 1, 1\n\n[node.b]\nloc = 2, 2\n";
        String result = apply(text, TextCoordinateUpdater.computeDeletionSpans(text, Set.of("b"), Set.of()));
        assertFalse(result.contains("ds_1"));
        assertTrue(result.contains("loc = 1, 1"));
    }

    @Test
    void referencesInsideDeletedSectionsAreNotDoubleDeleted() {
        // node.a's own ds line sits inside its deleted section; the merge must
        // swallow it rather than producing an overlapping second deletion.
        String text = "[node.a]\nds_1 = b\nloc = 1, 1\n\n[node.b]\nloc = 2, 2\n";
        List<TextSpan> spans = TextCoordinateUpdater.computeDeletionSpans(text, Set.of("a", "b"), Set.of());
        for (int i = 1; i < spans.size(); i++) {
            assertTrue(spans.get(i).start() >= spans.get(i - 1).end(), "spans must not overlap");
        }
        assertEquals("", apply(text, spans).trim());
    }

    @Test
    void deletingLinkRemovesOnlyItsPropertyLine() {
        String text = "[node.a]\nds_1 = b\nds_2 = c\nloc = 1, 1\n\n[node.b]\nloc = 2, 2\n";
        String result = apply(text, TextCoordinateUpdater.computeDeletionSpans(text, Set.of(), Set.of("a->b")));
        assertFalse(result.contains("ds_1 = b"));
        assertTrue(result.contains("ds_2 = c"));
        assertTrue(result.contains("[node.a]"));
        assertTrue(result.contains("[node.b]"));
    }

    @Test
    void selectedLinkAlsoTargetingDeletedNodeIsDeletedExactlyOnce() {
        // The link a->b is selected AND b is deleted: the ds line qualifies twice
        // but must be removed exactly once.
        String text = "[node.a]\nds_1 = b\nloc = 1, 1\n\n[node.b]\nloc = 2, 2\n";
        List<TextSpan> spans = TextCoordinateUpdater.computeDeletionSpans(text, Set.of("b"), Set.of("a->b"));
        for (int i = 1; i < spans.size(); i++) {
            assertTrue(spans.get(i).start() >= spans.get(i - 1).end(), "spans must not overlap");
        }
        String result = apply(text, spans);
        assertEquals("[node.a]\nloc = 1, 1\n\n", result);
    }

    @Test
    void linkWhoseSourceIsDeletedIsHandledBySectionRemoval() {
        String text = "[node.a]\nds_1 = b\nloc = 1, 1\n\n[node.b]\nloc = 2, 2\n";
        String result = apply(text, TextCoordinateUpdater.computeDeletionSpans(text, Set.of("a"), Set.of("a->b")));
        assertEquals("[node.b]\nloc = 2, 2\n", result);
    }

    @Test
    void deletingLastSectionInFileWithoutTrailingNewline() {
        String text = "[node.a]\nloc = 1, 1\n\n[node.b]\nloc = 2, 2";
        String result = apply(text, TextCoordinateUpdater.computeDeletionSpans(text, Set.of("b"), Set.of()));
        assertEquals("[node.a]\nloc = 1, 1\n\n", result);
    }

    @Test
    void commentedReferencesAreLeftAlone() {
        String text = "[node.a]\n# ds_1 = b\nloc = 1, 1\n\n[node.b]\nloc = 2, 2\n";
        String result = apply(text, TextCoordinateUpdater.computeDeletionSpans(text, Set.of("b"), Set.of()));
        assertTrue(result.contains("# ds_1 = b"));
    }
}
