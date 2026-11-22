package com.kalix.ide.rendering;

import com.kalix.ide.model.ClusterCollapse;
import com.kalix.ide.model.HydrologicalModel;
import com.kalix.ide.model.ModelNode;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The map's drawing order: loose nodes first, then each cluster in order of its first
 * mention, its members in file order, every node on the map exactly once.
 */
class MapLayersTest {

    private static String section(String name, String cluster) {
        return (cluster == null ? "" : "#@cluster = " + cluster + "\n")
                + "[node." + name + "]\ntype = storage\nloc = 1, 2\n\n";
    }

    private static List<MapLayers.Layer> layersOf(String... sections) {
        HydrologicalModel model = new HydrologicalModel();
        model.parseFromIniText(String.join("", sections));
        return MapLayers.of(model, AllExpanded.of(model));
    }

    private static List<String> names(MapLayers.Layer layer) {
        return layer.nodes().stream().map(ModelNode::getName).sorted().toList();
    }

    private static List<String> namesInOrder(MapLayers.Layer layer) {
        return layer.nodes().stream().map(ModelNode::getName).toList();
    }

    @Test
    void aModelWithNoNodesHasNoLayers() {
        assertTrue(MapLayers.of(new HydrologicalModel(), new ClusterCollapse()).isEmpty());
    }

    @Test
    void aModelWithNoClustersIsOneLooseLayer() {
        List<MapLayers.Layer> layers = layersOf(section("a", null), section("b", null));
        assertEquals(1, layers.size());
        assertNull(layers.get(0).clusterName());
        assertEquals(List.of("a", "b"), names(layers.get(0)));
    }

    @Test
    void looseNodesComeFirstThenClustersInOrderOfFirstMention() {
        List<MapLayers.Layer> layers = layersOf(
                section("z1", "zebra"),
                section("loose_1", null),
                section("a1", "apple"),
                section("z2", "zebra"),
                section("loose_2", null));
        assertEquals(Arrays.asList(null, "zebra", "apple"),
                layers.stream().map(MapLayers.Layer::clusterName).toList());
        assertEquals(List.of("loose_1", "loose_2"), names(layers.get(0)));
        assertEquals(List.of("z1", "z2"), namesInOrder(layers.get(1)));
        assertEquals(List.of("a1"), namesInOrder(layers.get(2)));
    }

    @Test
    void membersAreInFileOrder() {
        List<MapLayers.Layer> layers = layersOf(
                section("m3", "farm"), section("m1", "farm"), section("m2", "farm"));
        assertEquals(List.of("m3", "m1", "m2"), namesInOrder(layers.get(0)));
    }

    @Test
    void everyNodeOnTheMapIsInExactlyOneLayer() {
        HydrologicalModel model = new HydrologicalModel();
        model.parseFromIniText(String.join("",
                section("twice", "farm"),
                section("loose", null),
                section("upper", "Farm"),
                section("lower", "farm"),
                section("twice", null),
                section("bad_name", "not a name"),
                "[node.no_loc]\ntype = storage\n\n",
                section("other", "dam")));
        List<MapLayers.Layer> layers = MapLayers.of(model, AllExpanded.of(model));

        List<String> drawn = layers.stream().flatMap(layer -> layer.nodes().stream())
                .map(ModelNode::getName).sorted().toList();
        List<String> onTheMap = model.getAllNodes().stream().map(ModelNode::getName).sorted().toList();
        assertEquals(onTheMap, drawn);
        // "Farm" and "farm" are one layer. A name used twice counts at its last section,
        // which is loose; an invalid cluster name leaves its node loose.
        assertEquals(Arrays.asList(null, "Farm", "dam"),
                layers.stream().map(MapLayers.Layer::clusterName).toList());
        assertEquals(List.of("bad_name", "loose", "twice"), names(layers.get(0)));
        assertEquals(List.of("upper", "lower"), namesInOrder(layers.get(1)));
    }

    @Test
    void aModelWithOnlyClustersHasNoLooseLayer() {
        List<MapLayers.Layer> layers = layersOf(section("a", "farm"));
        assertEquals(1, layers.size());
        assertEquals("farm", layers.get(0).clusterName());
    }

    /** A model of loose {@code l} and three clusters, with {@code dam} expanded. */
    private static HydrologicalModel threeClusters() {
        HydrologicalModel model = new HydrologicalModel();
        model.parseFromIniText(
                "[node.l]\ntype = storage\nloc = 0, 0\n\n"
                + "#@cluster = weir\n[node.w]\ntype = storage\nloc = 5, 5\n\n"
                + "#@cluster = farm\n[node.f1]\ntype = storage\nloc = 10, 20\n\n"
                + "#@cluster = dam\n[node.d]\ntype = storage\nloc = 50, 50\n\n"
                + "#@cluster = Farm\n[node.f2]\ntype = storage\nloc = 30, 20\n\n"
                + "#@cluster = farm\n[node.f3]\ntype = storage\nloc = 20, 50\n\n");
        return model;
    }

    private static ClusterCollapse damExpanded() {
        ClusterCollapse collapse = new ClusterCollapse();
        collapse.setCollapsed(List.of("dam"), false);
        return collapse;
    }

    @Test
    void aCollapsedClusterHasNoLayerAndItsMembersAreNotDrawn() {
        List<MapLayers.Layer> layers = MapLayers.of(threeClusters(), damExpanded());
        assertEquals(Arrays.asList(null, "dam"), layers.stream().map(MapLayers.Layer::clusterName).toList());
        assertEquals(List.of("l"), names(layers.get(0)));
    }

    @Test
    void aCollapsedClusterIsAnIconAtItsMembersCentroidWithTheirCount() {
        List<MapLayers.Icon> icons = MapLayers.icons(threeClusters(), damExpanded());
        // In order of first mention; the centroid of (10, 20), (30, 20), (20, 50) is
        // (20, 30), where the span's centre would be (20, 35).
        assertEquals(List.of(new MapLayers.Icon("weir", 5, 5, 1), new MapLayers.Icon("farm", 20, 30, 3)), icons);
    }

    @Test
    void anExpandedClusterHasNoIcon() {
        HydrologicalModel model = threeClusters();
        assertTrue(MapLayers.icons(model, AllExpanded.of(model)).isEmpty());
    }
}
