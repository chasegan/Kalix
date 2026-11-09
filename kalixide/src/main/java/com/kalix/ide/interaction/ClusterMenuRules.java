package com.kalix.ide.interaction;

import com.kalix.ide.model.ClusterIndex;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The one place that decides which cluster items the map's context menu offers for a
 * selection (ADR-0002 §4: an item that can't apply is absent). Pure: a function of the
 * selected nodes, the model's clusters and which of them are collapsed, so every case
 * can be tested without a menu. It names clusters, never labels them; the menu turns
 * names into labels through {@link ClusterLabels} (ADR-0003 §2).
 *
 * <p>The selection is sorted into three kinds. A <em>collapsed</em> cluster with every
 * member selected counts as one selected thing, which is how it is selected (the design,
 * rule 10). A <em>member</em> is a selected node of any other cluster, including part of
 * a collapsed one, since selecting part of a cluster expands it. A <em>loose</em> node is
 * in no cluster. Links take no part: the menu passes only the selected nodes.
 */
final class ClusterMenuRules {

    private final boolean create;
    private final List<String> addTargets;
    private final boolean remove;
    private final String renameTarget;
    private final List<String> disbandTargets;
    private final List<String> collapseTargets;
    private final List<String> expandTargets;
    private final boolean nodeRename;
    private final boolean rotate;
    private final boolean collapseAll;
    private final boolean expandAll;

    private ClusterMenuRules(Collection<String> selectedNodes, ClusterIndex index, Set<String> collapsed) {
        Set<String> selected = new LinkedHashSet<>(selectedNodes);

        List<String> collapsedSelected = new ArrayList<>();
        List<String> memberClusters = new ArrayList<>();
        int members = 0;
        int loose = 0;
        for (String cluster : index.names()) {
            boolean whole = selected.containsAll(index.members(cluster));
            if (collapsed.contains(cluster) && whole) {
                collapsedSelected.add(cluster);
            }
        }
        for (String node : selected) {
            String cluster = index.clusterOf(node);
            if (cluster == null) {
                loose++;
            } else if (!collapsedSelected.contains(cluster)) {
                members++;
                if (!memberClusters.contains(cluster)) {
                    memberClusters.add(cluster);
                }
            }
        }
        boolean visible = loose + members > 0;
        boolean anyLoose = loose > 0;

        create = visible && collapsedSelected.isEmpty();
        addTargets = !visible ? List.of()
                : !collapsedSelected.isEmpty() ? List.copyOf(collapsedSelected)
                : index.names().stream()
                        .filter(cluster -> anyLoose || !memberClusters.equals(List.of(cluster)))
                        .toList();
        remove = members > 0;
        renameTarget = wholeCluster(selected, index);
        disbandTargets = index.names().stream()
                .filter(cluster -> memberClusters.contains(cluster) || collapsedSelected.contains(cluster))
                .toList();
        collapseTargets = index.names().stream().filter(memberClusters::contains).toList();
        expandTargets = List.copyOf(collapsedSelected);
        // A collapsed cluster is one thing, not a node to rename (clusters.md, settled
        // decision 12), and only counts as one thing towards the two that Rotate needs
        // (decision 17).
        nodeRename = selected.size() == 1 && collapsedSelected.isEmpty();
        rotate = loose + members + collapsedSelected.size() >= 2;
        // Collapse state outlives the clusters it names, so only live names count.
        collapseAll = index.names().stream().anyMatch(cluster -> !collapsed.contains(cluster));
        expandAll = index.names().stream().anyMatch(collapsed::contains);
    }

    /** The rules for {@code selectedNodes}, given the model's clusters and the collapsed ones. */
    static ClusterMenuRules of(Collection<String> selectedNodes, ClusterIndex index, Set<String> collapsed) {
        return new ClusterMenuRules(selectedNodes, index, collapsed);
    }

    /** The cluster whose members are exactly the selection, collapsed or not, or {@code null}. */
    private static String wholeCluster(Set<String> selected, ClusterIndex index) {
        if (selected.isEmpty()) {
            return null;
        }
        String cluster = index.clusterOf(selected.iterator().next());
        if (cluster == null) {
            return null;
        }
        List<String> members = index.members(cluster);
        return members.size() == selected.size() && selected.containsAll(members) ? cluster : null;
    }

    /** {@code Create cluster…}: visible nodes selected, and no collapsed cluster. */
    boolean offersCreate() {
        return create;
    }

    /**
     * Where {@code Add to} can put the visible nodes, in file order: the selected collapsed
     * clusters if there are any, otherwise every cluster but one already holding all of
     * them. Empty when there is nothing to add or nowhere to add it.
     */
    List<String> addTargets() {
        return addTargets;
    }

    /** {@code Remove from cluster}: a selected member, of any number of clusters. */
    boolean offersRemove() {
        return remove;
    }

    /** The cluster {@code Rename "x"…} renames: the selection is exactly its members. */
    String renameTarget() {
        return renameTarget;
    }

    /** The clusters {@code Disband} takes apart: every one the selection touches, in file order. */
    List<String> disbandTargets() {
        return disbandTargets;
    }

    /** The expanded clusters with a selected member, which {@code Collapse} folds. */
    List<String> collapseTargets() {
        return collapseTargets;
    }

    /** The selected collapsed clusters, which {@code Expand} opens. */
    List<String> expandTargets() {
        return expandTargets;
    }

    /** {@code Select nodes ▸ Cluster}: a selected member, whose cluster it can select. */
    boolean offersSelectCluster() {
        return remove;
    }

    /** The node {@code Rename}: one selected node, which isn't a collapsed cluster. */
    boolean offersNodeRename() {
        return nodeRename;
    }

    /** Rotate: at least two things selected, a collapsed cluster counting as one. */
    boolean offersRotate() {
        return rotate;
    }

    /** {@code Collapse all clusters}: some cluster is expanded. */
    boolean offersCollapseAll() {
        return collapseAll;
    }

    /** {@code Expand all clusters}: some cluster is collapsed. */
    boolean offersExpandAll() {
        return expandAll;
    }
}
