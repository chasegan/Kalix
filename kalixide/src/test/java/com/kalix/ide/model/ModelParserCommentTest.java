package com.kalix.ide.model;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The map's parser follows the shared comment grammar (issue #142): a node whose
 * header carries a trailing '#' comment is still drawn, and ';' is not a comment.
 */
class ModelParserCommentTest {

    @Test
    void nodeWithHeaderCommentAppearsOnTheMap() {
        String model = """
                [node.a]   # UAW in Bulloo
                type = gr4j        # rainfall runoff
                loc = 10, 20       # near the weir
                ds_1 = b           # to the gauge

                [node.b]
                type = gauge
                loc = 30, 40
                """;
        ModelParser.ParseResult result = ModelParser.parse(model);

        List<String> names = result.getNodes().stream().map(ModelNode::getName).toList();
        assertEquals(List.of("a", "b"), names);
        ModelNode a = result.getNodes().get(0);
        assertEquals("gr4j", a.getType());
        assertEquals(10.0, a.getX());
        assertEquals(20.0, a.getY());

        assertEquals(1, result.getLinks().size());
        assertEquals("a", result.getLinks().get(0).getUpstreamTerminus());
        assertEquals("b", result.getLinks().get(0).getDownstreamTerminus());
    }

    @Test
    void semicolonIsNotACommentOnPropertyLines() {
        // A ';' does not end the value, so this loc is not a valid coordinate pair
        // and the node is incomplete - exactly as the engine would read it.
        String model = """
                [node.a]
                type = gr4j
                loc = 10, 20 ; not a comment
                """;
        ModelParser.ParseResult result = ModelParser.parse(model);
        assertTrue(result.getNodes().isEmpty());
    }
}
