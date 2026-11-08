package com.kalix.ide.flowviz;

import com.kalix.ide.flowviz.data.DataSet;
import com.kalix.ide.flowviz.data.DatasetSeries;
import com.kalix.ide.flowviz.data.TimeSeriesData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.swing.JCheckBoxMenuItem;
import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.event.PopupMenuEvent;
import javax.swing.event.PopupMenuListener;
import java.awt.Component;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The plot's context menu offers each axis as a submenu of its own commands, the right
 * axis' only while there is one, and the one Y axis is called just that until a right
 * axis makes it the left (issue #505).
 */
class PlotContextMenuTest {

    private static final long DAY = 86_400_000L;
    private final DatasetSeries small = new DatasetSeries("/tmp/x.csv", "small");
    private final DatasetSeries large = new DatasetSeries("/tmp/x.csv", "large");
    private FlowVizPanel panel;

    @BeforeEach
    void setUp() {
        DataSet dataSet = new DataSet();
        long[] times = {0, DAY, 2 * DAY};
        dataSet.addSeries(small, new TimeSeriesData(times, new double[] {0, 5, 10}));
        dataSet.addSeries(large, new TimeSeriesData(times, new double[] {1000, 1500, 2000}));
        panel = new FlowVizPanel();
        panel.setSize(800, 600);
        panel.setDataSet(dataSet);
        panel.model().update(st -> st.withVisibleSeries(List.of(small, large)));
        panel.zoomToFit();
    }

    /** The menu as it is about to show: its listener has synced it with the plot. */
    private JPopupMenu menu() {
        JPopupMenu menu = panel.interactionsForTests().contextMenuForTests();
        for (PopupMenuListener l : menu.getPopupMenuListeners()) {
            l.popupMenuWillBecomeVisible(new PopupMenuEvent(menu));
        }
        return menu;
    }

    private static List<String> visibleLabels(Component[] components) {
        List<String> labels = new ArrayList<>();
        for (Component c : components) {
            if (c instanceof JPopupMenu.Separator) {
                labels.add("---");
            } else if (c instanceof JMenuItem item && item.isVisible()) {
                labels.add(item.getText());
            }
        }
        return labels;
    }

    private static JMenu submenu(JPopupMenu menu, String label) {
        for (Component c : menu.getComponents()) {
            if (c instanceof JMenu m && label.equals(m.getText())) {
                return m;
            }
        }
        throw new AssertionError("no submenu " + label);
    }

    @Test
    void withOneYAxisTheMenuNamesItPlainlyAndHidesTheRightOne() {
        assertEquals(List.of(
            "Zoom to fit", "---",
            "Save data…", "---",
            "X axis", "Y axis", "Set axis ranges…", "Y-axis scale", "Line shape", "Missing data", "---",
            "Reset key"), visibleLabels(menu().getComponents()));
    }

    @Test
    void withARightAxisTheMenuQualifiesTheTwoAsLeftAndRight() {
        panel.model().update(st -> st.withSeriesOnRightAxis(large, true));
        JPopupMenu menu = menu();

        assertTrue(visibleLabels(menu.getComponents()).containsAll(List.of("X axis", "Y axis (left)", "Y axis (right)")));
        assertEquals(List.of("Fit to data", "Copy range", "Paste range"),
            visibleLabels(submenu(menu, "X axis").getMenuComponents()), "the time axis has no range to auto-fit");
        assertEquals(List.of("Fit to data", "Auto-range", "Copy range", "Paste range"),
            visibleLabels(submenu(menu, "Y axis (left)").getMenuComponents()));
        assertEquals(List.of("Fit to data", "Auto-range", "Copy range", "Paste range"),
            visibleLabels(submenu(menu, "Y axis (right)").getMenuComponents()));
    }

    @Test
    void eachAxisAutoRangeItemShowsAndSetsItsOwnFlag() {
        panel.model().update(st -> st.withSeriesOnRightAxis(large, true));
        panel.model().update(st -> st.withAutoRangeAll(true).withAutoRange(Axis.Y, false));
        JPopupMenu menu = menu();
        JCheckBoxMenuItem left = (JCheckBoxMenuItem) submenu(menu, "Y axis (left)").getMenuComponent(1);
        JCheckBoxMenuItem right = (JCheckBoxMenuItem) submenu(menu, "Y axis (right)").getMenuComponent(1);
        assertFalse(left.isSelected());
        assertTrue(right.isSelected());

        left.doClick();

        assertTrue(panel.isAutoRange(Axis.Y), "the left item sets the left axis");
        assertTrue(panel.isAutoRange(Axis.Y2), "and leaves the right one alone");
    }
}
