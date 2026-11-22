package com.kalix.ide.interaction;

import com.kalix.ide.model.ClusterCollapse;
import com.kalix.ide.model.HydrologicalModel;
import com.kalix.ide.utils.ClusterKey;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The one owner of collapse state (clusters.md, Architecture, "Collapse state"): each
 * command writes the state, deselects what it hides, nodes and links alike, and tells its
 * listeners once. Until a selected icon can be drawn as selected, that is every hidden
 * node, even a wholly selected cluster (clusters.md rule 10).
 *
 * <p>The model: cluster north (n1, n2), cluster south (s1), and loose x, linked to n1.</p>
 */
class ClusterCollapseManagerTest {

    private static final String MODEL = """
            [node.x]
            type = gauge
            loc = 0, 0
            ds_1 = n1

            #@cluster = north
            [node.n1]
            type = gauge
            loc = 1, 0

            #@cluster = north
            [node.n2]
            type = gauge
            loc = 2, 0

            #@cluster = south
            [node.s1]
            type = gauge
            loc = 3, 0
            """;

    private final HydrologicalModel model = new HydrologicalModel();
    private final ClusterCollapse collapse = new ClusterCollapse();
    private final ClusterCollapseManager manager = new ClusterCollapseManager(model, collapse);
    private int changes;

    ClusterCollapseManagerTest() {
        model.parseFromIniText(MODEL);
        collapse.setCollapsed(List.of("north", "south"), false);
        manager.addListener(() -> changes++);
    }

    private void select(String... nodes) {
        model.selectNodes(List.of(nodes));
    }

    private void selectTheLink() {
        model.selectLink(model.getAllLinks().get(0), true);
    }

    @Test
    void collapseDeselectsTheNodesAndLinksItHidesAndTellsListenersOnce() {
        select("x", "n1", "n2", "s1");
        selectTheLink();

        manager.collapse(List.of("north"));

        assertTrue(collapse.isCollapsed("north"));
        assertFalse(collapse.isCollapsed("south"));
        assertEquals(Set.of("x", "s1"), model.getSelectedNodes(), "a wholly selected cluster too, until icons draw as selected");
        assertEquals(0, model.getSelectedLinkCount(), "x -> n1 now runs to the icon");
        assertEquals(1, changes);
    }

    @Test
    void expandLeavesTheSelectionAloneAndTellsListenersOnce() {
        collapse.setCollapsed(List.of("north"), true);
        select("x", "s1");

        manager.expand(List.of("NORTH"));

        assertFalse(collapse.isCollapsed("north"));
        assertEquals(Set.of("x", "s1"), model.getSelectedNodes());
        assertEquals(1, changes);
    }

    @Test
    void expandAllExpandsEveryClusterOfTheModel() {
        collapse.setCollapsed(List.of("north", "south"), true);

        manager.expandAll();

        assertFalse(collapse.isCollapsed("north") || collapse.isCollapsed("south"));
        assertEquals(1, changes);
    }

    @Test
    void createdWithANewNameStartsCollapsedAndDeselectsItsNodes() {
        select("x", "s1");
        selectTheLink();

        manager.created(model.getClusters(), "east", List.of("x"));

        assertTrue(collapse.isCollapsed("east"));
        assertEquals(Set.of("s1"), model.getSelectedNodes(), "only the nodes put in");
        assertEquals(0, model.getSelectedLinkCount(), "x -> n1 ends at x");
        assertEquals(1, changes);
    }

    @Test
    void createdJoiningAnExpandedClusterKeepsTheNodesSelected() {
        select("x", "s1");

        manager.created(model.getClusters(), "North", List.of("x"));

        assertFalse(collapse.isCollapsed("north"));
        assertEquals(Set.of("x", "s1"), model.getSelectedNodes());
        assertEquals(1, changes);
    }

    @Test
    void renamedIntoACollapsedClusterDeselectsTheMembers() {
        collapse.setCollapsed(List.of("south"), true);
        select("n1", "n2", "x");

        manager.renamed(model.getClusters(), "north", "south");

        assertTrue(collapse.isCollapsed("south"), "a merge keeps the target's state");
        assertEquals(Set.of("x"), model.getSelectedNodes());
        assertEquals(1, changes);
    }

    @Test
    void addedToACollapsedClusterDeselectsWithoutAnEvent() {
        collapse.setCollapsed(List.of("north"), true);
        select("x", "s1");
        selectTheLink();

        manager.added(List.of("x"), "NORTH");

        assertEquals(Set.of("s1"), model.getSelectedNodes());
        assertEquals(0, model.getSelectedLinkCount());
        assertEquals(0, changes, "no collapse state changed");
    }

    @Test
    void addedToAnExpandedClusterChangesNothing() {
        select("x", "s1");
        selectTheLink();

        manager.added(List.of("x"), "north");

        assertEquals(Set.of("x", "s1"), model.getSelectedNodes());
        assertEquals(1, model.getSelectedLinkCount());
        assertEquals(0, changes);
    }

    @Test
    void collapsedKeysAreTheStates() {
        collapse.setCollapsed(List.of("south"), true);
        assertEquals(Set.of(new ClusterKey("south")), manager.collapsedKeys(model.getClusters()));
    }
}
