package com.kalix.ide.dataview;

import javax.swing.JComponent;
import javax.swing.UIManager;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Rectangle;

/**
 * Line numbers for the virtual text view — the editor-parity gutter the
 * above-gate view lacked. Virtual like its text area: only visible numbers
 * are painted, and the preferred width follows the digit count as indexing
 * grows the file. Numbers are whole-file physical lines (extended header
 * included), 1-based — what the below-gate editor's gutter would show.
 */
public final class VirtualLineNumberGutter extends JComponent {

    private static final int H_PAD = 6;

    private final VirtualTextArea area;
    private Color background;
    private Color numberColor;
    private Color edgeColor;

    public VirtualLineNumberGutter(VirtualTextArea area) {
        this.area = area;
        setOpaque(true);
        resolveColors();
    }

    /** Re-resolves theme colours on every LaF switch via updateComponentTreeUI. */
    @Override
    public void updateUI() {
        super.updateUI();
        resolveColors();
        repaint();
    }

    private void resolveColors() {
        background = orElse(UIManager.getColor("TextArea.background"), Color.WHITE);
        numberColor = orElse(UIManager.getColor("Label.disabledForeground"), Color.GRAY);
        edgeColor = orElse(UIManager.getColor("Table.gridColor"), Color.LIGHT_GRAY);
    }

    private static Color orElse(Color color, Color fallback) {
        return color != null ? color : fallback;
    }

    @Override
    public Dimension getPreferredSize() {
        FontMetrics fm = getFontMetrics(area.getFont());
        int digits = Math.max(2, String.valueOf(Math.max(1, area.totalLines())).length());
        return new Dimension(2 * H_PAD + fm.charWidth('0') * digits, area.getPreferredSize().height);
    }

    @Override
    protected void paintComponent(Graphics g) {
        Rectangle clip = g.getClipBounds();
        g.setColor(background);
        g.fillRect(clip.x, clip.y, clip.width, clip.height);
        g.setColor(edgeColor);
        g.drawLine(getWidth() - 1, clip.y, getWidth() - 1, clip.y + clip.height);

        long total = area.totalLines();
        if (total == 0) {
            return;
        }
        g.setFont(area.getFont());
        FontMetrics fm = g.getFontMetrics();
        int lineHeight = area.lineHeight();
        long first = Math.max(0, clip.y / lineHeight);
        long last = Math.min(total - 1, (long) (clip.y + clip.height) / lineHeight + 1);
        g.setColor(numberColor);
        for (long line = first; line <= last; line++) {
            String number = String.valueOf(line + 1);
            g.drawString(number, getWidth() - H_PAD - fm.stringWidth(number),
                (int) (line * lineHeight) + fm.getAscent());
        }
    }
}
