package com.kalix.ide.flowviz.style;

import com.kalix.ide.flowviz.data.SeriesRef;

/**
 * {@link SeriesStyleResolver} that draws styles from the globally active
 * {@link PlotPalette}.
 *
 * <p>Resolution is two hops, both read live on every call:</p>
 * <ol>
 *   <li>{@link SeriesSlotManager} maps the ref to a slot index;</li>
 *   <li>the {@link PlotPaletteManager}'s active palette maps that slot to a
 *       {@link LineStyle}.</li>
 * </ol>
 *
 * <p>Because nothing is cached, editing the active palette or switching palettes
 * restyles every plot on its next repaint.</p>
 */
public final class PaletteSeriesStyleResolver implements SeriesStyleResolver {

    private final SeriesSlotManager slotManager;
    private final PlotPaletteManager paletteManager;

    public PaletteSeriesStyleResolver(SeriesSlotManager slotManager,
                                      PlotPaletteManager paletteManager) {
        this.slotManager = slotManager;
        this.paletteManager = paletteManager;
    }

    @Override
    public LineStyle styleFor(SeriesRef ref) {
        return paletteManager.getActivePalette().entryAt(slotManager.assignSlot(ref));
    }
}
