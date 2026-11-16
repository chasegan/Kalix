package com.kalix.ide.rendering;

import com.kalix.ide.model.ClusterCollapse;
import com.kalix.ide.model.ClusterIndex;
import com.kalix.ide.model.ModelLink;
import com.kalix.ide.model.ModelNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Links as the map draws them (clusters.md, Architecture, "Links"): an end on a member of a
 * collapsed cluster goes to its icon, a link within one collapsed cluster isn't drawn, and
 * links with the same drawn ends in the same direction are drawn once.
 *
 * <p>The model: loose L and M; f1, f2 in {@code farm}; d1, d2 in {@code dam}.</p>
 */
class MapLinksTest {

    private static final ClusterIndex INDEX = ClusterIndex.of(List.of(
            node("L", null), node("M", null),
            node("f1", "farm"), node("f2", "farm"),
            node("d1", "dam"), node("d2", "dam")));

    private static ModelNode node(String name, String cluster) {
        return new ModelNode(name, "storage", 0, 0, null, cluster);
    }

    private static ModelLink link(String up, String down) {
        return new ModelLink(up, down, true);
    }

    private static ClusterCollapse collapsed(String... clusters) {
        ClusterCollapse collapse = new ClusterCollapse();
        collapse.setCollapsed(INDEX.names(), false);
        collapse.setCollapsed(List.of(clusters), true);
        return collapse;
    }

    /** The drawn links, each as "up>down", an icon's end written as [cluster]. */
    private static List<String> drawn(ClusterCollapse collapse, ModelLink... links) {
        return MapLinks.of(List.of(links), INDEX, collapse, l -> false).stream()
                .map(d -> show(d.upstream()) + ">" + show(d.downstream()))
                .toList();
    }

    private static String show(MapLinks.End end) {
        return end.isIcon() ? "[" + end.cluster() + "]" : end.node();
    }

    @Test
    void theRedirectionTable() {
        ClusterCollapse farmAndDam = collapsed("farm", "dam");
        // One row per case: link(s) -> what is drawn.
        assertEquals(List.of("L>M"), drawn(farmAndDam, link("L", "M")), "loose to loose");
        assertEquals(List.of("L>[farm]"), drawn(farmAndDam, link("L", "f1")), "loose to a hidden member");
        assertEquals(List.of("[farm]>L"), drawn(farmAndDam, link("f1", "L")), "a hidden member to loose");
        assertEquals(List.of("[farm]>[dam]"), drawn(farmAndDam, link("f1", "d1")), "icon to icon");
        assertEquals(List.of(), drawn(farmAndDam, link("f1", "f2")), "within one collapsed cluster");
        assertEquals(List.of("[farm]>[dam]"), drawn(farmAndDam, link("f1", "d1"), link("f2", "d2")),
                "same drawn ends, drawn once");
        assertEquals(List.of("[dam]>[farm]", "[farm]>[dam]"), drawn(farmAndDam, link("d1", "f1"), link("f2", "d2")),
                "opposite directions, drawn once each");
        assertEquals(List.of("L>[farm]", "M>[farm]"), drawn(farmAndDam, link("L", "f1"), link("M", "f2")),
                "different loose ends stay apart");
    }

    @Test
    void anExpandedClusterLeavesItsLinksAlone() {
        ClusterCollapse farmOnly = collapsed("farm");
        assertEquals(List.of("f1>f2"), drawn(collapsed(), link("f1", "f2")));
        assertEquals(List.of("[farm]>d1", "[farm]>d2"), drawn(farmOnly, link("f1", "d1"), link("f2", "d2")));
    }

    @Test
    void aMergedLinkIsSolidIfAnyIsPrimaryAndSelectedIfAnyIsSelected() {
        ModelLink dashed = new ModelLink("L", "f1", false);
        ModelLink solid = new ModelLink("L", "f2", true);
        Set<ModelLink> selected = Set.of(dashed);
        List<MapLinks.Drawn> drawn = MapLinks.of(List.of(dashed, solid), INDEX, collapsed("farm"), selected::contains);
        assertEquals(1, drawn.size());
        assertEquals(true, drawn.get(0).primary());
        assertEquals(true, drawn.get(0).selected());

        List<MapLinks.Drawn> neither = MapLinks.of(List.of(dashed), INDEX, collapsed("farm"), l -> false);
        assertEquals(false, neither.get(0).primary());
        assertEquals(false, neither.get(0).selected());
    }
}
