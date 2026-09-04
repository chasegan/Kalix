package com.kalix.ide.linter.parsing;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The linter parser reads comments the way the engine does (issue #142 and the
 * grammar alignment around it): a '#' comment is legal after a section header and
 * after a list item, ';' is not a comment, and a '#' inside quotes is text.
 */
class INIModelParserCommentGrammarTest {

    @Test
    void headerWithTrailingCommentOpensTheSection() {
        String model = """
                [data]
                rain.csv

                [node.a]   # UAW in Bulloo
                type = gr4j
                loc = 1, 2
                """;
        INIModelParser.ParsedModel parsed = INIModelParser.parse(model);

        INIModelParser.NodeSection node = parsed.getNodes().get("a");
        assertNotNull(node, "node must not vanish because its header carries a comment");
        assertEquals("gr4j", node.getNodeType());
        assertEquals("1, 2", node.getProperties().get("loc").getValue());
        assertEquals(4, node.getStartLine());
        assertEquals(List.of("rain.csv"), parsed.getInputFiles());
        assertTrue(parsed.getSyntaxIssues().isEmpty());
    }

    @Test
    void headerCommentMayContainBracketsAndEquals() {
        INIModelParser.ParsedModel parsed = INIModelParser.parse("[node.a] # see [node.b], x = 1\ntype = gr4j\n");
        assertEquals(List.of("node.a"), List.copyOf(parsed.getSections().keySet()));
        assertEquals("gr4j", parsed.getNodes().get("a").getNodeType());
    }

    @Test
    void listItemCommentIsNotPartOfTheReference() {
        String model = """
                [data]
                rain.csv          # observed
                evap = evap.csv   # aliased

                [outputs]
                node.a.dsflow # gauge flow
                node.b.dsflow # flow = big
                """;
        INIModelParser.ParsedModel parsed = INIModelParser.parse(model);

        assertEquals(List.of("rain.csv", "evap.csv"), parsed.getInputFiles());
        assertEquals("evap.csv", parsed.getInputFileAliases().get("evap"));
        assertEquals(List.of("node.a.dsflow", "node.b.dsflow"), parsed.getOutputReferences());
    }

    @Test
    void semicolonLineIsReportedNotSwallowed() {
        String model = """
                ; a semicolon comment
                [kalix]
                ; another one

                [outputs]
                ; node.a.dsflow
                """;
        INIModelParser.ParsedModel parsed = INIModelParser.parse(model);

        List<INIModelParser.SyntaxIssue> issues = parsed.getSyntaxIssues();
        assertEquals(3, issues.size());
        assertEquals(List.of(1, 3, 6), issues.stream().map(INIModelParser.SyntaxIssue::lineNumber).toList());
        assertTrue(issues.stream().allMatch(i -> INIModelParser.RULE_SEMICOLON_COMMENT.equals(i.ruleName())));
        assertTrue(parsed.getOutputReferences().isEmpty(), "a ';' line is not read as an output reference");
    }

    @Test
    void semicolonInsideAValueIsOrdinaryText() {
        INIModelParser.ParsedModel parsed = INIModelParser.parse("[node.a]\nexpr = { x = 1; x + 1 }\n");
        assertEquals("{ x = 1; x + 1 }", parsed.getNodes().get("a").getProperties().get("expr").getValue());
        assertTrue(parsed.getSyntaxIssues().isEmpty());
    }

    @Test
    void malformedHeaderIsReportedAndClosesThePreviousSection() {
        String model = """
                [node.a]
                type = gr4j
                [node.b
                type = gauge
                [node.c] junk
                type = inflow
                """;
        INIModelParser.ParsedModel parsed = INIModelParser.parse(model);

        List<INIModelParser.SyntaxIssue> issues = parsed.getSyntaxIssues();
        assertEquals(List.of(3, 5), issues.stream().map(INIModelParser.SyntaxIssue::lineNumber).toList());
        assertTrue(issues.stream().allMatch(i -> INIModelParser.RULE_MALFORMED_SECTION_HEADER.equals(i.ruleName())));

        // The properties after a malformed header belong to nobody.
        assertEquals(List.of("node.a"), List.copyOf(parsed.getSections().keySet()));
        assertEquals("gr4j", parsed.getNodes().get("a").getNodeType());
        assertNull(parsed.getNodes().get("a").getProperties().get("type_from_b"));
        assertEquals(1, parsed.getNodes().get("a").getProperties().size());
    }

    @Test
    void hashInsideQuotesIsText() {
        String model = """
                [data]
                "weird # name.csv"
                alias = "a # b" # real comment
                """;
        INIModelParser.ParsedModel parsed = INIModelParser.parse(model);

        assertEquals(List.of("\"weird # name.csv\"", "\"a # b\""), parsed.getInputFiles());
        assertEquals("\"a # b\"", parsed.getInputFileAliases().get("alias"));
    }

    @Test
    void continuationLineCommentsAreStillStripped() {
        String model = """
                [node.a]
                params = 1, 2,   # first
                         3, 4    # second
                """;
        INIModelParser.ParsedModel parsed = INIModelParser.parse(model);
        assertEquals("1, 2, 3, 4", parsed.getNodes().get("a").getProperties().get("params").getValue());
    }
}
