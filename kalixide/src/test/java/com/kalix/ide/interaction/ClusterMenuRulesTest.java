package com.kalix.ide.interaction;

import com.kalix.ide.model.ClusterIndex;
import com.kalix.ide.model.ModelNode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Which cluster items the map menu offers, for every kind of selection: loose nodes,
 * members of expanded clusters, collapsed clusters, mixes of these, and nothing selected.
 * A collapsed cluster whose members are all selected counts as one selected thing; a
 * partly selected one counts as members, since selecting part of it expands it. Each row
 * reads as the menu would: only what is offered is listed.
 *
 * <p>The model: clusters a (a1, a2, a3), b (b1, b2), c (c1 only) and d (d1, d2), first
 * mentioned in that order, and loose nodes x and y.
 */
class ClusterMenuRulesTest {

    private static final ClusterIndex INDEX = ClusterIndex.of(List.of(
            node("x", null), node("a1", "a"), node("b1", "b"), node("a2", "a"), node("c1", "c"),
            node("b2", "b"), node("a3", "a"), node("d1", "d"), node("d2", "d"), node("y", null)));

    private static ModelNode node(String name, String cluster) {
        return new ModelNode(name, "storage", 0, 0, null, cluster);
    }

    /** With every cluster expanded. */
    private static String menu(String... selected) {
        return menu(Set.of(), selected);
    }

    private static String menu(Set<String> collapsed, String... selected) {
        return describe(ClusterMenuRules.of(List.of(selected), INDEX, collapsed));
    }

    private static String describe(ClusterMenuRules rules) {
        List<String> offered = new ArrayList<>();
        if (rules.offersCreate()) offered.add("create");
        if (!rules.addTargets().isEmpty()) offered.add("add" + rules.addTargets());
        if (rules.offersRemove()) offered.add("remove");
        if (rules.renameTarget() != null) offered.add("rename[" + rules.renameTarget() + "]");
        if (!rules.disbandTargets().isEmpty()) offered.add("disband" + rules.disbandTargets());
        if (!rules.collapseTargets().isEmpty()) offered.add("collapse" + rules.collapseTargets());
        if (!rules.expandTargets().isEmpty()) offered.add("expand" + rules.expandTargets());
        if (rules.offersSelectCluster()) offered.add("select-cluster");
        if (rules.offersNodeRename()) offered.add("node-rename");
        if (rules.offersRotate()) offered.add("rotate");
        if (rules.offersCollapseAll()) offered.add("collapse-all");
        if (rules.offersExpandAll()) offered.add("expand-all");
        return String.join(" | ", offered).replace(", ", ",");
    }

    @Test
    void oneLooseNode() {
        assertEquals("create | add[a,b,c,d] | node-rename | collapse-all", menu("x"));
    }

    @Test
    void inAModelWithNoClustersOffersOnlyCreate() {
        ClusterMenuRules rules = ClusterMenuRules.of(List.of("x"), ClusterIndex.EMPTY, Set.of());
        assertEquals("create | node-rename", describe(rules));
    }

    @Test
    void severalLooseNodes() {
        assertEquals("create | add[a,b,c,d] | rotate | collapse-all", menu("x", "y"));
    }

    @Test
    void oneMemberNode() {
        assertEquals("create | add[b,c,d] | remove | disband[a] | collapse[a] | select-cluster"
                + " | node-rename | collapse-all", menu("a1"));
    }

    @Test
    void theOnlyMemberOfItsClusterIsAlsoTheWholeCluster() {
        assertEquals("create | add[a,b,d] | remove | rename[c] | disband[c] | collapse[c]"
                + " | select-cluster | node-rename | collapse-all", menu("c1"));
    }

    @Test
    void partOfOneCluster() {
        assertEquals("create | add[b,c,d] | remove | disband[a] | collapse[a] | select-cluster"
                + " | rotate | collapse-all", menu("a1", "a2"));
    }

