package com.kalix.ide.model;

import com.kalix.ide.model.NodeSectionLocator.DsReference;
import com.kalix.ide.model.NodeSectionLocator.NodeSection;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the single INI-section grammar. These pin the exact failure modes the
 * old per-call-site regexes had: comments containing '[', '# loc =' lines, keys
 * ending in "loc", indented headers, duplicate node names, sections at EOF.
 */
class NodeSectionLocatorTest {

    // --- Basic location ---

    @Test
    void findsSimpleSection() {
        String text = "[node.a]\ntype = inflow\nloc = 10, 20\n\n[node.b]\nloc = 1, 2\n";
        NodeSection section = NodeSectionLocator.find(text, "a");
        assertNotNull(section);
        assertEquals(0, section.start());
        // Section runs up to (not including) the [node.b] header line
        assertEquals(text.indexOf("[node.b]"), section.end());
        assertNotNull(section.locValue());
        assertEquals("10, 20", text.substring(section.locValue().start(), section.locValue().end()));
    }

    @Test
    void returnsNullForUnknownNode() {
        assertNull(NodeSectionLocator.find("[node.a]\nloc = 1, 2\n", "missing"));
    }

    @Test
    void nodeNameMustMatchExactly() {
        String text = "[node.ab]\nloc = 1, 2\n";
        assertNull(NodeSectionLocator.find(text, "a"));
        assertNotNull(NodeSectionLocator.find(text, "ab"));
    }

    @Test
    void findsSectionAtEndOfFileWithoutTrailingNewline() {
        String text = "[node.a]\nloc = 1, 2\n\n[node.b]\ntype = gauge\nloc = 3, 4";
        NodeSection section = NodeSectionLocator.find(text, "b");
        assertNotNull(section);
        assertEquals(text.indexOf("[node.b]"), section.start());
        assertEquals(text.length(), section.end());
        assertEquals("3, 4", text.substring(section.locValue().start(), section.locValue().end()));
    }

    @Test
    void sectionEndsAtNonNodeHeader() {
        String text = "[node.a]\nloc = 1, 2\n[outputs]\nnode.a.dsflow\n";
        NodeSection section = NodeSectionLocator.find(text, "a");
        assertNotNull(section);
        assertEquals(text.indexOf("[outputs]"), section.end());
    }

    @Test
    void sectionSwallowsTrailingBlankAndCommentLines() {
        String text = "[node.a]\nloc = 1, 2\n\n# trailing note\n\n[node.b]\nloc = 3, 4\n";
        NodeSection section = NodeSectionLocator.find(text, "a");
        assertNotNull(section);
        assertEquals(text.indexOf("[node.b]"), section.end());
    }

    // --- Failure modes of the old regexes ---

    @Test
    void bracketInsideCommentDoesNotBreakSection() {
        // The old DOTALL regex used [^\[]*? to reach the loc line, so a '[' in a
        // comment made drag write-back silently fail.
        String text = "[node.a]\n# see [outputs] below\nloc = 10, 20\n";
        NodeSection section = NodeSectionLocator.find(text, "a");
        assertNotNull(section);
        assertNotNull(section.locValue());
        assertEquals("10, 20", text.substring(section.locValue().start(), section.locValue().end()));
        assertEquals(text.length(), section.end());
    }

    @Test
    void commentedOutLocLineIsNotALocProperty() {
        String text = "[node.a]\n# loc = 99, 99\ntype = inflow\n";
        NodeSection section = NodeSectionLocator.find(text, "a");
        assertNotNull(section);
        assertNull(section.locValue());
    }

    @Test
    void semicolonCommentedLocLineIsNotALocProperty() {
        String text = "[node.a]\n; loc = 99, 99\n";
        NodeSection section = NodeSectionLocator.find(text, "a");
        assertNotNull(section);
        assertNull(section.locValue());
    }

