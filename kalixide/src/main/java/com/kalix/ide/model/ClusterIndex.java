package com.kalix.ide.model;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The clusters of a model, in order of each cluster's first mention in the file, which
 * is the order overlapping clusters are drawn in. Nothing here is stored that isn't
 * derived from the text: this is rebuilt from the nodes at every parse, so a cluster
 * with no members does not exist. Only complete nodes count, as only they are on the
 * map, so a section with no {@code type} or {@code loc} is neither a mention nor a
 * member. Immutable. Two indexes are equal only if their clusters and members are in
 * the same order.
 */
public final class ClusterIndex {

    public static final ClusterIndex EMPTY = new ClusterIndex(new LinkedHashMap<>());

    /** Insertion order is first mention. */
    private final Map<String, List<String>> membersByCluster;
    private final Map<String, String> clusterByNode = new HashMap<>();
    private final List<String> names;

    private ClusterIndex(LinkedHashMap<String, List<String>> membersByCluster) {
        this.membersByCluster = membersByCluster;
        this.names = List.copyOf(membersByCluster.keySet());
        membersByCluster.forEach((cluster, members) ->
                members.forEach(member -> clusterByNode.put(member, cluster)));
    }

    /**
     * Builds the index from nodes in file order. A node name that appears twice counts
     * once, at its last section, matching the model's last-wins view of a name.
     */
    public static ClusterIndex of(Collection<ModelNode> nodesInFileOrder) {
        LinkedHashMap<String, ModelNode> lastSections = new LinkedHashMap<>();
        for (ModelNode node : nodesInFileOrder) {
            lastSections.remove(node.getName());
            lastSections.put(node.getName(), node);
        }

        LinkedHashMap<String, List<String>> membersByCluster = new LinkedHashMap<>();
        for (ModelNode node : lastSections.values()) {
            if (node.getCluster() != null) {
                membersByCluster.computeIfAbsent(node.getCluster(), cluster -> new ArrayList<>())
                        .add(node.getName());
            }
        }
        membersByCluster.replaceAll((cluster, members) -> List.copyOf(members));
        return new ClusterIndex(membersByCluster);
    }

    /** Every cluster's name, in order of first mention. */
    public List<String> names() {
        return names;
    }

    /** A cluster's members in file order; empty if there is no such cluster. */
    public List<String> members(String cluster) {
        return membersByCluster.getOrDefault(cluster, List.of());
    }

    /** The cluster a node belongs to, or {@code null} if it is loose or unknown. */
    public String clusterOf(String node) {
        return clusterByNode.get(node);
    }

    public boolean isEmpty() {
        return names.isEmpty();
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;

        ClusterIndex other = (ClusterIndex) obj;
        return names.equals(other.names) && membersByCluster.equals(other.membersByCluster);
    }

    @Override
    public int hashCode() {
        return 31 * names.hashCode() + membersByCluster.hashCode();
    }
}
