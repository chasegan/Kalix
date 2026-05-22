package com.kalix.ide.flowviz;

import com.kalix.ide.flowviz.data.SeriesRef;
import com.kalix.ide.flowviz.style.LineStyle;
import com.kalix.ide.flowviz.style.PlotPalette;
import com.kalix.ide.flowviz.style.PlotPaletteManager;
import com.kalix.ide.flowviz.style.SeriesSlotManager;

import javax.swing.Icon;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;

/**
 * A lightweight popup for re-assigning a series to a different palette slot.
 *
 * <p>Shown when a legend ("Key") line sample is clicked. Each item previews one
 * slot of the active palette as a line sample — the same colour-and-stroke
 * rendering used in the Key — and the series' current slot is ticked. Choosing
 * an item points the series' {@link SeriesSlotManager} entry at that slot, which
 * restyles the series consistently across every plot.</p>
 *
 * <p>This addresses palette-slot collisions: with more than ten series the slot
 * wrap-around can give two series the same style, and the picker lets the user
 * nudge one onto a free slot.</p>
 */
public final class LineStylePicker {

    private LineStylePicker() {
    }

    /**
     * Builds and shows the slot picker for {@code ref}, dropping down from
     * {@code (x, y)} within {@code invoker}.
     */
    public static void show(Component invoker, int x, int y, SeriesRef ref,
                            SeriesSlotManager slotManager, PlotPaletteManager paletteManager) {
        PlotPalette palette = paletteManager.getActivePalette();
        int currentSlot = slotManager.assignSlot(ref);

        JPopupMenu menu = new JPopupMenu();
        for (int slot = 0; slot < PlotPalette.SLOT_COUNT; slot++) {
            // Plain menu items (not JCheckBoxMenuItem) so the menu reserves no
            // check column — the current-slot tick is drawn into the icon itself,
            // keeping the popup tight around the line samples.
            JMenuItem item = new JMenuItem(new SlotIcon(palette.entryAt(slot), slot == currentSlot));
            final int targetSlot = slot;
            item.addActionListener(e -> slotManager.setSlot(ref, targetSlot));
            menu.add(item);
        }
        menu.show(invoker, x, y);
    }

    /**
     * One picker row: an optional tick (when this is the series' current slot)
     * followed by a line sample rendered exactly as in the legend Key.
     */
    private static final class SlotIcon implements Icon {
        private static final int CHECK_WIDTH = 16;
        private static final int LEAD = 3;
        private static final int HEIGHT = 16;

        private final LineStyle style;
        private final boolean current;

        SlotIcon(LineStyle style, boolean current) {
            this.style = style;
            this.current = current;
        }

        @Override
        public int getIconWidth() {
            return CHECK_WIDTH + LEAD + PlotLegendManager.SAMPLE_WIDTH + LEAD;
        }

        @Override
        public int getIconHeight() {
            return HEIGHT;
        }

        @Override
        public void paintIcon(Component c, Graphics g, int x, int y) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            if (current) {
                paintTick(g2, c.getForeground(), x, y);
            }
            PlotLegendManager.paintLineSample(g2, style, x + CHECK_WIDTH + LEAD, y + HEIGHT / 2);
            g2.dispose();
        }

        /** Draws a small check mark in the left {@link #CHECK_WIDTH} pixels. */
        private static void paintTick(Graphics2D g, Color color, int x, int y) {
            g.setColor(color);
            g.setStroke(new BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            int cy = y + HEIGHT / 2;
            g.drawLine(x + 3, cy, x + 6, cy + 3);
            g.drawLine(x + 6, cy + 3, x + 12, cy - 4);
        }
    }
}
