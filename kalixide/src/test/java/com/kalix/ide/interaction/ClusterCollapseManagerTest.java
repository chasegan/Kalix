package com.kalix.ide.interaction;

import com.kalix.ide.model.ClusterCollapse;
import com.kalix.ide.model.HydrologicalModel;
import com.kalix.ide.utils.ClusterKey;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The one owner of collapse state (clusters.md, Architecture, "Collapse state"): each
 * command writes the state, keeps the selection to clusters.md rule 10 and tells its
 * listeners once; the rule itself runs after every selection change and reparse.
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

    /** The model with a third member of north: an undo or hand edit, as the reparse sees it. */
    private static final String MODEL_WITH_N3 = MODEL + """

            #@cluster = north
            [node.n3]
            type = gauge
            loc = 4, 0
            """;

    private final HydrologicalModel model = new HydrologicalModel();
    private final ClusterCollapse collapse = new ClusterCollapse();
    private final ClusterCollapseManager manager = new ClusterCollapseManager(model, collapse);
    private int changes;

    ClusterCollapseManagerTest() {
        model.parseFromIniTextIncremental(MODEL);
        collapse.setCollapsed(List.of("north", "south"), false);
        manager.addListener(() -> changes++);
    }

    private void select(String... nodes) {
        model.selectNodes(List.of(nodes));
    }

    private void selectTheLink() {
        model.selectLink(model.getAllLinks().get(0), true);
    }

    // Commands

    @Test
    void collapseKeepsAWhollySelectedClusterSelectedAndDeselectsItsLinks() {
        select("x", "n1", "n2", "s1");
        selectTheLink();

        manager.collapse(List.of("north"));

        assertTrue(collapse.isCollapsed("north"));
        assertFalse(collapse.isCollapsed("south"));
        assertEquals(Set.of("x", "n1", "n2", "s1"), model.getSelectedNodes(), "the icon is selected");
        assertEquals(0, model.getSelectedLinkCount(), "x -> n1 now runs to the icon");
        assertEquals(1, changes);
    }

    @Test
    void collapseDeselectsAPartlySelectedClustersMembersAndStaysCollapsed() {
        select("x", "n1");

        manager.collapse(List.of("North"));

        assertTrue(collapse.isCollapsed("north"), "not expanded straight back by the rule");
        assertEquals(Set.of("x"), model.getSelectedNodes());
        assertEquals(1, changes);
    }

    @Test
    void listenersRunAfterTheSelectionIsFinal() {
        select("x", "n1");
        List<Set<String>> seen = new ArrayList<>();
        manager.addListener(() -> seen.add(model.getSelectedNodes()));

        manager.collapse(List.of("north"));

        assertEquals(List.of(Set.of("x")), seen);
    }

    @Test
    void aListenerMayAddAListenerWhileBeingTold() {
        manager.addListener(() -> manager.addListener(() -> { }));

        manager.expandAll();
        manager.expandAll();

        assertEquals(2, changes);
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
    void createdWithANewNameStartsCollapsedAndKeepsItsNodesSelected() {
        select("x", "s1");

        manager.created(model.getClusters(), "east", List.of("x"));

        assertTrue(collapse.isCollapsed("east"));
        assertEquals(Set.of("x", "s1"), model.getSelectedNodes(), "a selected icon, once reparsed");
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
    void createdJoiningACollapsedClusterThatIsNotSelectedDeselectsTheNodes() {
        collapse.setCollapsed(List.of("north"), true);
        select("x", "s1");
        selectTheLink();

        manager.created(model.getClusters(), "North", List.of("x"));

        assertEquals(Set.of("s1"), model.getSelectedNodes());
        assertEquals(0, model.getSelectedLinkCount(), "x -> n1 ends at x");
        assertEquals(1, changes);
    }

    @Test
    void renamedIntoACollapsedClusterThatIsNotSelectedDeselectsTheMembers() {
        collapse.setCollapsed(List.of("south"), true);
        select("n1", "n2", "x");

        manager.renamed(model.getClusters(), "north", "south");

        assertTrue(collapse.isCollapsed("south"), "a merge keeps the target's state");
        assertEquals(Set.of("x"), model.getSelectedNodes());
        assertEquals(1, changes);
    }

    @Test
    void renamedIntoAWhollySelectedCollapsedClusterKeepsTheMembers() {
        collapse.setCollapsed(List.of("south"), true);
        select("n1", "n2", "s1");

        manager.renamed(model.getClusters(), "north", "south");

        assertEquals(Set.of("n1", "n2", "s1"), model.getSelectedNodes());
    }

    @Test
    void aRenameOfCapitalisationAloneKeepsASelectedCollapsedCluster() {
        collapse.setCollapsed(List.of("north"), true);
        select("n1", "n2");

        manager.renamed(model.getClusters(), "north", "North");

        assertTrue(collapse.isCollapsed("north"));
        assertEquals(Set.of("n1", "n2"), model.getSelectedNodes());
    }

    @Test
    void addedToACollapsedClusterThatIsNotSelectedDeselectsWithoutAnEvent() {
        collapse.setCollapsed(List.of("north"), true);
        select("x", "s1");
        selectTheLink();

        manager.added(List.of("x"), "NORTH");

        assertEquals(Set.of("s1"), model.getSelectedNodes());
        assertEquals(0, model.getSelectedLinkCount());
        assertEquals(0, changes, "no collapse state changed");
    }

    @Test
    void addedToAWhollySelectedCollapsedClusterKeepsTheNodes() {
        collapse.setCollapsed(List.of("north"), true);
        select("x", "n1", "n2");

        manager.added(List.of("x"), "north");

        assertEquals(Set.of("x", "n1", "n2"), model.getSelectedNodes());
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

    // The selection rule, run on the model's events

    @Test
    void selectingSomeOfACollapsedClusterExpandsIt() {
        collapse.setCollapsed(List.of("north"), true);

        model.selectNode("n1", false);

        assertFalse(collapse.isCollapsed("north"));
        assertEquals(Set.of("n1"), model.getSelectedNodes(), "never widened to the other members");
        assertEquals(1, changes);
    }

    @Test
    void selectingAllOfACollapsedClusterKeepsItCollapsed() {
        collapse.setCollapsed(List.of("north"), true);

        select("n1", "n2");

        assertTrue(collapse.isCollapsed("north"));
        assertEquals(0, changes);
    }

    @Test
    void deselectingOneMemberOfASelectedCollapsedClusterExpandsIt() {
        collapse.setCollapsed(List.of("north"), true);
        select("n1", "n2");

        model.deselectNode("n1");

        assertFalse(collapse.isCollapsed("north"));
    }

    @Test
    void deselectingAllTheMembersAtOnceKeepsItCollapsed() {
        collapse.setCollapsed(List.of("north"), true);
        select("n1", "n2");

        model.deselectNodes(List.of("n1", "n2"));

        assertTrue(collapse.isCollapsed("north"), "the rule never sees the change half made");
    }

    @Test
    void aReparseThatLeavesACollapsedClusterPartlySelectedExpandsIt() {
        collapse.setCollapsed(List.of("north"), true);
        select("n1", "n2");

        model.parseFromIniTextIncremental(MODEL_WITH_N3);

        assertFalse(collapse.isCollapsed("north"), "n3 joined unselected, as an undo can make it");
        assertEquals(Set.of("n1", "n2"), model.getSelectedNodes());
        assertEquals(1, changes);
    }

    @Test
    void aSelectedLinkWithAnEndInACollapsedClusterIsDeselected() {
        collapse.setCollapsed(List.of("north"), true);

        selectTheLink();

        assertEquals(0, model.getSelectedLinkCount(), "x -> n1 runs to the icon");
        assertTrue(collapse.isCollapsed("north"), "a link never expands a cluster");
        assertEquals(0, changes);
    }
}
