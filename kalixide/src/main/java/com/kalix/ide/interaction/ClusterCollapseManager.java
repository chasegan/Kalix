package com.kalix.ide.interaction;

import com.kalix.ide.model.ClusterCollapse;
import com.kalix.ide.model.ClusterIndex;
import com.kalix.ide.model.HydrologicalModel;
import com.kalix.ide.model.ModelLink;
import com.kalix.ide.utils.ClusterKey;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * The one owner of which clusters the map shows collapsed (clusters.md, Architecture,
 * "Collapse state"). Every collapse or expand is a command here: the map's menu today, and
 * the icon click and the selection rule to come. A command writes the state, deselects whatever
 * it hides, and tells its listeners once, so no caller has to remember those three steps and
 * no second caller can forget one. The state itself is a {@link ClusterCollapse}, which only
 * answers questions; this is its one writer, a rule held by Javadoc and review, since the
 * two live in different packages.
 *
 * <p>Like the map's other managers it holds the model and acts on it. Unlike them it owns
 * durable session state and has listeners, which is the shape ADR-0016 §3 asks of a view's
 * state. The map panel holds one for its model and repaints on each change.</p>
 *
 * <p>Until a selected icon can be drawn as selected, a selection out of sight would be
 * acted on by Delete or Cut unseen. So a collapse deselects every node it hides, even a
 * wholly selected cluster, and every link that ends at one. So does an edit that puts nodes
 * into a collapsed cluster: a selection the modeller didn't make would otherwise expand it
 * (clusters.md rules 10 and 15).</p>
 */
public final class ClusterCollapseManager {

    private final HydrologicalModel model;
    private final ClusterCollapse collapse;
    private final List<Runnable> listeners = new ArrayList<>();

    public ClusterCollapseManager(HydrologicalModel model, ClusterCollapse collapse) {
        this.model = model;
        this.collapse = collapse;
    }

    /** Runs {@code listener} after every change of collapse state, once per command. */
    public void addListener(Runnable listener) {
        listeners.add(listener);
    }

    /** The keys of the collapsed clusters of {@code index}: what the menu's rules are given. */
    public Set<ClusterKey> collapsedKeys(ClusterIndex index) {
        return collapse.collapsedKeys(index);
    }

    /** Collapses the clusters, by any spelling, deselecting the nodes and links that hides. */
    public void collapse(Collection<String> clusters) {
        collapse.setCollapsed(clusters, true);
        deselectHidden();
        changed();
    }

    /** Expands the clusters, by any spelling. Nothing is hidden, so the selection is left alone. */
    public void expand(Collection<String> clusters) {
        collapse.setCollapsed(clusters, false);
        changed();
    }

    /** Expands every cluster of the model. */
    public void expandAll() {
        expand(model.getClusters().names());
    }

    /**
     * After a Create named {@code name} that put {@code nodes} in it. A new name starts
     * collapsed and a join leaves the cluster's state alone ({@link ClusterCollapse#created});
     * either way the nodes are deselected if the cluster is collapsed.
     *
     * @param before the clusters before the edit, read at the click: only they say whether
     *               the name is new, since the model is reparsed only later, on the event thread
     */
    public void created(ClusterIndex before, String name, Collection<String> nodes) {
        collapse.created(before, name);
        deselectIfHidden(nodes, name);
        changed();
    }

    /**
     * After a Rename of {@code oldName} to {@code newName} ({@link ClusterCollapse#renamed}).
     * The members are deselected if the renamed, or merged into, cluster is collapsed.
     */
    public void renamed(ClusterIndex before, String oldName, String newName) {
        collapse.renamed(before, oldName, newName);
        deselectIfHidden(before.members(oldName), newName);
        changed();
    }

    /**
     * After an Add that put {@code nodes} in {@code cluster}. No collapse state changes, so no
     * listener runs; the nodes are deselected if the cluster is collapsed.
     */
    public void added(Collection<String> nodes, String cluster) {
        deselectIfHidden(nodes, cluster);
    }

    /** Deselects every selected node that is now hidden, and every selected link with a hidden end. */
    private void deselectHidden() {
        ClusterIndex index = model.getClusters();
        for (String node : model.getSelectedNodes()) {
            if (collapse.isHidden(node, index)) {
                model.deselectNode(node);
            }
        }
        for (ModelLink link : model.getAllLinks()) {
            if (model.isLinkSelected(link)
                    && (collapse.isHidden(link.getUpstreamTerminus(), index)
                        || collapse.isHidden(link.getDownstreamTerminus(), index))) {
                model.deselectLink(link);
            }
        }
    }

    /**
     * Deselects {@code nodes}, and the selected links that end at them, if {@code cluster} is
     * collapsed. Decided from the names, not the index, because the model is reparsed only
     * later, on the event thread.
     */
    private void deselectIfHidden(Collection<String> nodes, String cluster) {
        if (!collapse.isCollapsed(cluster)) {
            return;
        }
        for (String node : nodes) {
            model.deselectNode(node);
        }
        for (ModelLink link : model.getAllLinks()) {
            if (model.isLinkSelected(link)
                    && (nodes.contains(link.getUpstreamTerminus())
                        || nodes.contains(link.getDownstreamTerminus()))) {
                model.deselectLink(link);
            }
        }
    }

    private void changed() {
        for (Runnable listener : listeners) {
            listener.run();
        }
    }
}