    @Test
    void keyEndingInLocIsNotALocProperty() {
        // 'loc' must be line-anchored: refloc's value must never be rewritten.
        String text = "[node.a]\nrefloc = 99, 99\nloc = 10, 20\n";
        NodeSection section = NodeSectionLocator.find(text, "a");
        assertNotNull(section);
        assertEquals("10, 20", text.substring(section.locValue().start(), section.locValue().end()));
    }

    @Test
    void keyEndingInLocAloneYieldsNoLocValue() {
        String text = "[node.a]\nrefloc = 99, 99\n";
        NodeSection section = NodeSectionLocator.find(text, "a");
        assertNotNull(section);
        assertNull(section.locValue());
    }

    @Test
    void indentedHeaderIsRecognised() {
        // The parser trims lines, so an indented [node.x] parses; the locator
        // must find it too (the old ^-anchored regex could not).
        String text = "  [node.a]\n  loc = 10, 20\n";
        NodeSection section = NodeSectionLocator.find(text, "a");
        assertNotNull(section);
        assertEquals(0, section.start());
        assertEquals("10, 20", text.substring(section.locValue().start(), section.locValue().end()));
    }

    @Test
    void headerWithTrailingWhitespaceIsRecognised() {
        String text = "[node.a]   \nloc = 10, 20\n";
        NodeSection section = NodeSectionLocator.find(text, "a");
        assertNotNull(section);
        assertNotNull(section.locValue());
    }

    @Test
    void headerWithTrailingCommentIsNotAHeader() {
        // The parser's trimmed-line pattern requires the line to end at ']', so
        // '[node.a] # x' is not a header — but it still closes the previous section.
        String text = "[node.a]\nloc = 1, 2\n[node.b] # not a header\nloc = 3, 4\n";
        NodeSection a = NodeSectionLocator.find(text, "a");
        assertNotNull(a);
        assertEquals(text.indexOf("[node.b]"), a.end());
        assertNull(NodeSectionLocator.find(text, "b"));
    }

    @Test
    void duplicateNodeNamesResolveToLastSection() {
        // The parser's last section wins in the model map; the updater rewriting
        // the FIRST section made drags appear to revert.
        String text = "[node.a]\nloc = 1, 1\n\n[node.b]\nloc = 5, 5\n\n[node.a]\nloc = 2, 2\n";
        NodeSection section = NodeSectionLocator.find(text, "a");
        assertNotNull(section);
        assertEquals(text.lastIndexOf("[node.a]"), section.start());
        assertEquals("2, 2", text.substring(section.locValue().start(), section.locValue().end()));
    }

    @Test
    void duplicateLocLinesResolveToLast() {
        // The parser overwrites loc as it reads, so the last loc line wins.
        String text = "[node.a]\nloc = 1, 1\nloc = 2, 2\n";
        NodeSection section = NodeSectionLocator.find(text, "a");
        assertNotNull(section);
        assertEquals("2, 2", text.substring(section.locValue().start(), section.locValue().end()));
    }

    // --- Loc value details ---

    @Test
    void locWithInlineCommentExcludesComment() {
        String text = "[node.a]\nloc = 10.5, -20.25 # near the weir\n";
        NodeSection section = NodeSectionLocator.find(text, "a");
        assertNotNull(section.locValue());
        assertEquals("10.5, -20.25", text.substring(section.locValue().start(), section.locValue().end()));
    }

    @Test
    void locPreservesSeparatorFormatting() {
        String text = "[node.a]\nloc = 1.0 ,   2.0\n";
        NodeSection section = NodeSectionLocator.find(text, "a");
        assertEquals(" ,   ", section.locValue().separator());
    }

    @Test
    void locSupportsScientificNotation() {
        String text = "[node.a]\nloc = 1.5e3, -2.5E-2\n";
        NodeSection section = NodeSectionLocator.find(text, "a");
        assertNotNull(section.locValue());
        assertEquals("1.5e3, -2.5E-2", text.substring(section.locValue().start(), section.locValue().end()));
    }

