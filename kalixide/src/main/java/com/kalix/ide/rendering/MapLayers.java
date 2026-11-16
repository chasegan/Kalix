package com.kalix.ide.rendering;

import com.kalix.ide.model.ClusterIndex;
import com.kalix.ide.model.HydrologicalModel;
import com.kalix.ide.model.ModelNode;

import java.util.ArrayList;
import java.util.List;

/**
 * The one place that decides the order the map draws its nodes in, as layers drawn one
 * after another (clusters.md, Architecture, "Drawing order"): the loose nodes first, then
 * each cluster in order of its first mention in the file, with its members in file order.
 * Loose nodes go first so that one lying inside a box takes the box's tint, and each
 * member goes after its own box so its label stays clear of it.
 *
 * <p>The renderer draws every shape in a layer before any of its labels, so within a
 * layer no shape covers a label (#495). A single labels-last pass over the whole map
 * would put loose labels on top of boxes and lose the tint.</p>
 */
public final class MapLayers {

    /** One layer: a cluster's members, or the loose nodes when {@code cluster} is null. */
    public record Layer(String cluster, List<ModelNode> nodes) {
    }

    private MapLayers() {
    }

    /** The layers of {@code model}, in drawing order; a model with no nodes has none. */
    public static List<Layer> of(HydrologicalModel model) {
        ClusterIndex clusters = model.getClusters();
        List<Layer> layers = new ArrayList<>();

        List<ModelNode> loose = new ArrayList<>();
        for (ModelNode node : model.getAllNodes()) {
            if (clusters.clusterOf(node.getName()) == null) {
                loose.add(node);
            }
        }
        if (!loose.isEmpty()) {
            layers.add(new Layer(null, loose));
        }

        for (String cluster : clusters.names()) {
            List<ModelNode> members = new ArrayList<>();
            for (String name : clusters.members(cluster)) {
                // The index and the nodes come from the same parse; the check only keeps
                // a node the map doesn't hold from reaching the renderer.
                ModelNode node = model.getNode(name);
                if (node != null) {
                    members.add(node);
                }
            }
            if (!members.isEmpty()) {
                layers.add(new Layer(cluster, members));
            }
        }
        return layers;
    }
}
