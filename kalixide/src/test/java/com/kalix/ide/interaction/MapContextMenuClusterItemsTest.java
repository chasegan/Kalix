package com.kalix.ide.interaction;

import com.kalix.ide.model.HydrologicalModel;
import org.junit.jupiter.api.Test;

import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import java.awt.Component;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * The cluster items in the map's real menu, from {@code Insert node} to {@code Delete}:
 * where they sit, how they are labelled, and that only the selected nodes decide them.
 * Which items a selection is offered is {@link ClusterMenuRulesTest}'s job; this checks
 * the menu asks those rules and labels the answer through {@link ClusterLabels}. Every
 * cluster counts as expanded until the map can draw a collapsed one.
 *
 * <p>The model: cluster north (n1, n2), cluster south (s1), and loose nodes x and
 * south, so the cluster south is labelled {@code (cluster)}. x links to n1.
 */
class MapContextMenuClusterItemsTest {

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

            [node.south]
            type = gauge
            loc = 4, 0
            """;

    private static HydrologicalModel model(String text, String... selected) {
        HydrologicalModel model = new HydrologicalModel();
        model.parseFromIniText(text);
        model.selectNodes(List.of(selected));
        return model;
    }

    /** The labels from {@code Insert node} to {@code Delete}, a submenu as {@code title[children]}. */
    private static String block(HydrologicalModel model) {
        JPopupMenu menu = new MapContextMenuManager(null, null, model).buildMenu();
        List<String> labels = new ArrayList<>();
        boolean in = false;
        for (Component component : menu.getComponents()) {
            if (!(component instanceof JMenuItem item)) {
                if (in) labels.add("—");
                continue;
            }
            if (item.getText().equals("Insert node")) in = true;
            if (!in) continue;
            labels.add(item instanceof JMenu sub && !item.getText().equals("Insert node")
                    ? item.getText() + children(sub) : item.getText());
            if (item.getText().equals("Delete")) break;
        }
        return String.join(" | ", labels);
    }

    private static String children(JMenu menu) {
        List<String> names = new ArrayList<>();
        for (Component component : menu.getMenuComponents()) {
            names.add(((JMenuItem) component).getText());
        }
        return names.toString();
    }

    @Test
    void nothingSelectedOffersNoClusterItem() {
        assertEquals("Insert node | — | Rename | Delete", block(model(MODEL)));
    }

    @Test
    void aLooseNodeCanStartAClusterOrJoinOneFromASubmenu() {
        assertEquals("Insert node | Create cluster… | — | Rename \"x\" "
                        + "| Add to cluster[north, south (cluster)] | Delete",
                block(model(MODEL, "x")));
    }

    @Test
    void aSingleTargetIsADirectItem() {
        assertEquals("Insert node | Create cluster… | — | Rename \"n1\" | Add to \"south\" (cluster) "
                        + "| Remove from cluster | Disband cluster | Delete",
                block(model(MODEL, "n1")));
    }

    @Test
    void theWholeOfAClusterCanBeRenamed() {
        assertEquals("Insert node | Create cluster… | — | Rename | Add to \"south\" (cluster) "
                        + "| Remove from cluster | Rename \"north\"… | Disband cluster | Delete",
                block(model(MODEL, "n1", "n2")));
    }

    @Test
    void membersOfTwoClustersDisbandBoth() {
        assertEquals("Insert node | Create cluster… | — | Rename "
                        + "| Add to cluster[north, south (cluster)] | Remove from cluster "
                        + "| Disband clusters | Delete",
                block(model(MODEL, "n1", "s1")));
    }

    @Test
    void aModelWithNoClustersOffersOnlyCreate() {
        String text = "[node.x]\ntype = gauge\nloc = 0, 0\n";
        assertEquals("Insert node | Create cluster… | — | Rename \"x\" | Delete", block(model(text, "x")));
    }

    @Test
    void aSelectedLinkChangesNoClusterItem() {
        HydrologicalModel withLink = model(MODEL, "n1");
        assertFalse(withLink.getAllLinks().isEmpty(), "the model's link x → n1");
        withLink.selectLink(withLink.getAllLinks().get(0), true);
        assertEquals(block(model(MODEL, "n1")), block(withLink));
    }

    @Test
    void aSelectedLinkAloneOffersNoClusterItem() {
        HydrologicalModel linkOnly = model(MODEL);
        linkOnly.selectLink(linkOnly.getAllLinks().get(0), false);
        assertEquals(block(model(MODEL)), block(linkOnly));
    }
}