    @Test
    void handlesCrlfLineEndings() {
        String text = "[node.a]\r\nloc = 10, 20\r\n\r\n[node.b]\r\nloc = 1, 2\r\n";
        NodeSection section = NodeSectionLocator.find(text, "a");
        assertNotNull(section);
        assertEquals(text.indexOf("[node.b]"), section.end());
        assertEquals("10, 20", text.substring(section.locValue().start(), section.locValue().end()));
    }

    @Test
    void nullAndEmptyInputsAreSafe() {
        assertNull(NodeSectionLocator.find(null, "a"));
        assertNull(NodeSectionLocator.find("", "a"));
        assertNull(NodeSectionLocator.find("[node.a]\n", null));
        assertTrue(NodeSectionLocator.findDsReferences(null).isEmpty());
        assertTrue(NodeSectionLocator.findDsReferences("").isEmpty());
    }

    // --- Downstream references ---

    @Test
    void findsDsReferencesWithSourceAttribution() {
        String text = "[node.a]\nds_1 = b\nds_2 = c # secondary\n\n[node.b]\nds_1 = c\n";
        List<DsReference> refs = NodeSectionLocator.findDsReferences(text);
        assertEquals(3, refs.size());
        assertEquals("a", refs.get(0).sourceNode());
        assertEquals("b", refs.get(0).target());
        assertEquals("a", refs.get(1).sourceNode());
        assertEquals("c", refs.get(1).target());
        assertEquals("b", refs.get(2).sourceNode());
        assertEquals("c", refs.get(2).target());
    }

    @Test
    void dsReferenceSpanCoversWholeLineIncludingNewline() {
        String text = "[node.a]\nds_1 = b\nloc = 1, 2\n";
        DsReference ref = NodeSectionLocator.findDsReferences(text).get(0);
        assertEquals("ds_1 = b\n", text.substring(ref.lineStart(), ref.lineEnd()));
    }

    @Test
    void dsLinesOutsideNodeSectionsAreIgnored() {
        String text = "ds_1 = orphan\n[outputs]\nds_1 = alsoOrphan\n[node.a]\nds_1 = b\n";
        List<DsReference> refs = NodeSectionLocator.findDsReferences(text);
        assertEquals(1, refs.size());
        assertEquals("a", refs.get(0).sourceNode());
    }

    @Test
    void commentedDsLinesAreIgnored() {
        String text = "[node.a]\n# ds_1 = b\n; ds_2 = c\nds_1 = d\n";
        List<DsReference> refs = NodeSectionLocator.findDsReferences(text);
        assertEquals(1, refs.size());
        assertEquals("d", refs.get(0).target());
    }

    @Test
    void nodeScopeEndsAtNonNodeHeaderForDsReferences() {
        String text = "[node.a]\nds_1 = b\n[attributes]\nds_1 = notALink\n";
        List<DsReference> refs = NodeSectionLocator.findDsReferences(text);
        assertEquals(1, refs.size());
    }

    // --- Grammar agreement with ModelParser ---

    @Test
    void parserAgreesOnDuplicateNames() {
        String text = "[node.a]\ntype = inflow\nloc = 1, 1\n\n[node.a]\ntype = gauge\nloc = 2, 2\n";
        ModelParser.ParseResult result = ModelParser.parse(text);
        HydrologicalModel model = new HydrologicalModel();
        model.updateFromParsedData(result);
        ModelNode node = model.getNode("a");
        assertNotNull(node);
        // The parser's model keeps the LAST section's values...
        assertEquals(2.0, node.getX());
        // ...and the locator points at the same section.
        NodeSection section = NodeSectionLocator.find(text, "a");
        assertEquals(text.lastIndexOf("[node.a]"), section.start());
    }

    @Test
    void parserClosesNodeScopeAtNonNodeHeader() {
        // Properties after [outputs] must not leak into the preceding node —
        // the locator ends the section there, and the parser must agree.
        String text = "[node.a]\ntype = inflow\nloc = 1, 1\n[outputs]\nloc = 9, 9\n";
        ModelParser.ParseResult result = ModelParser.parse(text);
        assertEquals(1, result.getNodes().size());
        assertEquals(1.0, result.getNodes().get(0).getX());
    }
}
