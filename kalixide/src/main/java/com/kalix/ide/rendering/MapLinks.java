package com.kalix.ide.rendering;

import com.kalix.ide.model.ClusterCollapse;
import com.kalix.ide.model.ClusterIndex;
import com.kalix.ide.model.ModelLink;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * The links as the map draws them (clusters.md, Architecture, "Links"). An end on a member
 * of a collapsed cluster is drawn to that cluster's icon instead; a link within one
 * collapsed cluster isn't drawn; and links with the same drawn ends in the same direction
 * are drawn once, solid if any of them is primary and selected if any of them is.
 */
public final class MapLinks {

    /** One end of a drawn link: a node, or the icon of the collapsed cluster named. */
    public record End(String node, String cluster) {

        static End node(String name) {
            return new End(name, null);
        }

        static End icon(String cluster) {
            return new End(null, cluster);
        }

        public boolean isIcon() {
            return cluster != null;
        }
    }

    /** A link as drawn, from {@code upstream} to {@code downstream}. */
    public record Drawn(End upstream, End downstream, boolean primary, boolean selected) {
    }

    private MapLinks() {
    }

    /**
     * The links to draw, in the order of their first link in {@code links}.
     *
     * @param selected whether a link is selected
     */
    public static List<Drawn> of(Collection<ModelLink> links, ClusterIndex index,
                                 ClusterCollapse collapse, Predicate<ModelLink> selected) {
        Map<List<End>, Drawn> drawn = new LinkedHashMap<>();
        for (ModelLink link : links) {
            End upstream = end(link.getUpstreamTerminus(), index, collapse);
            End downstream = end(link.getDownstreamTerminus(), index, collapse);
            if (upstream.isIcon() && upstream.equals(downstream)) {
                continue;
            }
            boolean isSelected = selected.test(link);
            drawn.merge(List.of(upstream, downstream),
                    new Drawn(upstream, downstream, link.isPrimary(), isSelected),
                    (a, b) -> new Drawn(upstream, downstream,
                            a.primary() || b.primary(), a.selected() || b.selected()));
        }
        return new ArrayList<>(drawn.values());
    }

    private static End end(String node, ClusterIndex index, ClusterCollapse collapse) {
        return collapse.isHidden(node, index) ? End.icon(index.clusterOf(node)) : End.node(node);
    }
}
