package com.kalix.ide.linter.parsing;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the IDE's copy of the engine's line grammar ({@code src/io/custom_ini_parser.rs}):
 * '#' is the only comment marker, it is inert inside double quotes, and a section
 * header may carry a trailing comment.
 */
class IniSyntaxTest {

    // --- commentStart ---

    @Test
    void hashStartsAComment() {
        assertEquals(8, IniSyntax.commentStart("a = 1.0 # note"));
        assertEquals(0, IniSyntax.commentStart("# whole line"));
        assertEquals(-1, IniSyntax.commentStart("a = 1.0"));
        assertEquals(-1, IniSyntax.commentStart(""));
        assertEquals(-1, IniSyntax.commentStart(null));
    }

    @Test
    void semicolonIsNotAComment() {
        // ';' terminates statements in expression blocks; it never starts a comment.
        assertEquals(-1, IniSyntax.commentStart("expr = { x = 1; x + 1 }"));
        assertEquals(-1, IniSyntax.commentStart("; looks like an INI comment, is not"));
    }

    @Test
    void hashInsideQuotesIsNotAComment() {
        String line = "alias = \"a # b\" # real";
        assertEquals(line.indexOf("# real"), IniSyntax.commentStart(line));
        assertEquals(-1, IniSyntax.commentStart("\"weird # name.csv\""));
    }

    @Test
    void backslashEscapesTheNextCharacter() {
        assertEquals(-1, IniSyntax.commentStart("a = \"say \\\" # \" no"));
        assertEquals(-1, IniSyntax.commentStart("a = \\# not a comment"));
    }

    @Test
    void rangeFormScansOnlyTheWindow() {
        String line = "# before | value # after";
        int bar = line.indexOf('|');
        assertEquals(line.indexOf("# after"), IniSyntax.commentStart(line, bar, line.length()));
        assertEquals(-1, IniSyntax.commentStart(line, bar, line.indexOf("# after")));
    }

    // --- stripComment / isBlankOrComment ---

    @Test
    void stripCommentKeepsLeadingWhitespaceAndTrimsTrailing() {
        assertEquals("   0.5, 0.8,", IniSyntax.stripComment("   0.5, 0.8,   # continuation"));
        assertEquals("a = 1", IniSyntax.stripComment("a = 1   "));
        assertEquals("", IniSyntax.stripComment("# only"));
        assertEquals("", IniSyntax.stripComment(null));
    }

    @Test
    void blankOrCommentLines() {
        assertTrue(IniSyntax.isBlankOrComment(""));
        assertTrue(IniSyntax.isBlankOrComment("   "));
        assertTrue(IniSyntax.isBlankOrComment("  # indented comment"));
        assertFalse(IniSyntax.isBlankOrComment("; not a comment"));
        assertFalse(IniSyntax.isBlankOrComment("[node.a] # header"));
    }

    // --- section headers ---

    @Test
    void sectionNameToleratesWhitespaceAndTrailingComment() {
        assertEquals("node.a", IniSyntax.sectionName("[node.a]"));
        assertEquals("node.a", IniSyntax.sectionName("  [node.a]  "));
        assertEquals("node.a", IniSyntax.sectionName("[node.a]   # UAW in Bulloo"));
        assertEquals("node.a", IniSyntax.sectionName("[node.a] # see [node.b], x = 1"));
        assertEquals("", IniSyntax.sectionName("[]"));
    }

    @Test
    void sectionNameRejectsMalformedHeaders() {
        assertNull(IniSyntax.sectionName("[node.a"));
        assertNull(IniSyntax.sectionName("[node.a] trailing junk"));
        assertNull(IniSyntax.sectionName("["));
        assertNull(IniSyntax.sectionName("a = [1]"));
        assertNull(IniSyntax.sectionName("# [node.a]"));
        assertNull(IniSyntax.sectionName(null));
    }

    @Test
    void headerLineIsAnyLineWhoseCodeStartsWithBracket() {
        assertTrue(IniSyntax.isSectionHeaderLine("[node.a]"));
        assertTrue(IniSyntax.isSectionHeaderLine("[node.a] # c"));
        assertTrue(IniSyntax.isSectionHeaderLine("[node.a"));      // malformed, still a header attempt
        assertFalse(IniSyntax.isSectionHeaderLine("# [node.a]"));
        assertFalse(IniSyntax.isSectionHeaderLine("a = [1]"));
        assertFalse(IniSyntax.isSectionHeaderLine(""));
    }
}
