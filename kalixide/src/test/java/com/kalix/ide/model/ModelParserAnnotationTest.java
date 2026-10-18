package com.kalix.ide.model;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * An {@code #@aka} annotation gives the node below it a readable name
 * (ADR-0010 §3, §6.1): it attaches to the next section header, a code line in
 * between orphans it, and a disabled annotation is a plain comment.
 */
class ModelParserAnnotationTest {

    private static ModelNode only(String model) {
        List<ModelNode> nodes = ModelParser.parse(model).getNodes();
        assertEquals(1, nodes.size());
        return nodes.get(0);
    }

    @Test
    void akaAboveTheHeaderNamesTheNode() {
        ModelNode node = only("""
                #@aka = Teemburra Dam (gauge 125001)
                [node.teemburra]
                type = storage
                loc = 10, 20
                """);
        assertEquals("teemburra", node.getName());
        assertEquals("Teemburra Dam (gauge 125001)", node.getAka());
        assertEquals("Teemburra Dam (gauge 125001)", node.label());
    }

    @Test
    void nodeWithoutAkaIsLabelledByItsName() {
        ModelNode node = only("""
                [node.teemburra]
                type = storage
                loc = 10, 20
                """);
        assertNull(node.getAka());
        assertEquals("teemburra", node.label());
    }

    @Test
    void blankLinesAndCommentsMayLieBetweenTheAnnotationAndTheHeader() {
        ModelNode node = only("""
                #@aka = Teemburra Dam

                # the dam itself
                [node.teemburra]
                type = storage
                loc = 10, 20
                """);
        assertEquals("Teemburra Dam", node.getAka());
    }

    @Test
    void aCodeLineBetweenOrphansTheAnnotation() {
        ModelNode node = only("""
                [kalix]
                #@aka = Not for the node
                start = 2000-01-01

                [node.teemburra]
                type = storage
                loc = 10, 20
                """);
        assertNull(node.getAka());
    }

    @Test
    void annotationOnAnEarlierSectionDoesNotLeak() {
        ModelNode node = only("""
                #@aka = The model
                [kalix]

                [node.teemburra]
                type = storage
                loc = 10, 20
                """);
        assertNull(node.getAka());
    }

    @Test
    void lastAkaWins() {
        ModelNode node = only("""
                #@aka = First
                #@aka = Second
                [node.teemburra]
                type = storage
                loc = 10, 20
                """);
        assertEquals("Second", node.getAka());
    }

    @Test
    void disabledAndIndentedAnnotationsAreComments() {
        ModelNode node = only("""
                ##@aka = Hand-disabled
                # #@aka = Toggle-disabled
                  #@aka = Indented
                [node.teemburra]
                type = storage
                loc = 10, 20
                """);
        assertNull(node.getAka());
    }

    @Test
    void otherKeysAndEmptyValuesAreIgnored() {
        ModelNode node = only("""
                #@cluster = farm_3
                #@aka =
                #@AKA = Wrong case
                [node.teemburra]
                type = storage
                loc = 10, 20
                """);
        assertNull(node.getAka());
    }

    @Test
    void akaKeepsHashesAndEqualsSignsInItsValue() {
        ModelNode node = only("""
                #@aka = Weir #3 = the big one
                [node.weir3]
                type = gauge
                loc = 10, 20
                """);
        assertEquals("Weir #3 = the big one", node.getAka());
    }
}
