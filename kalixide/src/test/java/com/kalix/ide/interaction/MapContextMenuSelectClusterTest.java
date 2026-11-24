package com.kalix.ide.interaction;

import com.kalix.ide.MapPanel;
import com.kalix.ide.editor.EnhancedTextEditor;
import com.kalix.ide.model.ClusterCollapse;
import com.kalix.ide.model.HydrologicalModel;
import org.junit.jupiter.api.Test;

import javax.swing.JMenu;
import javax.swing.JMenuItem;
import java.awt.Component;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@code Select nodes ▸ Cluster} in the map's real menu: shown when a member of an
 * expanded cluster is selected, and adding every member of those clusters to the
 * selection. A selected collapsed cluster already has all its members selected, so it
 * isn't offered for one.
 *
 * <p>The model: cluster north (n1, n2), cluster south (s1, s2), and loose x, linked to n1.
 */
class MapContextMenuSelectClusterTest {

    private static final String MODEL = """
            [node.x]
            type = gauge
            loc = 0, 0
            ds_1 = n1

            #@cluster = north
            [node.n1]
            type = gauge
            loc = 1, 0

            #@cluster = north
            [node.n2]
            type = gauge
            loc = 2, 0

            #@cluster = south
            [node.s1]
            type = gauge
            loc = 3, 0

            #@cluster = south
            [node.s2]
            type = gauge
            loc = 4, 0
            """;

    private static HydrologicalModel model(String... selected) {
        HydrologicalModel model = new HydrologicalModel();
        model.parseFromIniText(MODEL);
        model.selectNodes(List.of(selected));
        return model;
    }

    private static ClusterCollapse collapsed(String... clusters) {
        ClusterCollapse collapse = new ClusterCollapse();
        collapse.setCollapsed(List.of("north", "south"), false);
        collapse.setCollapsed(List.of(clusters), true);
        return collapse;
    }

    /** The {@code Select nodes} submenu; a real panel, since its items repaint it. */
    private static JMenu selectMenu(HydrologicalModel model, ClusterCollapse collapse) {
        MapPanel panel = new MapPanel(model, new EnhancedTextEditor());
        for (Component component : new MapContextMenuManager(panel, null, model,
                new ClusterCollapseManager(model, collapse)).buildMenu().getComponents()) {
            if (component instanceof JMenu menu && menu.getText().equals("Select nodes")) {
                return menu;
            }
        }
        throw new AssertionError("no Select nodes submenu");
    }

    private static List<String> children(HydrologicalModel model, ClusterCollapse collapse) {
        List<String> labels = new ArrayList<>();
        for (Component component : selectMenu(model, collapse).getMenuComponents()) {
            labels.add(component instanceof JMenuItem item ? item.getText() : "—");
        }
        return labels;
    }

    @Test
    void aSelectedMemberOffersItsClusterLast() {
        assertEquals(List.of("All", "Upstream", "Downstream", "—", "Connected", "Connected downstream",
                "Connected upstream", "—", "Cluster"), children(model("n1"), collapsed()));
    }

    @Test
    void aLooseNodeOrAnIconOffersNoCluster() {
        assertEquals(List.of("All", "Upstream", "Downstream", "—", "Connected", "Connected downstream",
                "Connected upstream"), children(model("x"), collapsed()));
        assertEquals(List.of("All", "Upstream", "Downstream", "—", "Connected", "Connected downstream",
                "Connected upstream"), children(model("s1", "s2"), collapsed("south")));
    }

    @Test
    void clusterAddsEveryMemberOfEachSelectedMembersCluster() {
        HydrologicalModel model = model("x", "n1", "s2");
        JMenu menu = selectMenu(model, collapsed());
        JMenuItem cluster = (JMenuItem) menu.getMenuComponent(menu.getMenuComponentCount() - 1);

        for (var listener : cluster.getActionListeners()) {
            listener.actionPerformed(null);
        }

        assertEquals(Set.of("x", "n1", "n2", "s1", "s2"), model.getSelectedNodes());
    }
}
