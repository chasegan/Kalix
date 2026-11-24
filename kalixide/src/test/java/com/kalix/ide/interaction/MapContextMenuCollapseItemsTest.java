package com.kalix.ide.interaction;

import com.kalix.ide.model.ClusterCollapse;
import com.kalix.ide.model.HydrologicalModel;
import org.junit.jupiter.api.Test;

import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import java.awt.Component;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The collapse items at the foot of the map's real menu, after {@code Zoom to fit}: which
 * show for the collapse state and the selection, and what they do. Collapsing keeps a
 * wholly selected cluster selected, as a selected icon, and deselects the links it hides
 * (clusters.md rule 10); Create, Add and a merging Rename that put nodes into a collapsed
 * cluster that isn't selected deselect them (rule 15).
 *
 * <p>The model: cluster north (n1, n2), cluster south (s1), and loose x, linked to n1.
 * Not covered: that the menu's Create, Add and Rename items call the owner after their
 * text edit, since those items need a text editor to run; {@link #afterEdit} calls the
 * owner directly.</p>
 */
class MapContextMenuCollapseItemsTest {

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

    /** The items after {@code Zoom to fit}. */
    private static List<JMenuItem> viewItems(HydrologicalModel model, ClusterCollapse collapse) {
        JPopupMenu menu = new MapContextMenuManager(null, null, model,
                new ClusterCollapseManager(model, collapse)).buildMenu();
        List<JMenuItem> items = new ArrayList<>();
        boolean after = false;
        for (Component component : menu.getComponents()) {
            if (component instanceof JMenuItem item) {
                if (after) items.add(item);
                if (item.getText().equals("Zoom to fit")) after = true;
            }
        }
        return items;
    }

    private static String labels(HydrologicalModel model, ClusterCollapse collapse) {
        return String.join(" | ", viewItems(model, collapse).stream().map(JMenuItem::getText).toList());
    }

    private static void click(HydrologicalModel model, ClusterCollapse collapse, String label) {
        for (JMenuItem item : viewItems(model, collapse)) {
            if (item.getText().equals(label)) {
                for (var listener : item.getActionListeners()) {
                    listener.actionPerformed(null);
                }
                return;
            }
        }
        throw new AssertionError("no item " + label + " in " + labels(model, collapse));
    }

    @Test
    void theAllItemsFollowTheState() {
        assertEquals("Collapse all clusters", labels(model(), collapsed()));
        assertEquals("Expand all clusters", labels(model(), collapsed("north", "south")));
        assertEquals("Collapse all clusters | Expand all clusters", labels(model(), collapsed("south")));
    }

    @Test
    void aSelectedMemberOfAnExpandedClusterOffersCollapse() {
        assertEquals("Collapse cluster | Collapse all clusters", labels(model("n1"), collapsed()));
        assertEquals("Collapse clusters | Collapse all clusters", labels(model("n1", "s1"), collapsed()));
        assertEquals("Collapse all clusters", labels(model("x"), collapsed()), "a loose node");
    }

    @Test
    void aModelWithNoClusterOffersNone() {
        HydrologicalModel loose = new HydrologicalModel();
        loose.parseFromIniText("[node.x]\ntype = gauge\nloc = 0, 0\n");
        assertEquals("", labels(loose, new ClusterCollapse()));
    }

    @Test
    void collapseHidesTheClusterKeepingItSelectedAndDeselectsItsLinks() {
        HydrologicalModel model = model("x", "n1", "n2");
        model.selectLink(model.getAllLinks().get(0), true);
        ClusterCollapse collapse = collapsed();

        click(model, collapse, "Collapse cluster");

        assertTrue(collapse.isCollapsed("north"));
        assertFalse(collapse.isCollapsed("south"));
        assertEquals(Set.of("x", "n1", "n2"), model.getSelectedNodes(), "the icon is selected");
        assertEquals(0, model.getSelectedLinkCount(), "x → n1 now runs to the icon");
    }

    /** The owner's step after an Add, or a Create that joined, which the text edit can't test. */
    private static void afterEdit(HydrologicalModel model, ClusterCollapse collapse,
                                  List<String> nodes, String cluster) {
        new ClusterCollapseManager(model, collapse).added(nodes, cluster);
    }

    @Test
    void nodesPutIntoACollapsedClusterAreDeselectedWithTheirLinks() {
        HydrologicalModel model = model("x", "s1");
        model.selectLink(model.getAllLinks().get(0), true);

        afterEdit(model, collapsed("north"), List.of("x"), "north");

        assertEquals(Set.of("s1"), model.getSelectedNodes(), "only the nodes put in");
        assertEquals(0, model.getSelectedLinkCount(), "x → n1 now runs inside the icon");
    }

    @Test
    void nodesPutIntoAnExpandedClusterStaySelected() {
        HydrologicalModel model = model("x", "s1");
        model.selectLink(model.getAllLinks().get(0), true);

        afterEdit(model, collapsed(), List.of("x"), "north");

        assertEquals(Set.of("x", "s1"), model.getSelectedNodes());
        assertEquals(1, model.getSelectedLinkCount());
    }

    @Test
    void aNewClusterStartsCollapsedAndCreateKeepsItsNodesSelected() {
        HydrologicalModel model = model("x", "s1");
        ClusterCollapse collapse = collapsed();

        new ClusterCollapseManager(model, collapse).created(model.getClusters(), "east", List.of("x"));

        assertTrue(collapse.isCollapsed("east"));
        assertEquals(Set.of("x", "s1"), model.getSelectedNodes(), "a selected icon, once reparsed");
    }

    @Test
    void aLinkIsDeselectedByEitherEndAndKeptByNeither() {
        HydrologicalModel model = model();
        model.selectLink(model.getAllLinks().get(0), true);
        ClusterCollapse collapse = collapsed();
        collapse.created(model.getClusters(), "east");

        afterEdit(model, collapse, List.of("s1"), "east");
        assertEquals(1, model.getSelectedLinkCount(), "x → n1 doesn't end at s1");

        afterEdit(model, collapse, List.of("n1"), "east");
        assertEquals(0, model.getSelectedLinkCount(), "x → n1 ends at n1, downstream");
    }

    @Test
    void theClusterIsFoundByAnyCapitalisation() {
        HydrologicalModel model = model("x");

        afterEdit(model, collapsed("north"), List.of("x"), "NORTH");

        assertTrue(model.getSelectedNodes().isEmpty());
    }

    @Test
    void aSelectedIconOffersExpand() {
        assertEquals("Expand cluster | Collapse all clusters | Expand all clusters",
                labels(model("s1"), collapsed("south")));
        assertEquals("Expand clusters | Expand all clusters",
                labels(model("n1", "n2", "s1"), collapsed("north", "south")));
    }

    @Test
    void anExpandedAndACollapsedClusterOfferBoth() {
        assertEquals("Collapse cluster | Expand cluster | Collapse all clusters | Expand all clusters",
                labels(model("n1", "s1"), collapsed("south")));
    }

    @Test
    void expandOpensOnlyTheSelectedIconsKeepingTheirMembersSelected() {
        HydrologicalModel model = model("s1");
        ClusterCollapse collapse = collapsed("north", "south");

        click(model, collapse, "Expand cluster");

        assertFalse(collapse.isCollapsed("south"));
        assertTrue(collapse.isCollapsed("north"), "north wasn't selected");
        assertEquals(Set.of("s1"), model.getSelectedNodes());
    }

    /** The node {@code Rename} item's label, which names the node only when it offers to rename it. */
    private static String nodeRenameLabel(HydrologicalModel model, ClusterCollapse collapse) {
        JPopupMenu menu = new MapContextMenuManager(null, null, model,
                new ClusterCollapseManager(model, collapse)).buildMenu();
        for (Component component : menu.getComponents()) {
            if (component instanceof JMenuItem item && item.getText().startsWith("Rename")
                    && !item.getText().endsWith("…")) {
                return item.getText();
            }
        }
        throw new AssertionError("no node Rename item");
    }

    @Test
    void aSelectedIconIsNotANodeToRename() {
        assertEquals("Rename \"s1\"", nodeRenameLabel(model("s1"), collapsed()), "an expanded member");
        assertEquals("Rename", nodeRenameLabel(model("s1"), collapsed("south")), "south's icon");
    }

    @Test
    void collapseAllAndExpandAllActOnEveryCluster() {
        HydrologicalModel model = model("s1");
        ClusterCollapse collapse = collapsed();
        click(model, collapse, "Collapse all clusters");
        assertTrue(collapse.isCollapsed("north") && collapse.isCollapsed("south"));
        assertEquals(Set.of("s1"), model.getSelectedNodes(), "south is wholly selected");

        click(model, collapse, "Expand all clusters");
        assertFalse(collapse.isCollapsed("north") || collapse.isCollapsed("south"));
    }
}
