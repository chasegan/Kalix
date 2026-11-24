package com.kalix.ide.model;

import com.kalix.ide.utils.ClusterKey;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which clusters are collapsed (clusters.md, Architecture, "Collapse state"): everything
 * starts collapsed, entries are kept by key whatever the spelling and are never removed,
 * a new name from Create starts collapsed, a rename to a new name carries the old state,
 * and joining or merging into an existing cluster leaves its state alone.
 */
class ClusterCollapseTest {

    private static ModelNode node(String name, String cluster) {
        return new ModelNode(name, "storage", 0, 0, null, cluster);
    }

    private static final ClusterIndex FARM_AND_DAM =
            ClusterIndex.of(List.of(node("a", "farm"), node("b", "farm"), node("c", "dam"), node("d", null)));

    private static ClusterCollapse expanded(String... clusters) {
        ClusterCollapse collapse = new ClusterCollapse();
        collapse.setCollapsed(List.of(clusters), false);
        return collapse;
    }

    @Test
    void everyClusterStartsCollapsed() {
        ClusterCollapse collapse = new ClusterCollapse();
        assertTrue(collapse.isCollapsed("farm"));
        assertTrue(collapse.isCollapsed("never_seen"));
    }

    @Test
    void stateIsKeptWhateverTheCapitalisation() {
        ClusterCollapse collapse = expanded("Farm");
        assertFalse(collapse.isCollapsed("farm"));
        assertFalse(collapse.isCollapsed("FARM"));
        collapse.setCollapsed(List.of("fARM"), true);
        assertTrue(collapse.isCollapsed("Farm"));
    }

    @Test
    void aClusterThatVanishesAndComesBackKeepsItsState() {
        // Nothing removes an entry: reading against an index without the cluster (after a
        // disband, or an undo) leaves it for the cluster's return.
        ClusterCollapse collapse = expanded("farm");
        collapse.collapsedKeys(ClusterIndex.EMPTY);
        collapse.isHidden("a", ClusterIndex.EMPTY);
        assertFalse(collapse.isCollapsed("farm"));
    }

    @Test
    void membersOfACollapsedClusterAreHiddenAndOthersAreNot() {
        ClusterCollapse collapse = expanded("dam");
        assertTrue(collapse.isHidden("a", FARM_AND_DAM));
        assertTrue(collapse.isHidden("b", FARM_AND_DAM));
        assertFalse(collapse.isHidden("c", FARM_AND_DAM), "a member of an expanded cluster");
        assertFalse(collapse.isHidden("d", FARM_AND_DAM), "a loose node");
        assertFalse(collapse.isHidden("no_such_node", FARM_AND_DAM));
    }

    @Test
    void collapsedKeysAreTheKeysOfTheIndexsCollapsedClusters() {
        ClusterIndex index = ClusterIndex.of(List.of(node("a", "Farm"), node("b", "dam"), node("c", "weir")));
        ClusterCollapse collapse = expanded("DAM");
        assertEquals(Set.of(new ClusterKey("farm"), new ClusterKey("weir")), collapse.collapsedKeys(index));
    }

    @Test
    void createWithANewNameStartsCollapsedEvenOverAStaleExpandedEntry() {
        // An expanded farm was disbanded, so it isn't in the index, but its entry is kept.
        ClusterCollapse collapse = expanded("farm");
        ClusterIndex withoutFarm = ClusterIndex.of(List.of(node("c", "dam")));
        collapse.created(withoutFarm, "Farm");
        assertTrue(collapse.isCollapsed("farm"));
    }

    @Test
    void createJoiningAnExistingClusterLeavesItsStateAlone() {
        ClusterCollapse collapse = expanded("farm");
        collapse.created(FARM_AND_DAM, "FARM");
        assertFalse(collapse.isCollapsed("farm"));
    }

    @Test
    void renameToANewNameCarriesTheOldState() {
        ClusterCollapse collapse = expanded("farm");
        collapse.renamed(FARM_AND_DAM, "farm", "orchard");
        assertFalse(collapse.isCollapsed("orchard"), "expanded carried over");

        collapse.renamed(FARM_AND_DAM, "dam", "reservoir");
        assertTrue(collapse.isCollapsed("reservoir"), "collapsed carried over");
    }

    @Test
    void renameToANewNameOverwritesAStaleEntry() {
        ClusterCollapse collapse = expanded("orchard");
        collapse.renamed(FARM_AND_DAM, "dam", "orchard");
        assertTrue(collapse.isCollapsed("orchard"));
    }

    @Test
    void renameIntoAnExistingClusterKeepsTheTargetsState() {
        ClusterCollapse collapse = expanded("farm");
        collapse.renamed(FARM_AND_DAM, "farm", "Dam");
        assertTrue(collapse.isCollapsed("dam"));
    }

    @Test
    void aRenameOfCapitalisationAloneChangesNothing() {
        ClusterCollapse collapse = expanded("farm");
        collapse.renamed(FARM_AND_DAM, "farm", "Farm");
        assertFalse(collapse.isCollapsed("farm"));
    }
}
