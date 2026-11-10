package com.kalix.ide.model;

import com.kalix.ide.utils.EngineNames;

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
 * member. Immutable. Two indexes are equal only if their clusters, spellings and members
 * are the same and in the same order.
 *
 * <p>Names that differ only in case are one cluster (ADR-0010 §6.2), found from any
 * spelling. Two choices about that are assumptions, each made in one place so it is
 * cheap to change: that case is ignored at all ({@link #key}), and that a cluster shows
 * the spelling of its first mention in the file ({@link #of}).</p>
 */
public final class ClusterIndex {

    public static final ClusterIndex EMPTY = new ClusterIndex(new LinkedHashMap<>(), Map.of());

    /** Keyed by {@link #key}; insertion order is first mention. */
    private final Map<String, List<String>> membersByKey;
    /** The name each cluster is shown with, by key. */
    private final Map<String, String> nameByKey;
    private final Map<String, String> clusterByNode = new HashMap<>();
    private final List<String> names;

    private ClusterIndex(LinkedHashMap<String, List<String>> membersByKey, Map<String, String> nameByKey) {
        this.membersByKey = membersByKey;
        this.nameByKey = nameByKey;
        this.names = membersByKey.keySet().stream().map(nameByKey::get).toList();
        membersByKey.forEach((key, members) ->
                members.forEach(member -> clusterByNode.put(member, nameByKey.get(key))));
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

        LinkedHashMap<String, List<String>> membersByKey = new LinkedHashMap<>();
        Map<String, String> nameByKey = new HashMap<>();
        for (ModelNode node : lastSections.values()) {
            String cluster = node.getCluster();
            if (cluster != null) {
                String key = key(cluster);
                // The first mention's spelling is the one shown (an assumption: see above).
                nameByKey.putIfAbsent(key, cluster);
                membersByKey.computeIfAbsent(key, k -> new ArrayList<>()).add(node.getName());
            }
        }
        membersByKey.replaceAll((key, members) -> List.copyOf(members));
        return new ClusterIndex(membersByKey, Map.copyOf(nameByKey));
    }

    /**
     * The one key cluster names are compared by, wherever they are compared. Names are
     * matched ignoring case, as node names are ({@link EngineNames#nodeKey}).
     */
    public static String key(String name) {
        return EngineNames.nodeKey(name);
    }

    /** Every cluster's name, in order of first mention. */
    public List<String> names() {
        return names;
    }

    /** The name a cluster is shown with, from any spelling, or {@code null} if there is none. */
    public String name(String anySpelling) {
        return nameByKey.get(key(anySpelling));
    }

    /** A cluster's members in file order, from any spelling; empty if there is no such cluster. */
    public List<String> members(String cluster) {
        return membersByKey.getOrDefault(key(cluster), List.of());
    }

    /** The name of the cluster a node belongs to, or {@code null} if it is loose or unknown. */
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
        return names.equals(other.names) && membersByKey.equals(other.membersByKey);
    }

    @Override
    public int hashCode() {
        return 31 * names.hashCode() + membersByKey.hashCode();
    }
}