    @Test
    void theWholeOfOneExpandedCluster() {
        assertEquals("create | add[b,c,d] | remove | rename[a] | disband[a] | collapse[a]"
                + " | select-cluster | rotate | collapse-all", menu("a1", "a2", "a3"));
    }

    @Test
    void membersOfOneClusterPlusLooseNodesCanAddTheLooseOnesToIt() {
        assertEquals("create | add[a,b,c,d] | remove | disband[a] | collapse[a] | select-cluster"
                + " | rotate | collapse-all", menu("a1", "x"));
    }

    @Test
    void membersOfTwoClusters() {
        String expected = "create | add[a,b,c,d] | remove | disband[a,b] | collapse[a,b]"
                + " | select-cluster | rotate | collapse-all";
        assertEquals(expected, menu("a1", "b1"));
        assertEquals(expected, menu("a1", "b1", "x"), "with a loose node too");
    }

    @Test
    void oneCollapsedCluster() {
        assertEquals("rename[a] | disband[a] | expand[a] | collapse-all | expand-all",
                menu(Set.of("a"), "a1", "a2", "a3"));
    }

    @Test
    void oneMemberCollapsedClusterIsAClusterNotANode() {
        // Neither node Rename nor Rotate: the one selected thing is a cluster (clusters.md,
        // settled decisions 12 and 17).
        assertEquals("rename[c] | disband[c] | expand[c] | collapse-all | expand-all",
                menu(Set.of("c"), "c1"));
    }

    @Test
    void twoCollapsedClusters() {
        assertEquals("disband[a,b] | expand[a,b] | rotate | collapse-all | expand-all",
                menu(Set.of("a", "b"), "a1", "a2", "a3", "b1", "b2"));
    }

    @Test
    void aCollapsedClusterPlusLooseNodesOffersOnlyThatClusterToAddTo() {
        assertEquals("add[a] | disband[a] | expand[a] | rotate | collapse-all | expand-all",
                menu(Set.of("a"), "a1", "a2", "a3", "x"));
    }

    @Test
    void twoCollapsedClustersPlusALooseNodeOfferBothToAddTo() {
        assertEquals("add[a,b] | disband[a,b] | expand[a,b] | rotate | collapse-all | expand-all",
                menu(Set.of("a", "b"), "a1", "a2", "a3", "b1", "b2", "x"));
    }

    @Test
    void aCollapsedClusterPlusAMemberOfAnExpandedOne() {
        assertEquals("add[a] | remove | disband[a,b] | collapse[b] | expand[a] | select-cluster"
                + " | rotate | collapse-all | expand-all", menu(Set.of("a"), "a1", "a2", "a3", "b1"));
    }

    @Test
    void nothingSelectedOffersOnlyTheAllItems() {
        assertEquals("collapse-all", menu(), "all expanded");
        assertEquals("expand-all", menu(Set.of("a", "b", "c", "d")), "all collapsed");
        assertEquals("collapse-all | expand-all", menu(Set.of("a")), "mixed");
        assertEquals("", describe(ClusterMenuRules.of(List.of(), ClusterIndex.EMPTY, Set.of())),
                "no clusters");
    }

    @Test
    void partOfACollapsedClusterCountsAsMembersSinceSelectingThemExpandsIt() {
        assertEquals("create | add[b,c,d] | remove | disband[a] | collapse[a] | select-cluster"
                + " | node-rename | collapse-all | expand-all", menu(Set.of("a"), "a1"));
    }

    @Test
    void aCollapsedNameWithNoClusterLeftCountsForNothing() {
        // Collapse state is never removed when a cluster vanishes, so stale names are normal.
        assertEquals("collapse-all", menu(Set.of("gone")));
    }

    @Test
    void clustersComeInFileOrderNotSelectionOrder() {
        assertEquals("create | add[a,b,c,d] | remove | disband[a,b] | collapse[a,b]"
                + " | select-cluster | rotate | collapse-all", menu("b1", "a1"));
    }
}
