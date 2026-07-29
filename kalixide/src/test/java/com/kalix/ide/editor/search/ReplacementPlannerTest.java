package com.kalix.ide.editor.search;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the plan a Replace All will execute.
 *
 * <p>The plan is the contract between the two halves of a replace: it is computed off
 * the EDT and then applied verbatim, so anything wrong here is written into the
 * document. The property that matters most is that applying it back to front reproduces
 * what a naive whole-document replace would have produced.</p>
 */
class ReplacementPlannerTest {

    private static final int NO_CAP = Integer.MAX_VALUE;

    private static List<Replacement> plan(String text, SearchQuery query, String template) {
        return ReplacementPlanner.plan(text, query, template, NO_CAP);
    }

    private static SearchQuery literal(String term) {
        return new SearchQuery(term, true, false, false);
    }

    private static SearchQuery regex(String term) {
        return new SearchQuery(term, true, false, true);
    }

    /**
     * Applies a plan the way {@code ChunkedReplacer} does — last match first, so every
     * remaining offset still refers to text that has not moved.
     */
    private static String applyBackToFront(String text, List<Replacement> plan) {
        StringBuilder result = new StringBuilder(text);
        for (int i = plan.size() - 1; i >= 0; i--) {
            Replacement r = plan.get(i);
            result.replace(r.start(), r.end(), r.text());
        }
        return result.toString();
    }

    @Test
    @DisplayName("A literal plan maps every match to the same text")
    void literalPlan() {
        List<Replacement> plan = plan("dam and dam", literal("dam"), "weir");
        assertEquals(2, plan.size());
        assertEquals(new Replacement(0, 3, "weir"), plan.get(0));
        assertEquals(new Replacement(8, 11, "weir"), plan.get(1));
    }

    @Test
    @DisplayName("Applying a literal plan back to front rewrites the text correctly")
    void literalRoundTrip() {
        String text = "dam and dam and dam";
        assertEquals("weir and weir and weir",
            applyBackToFront(text, plan(text, literal("dam"), "weir")));
    }

    @Test
    @DisplayName("Replacement text of a different length does not disturb later offsets")
    void differingLengths() {
        String text = "a a a";
        assertEquals("longer longer longer",
            applyBackToFront(text, plan(text, literal("a"), "longer")));
        assertEquals("  ", applyBackToFront("a a a", plan("a a a", literal("a"), "")));
    }

    @Test
    @DisplayName("A regex plan expands group references per match")
    void regexPlanExpandsGroups() {
        String text = "002_dam 003_weir";
        List<Replacement> plan = plan(text, regex("(\\d+)_(\\w+)"), "$2-$1");
        assertEquals(2, plan.size());
        assertEquals("dam-002", plan.get(0).text());
        assertEquals("weir-003", plan.get(1).text());
        assertEquals("dam-002 weir-003", applyBackToFront(text, plan));
    }

    /**
     * Anchors must be evaluated against the whole document. Planning each match from its
     * own isolated text would make every match look line-initial.
     */
    @Test
    @DisplayName("Anchors are evaluated against the whole document, not per match")
    void anchorsSeeTheWholeDocument() {
        String text = "dam\nxdam\ndam";
        List<Replacement> plan = plan(text, regex("^dam"), "weir");
        assertEquals(2, plan.size());
        assertEquals(0, plan.get(0).start());
        assertEquals(9, plan.get(1).start());
        assertEquals("weir\nxdam\nweir", applyBackToFront(text, plan));
    }

    @Test
    @DisplayName("Matches do not overlap, matching the scanner")
    void nonOverlapping() {
        assertEquals("bb", applyBackToFront("aaaa", plan("aaaa", literal("aa"), "b")));
    }

    @Test
    @DisplayName("No matches yields an empty plan")
    void noMatches() {
        assertTrue(plan("nothing here", literal("dam"), "weir").isEmpty());
        assertTrue(plan("", literal("dam"), "weir").isEmpty());
        assertTrue(plan("dam", literal(""), "weir").isEmpty());
    }

    @Test
    @DisplayName("The cap bounds a literal plan")
    void capBoundsPlan() {
        assertEquals(2, ReplacementPlanner.plan("a a a a", literal("a"), "b", 2).size());
        assertEquals(2, ReplacementPlanner.plan("a a a a", regex("a"), "b", 2).size());
    }

    @Test
    @DisplayName("A bad template fails during planning, before anything is written")
    void badTemplateFailsBeforeAnyEdit() {
        assertThrows(IndexOutOfBoundsException.class,
            () -> plan("002_dam", regex("(\\d+)_(\\w+)"), "$9"));
        assertThrows(IllegalArgumentException.class,
            () -> plan("002_dam", regex("(\\d+)_(\\w+)"), "trailing\\"));
    }

    @Test
    @DisplayName("A literal template is not interpreted as a group reference")
    void literalTemplateIsNotExpanded() {
        // "$1" is meaningless in a literal replace and must survive verbatim.
        assertEquals("$1 $1", applyBackToFront("a a", plan("a a", literal("a"), "$1")));
    }
}
