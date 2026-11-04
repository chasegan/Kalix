package com.kalix.ide.model;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The clusters of a model in order of first mention in the file. A cluster exists only
 * while it has a member, and a node name that appears twice counts once, at its last
 * section, as it does everywhere else in the model.
 */
class ClusterIndexTest {

    private static ModelNode node(String name, String cluster) {
        return new ModelNode(name, "storage", 0, 0, null, cluster);
    }

    @Test
    void clustersAreInOrderOfFirstMentionNotAlphabetical() {
        ClusterIndex index = ClusterIndex.of(List.of(
                node("a", "zebra"), node("b", "apple"), node("c", "zebra"), node("d", "mango")));
        assertEquals(List.of("zebra", "apple", "mango"), index.names());
    }

    @Test
    void membersAreInFileOrder() {
        ClusterIndex index = ClusterIndex.of(List.of(node("z", "farm"), node("a", "farm"), node("m", "farm")));
        assertEquals(List.of("z", "a", "m"), index.members("farm"));
    }

    @Test
    void looseNodesAreInNoCluster() {
        ClusterIndex index = ClusterIndex.of(List.of(node("a", null), node("b", "farm")));
        assertEquals(List.of("farm"), index.names());
        assertEquals(List.of("b"), index.members("farm"));
        assertNull(index.clusterOf("a"));
        assertEquals("farm", index.clusterOf("b"));
    }

    @Test
    void anUnknownClusterHasNoMembers() {
        assertEquals(List.of(), ClusterIndex.of(List.of(node("a", "farm"))).members("dam"));
    }

    @Test
    void noMembersMeansNoClusters() {
        assertTrue(ClusterIndex.of(List.of(node("a", null))).isEmpty());
        assertTrue(ClusterIndex.EMPTY.isEmpty());
        assertFalse(ClusterIndex.of(List.of(node("a", "farm"))).isEmpty());
    }

    @Test
    void aDuplicateNodeNameCountsAtItsLastSection() {
        ClusterIndex index = ClusterIndex.of(List.of(
                node("a", "first"), node("b", "second"), node("a", "third")));
        assertEquals(List.of("second", "third"), index.names(), "'first' lost its only member");
        assertEquals(List.of("a"), index.members("third"));
        assertEquals("third", index.clusterOf("a"));
    }

    @Test
    void aDuplicateNodeNameLeavingAClusterIsNotAMemberOfIt() {
        ClusterIndex index = ClusterIndex.of(List.of(node("a", "farm"), node("b", "farm"), node("a", null)));
        assertEquals(List.of("b"), index.members("farm"));
        assertNull(index.clusterOf("a"));
    }

    @Test
    void equalityIncludesOrder() {
        ClusterIndex one = ClusterIndex.of(List.of(node("a", "farm"), node("b", "dam")));
        ClusterIndex same = ClusterIndex.of(List.of(node("a", "farm"), node("b", "dam")));
        ClusterIndex reordered = ClusterIndex.of(List.of(node("b", "dam"), node("a", "farm")));
        assertEquals(one, same);
        assertEquals(one.hashCode(), same.hashCode());
        assertNotEquals(one, reordered);
    }

    @Test
    void equalityIncludesMemberOrder() {
        ClusterIndex one = ClusterIndex.of(List.of(node("a", "farm"), node("b", "farm")));
        ClusterIndex swapped = ClusterIndex.of(List.of(node("b", "farm"), node("a", "farm")));
        assertEquals(one.names(), swapped.names());
        assertNotEquals(one, swapped);
    }

    @Test
    void membersCannotBeChangedFromOutside() {
        ClusterIndex index = ClusterIndex.of(List.of(node("a", "farm")));
        assertThrows(UnsupportedOperationException.class, () -> index.members("farm").add("b"));
        assertThrows(UnsupportedOperationException.class, () -> index.names().add("dam"));
    }
}
