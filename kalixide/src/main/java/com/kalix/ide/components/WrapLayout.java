package com.kalix.ide.components;

import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Insets;

/**
 * A {@link FlowLayout} whose preferred size accounts for wrapping: where
 * FlowLayout always reports the single-row width (so a host container clips
 * the overflow), this reports the height of the rows the current container
 * width actually produces — the host grows a second row instead.
 *
 * <p>The wrap width is the container's current width (walking up to the first
 * ancestor with one before the first layout pass); with no width known yet it
 * degrades to FlowLayout's single-row answer. Containers whose height depends
 * on this should revalidate on resize, since the row count changes with width.
 *
 * <p>Layout itself is inherited untouched — FlowLayout already wraps when
 * positioning components; only the size <em>reporting</em> was wrong.
 */
public class WrapLayout extends FlowLayout {

    public WrapLayout(int align, int hgap, int vgap) {
        super(align, hgap, vgap);
    }

    @Override
    public Dimension preferredLayoutSize(Container target) {
        return layoutSize(target, true);
    }

    @Override
    public Dimension minimumLayoutSize(Container target) {
        Dimension size = layoutSize(target, false);
        size.width -= getHgap() + 1; // let the container shrink past one row's minimum
        return size;
    }

    private Dimension layoutSize(Container target, boolean preferred) {
        synchronized (target.getTreeLock()) {
            int targetWidth = target.getSize().width;
            Container probe = target;
            while (targetWidth == 0 && probe.getParent() != null) {
                probe = probe.getParent();
                targetWidth = probe.getSize().width;
            }
            if (targetWidth == 0) {
                targetWidth = Integer.MAX_VALUE; // no width yet: single-row answer
            }

            int hgap = getHgap();
            int vgap = getVgap();
            Insets insets = target.getInsets();
            int maxRowWidth = targetWidth - (insets.left + insets.right + hgap * 2);

            Dimension size = new Dimension(0, 0);
            int rowWidth = 0;
            int rowHeight = 0;
            for (int i = 0; i < target.getComponentCount(); i++) {
                Component component = target.getComponent(i);
                if (!component.isVisible()) {
                    continue; // matches FlowLayout: hidden components take no space
                }
                Dimension d = preferred ? component.getPreferredSize() : component.getMinimumSize();
                if (rowWidth > 0 && rowWidth + hgap + d.width > maxRowWidth) {
                    size.width = Math.max(size.width, rowWidth);
                    size.height += rowHeight + vgap;
                    rowWidth = 0;
                    rowHeight = 0;
                }
                if (rowWidth > 0) {
                    rowWidth += hgap;
                }
                rowWidth += d.width;
                rowHeight = Math.max(rowHeight, d.height);
            }
            size.width = Math.max(size.width, rowWidth);
            size.height += rowHeight;

            size.width += insets.left + insets.right + hgap * 2;
            size.height += insets.top + insets.bottom + vgap * 2;
            return size;
        }
    }
}
