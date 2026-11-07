package com.kalix.ide.flowviz;

import com.kalix.ide.flowviz.data.DatasetSeries;
import com.kalix.ide.flowviz.data.SeriesRef;
import com.kalix.ide.flowviz.rendering.ViewPort;
import com.kalix.ide.flowviz.transform.XAxisType;
import com.kalix.ide.flowviz.transform.YAxisScale;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Pins how the Key handles a name it cannot fit (#508): it grows to the whole plot area
 * before cutting a label short, and a row it did cut short offers the whole label as a
 * tooltip, while a row that fits offers none.
 *
 * <p>The Key loads its shown/collapsed state from the user's preferences, so each test
 * skips, saying so, rather than change them when the Key would not be drawn expanded.</p>
 */
class PlotLegendLongNamesTest {

    private static final int HEIGHT = 600;
    private static final String LONG_NAME =
        "a_catchment_with_a_very_long_name.and_a_sub_area_with_a_longer_one.and_the_variable_at_the_end";

    private final SeriesRef longOne = new DatasetSeries("/tmp/x.csv", LONG_NAME);
    private final SeriesRef shortOne = new DatasetSeries("/tmp/x.csv", "short");
    private PlotLegendManager legend;

    @BeforeEach
    void setUp() {
        legend = new PlotLegendManager();
        legend.setSeries(List.of(longOne, shortOne));
        assumeTrue(legend.isEnabled() && !legend.isCollapsed(),
            "skipped: the Key is hidden or collapsed in this user's preferences");
    }

    private void paint(int plotWidth) {
        BufferedImage image = new BufferedImage(plotWidth, HEIGHT, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        legend.render(g, new ViewPort(0, 1, 0, 1, 0, 0, plotWidth, HEIGHT, YAxisScale.LINEAR, XAxisType.TIME));
        g.dispose();
    }

    /** The Key's left edge, right edge (exclusive) and a y inside its first row, as last painted. */
    private int[] keyExtent(int plotWidth) {
        for (int y = 0; y < HEIGHT; y++) {
            for (int x = 0; x < plotWidth; x++) {
                if (legend.contains(x, y)) {
                    int right = x;
                    while (right < plotWidth && legend.contains(right, y)) right++;
                    return new int[] {x, right, y};
                }
            }
        }
        throw new AssertionError("the Key was not painted");
    }

    /** A y inside the first entry's row, found from the Key itself. */
    private int firstRowY(int[] extent) {
        int probeX = extent[0] + 10;
        for (int y = extent[2]; y < HEIGHT; y++) {
            if (longOne.equals(legend.entryAt(probeX, y))) return y;
        }
        throw new AssertionError("the long name's row was not found");
    }

    @Test
    void theKeyGrowsPastItsOldCapToFitALongName() {
        int plotWidth = 1600;
        paint(plotWidth);
        int[] extent = keyExtent(plotWidth);

        assertTrue(extent[1] - extent[0] > 400, "wider than the old 400px cap: " + (extent[1] - extent[0]));
        assertTrue(extent[1] - extent[0] < plotWidth, "but no wider than the name needs");
        assertNull(legend.tooltipAt(extent[0] + 10, firstRowY(extent)), "a label that fits has no tooltip");
    }

    @Test
    void inANarrowPlotTheKeyFillsItAndOffersTheCutLabelAsATooltip() {
        int plotWidth = 500;
        paint(plotWidth);
        int[] extent = keyExtent(plotWidth);
        int rowY = firstRowY(extent);

        assertEquals(0, extent[0], "pinned to the plot's left edge");
        assertEquals(plotWidth, extent[1], "as wide as the plot");
        String tip = legend.tooltipAt(extent[0] + 10, rowY);
        assertNotNull(tip, "the cut label is offered whole");
        // Whatever the user's display mode drops from the front, the variable at the end stays.
        assertTrue(tip.contains("and_the_variable_at_the_end"), tip);
    }

    @Test
    void aRowThatFitsHasNoTooltipEvenWhenAnotherIsCut() {
        int plotWidth = 500;
        paint(plotWidth);
        int[] extent = keyExtent(plotWidth);
        int probeX = extent[0] + 10;
        int shortRowY = -1;
        for (int y = extent[2]; y < HEIGHT; y++) {
            if (shortOne.equals(legend.entryAt(probeX, y))) { shortRowY = y; break; }
        }
        assertTrue(shortRowY >= 0, "the short name's row was not found");

        assertNull(legend.tooltipAt(probeX, shortRowY));
    }
}
