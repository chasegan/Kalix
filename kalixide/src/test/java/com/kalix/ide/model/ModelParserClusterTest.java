package com.kalix.ide.model;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * A {@code #@cluster} annotation puts the node below it in a cluster (ADR-0010 §6.2).
 * The text is the truth, so every malformed case has one fixed answer: the last line
 * counts, and a last line that breaks the node-name rule leaves the node loose.
 */
class ModelParserClusterTest {

    private static final String NODE = "[node.a]\ntype = storage\nloc = 10, 20\n";

    private static String clusterOf(String model) {
        List<ModelNode> nodes = ModelParser.parse(model).getNodes();
        assertEquals(1, nodes.size());
        return nodes.get(0).getCluster();
    }

    @Test
    void clusterAboveTheHeaderNamesTheCluster() {
        assertEquals("farm_3", clusterOf("#@cluster = farm_3\n" + NODE));
    }

    @Test
    void aNodeWithNoAnnotationIsLoose() {
        assertNull(clusterOf(NODE));
    }

    @Test
    void clusterStaysWithItsOwnNodeWhenAnotherFollows() {
        List<ModelNode> nodes = ModelParser.parse("#@cluster = farm\n" + NODE
                + "[node.b]\ntype = storage\nloc = 30, 40\n").getNodes();
        assertEquals("farm", nodes.get(0).getCluster());
        assertNull(nodes.get(1).getCluster());
    }

    @Test
    void theLastLineCounts() {
        assertEquals("second", clusterOf("#@cluster = first\n#@cluster = second\n" + NODE));
    }

    @Test
    void anInvalidLastLineLeavesTheNodeLooseWithNoFallback() {
        assertNull(clusterOf("#@cluster = first\n#@cluster = Not Valid\n" + NODE));
    }

    @Test
    void anEmptyLastLineLeavesTheNodeLooseWithNoFallback() {
        assertNull(clusterOf("#@cluster = first\n#@cluster =\n" + NODE));
    }

    @Test
    void aLastLineWithNoEqualsIsNotAnAnnotationSoTheEarlierLineCounts() {
        // The lexer's answer, and the one exception to "no fallback": the parser sees
        // a comment here, and the linter reports the line as malformed (ADR-0010 §2).
        assertEquals("first", clusterOf("#@cluster = first\n#@cluster\n" + NODE));
    }

    @Test
    void namesUnderTheNodeNameRuleAreClusters() {
        for (String name : List.of("a", "farm", "farm_3", "a1_b2", "f_", "Teemburra", "0202_dam", "_spare")) {
            assertEquals(name, clusterOf("#@cluster = " + name + "\n" + NODE));
        }
    }

    @Test
    void anythingElseIsLoose() {
        for (String name : List.of("", "farm-3", "farm 3", "farm.north", "farm # note", "café")) {
            assertNull(clusterOf("#@cluster = " + name + "\n" + NODE), name);
        }
    }

    @Test
    void blankLinesAndCommentsMayLieBetweenTheAnnotationAndTheHeader() {
        assertEquals("farm", clusterOf("#@cluster = farm\n\n# a comment\n" + NODE));
    }

    @Test
    void aCodeLineOrphansTheAnnotation() {
        List<ModelNode> nodes = ModelParser.parse(NODE + "#@cluster = farm\nds_1 = x\n"
                + "[node.b]\ntype = storage\nloc = 30, 40\n").getNodes();
        assertNull(nodes.get(0).getCluster());
        assertNull(nodes.get(1).getCluster());
    }

    @Test
    void aNonNodeHeaderTakesTheAnnotationWithIt() {
        // No code line between the headers, so only the header itself can clear it.
        assertNull(clusterOf("#@cluster = farm\n[kalix]\n" + NODE));
    }

    @Test
    void aDisabledOrWrongCaseAnnotationIsNotOne() {
        for (String line : List.of("##@cluster = farm", "# #@cluster = farm", " #@cluster = farm", "#@CLUSTER = farm")) {
            assertNull(clusterOf(line + "\n" + NODE), line);
        }
    }

    @Test
    void crlfLineEndingsReadTheSameAsLf() {
        assertEquals("farm", clusterOf(("#@cluster = farm\n" + NODE).replace("\n", "\r\n")));
    }

    @Test
    void anAkaAndAClusterSitTogether() {
        ModelNode node = ModelParser.parse("#@aka = Farm Dam\n#@cluster = farm\n" + NODE).getNodes().get(0);
        assertEquals("Farm Dam", node.getAka());
        assertEquals("farm", node.getCluster());
    }

    @Test
    void nodesInDifferentClustersAreNotEqual() {
        // ModelDiff compares nodes with equals, so a cluster-only edit must count as a change.
        ModelNode inFarm = new ModelNode("a", "storage", 1, 2, null, "farm");
        assertNotEquals(inFarm, new ModelNode("a", "storage", 1, 2, null, "dam"));
        assertNotEquals(inFarm, new ModelNode("a", "storage", 1, 2, null, null));
        assertEquals(inFarm, new ModelNode("a", "storage", 1, 2, null, "farm"));
        assertEquals(inFarm.hashCode(), new ModelNode("a", "storage", 1, 2, null, "farm").hashCode());
    }
}
