package com.kalix.ide.flowviz;

import com.kalix.ide.flowviz.data.DatasetSeries;
import com.kalix.ide.flowviz.data.SeriesRef;
import com.kalix.ide.flowviz.rendering.ViewPort;
import com.kalix.ide.flowviz.rendering.XAxisType;
import com.kalix.ide.flowviz.transform.YAxisScale;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Pins how the Key lists series by axis: while something is on the right, a caption above
 * each group, left-axis series first; otherwise no captions. Ctrl/Cmd+click on any part
 * of an entry's row asks for that series to move to the other axis.
 *
 * <p>The Key loads its shown/collapsed state from the user's preferences and every setter
 * writes them back, so each test skips, saying so, rather than change the preferences when
 * the Key would not be drawn expanded.</p>
 */
class PlotLegendAxisGroupsTest {

    private static final int WIDTH = 800;
    private static final int HEIGHT = 600;
    // Inside every row: the Key sits DEFAULT_OFFSET (20) in from the plot's top-right corner
    private static final int PROBE_X = WIDTH - 30;

    private final SeriesRef a = new DatasetSeries("/tmp/x.csv", "a");
    private final SeriesRef b = new DatasetSeries("/tmp/x.csv", "b");
    private final SeriesRef c = new DatasetSeries("/tmp/x.csv", "c");
    private final Set<SeriesRef> right = new HashSet<>();
    private final List<SeriesRef> toggled = new ArrayList<>();
    private PlotLegendManager legend;

    @BeforeEach
    void setUp() {
        legend = new PlotLegendManager();
        legend.setRightAxisTest(right::contains);
        legend.setOnAxisToggle(toggled::add);
        legend.addSeries(a);
        legend.addSeries(b);
        legend.addSeries(c);
    }

    /** Gated per method: the Key's shown/collapsed state is the user's, not the test's. */
    private void assumeKeyDrawnExpanded() {
        assumeTrue(legend.isEnabled() && !legend.isCollapsed(),
            "skipped: the Key is hidden or collapsed in this user's preferences");
    }

    private void paint() {
        BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        legend.render(g, new ViewPort(0, 1, 0, 1, 0, 0, WIDTH, HEIGHT, YAxisScale.LINEAR, XAxisType.TIME));
        g.dispose();
    }

    /** The series met walking down the Key, with the screen row where each is first met. */
    private List<int[]> rowsTopToBottom(List<SeriesRef> order) {
        List<int[]> tops = new ArrayList<>();
        SeriesRef last = null;
        for (int y = 0; y < HEIGHT; y++) {
            SeriesRef here = legend.entryAt(PROBE_X, y);
            if (here != null && !here.equals(last)) {
                order.add(here);
                tops.add(new int[] {y});
            }
            last = here;
        }
        return tops;
    }

    @Test
    void withNothingOnTheRightTheKeyListsSeriesInOrderWithNoDivider() {
        assumeKeyDrawnExpanded();
        paint();
        List<SeriesRef> order = new ArrayList<>();
        List<int[]> tops = rowsTopToBottom(order);

        assertEquals(List.of(a, b, c), order);
        assertEquals(tops.get(1)[0] - tops.get(0)[0], tops.get(2)[0] - tops.get(1)[0], "evenly spaced");
    }

    @Test
    void rightAxisSeriesAreListedAfterTheDivider() {
        assumeKeyDrawnExpanded();
        paint();
        int unsplitTop = rowsTopToBottom(new ArrayList<>()).get(0)[0];
        right.add(a);
        paint();
        List<SeriesRef> order = new ArrayList<>();
        List<int[]> tops = rowsTopToBottom(order);

        assertEquals(List.of(b, c, a), order);
        assertTrue(tops.get(0)[0] > unsplitTop, "a caption sits above the left group");
        int step = tops.get(1)[0] - tops.get(0)[0];
        assertTrue(tops.get(2)[0] - tops.get(1)[0] > step, "the divider opens a gap before the right group");
    }

    @Test
    void ctrlClickAnywhereOnARowAsksToMoveThatSeries() {
        assumeKeyDrawnExpanded();
        right.add(a);
        paint();
        List<SeriesRef> order = new ArrayList<>();
        int aTop = rowsTopToBottom(order).get(2)[0];

        legend.handleMousePress(PROBE_X, aTop + 2, true);
        legend.handleMouseRelease();

        assertEquals(List.of(a), toggled);
    }

    @Test
    void aPlainClickDoesNotMoveASeries() {
        assumeKeyDrawnExpanded();
        paint();
        List<SeriesRef> order = new ArrayList<>();
        int bTop = rowsTopToBottom(order).get(1)[0];

        legend.handleMousePress(PROBE_X, bTop + 2, false);
        legend.handleMouseRelease();

        assertTrue(toggled.isEmpty());
    }
}
