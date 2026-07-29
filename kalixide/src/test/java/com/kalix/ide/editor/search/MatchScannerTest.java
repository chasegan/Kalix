package com.kalix.ide.editor.search;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.regex.PatternSyntaxException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins {@link MatchScanner} to RSTA's matching rules.
 *
 * <p>The counter is only worth having if it agrees with the matches Find Next actually
 * visits, so these lock down the three rules that are easy to get wrong: non-overlap,
 * the literal/regex whole-word asymmetry, and the regex flags.</p>
 */
class MatchScannerTest {

    private static final int NO_CAP = Integer.MAX_VALUE;

    private static List<Integer> starts(String text, String term,
                                        boolean matchCase, boolean wholeWord, boolean regex) {
        return MatchScanner.scan(text, new SearchQuery(term, matchCase, wholeWord, regex), NO_CAP)
            .matches().stream().map(MatchScan.MatchRange::start).toList();
    }

    private static List<Integer> literal(String text, String term, boolean matchCase, boolean wholeWord) {
        return starts(text, term, matchCase, wholeWord, false);
    }

    private static List<Integer> regex(String text, String term, boolean matchCase, boolean wholeWord) {
        return starts(text, term, matchCase, wholeWord, true);
    }

    @Test
    @DisplayName("Literal search finds every occurrence, in document order")
    void literalFindsAllInOrder() {
        assertEquals(List.of(0, 9, 18), literal("dam here dam here dam", "dam", true, false));
    }

    /**
     * The engine resumes <em>after</em> each match, so occurrences never overlap. A
     * naive scanner steps one character at a time and reports three matches here where
     * Find Next only ever visits two.
     */
    @Test
    @DisplayName("Matches do not overlap")
    void matchesDoNotOverlap() {
        assertEquals(List.of(0, 2), literal("aaaa", "aa", true, false));
        assertEquals(List.of(0), literal("aaa", "aa", true, false));
    }

    @Test
    @DisplayName("Match case off folds case; on does not")
    void caseSensitivity() {
        assertEquals(List.of(0, 4), literal("Dam dam", "dam", false, false));
        assertEquals(List.of(4), literal("Dam dam", "dam", true, false));
    }

    @Test
    @DisplayName("Empty term, empty text or a zero cap yields no matches")
    void degenerateInputs() {
        assertTrue(literal("some text", "", true, false).isEmpty());
        assertTrue(literal("", "dam", true, false).isEmpty());
        assertTrue(MatchScanner.scan("dam dam", new SearchQuery("dam", true, false, false), 0)
            .isEmpty());
    }

    /**
     * RSTA's literal whole-word test asks whether the neighbours are letters or digits,
     * so an underscore is a boundary and {@code 002_dam} contains a whole-word
     * {@code dam}. Directly load-bearing for Kalix node names.
     */
    @Test
    @DisplayName("Literal whole-word treats underscore as a boundary, as RSTA does")
    void literalWholeWordUnderscoreIsBoundary() {
        assertEquals(List.of(4, 12), literal("002_dam 002_dam", "dam", false, true));
    }

    @Test
    @DisplayName("Literal whole-word rejects matches flanked by letters or digits")
    void literalWholeWordRejectsAlphanumericNeighbours() {
        assertTrue(literal("damage", "dam", true, true).isEmpty());
        assertTrue(literal("2dam", "dam", true, true).isEmpty());
        assertEquals(List.of(0), literal("dam.ds_1", "dam", true, true));
    }

    @Test
    @DisplayName("Regex whole-word wraps in \\b, so underscore is NOT a boundary")
    void regexWholeWordUsesWordBoundary() {
        // Deliberately the opposite of the literal case above. The asymmetry is RSTA's,
        // and it is reproduced rather than smoothed over.
        assertTrue(regex("002_dam", "dam", true, true).isEmpty());
        assertEquals(List.of(0), regex("dam here", "dam", true, true));
    }

    @Test
    @DisplayName("Regex mode matches patterns, honouring case folding")
    void regexMatches() {
        assertEquals(List.of(0, 9), regex("node.001 node.002", "node\\.\\d+", true, false));
        assertEquals(List.of(0), regex("NODE.001", "node\\.\\d+", false, false));
        assertTrue(regex("NODE.001", "node\\.\\d+", true, false).isEmpty());
    }

    @Test
    @DisplayName("A zero-length regex match advances instead of looping forever")
    void zeroLengthRegexTerminates() {
        assertEquals(List.of(0, 1, 2), regex("ab", "x*", true, false));
    }

    @Test
    @DisplayName("Invalid regex surfaces as PatternSyntaxException for the caller to report")
    void invalidRegexThrows() {
        assertThrows(PatternSyntaxException.class, () -> regex("text", "[", true, false));
    }

    @Test
    @DisplayName("Multiline flag lets ^ anchor per line")
    void multilineAnchors() {
        assertEquals(List.of(0, 9), regex("[node_a]\n[node_b]", "^\\[", true, false));
    }

    @Test
    @DisplayName("Match ranges span the matched text")
    void rangesCoverTheMatch() {
        MatchScan scan = MatchScanner.scan("xx dam xx",
            new SearchQuery("dam", true, false, false), NO_CAP);
        assertEquals(1, scan.count());
        assertEquals(3, scan.matches().get(0).start());
        assertEquals(6, scan.matches().get(0).end());
        assertEquals(3, scan.matches().get(0).length());
    }

    @Test
    @DisplayName("Scanning stops at the cap and says so")
    void capTruncates() {
        MatchScan scan = MatchScanner.scan("a a a a a",
            new SearchQuery("a", true, false, false), 3);
        assertEquals(3, scan.count());
        assertTrue(scan.truncated());

        MatchScan full = MatchScanner.scan("a a a",
            new SearchQuery("a", true, false, false), 3);
        assertEquals(3, full.count());
        assertFalse(full.truncated());
    }
}
