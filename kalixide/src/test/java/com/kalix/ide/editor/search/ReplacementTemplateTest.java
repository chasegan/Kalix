package com.kalix.ide.editor.search;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Pins template expansion to {@code Matcher}'s behaviour.
 *
 * <p>The expansion is reproduced rather than delegated (see
 * {@link ReplacementTemplate}), so it has to be held to the original's semantics —
 * including the awkward corners: greedy-but-valid group numbers, unmatched optional
 * groups contributing nothing, and the exact failure cases.</p>
 */
class ReplacementTemplateTest {

    /** Expands {@code template} against the first match of {@code regex} in {@code input}. */
    private static String expand(String regex, String input, String template) {
        Matcher matcher = Pattern.compile(regex).matcher(input);
        if (!matcher.find()) {
            throw new IllegalStateException("fixture did not match");
        }
        return ReplacementTemplate.expand(template, matcher);
    }

    /**
     * What the JDK produces for the same inputs — the behaviour being reproduced.
     *
     * <p>{@code replaceFirst} returns the whole input with the match substituted, not the
     * expansion alone, so this is only equal to {@code expand} when the match covers the
     * entire input. Every fixture below is written that way on purpose; a fixture that
     * is not would compare two different things and pass or fail for the wrong reason.</p>
     */
    private static String jdkWholeInputExpansion(String regex, String input, String template) {
        Matcher matcher = Pattern.compile(regex).matcher(input);
        if (!matcher.matches()) {
            throw new IllegalStateException("fixture must match the whole input: " + regex);
        }
        return matcher.replaceFirst(template);
    }

    @Test
    @DisplayName("Plain text passes through untouched")
    void plainText() {
        assertEquals("weir", expand("dam", "the dam", "weir"));
    }

    @Test
    @DisplayName("$0 is the whole match; $n are the groups")
    void groupReferences() {
        assertEquals("dam", expand("(\\w+)_(\\w+)", "002_dam", "$2"));
        assertEquals("002", expand("(\\w+)_(\\w+)", "002_dam", "$1"));
        assertEquals("002_dam", expand("(\\w+)_(\\w+)", "002_dam", "$0"));
        assertEquals("dam-002", expand("(\\w+)_(\\w+)", "002_dam", "$2-$1"));
    }

    @Test
    @DisplayName("Named groups expand by name")
    void namedGroups() {
        assertEquals("dam", expand("(?<num>\\d+)_(?<name>\\w+)", "002_dam", "${name}"));
        assertEquals("dam/002", expand("(?<num>\\d+)_(?<name>\\w+)", "002_dam", "${name}/${num}"));
    }

    @Test
    @DisplayName("Backslash escapes the next character")
    void escapes() {
        assertEquals("$1", expand("dam", "dam", "\\$1"));
        assertEquals("\\", expand("dam", "dam", "\\\\"));
    }

    /**
     * Matcher extends a group number only while the longer number is a group that
     * exists, so the same template means different things against different patterns.
     */
    @Test
    @DisplayName("Group numbers extend greedily, but only as far as the groups go")
    void greedyGroupNumbers() {
        // One group: "$12" is group 1 followed by a literal "2".
        assertEquals("a2", expand("(a)", "a", "$12"));
        // Twelve groups: "$12" is group 12.
        String twelve = "(a)(b)(c)(d)(e)(f)(g)(h)(i)(j)(k)(l)";
        assertEquals("l", expand(twelve, "abcdefghijkl", "$12"));
    }

    @Test
    @DisplayName("An unmatched optional group contributes nothing")
    void unmatchedGroup() {
        assertEquals("a", expand("(a)(b)?", "a", "$1$2"));
        assertEquals("[a|]", expand("(a)(b)?", "a", "[$1|$2]"));
    }

    @Test
    @DisplayName("Agrees with the JDK on the same inputs")
    void agreesWithJdk() {
        record Case(String regex, String input, String template) { }
        for (Case c : new Case[]{
            new Case("(\\w+)_(\\w+)", "002_dam", "$2-$1"),
            new Case("(?<num>\\d+)_(?<name>\\w+)", "002_dam", "${name}"),
            new Case("(a)", "a", "$12"),
            new Case("(a)(b)?", "a", "[$1|$2]"),
            new Case("dam", "dam", "\\$literal"),
        }) {
            assertEquals(jdkWholeInputExpansion(c.regex(), c.input(), c.template()),
                expand(c.regex(), c.input(), c.template()),
                "template " + c.template() + " on " + c.input());
        }
    }

    @Test
    @DisplayName("A trailing backslash is rejected")
    void trailingBackslash() {
        assertThrows(IllegalArgumentException.class, () -> expand("dam", "dam", "x\\"));
    }

    @Test
    @DisplayName("A dangling or non-numeric $ is rejected")
    void malformedGroupReference() {
        assertThrows(IllegalArgumentException.class, () -> expand("dam", "dam", "x$"));
        assertThrows(IllegalArgumentException.class, () -> expand("dam", "dam", "$x"));
    }

    @Test
    @DisplayName("A named group missing its closing brace is rejected")
    void unterminatedNamedGroup() {
        assertThrows(IllegalArgumentException.class,
            () -> expand("(?<name>\\w+)", "dam", "${name"));
    }

    @Test
    @DisplayName("Referencing a group the pattern lacks is rejected")
    void missingGroup() {
        assertThrows(IndexOutOfBoundsException.class, () -> expand("(a)", "a", "$9"));
    }
}
