package com.kalix.ide.model;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Cluster names are matched ignoring case, as node names are (ADR-0010 §6.2), so
 * {@code teemburra} and {@code Teemburra} are one cluster. It is shown with the
 * spelling of its first mention in the file, wherever it is looked up from.
 */
class ClusterIndexCaseTest {

    private static ModelNode node(String name, String cluster) {
        return new ModelNode(name, "storage", 0, 0, null, cluster);
    }

    @Test
    void twoSpellingsAreOneCluster() {
        ClusterIndex index = ClusterIndex.of(List.of(node("a", "north"), node("b", "North"), node("c", "NORTH")));
        assertEquals(List.of("north"), index.names());
        assertEquals(List.of("a", "b", "c"), index.members("north"));
    }

    @Test
    void theClusterShowsTheSpellingOfItsFirstMention() {
        ClusterIndex index = ClusterIndex.of(List.of(node("a", "North"), node("b", "north")));
        assertEquals(List.of("North"), index.names());
        assertEquals("North", index.clusterOf("a"));
        assertEquals("North", index.clusterOf("b"));
    }

    @Test
    void membersAreFoundFromAnySpelling() {
        ClusterIndex index = ClusterIndex.of(List.of(node("a", "north"), node("b", "North")));
        assertEquals(List.of("a", "b"), index.members("NORTH"));
    }

    @Test
    void aClusterIsNamedFromAnySpelling() {
        ClusterIndex index = ClusterIndex.of(List.of(node("a", "north"), node("b", "North")));
        assertEquals("north", index.name("NORTH"));
        assertNull(index.name("south"));
    }

    @Test
    void anotherClusterBetweenTwoSpellingsKeepsTheOrderOfFirstMention() {
        ClusterIndex index = ClusterIndex.of(List.of(
                node("a", "north"), node("b", "south"), node("c", "North")));
        assertEquals(List.of("north", "south"), index.names());
        assertEquals(List.of("a", "c"), index.members("north"));
    }

    @Test
    void aChangeOfSpellingAloneIsAChange() {
        // A case-only rename must reach the model's listeners, so the map relabels.
        assertNotEquals(ClusterIndex.of(List.of(node("a", "north"))), ClusterIndex.of(List.of(node("a", "North"))));
    }
}
