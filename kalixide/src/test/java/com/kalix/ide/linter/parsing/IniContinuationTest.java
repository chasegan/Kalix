package com.kalix.ide.linter.parsing;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IniContinuationTest {

    // --- isContinuationLine -------------------------------------------------

    @Test
    void isContinuationLine_indented() {
        assertTrue(IniContinuation.isContinuationLine("    foo"));
        assertTrue(IniContinuation.isContinuationLine("\tfoo"));
        assertTrue(IniContinuation.isContinuationLine("   ")); // whitespace-only still qualifies (matches parser)
    }

    @Test
    void isContinuationLine_flushLeft() {
        assertFalse(IniContinuation.isContinuationLine("foo = bar"));
        assertFalse(IniContinuation.isContinuationLine("[section]"));
        assertFalse(IniContinuation.isContinuationLine("# comment"));
    }

    @Test
    void isContinuationLine_emptyAndNull() {
        assertFalse(IniContinuation.isContinuationLine(""));
        assertFalse(IniContinuation.isContinuationLine(null));
    }

    // --- findOwningPropertyLine ---------------------------------------------

    @Test
    void currentLineIsPropertyHeader_returnsItself() {
        String[] lines = {
                "[node.foo]",
                "key = value",
        };
        assertEquals(1, IniContinuation.findOwningPropertyLine(lines, 1));
    }

    @Test
    void singleContinuation_returnsHeader() {
        String[] lines = {
                "[node.foo]",
                "key = a,",
                "    b,",
        };
        assertEquals(1, IniContinuation.findOwningPropertyLine(lines, 2));
    }

    @Test
    void multipleContinuations_returnsHeader() {
        String[] lines = {
                "[node.foo]",
                "key = a,",
                "    b,",
                "    c,",
                "    d",
        };
        assertEquals(1, IniContinuation.findOwningPropertyLine(lines, 4));
        assertEquals(1, IniContinuation.findOwningPropertyLine(lines, 3));
        assertEquals(1, IniContinuation.findOwningPropertyLine(lines, 2));
    }

    @Test
    void blankLineBreaksChain() {
        String[] lines = {
                "[node.foo]",
                "key = a,",
                "",
                "    b,",  // orphaned: blank line breaks the chain above it
        };
        assertEquals(-1, IniContinuation.findOwningPropertyLine(lines, 3));
    }

    @Test
    void cursorOnBlankLine_returnsMinusOne() {
        String[] lines = {
                "[node.foo]",
                "key = a,",
                "",
                "    b,",
        };
        assertEquals(-1, IniContinuation.findOwningPropertyLine(lines, 2));
    }

    @Test
    void cursorOnSectionHeader_returnsMinusOne() {
        String[] lines = {
                "[node.foo]",
                "key = a",
        };
        assertEquals(-1, IniContinuation.findOwningPropertyLine(lines, 0));
    }

    @Test
    void cursorOnFlushLeftComment_returnsMinusOne() {
        String[] lines = {
                "# header",
                "key = a",
        };
        assertEquals(-1, IniContinuation.findOwningPropertyLine(lines, 0));
        // Semicolon variant
        assertEquals(-1, IniContinuation.findOwningPropertyLine(new String[]{"; note"}, 0));
    }

    @Test
    void flushLeftCommentBetween_breaksChain() {
        String[] lines = {
                "key = a,",
                "    b,",
                "# interjected comment",
                "    c,",
        };
        // Line 3 is an indented continuation, but walking back hits a flush-left
        // comment before finding a header, so it is not owned.
        assertEquals(-1, IniContinuation.findOwningPropertyLine(lines, 3));
        // Line 1 still resolves to the header on line 0.
        assertEquals(0, IniContinuation.findOwningPropertyLine(lines, 1));
    }

    @Test
    void sectionHeaderBetween_breaksChain() {
        String[] lines = {
                "key = a,",
                "[other]",
                "    b,",
        };
        assertEquals(-1, IniContinuation.findOwningPropertyLine(lines, 2));
    }

    @Test
    void whitespaceOnlyLineWithinChain_isStillContinuation() {
        // Parser counts whitespace-only lines as continuation (contributing
        // nothing), so the chain stays intact across them.
        String[] lines = {
                "key = a,",
                "    b,",
                "    ",
                "    c,",
        };
        assertEquals(0, IniContinuation.findOwningPropertyLine(lines, 3));
    }

    @Test
    void commentOnlyContinuationLine_isStillContinuation() {
        // An indented comment line starts with whitespace, so isContinuationLine
        // is true; the chain continues across it.
        String[] lines = {
                "key = a,",
                "    b,",
                "    # note in middle",
                "    c,",
        };
        assertEquals(0, IniContinuation.findOwningPropertyLine(lines, 3));
    }

    @Test
    void orphanIndentedLineAtStartOfFile_returnsMinusOne() {
        String[] lines = {
                "    orphan",
        };
        assertEquals(-1, IniContinuation.findOwningPropertyLine(lines, 0));
    }

    @Test
    void propertyHeaderAtFirstLine() {
        String[] lines = {
                "key = a,",
                "    b,",
        };
        assertEquals(0, IniContinuation.findOwningPropertyLine(lines, 0));
        assertEquals(0, IniContinuation.findOwningPropertyLine(lines, 1));
    }

    @Test
    void propertyHeaderWithInlineComment_isRecognised() {
        String[] lines = {
                "key = a,  # trailing comment",
                "    b,",
        };
        assertEquals(0, IniContinuation.findOwningPropertyLine(lines, 1));
    }

    @Test
    void commentedLineWithEqualsInside_isNotAPropertyHeader() {
        // A flush-left comment containing '=' is still just a comment; it
        // breaks the chain instead of acting as a header.
        String[] lines = {
                "# key = sneaky",
                "    b,",
        };
        assertEquals(-1, IniContinuation.findOwningPropertyLine(lines, 1));
    }

    @Test
    void lineWithoutEquals_isNotAPropertyHeader() {
        // Bare entries (e.g. lines in [outputs]) are not property headers in
        // the key=value sense, so an indented line under them is not owned.
        String[] lines = {
                "[outputs]",
                "node.foo.recorder",
                "    indented",
        };
        assertEquals(-1, IniContinuation.findOwningPropertyLine(lines, 2));
    }

    @Test
    void outOfRangeIndex_returnsMinusOne() {
        String[] lines = {"key = a"};
        assertEquals(-1, IniContinuation.findOwningPropertyLine(lines, -1));
        assertEquals(-1, IniContinuation.findOwningPropertyLine(lines, 5));
        assertEquals(-1, IniContinuation.findOwningPropertyLine(null, 0));
    }

    @Test
    void tabIndentedContinuation_works() {
        String[] lines = {
                "key = a,",
                "\tb,",
        };
        assertEquals(0, IniContinuation.findOwningPropertyLine(lines, 1));
    }

    // TODO(continuation-comment-policy): A flush-left comment in the middle of
    // a value's continuation chain currently terminates the chain - both here
    // and in INIModelParser.collectContinuationLines. Pythonic implicit-
    // continuation conventions would skip such comments instead. Revisit
    // together with the backend parser; if the policy flips, this test and the
    // parser must change together so the editor and validator agree.
    @Test
    void flushLeftCommentInsideMultiLineExpression_terminatesChain() {
        String[] lines = {
                "rain = 4 + data.my_file.by_index.1 # some comment",
                "   + 1234 +",
                "#   log(3)",
                "   4",
                "",
                "",
        };
        assertEquals(0, IniContinuation.findOwningPropertyLine(lines, 0));
        assertEquals(0, IniContinuation.findOwningPropertyLine(lines, 1));
        assertEquals(-1, IniContinuation.findOwningPropertyLine(lines, 2));
        assertEquals(-1, IniContinuation.findOwningPropertyLine(lines, 3));
        assertEquals(-1, IniContinuation.findOwningPropertyLine(lines, 4));
        assertEquals(-1, IniContinuation.findOwningPropertyLine(lines, 5));
    }
}
