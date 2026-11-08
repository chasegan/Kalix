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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Pins how the Key handles a name it cannot fit (#508): it grows to the whole plot area
 * before cutting a label short, and a row it did cut short offers the whole label as a
 * tooltip, while a row that fits offers none.
 *
 * <p>The Key loads its shown/collapsed state and its label display mode from the user's
 * preferences. Each test skips, saying so, rather than change them when the Key would not
 * be drawn expanded; and the long name has no dots and the resolver no source, so every
 * display mode shows the same label.</p>
 */
class PlotLegendLongNamesTest {

    private static final int HEIGHT = 600;
    private static final int WIDE_PLOT = 1600;
    private static final int NARROW_PLOT = 400;
    private static final String LONG_NAME =
        "a_catchment_with_a_very_long_name_and_a_sub_area_with_an_even_longer_one"
            + "_and_the_variable_of_interest_at_the_very_end";

    private final SeriesRef longOne = new DatasetSeries("/tmp/x.csv", LONG_NAME);
    private final SeriesRef shortOne = new DatasetSeries("/tmp/x.csv", "short");
    private PlotLegendManager legend;

    @BeforeEach
    void setUp() {
        legend = new PlotLegendManager();
        legend.setLabelResolver(SeriesRef::baseName);
        legend.setSeries(List.of(longOne, shortOne));
    }

    /** Gated per method: the Key's shown/collapsed state is the user's, not the test's. */
    private void assumeKeyDrawnExpanded() {
        assumeTrue(legend.isEnabled() && !legend.isCollapsed(),
            "skipped: the Key is hidden or collapsed in this user's preferences");
    }

    private void paint(int plotWidth) {
        BufferedImage image = new BufferedImage(plotWidth, HEIGHT, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        legend.render(g, new ViewPort(0, 1, 0, 1, 0, 0, plotWidth, HEIGHT, YAxisScale.LINEAR, XAxisType.TIME));
        g.dispose();
    }

    /** The Key's left edge, right edge (exclusive) and a y inside it, as last painted. */
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

    /** A point inside the row of {@code series}, found from the Key itself. */
    private int[] rowPoint(int[] extent, SeriesRef series) {
        int probeX = extent[0] + 10;
        for (int y = extent[2]; y < HEIGHT; y++) {
            if (series.equals(legend.entryAt(probeX, y))) return new int[] {probeX, y};
        }
        throw new AssertionError("the row for " + series + " was not found");
    }

    @Test
    void theKeyGrowsPastItsOldCapToFitALongName() {
        assumeKeyDrawnExpanded();
        paint(WIDE_PLOT);
        int[] extent = keyExtent(WIDE_PLOT);
        int width = extent[1] - extent[0];

        assertTrue(width > 400, "wider than the old 400px cap: " + width);
        assertTrue(width < WIDE_PLOT, "but no wider than the name needs: " + width);
        int[] row = rowPoint(extent, longOne);
        assertTrue(legend.tooltipAt(row[0], row[1]).endsWith("+click moves it to the right Y axis"),
            "a label that fits is not restated; the tooltip teaches the modifier-click");
    }

    @Test
    void inANarrowPlotTheKeyFillsItAndOffersTheCutLabelAsATooltip() {
        assumeKeyDrawnExpanded();
        paint(NARROW_PLOT);
        int[] extent = keyExtent(NARROW_PLOT);

        assertEquals(0, extent[0], "pinned to the plot's left edge");
        assertEquals(NARROW_PLOT, extent[1], "as wide as the plot");
        int[] row = rowPoint(extent, longOne);
        assertTrue(legend.tooltipAt(row[0], row[1]).startsWith(LONG_NAME + ". "), "the cut label is offered whole, then the hint");
    }

    @Test
    void aRowThatFitsHasNoTooltipEvenWhenAnotherIsCut() {
        assumeKeyDrawnExpanded();
        paint(NARROW_PLOT);
        int[] row = rowPoint(keyExtent(NARROW_PLOT), shortOne);

        assertTrue(legend.tooltipAt(row[0], row[1]).endsWith("+click moves it to the right Y axis"), "only the hint, not the label");
    }

    @Test
    void theTooltipGoesWhenAWiderPlotLetsTheLabelFit() {
        assumeKeyDrawnExpanded();
        paint(NARROW_PLOT);
        int[] narrowRow = rowPoint(keyExtent(NARROW_PLOT), longOne);
        assertTrue(legend.tooltipAt(narrowRow[0], narrowRow[1]).startsWith(LONG_NAME + ". "));

        paint(WIDE_PLOT);
        int[] wideRow = rowPoint(keyExtent(WIDE_PLOT), longOne);
        assertFalse(legend.tooltipAt(wideRow[0], wideRow[1]).contains(LONG_NAME), "clipping is decided afresh on every paint");
    }
}
