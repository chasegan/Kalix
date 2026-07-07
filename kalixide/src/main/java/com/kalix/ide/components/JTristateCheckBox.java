package com.kalix.ide.components;

import javax.swing.Icon;
import javax.swing.JCheckBox;
import javax.swing.UIManager;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;

/// A {@link JCheckBox} that can additionally render a third, PARTIAL state - some but not
/// all of a group's members are checked - as a small filled dot over the centre of the box.
///
/// This deliberately does not use the classic "pressed+armed" {@code ButtonModel} trick for
/// faking an indeterminate checkbox: that trick is Look-and-Feel-dependent (some L&Fs render
/// pressed-but-unselected in a way that's indistinguishable from checked or unchecked), and
/// is fragile to get right on a checkbox instance that's reused across many renders (see
/// {@code CheckboxTreeCellRenderer} history - resetting {@code pressed} before {@code armed}
/// silently toggled {@code selected} on the *next* rendered row via
/// {@code DefaultButtonModel}'s click-simulation side effect). Painting our own indicator
/// avoids both problems: it looks the same everywhere and never touches button-model state.
public class JTristateCheckBox extends JCheckBox {

    private boolean partial = false;

    /// Sets whether this checkbox should render its PARTIAL (indeterminate) indicator.
    /// PARTIAL and CHECKED are mutually exclusive display states - callers should pair this
    /// with {@code setSelected(false)} when setting partial to {@code true}.
    public void setPartial(boolean partial) {
        if (this.partial != partial) {
            this.partial = partial;
            repaint();
        }
    }

    public boolean isPartial() {
        return partial;
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        if (!partial) {
            return;
        }

        int boxSize = boxSize();
        int dotSize = Math.max(2, boxSize / 3);
        int x = (boxSize - dotSize) / 2 + getInsets().left;
        int y = (getHeight() - dotSize) / 2;

        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(isEnabled() ? getForeground() : UIManager.getColor("CheckBox.disabledText"));
            g2.fillRoundRect(x, y, dotSize, dotSize, dotSize, dotSize);
        } finally {
            g2.dispose();
        }
    }

    /// Approximates the width of the checkbox glyph itself (as opposed to the whole
    /// component, which may include room for a label this checkbox never has in practice -
    /// it's always used bare, alongside a separate label component). Falls back to the
    /// component height, which is a reasonable proxy since the glyph is roughly square.
    private int boxSize() {
        Icon icon = UIManager.getIcon("CheckBox.icon");
        int iconWidth = icon != null ? icon.getIconWidth() : -1;
        return iconWidth > 0 ? iconWidth : getHeight();
    }
}
