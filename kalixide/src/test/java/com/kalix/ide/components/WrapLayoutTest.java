package com.kalix.ide.components;

import org.junit.jupiter.api.Test;

import javax.swing.JPanel;
import java.awt.Dimension;
import java.awt.FlowLayout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins WrapLayout's one job: preferred height follows the row count the
 * container's width produces, so toolbar controls flow onto further rows
 * instead of clipping when the plot region is narrow.
 */
class WrapLayoutTest {

    private static JPanel panel(int width, int componentCount) {
        JPanel panel = new JPanel(new WrapLayout(FlowLayout.LEADING, 0, 2));
        for (int i = 0; i < componentCount; i++) {
            JPanel child = new JPanel();
            child.setPreferredSize(new Dimension(100, 25));
            panel.add(child);
        }
        panel.setSize(width, 25);
        return panel;
    }

    @Test
    void wideContainerReportsOneRow() {
        Dimension size = panel(600, 5).getPreferredSize();
        assertTrue(size.height < 2 * 25, "five 100px components fit one row at 600px, got " + size.height);
        assertEquals(500, size.width);
    }

    @Test
    void narrowContainerReportsWrappedHeight() {
        Dimension wide = panel(600, 5).getPreferredSize();
        Dimension narrow = panel(250, 5).getPreferredSize();
        assertTrue(narrow.height >= 3 * 25,
            "five 100px components need three rows at 250px, got " + narrow.height);
        assertTrue(narrow.height > wide.height);
    }

    @Test
    void hiddenComponentsTakeNoSpace() {
        JPanel panel = panel(250, 5);
        for (int i = 1; i < panel.getComponentCount(); i++) {
            panel.getComponent(i).setVisible(false); // the plot-only cluster hides in stats view
        }
        assertTrue(panel.getPreferredSize().height < 2 * 25,
            "one visible component is one row");
    }

    @Test
    void zeroWidthFallsBackToSingleRow() {
        JPanel panel = panel(0, 5);
        panel.setSize(0, 0);
        assertEquals(500, panel.getPreferredSize().width, "no width yet: FlowLayout's single-row answer");
    }
}
