package com.kalix.ide.model;

import com.kalix.ide.linter.parsing.AnnotationKey;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Finding one key's annotation lines above a section's header, with spans valid in the
 * whole text. An edit reads the text through these, as every section edit does.
 */
class NodeSectionLocatorAnnotationTest {

    private static final String BODY = "[node.a]\ntype = storage\nloc = 1, 2\n";

    private static NodeSectionLocator.HeaderBlock block(String text, AnnotationKey key) {
        return NodeSectionLocator.headerBlock(text, NodeSectionLocator.find(text, "a"), key);
    }

    @Test
    void aSectionWithNoAnnotationsHasItsHeaderAndNoLines() {
        String text = "[kalix]\n" + BODY;
        NodeSectionLocator.HeaderBlock block = block(text, AnnotationKey.CLUSTER);
        assertEquals(text.indexOf("[node.a]"), block.headerStart());
        assertTrue(block.lines().isEmpty());
    }

    @Test
    void onlyTheNamedKeysLinesAreReturnedInFileOrder() {
        String text = "#@cluster = one\n#@aka = x\n# note\n#@cluster = two\n" + BODY;
        NodeSectionLocator.HeaderBlock block = block(text, AnnotationKey.CLUSTER);
        assertEquals(List.of("one", "two"),
                block.lines().stream().map(NodeSectionLocator.AnnotationLine::value).toList());
        assertEquals(text.indexOf("[node.a]"), block.headerStart());
    }

    @Test
    void spansCoverTheLineWithAndWithoutItsTerminator() {
        String text = "#@aka = x\r\n#@cluster = one\r\n" + BODY;
        NodeSectionLocator.AnnotationLine line = block(text, AnnotationKey.CLUSTER).lines().get(0);
        assertEquals("#@cluster = one", text.substring(line.lineStart(), line.contentEnd()));
        assertEquals("#@cluster = one\r\n", text.substring(line.lineStart(), line.lineEnd()));
    }

    @Test
    void aMalformedOrDisabledLineIsNotReturned() {
        String text = "#@cluster\n##@cluster = two\n" + BODY;
        assertTrue(block(text, AnnotationKey.CLUSTER).lines().isEmpty());
    }

    @Test
    void anEarlierSectionsAnnotationsAreNotThisOnes() {
        String text = "#@cluster = other\n[node.z]\ntype = storage\n" + BODY;
        assertTrue(block(text, AnnotationKey.CLUSTER).lines().isEmpty());
    }
}
