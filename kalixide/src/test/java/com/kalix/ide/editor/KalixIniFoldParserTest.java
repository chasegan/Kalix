package com.kalix.ide.editor;

import com.kalix.ide.editor.KalixIniFoldParser.Kind;
import com.kalix.ide.editor.KalixIniFoldParser.Range;
import com.kalix.ide.linter.parsing.IniSyntax;
import com.kalix.ide.model.NodeSectionLocator;
import com.kalix.ide.model.NodeSectionLocator.NodeSection;
import com.kalix.ide.model.RepositoryModels;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.kalix.ide.editor.KalixIniFoldParser.ranges;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The fold grammar: regions, sections and multi-line values, nested
 * region ⊃ section ⊃ value, never overlapping. A section or value ends at its last
 * line of code; a region ends at its last non-blank line, comments included.
 */
class KalixIniFoldParserTest {

    private static Range region(int first, int last) {
        return new Range(Kind.REGION, first, last);
    }

    private static Range section(int first, int last) {
        return new Range(Kind.SECTION, first, last);
    }

    private static Range value(int first, int last) {
        return new Range(Kind.VALUE, first, last);
    }

    // --- sections ---

    @Test
    void sectionFoldsFromItsHeaderToItsLastLineOfCode() {
        String text = """
                [node.a]
                type = inflow
                loc = 1, 2

                # about b
                [node.b]
                type = gauge
                """;
        assertEquals(List.of(section(0, 2), section(5, 6)), ranges(text));
    }

    @Test
    void sectionWithOnlyAHeaderDoesNotFold() {
        assertEquals(List.of(section(2, 3)), ranges("[node.a]\n\n[node.b]\ntype = gauge\n"));
    }

    @Test
    void annotationsAboveTheNextHeaderStayOutsideTheFoldAbove() {
        String text = """
                [node.a]
                loc = 1, 2
                #@aka = Bee
                [node.b]
                loc = 3, 4
                """;
        assertEquals(List.of(section(0, 1), section(3, 4)), ranges(text));
    }

    // --- values ---

    @Test
    void multiLineValueFoldsOverItsContinuationLines() {
        String text = """
                [crop.wheat]
                kc = Day, Kc,
                     0,   0.35,
                     30,  0.35,
                season_len = 195
                """;
        assertEquals(List.of(section(0, 4), value(1, 3)), ranges(text));
    }

    @Test
    void bareEntryInOutputsFoldsLikeAValue() {
        String text = """
                [outputs]
                node.a.dsflow,
                    node.b.dsflow
                node.c.dsflow
                """;
        assertEquals(List.of(section(0, 3), value(1, 2)), ranges(text));
    }

    @Test
    void trailingIndentedCommentAndWhitespaceLinesStayOutsideAValue() {
        String text = "[node.a]\nkc = 1,\n   2\n   # end of kc\n   \nloc = 1, 2\n";
        assertEquals(List.of(section(0, 5), value(1, 2)), ranges(text));
    }

    @Test
    void blankLineOrFlushLeftCommentEndsAValue() {
        String text = "[node.a]\nkc = 1,\n   2\n# note\n   3\nloc = 1, 2\n";
        assertEquals(List.of(section(0, 5), value(1, 2)), ranges(text));
    }

    @Test
    void singleLineValueDoesNotFold() {
        assertEquals(List.of(section(0, 1)), ranges("[node.a]\nloc = 1, 2\n"));
    }

    // --- regions ---

    @Test
    void regionRunsToTheNextRegion() {
        String text = """
                #@region = Catchment
                [node.a]
                type = inflow

                #@region = Outputs
                [outputs]
                node.a.dsflow
                """;
        assertEquals(List.of(region(0, 2), section(1, 2), region(4, 6), section(5, 6)), ranges(text));
    }

    @Test
    void regionTakesItsCommentsAndLeavesTrailingBlankLines() {
        String text = "#@region = All\n[node.a]\nloc = 1, 2\n\n# trailing note\n\n";
        assertEquals(List.of(region(0, 4), section(1, 2)), ranges(text));
    }

    @Test
    void regionOfNothingButCommentsFolds() {
        String text = """
                #@region = Notes
                # how this model came to be
                # and who to ask

                #@region = Nodes
                [node.a]
                type = inflow
                """;
        assertEquals(List.of(region(0, 2), region(4, 6), section(5, 6)), ranges(text));
        assertEquals(List.of(section(0, 1), region(2, 3)), ranges("[x]\ny = 1\n#@region = Notes\n# only notes\n"));
    }

