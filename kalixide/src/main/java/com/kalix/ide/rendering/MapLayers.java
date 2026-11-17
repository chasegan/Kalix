package com.kalix.ide.rendering;

import com.kalix.ide.model.ClusterCollapse;
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
 *
 * <p>A collapsed cluster has no layer: its members aren't drawn. It is drawn as an
 * {@link Icon} instead, after every layer.</p>
 */
public final class MapLayers {

    /** One layer: a cluster's members, or the loose nodes when {@code cluster} is null. */
    public record Layer(String cluster, List<ModelNode> nodes) {
    }

    /**
     * A collapsed cluster as drawn: at its members' centroid, in map coordinates, with the
     * number of its members on the map.
     */
    public record Icon(String cluster, double x, double y, int count) {
    }

    private MapLayers() {
    }

    /**
     * The layers of {@code model}, in drawing order, leaving out the collapsed clusters; a
     * model with no nodes has none.
     */
    public static List<Layer> of(HydrologicalModel model, ClusterCollapse collapse) {
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
            List<ModelNode> members = membersOnTheMap(model, cluster);
            if (!members.isEmpty() && !collapse.isCollapsed(cluster)) {
                layers.add(new Layer(cluster, members));
            }
        }
        return layers;
    }

    /** The icons of {@code model}'s collapsed clusters, in order of first mention. */
    public static List<Icon> icons(HydrologicalModel model, ClusterCollapse collapse) {
        List<Icon> icons = new ArrayList<>();
        for (String cluster : model.getClusters().names()) {
            List<ModelNode> members = membersOnTheMap(model, cluster);
            if (!members.isEmpty() && collapse.isCollapsed(cluster)) {
                double x = 0;
                double y = 0;
                for (ModelNode member : members) {
                    x += member.getX();
                    y += member.getY();
                }
                icons.add(new Icon(cluster, x / members.size(), y / members.size(), members.size()));
            }
        }
        return icons;
    }

    private static List<ModelNode> membersOnTheMap(HydrologicalModel model, String cluster) {
        List<ModelNode> members = new ArrayList<>();
        for (String name : model.getClusters().members(cluster)) {
            // The index and the nodes come from the same parse; the check only keeps a
            // node the map doesn't hold from reaching the renderer.
            ModelNode node = model.getNode(name);
            if (node != null) {
                members.add(node);
            }
        }
        return members;
    }
}
