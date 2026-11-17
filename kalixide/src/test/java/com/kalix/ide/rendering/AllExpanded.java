package com.kalix.ide.rendering;

import com.kalix.ide.model.ClusterCollapse;
import com.kalix.ide.model.HydrologicalModel;

/** A collapse state with every cluster of a model expanded, for tests of expanded drawing. */
final class AllExpanded {

    private AllExpanded() {
    }

    static ClusterCollapse of(HydrologicalModel model) {
        ClusterCollapse collapse = new ClusterCollapse();
        collapse.setCollapsed(model.getClusters().names(), false);
        return collapse;
    }
}