    @Test
    void regionWithOnlyBlankLinesDoesNotFold() {
        assertEquals(List.of(region(2, 4), section(3, 4)), ranges("#@region = A\n\n#@region = B\n[x]\ny = 1\n"));
        assertEquals(List.of(section(0, 1)), ranges("[x]\ny = 1\n#@region = Empty\n\n"));
    }

    @Test
    void regionLineInsideASectionEndsThatSectionsFold() {
        String text = """
                [node.a]
                type = inflow
                #@region = Misplaced
                loc = 1, 2
                [node.b]
                type = gauge
                """;
        // The linter reports the misplacement; the folds just refuse to overlap.
        assertEquals(List.of(section(0, 1), region(2, 5), section(4, 5)), ranges(text));
    }

    @Test
    void regionTitleMayBeAnything() {
        assertEquals(List.of(region(0, 2), section(1, 2)), ranges("#@region =\n[x]\ny = 1\n"));
        assertEquals(List.of(region(0, 2), section(1, 2)), ranges("#@region = Weir #3 = big\n[x]\ny = 1\n"));
    }

    @Test
    void disabledMalformedWrongCaseOrIndentedRegionLinesAreComments() {
        String text = """
                # #@region = A
                ##@region = B
                #@region
                #@Region = C
                  #@region = D
                [x]
                y = 1
                """;
        assertEquals(List.of(section(5, 6)), ranges(text));
    }

    // --- the whole ---

    @Test
    void foldsNestRegionThenSectionThenValueInOrderOfFirstLine() {
        String text = """
                #@region = Crops
                [crop.wheat]
                kc = Day, Kc,
                     0, 0.35
                season_len = 195

                [crop.bare]
                kc = Day, Kc,
                     0, 0.0
                #@region = Nodes
                [node.a]
                type = inflow
                """;
        assertEquals(List.of(
                region(0, 8), section(1, 4), value(2, 3), section(6, 8), value(7, 8),
                region(9, 11), section(10, 11)),
            ranges(text));
    }

    @Test
    void crlfReadsTheSameAsLf() {
        String lf = "#@region = A\n[node.a]\nkc = 1,\n   2\n\n[node.b]\ntype = x\n";
        assertEquals(ranges(lf), ranges(lf.replace("\n", "\r\n")));
    }

    @Test
    void textWithoutCodeHasNoFolds() {
        assertEquals(List.of(), ranges(""));
        assertEquals(List.of(), ranges("# just a comment\n\n"));
        assertEquals(List.of(), ranges("#@region = A\n#@region = B\n"));
    }

    // --- agreement with the map's view of a section ---

    /**
     * A node section's fold ends where {@code NodeSectionLocator} says its content
     * ends, so the editor and the map share one view of a section.
     */
    @Test
    void sectionFoldsEndWhereTheLocatorSaysContentEndsInEveryRepositoryModel() throws IOException {
        List<Path> models = RepositoryModels.all();
        assertFalse(models.isEmpty(), "no model files found under the repository");

        for (Path model : models) {
            String text = Files.readString(model);
            String[] lines = IniSyntax.splitLines(text);
            Map<Integer, Range> sectionFolds = ranges(text).stream()
                .filter(range -> range.kind() == Kind.SECTION)
                .collect(Collectors.toMap(Range::firstLine, Function.identity()));

            for (NodeSection section : NodeSectionLocator.findAll(text)) {
                // The located span starts at the section's annotations; the fold at its header.
                int headerLine = lineOf(text, section.start());
                while (!IniSyntax.isSectionHeaderLine(lines[headerLine])) {
                    headerLine++;
                }
                int lastCodeLine = lineOf(text, section.contentEnd() - 1);
                String label = model + " [node." + section.nodeName() + "]";
                Range fold = sectionFolds.get(headerLine);
                if (lastCodeLine == headerLine) {
                    assertNull(fold, label + ": a header alone does not fold");
                } else {
                    assertNotNull(fold, label + ": no fold at its header");
                    assertEquals(lastCodeLine, fold.lastLine(), label + ": fold end");
                }
            }
        }
    }

    private static int lineOf(String text, int offset) {
        int line = 0;
        for (int i = 0; i < offset; i++) {
            if (text.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }
}
