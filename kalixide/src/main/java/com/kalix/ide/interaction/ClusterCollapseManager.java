package com.kalix.ide.interaction;

import com.kalix.ide.model.ClusterCollapse;
import com.kalix.ide.model.ClusterIndex;
import com.kalix.ide.model.HydrologicalModel;
import com.kalix.ide.model.ModelChangeEvent;
import com.kalix.ide.model.ModelLink;
import com.kalix.ide.utils.ClusterKey;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The one owner of which clusters the map shows collapsed (clusters.md, Architecture,
 * "Collapse state"). Every collapse or expand is a command here, or the selection rule
 * below; each writes the state, keeps the selection to the rule, and tells its listeners
 * once, so no caller has to remember those steps and no second caller can forget one. The
 * state itself is a {@link ClusterCollapse}, which only answers questions; this is its one
 * writer, a rule held by Javadoc and review, since the two live in different packages.
 *
 * <p>Like the map's other managers it holds the model and acts on it. Unlike them it owns
 * durable session state and has listeners, which is the shape ADR-0016 §3 asks of a view's
 * state. The map panel holds one for its model and repaints on each change. It listens to
 * the model and never stops, so it must live as long as the model: one per model, as the map
 * panel is. A second manager on the same model would run the rule a second time.</p>
 *
 * <p><b>The selection rule</b> (clusters.md rule 10) runs after every selection change and
 * every reparse, so undo, redo and hand edits are held to it as well as the map: a collapsed
 * cluster with all its members selected is selected, and its icon is drawn so; one with
 * only some selected is expanded, so the modeller sees what they selected. A selected link
 * with an end in a collapsed cluster is deselected, since it can't be shown or picked
 * there; links never decide whether a cluster expands.</p>
 */
public final class ClusterCollapseManager {

    private final HydrologicalModel model;
    private final ClusterCollapse collapse;
    // Copy-on-write: a listener may issue a command, which tells the listeners again; a plain
    // list would be iterated while that happens.
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();

    public ClusterCollapseManager(HydrologicalModel model, ClusterCollapse collapse) {
        this.model = model;
        this.collapse = collapse;
        model.addChangeListener(this::modelChanged);
    }

    /**
     * Runs {@code listener} once after every command that can change collapse state, whether
     * or not it did (a Create that turns out to join an existing cluster still tells), and
     * after the selection rule expands a cluster. Only {@link #added} can't change the state,
     * so only it is silent. The selection is already final when a listener runs.
     */
    public void addListener(Runnable listener) {
        listeners.add(listener);
    }

    /** The keys of the collapsed clusters of {@code index}: what the menu's rules are given. */
    public Set<ClusterKey> collapsedKeys(ClusterIndex index) {
        return collapse.collapsedKeys(index);
    }

    /**
     * Collapses the clusters, by any spelling. A wholly selected one stays selected; a partly
     * selected one has its members deselected, since they were selected some other way and
     * the rule would otherwise expand it straight back. Each command changes the selection
     * in one batch, so the rule, run on that change, never sees one half made.
     */
    public void collapse(Collection<String> clusters) {
        collapse.setCollapsed(clusters, true);
        ClusterIndex index = model.getClusters();
        List<String> deselect = new ArrayList<>();
        for (String cluster : clusters) {
            if (selection(index.members(cluster)) == Selection.SOME) {
                deselect.addAll(index.members(cluster));
            }
        }
        model.deselectNodes(deselect);
        applySelectionRule();
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
     * collapsed and its nodes stay selected: the icon is drawn selected once the model is
     * reparsed. A join leaves the cluster's state alone, and is treated as {@link #added}.
     *
     * @param before the clusters before the edit, read at the click: only they say whether
     *               the name is new, since the model is reparsed only later, on the event thread
     */
    public void created(ClusterIndex before, String name, Collection<String> nodes) {
        collapse.created(before, name);
        if (before.name(name) != null) {
            deselectIfJoiningUnselected(before, nodes, name);
        }
        changed();
    }

    /**
     * After a Rename of {@code oldName} to {@code newName} ({@link ClusterCollapse#renamed}).
     * A new name, or a change of capitalisation alone, keeps the cluster and its selection
     * as they were. A merge is an Add of the members moved in (clusters.md rule 13): into a
     * collapsed cluster that isn't selected it deselects them, as {@link #added} does.
     */
    public void renamed(ClusterIndex before, String oldName, String newName) {
        collapse.renamed(before, oldName, newName);
        boolean merge = before.name(newName) != null
                && !new ClusterKey(oldName).equals(new ClusterKey(newName));
        if (merge) {
            deselectIfJoiningUnselected(before, before.members(oldName), newName);
        }
        changed();
    }

    /**
     * After an Add that put {@code nodes} in {@code cluster}. No collapse state changes, so no
     * listener runs. If the cluster is collapsed and not selected, the nodes are deselected
     * (clusters.md rule 15), with the links that end at them: the cluster stays as it was, and
     * the nodes vanishing into it shows the Add worked.
     */
    public void added(Collection<String> nodes, String cluster) {
        deselectIfJoiningUnselected(model.getClusters(), nodes, cluster);
    }

    /**
     * Deselects {@code nodes}, and the selected links that end at them, if {@code cluster} is
     * collapsed and not wholly selected in {@code before}. Decided from the index before the
     * edit, because the model is reparsed only later, on the event thread.
     */
    private void deselectIfJoiningUnselected(ClusterIndex before, Collection<String> nodes, String cluster) {
        if (!collapse.isCollapsed(cluster) || selection(before.members(cluster)) == Selection.ALL) {
            return;
        }
        model.deselectNodes(nodes);
        for (ModelLink link : model.getAllLinks()) {
            if (model.isLinkSelected(link)
                    && (nodes.contains(link.getUpstreamTerminus())
                        || nodes.contains(link.getDownstreamTerminus()))) {
                model.deselectLink(link);
            }
        }
    }

    private void modelChanged(ModelChangeEvent event) {
        switch (event.getType()) {
            case NODE_SELECTED, NODE_DESELECTED, LINK_SELECTED, MODEL_RELOADED -> {
                if (applySelectionRule()) {
                    changed();
                }
            }
            default -> { }
        }
    }

    /**
     * Holds the selection to clusters.md rule 10: expands every collapsed cluster that is
     * partly selected, then deselects every selected link with a hidden end.
     *
     * @return whether a cluster was expanded
     */
    private boolean applySelectionRule() {
        ClusterIndex index = model.getClusters();
        List<String> partly = new ArrayList<>();
        for (String cluster : index.names()) {
            if (collapse.isCollapsed(cluster) && selection(index.members(cluster)) == Selection.SOME) {
                partly.add(cluster);
            }
        }
        collapse.setCollapsed(partly, false);
        for (ModelLink link : model.getAllLinks()) {
            if (model.isLinkSelected(link)
                    && (collapse.isHidden(link.getUpstreamTerminus(), index)
                        || collapse.isHidden(link.getDownstreamTerminus(), index))) {
                model.deselectLink(link);
            }
        }
        return !partly.isEmpty();
    }

    private enum Selection { NONE, SOME, ALL }

    /** How many of {@code members} are selected; no members counts as none. */
    private Selection selection(Collection<String> members) {
        int selected = 0;
        for (String member : members) {
            if (model.isNodeSelected(member)) {
                selected++;
            }
        }
        if (selected == 0) {
            return Selection.NONE;
        }
        return selected == members.size() ? Selection.ALL : Selection.SOME;
    }

    private void changed() {
        for (Runnable listener : listeners) {
            listener.run();
        }
    }
}
