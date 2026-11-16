package com.kalix.ide.model;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Which clusters the map shows collapsed: a session map keyed by {@link ClusterIndex#key},
 * never saved (clusters.md, Architecture, "Collapse state"). A cluster with no entry is
 * collapsed, so everything starts collapsed. Entries are never removed when a cluster
 * vanishes, so one that undo brings back finds its old state.
 *
 * <p>The map panel holds one, for its model. It is view state, so it isn't kept on
 * {@link HydrologicalModel}: only the map reads it, where the selection there is read by
 * the text editor too. Create and Rename write entries ({@link #created}, {@link #renamed})
 * from the index as it was before the edit, since only that says whether a name is new.
 * Used on the event thread only.</p>
 */
public final class ClusterCollapse {

    private final Map<String, Boolean> collapsedByKey = new HashMap<>();

    /** Whether the cluster, by any spelling, is collapsed; one never seen is. */
    public boolean isCollapsed(String cluster) {
        return collapsedByKey.getOrDefault(ClusterIndex.key(cluster), true);
    }

    /** Collapses or expands the clusters, by any spelling. */
    public void setCollapsed(Collection<String> clusters, boolean collapsed) {
        for (String cluster : clusters) {
            collapsedByKey.put(ClusterIndex.key(cluster), collapsed);
        }
    }

    /**
     * Whether a node is hidden: it is a member of a collapsed cluster. The one answer the
     * renderer, the layers and every hit-test ask.
     */
    public boolean isHidden(String node, ClusterIndex index) {
        String cluster = index.clusterOf(node);
        return cluster != null && isCollapsed(cluster);
    }

    /** The collapsed clusters of {@code index}, as it shows their names, in its order. */
    public List<String> collapsedNames(ClusterIndex index) {
        List<String> collapsed = new ArrayList<>();
        for (String name : index.names()) {
            if (isCollapsed(name)) {
                collapsed.add(name);
            }
        }
        return collapsed;
    }

    /**
     * Records a Create named {@code name}. A new name starts collapsed, even if an earlier
     * cluster of that name left an entry; a name that already exists in {@code before}, by
     * any spelling, is a join, which leaves that cluster's state alone.
     *
     * @param before the clusters before the edit
     */
    public void created(ClusterIndex before, String name) {
        if (before.name(name) == null) {
            collapsedByKey.put(ClusterIndex.key(name), true);
        }
    }

    /**
     * Records a Rename of {@code oldName} to {@code newName}. A new name takes the old
     * name's state; a name that already exists in {@code before} is a merge, which keeps
     * the target's state; a change of capitalisation alone keeps the same key, so nothing
     * changes.
     *
     * @param before the clusters before the edit
     */
    public void renamed(ClusterIndex before, String oldName, String newName) {
        if (before.name(newName) == null) {
            collapsedByKey.put(ClusterIndex.key(newName), isCollapsed(oldName));
        }
    }
}
