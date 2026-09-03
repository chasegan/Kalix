package com.kalix.ide.flowviz.rendering;

import com.kalix.ide.flowviz.transform.PlotType;
import com.kalix.ide.utils.ThemeUtils;

import org.kordamp.ikonli.fontawesome6.FontAwesomeSolid;
import org.kordamp.ikonli.swing.FontIcon;

import javax.swing.BorderFactory;
import javax.swing.Icon;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.ListCellRenderer;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;

import static com.kalix.ide.windows.ToolbarConstants.BUTTON_ICON_SIZE;
import static com.kalix.ide.windows.ToolbarConstants.HORIZONTAL_SPACING;

/**
 * Renders each {@link PlotType} combo-box item as its display name (left-aligned) plus a glyph
 * (right-aligned) showing whether that plot type starts with overlapping-data masking on
 * ({@link PlotType#isDataMaskDefault()}).
 */
public class PlotTypeListCellRenderer implements ListCellRenderer<PlotType> {

    /** Same footprint as the real mask glyph, painted as nothing — see the class doc. */
    private static final Icon BLANK_ICON = new Icon() {
        @Override
        public void paintIcon(Component c, java.awt.Graphics g, int x, int y) {
            // Intentionally blank.
        }

        @Override
        public int getIconWidth() {
            return BUTTON_ICON_SIZE;
        }

        @Override
        public int getIconHeight() {
            return BUTTON_ICON_SIZE;
        }
    };

    @Override
    public Component getListCellRendererComponent(
        JList<? extends PlotType> list,
        PlotType value,
        int index,
        boolean isSelected,
        boolean cellHasFocus
    ) {
        Color background = isSelected ? list.getSelectionBackground() : list.getBackground();
        Color foreground = isSelected ? list.getSelectionForeground() : list.getForeground();

        JPanel panel = new JPanel(new BorderLayout(HORIZONTAL_SPACING, 0));
        panel.setOpaque(true);
        panel.setBackground(background);

        JLabel textLabel = new JLabel(value == null ? "" : value.getDisplayName());
        textLabel.setForeground(foreground);
        textLabel.setBorder(BorderFactory.createEmptyBorder(2, HORIZONTAL_SPACING, 2, 0));
        panel.add(textLabel, BorderLayout.WEST);

        Icon icon;
        if (value == null || index == -1) {
            // Toolbar's own closed-state display (or the null-value case): no visible glyph,
            // but still occupy the glyph's width — see the class doc.
            icon = BLANK_ICON;
        } else {
            // Icon colour follows the row's own background (selected vs. not) rather than a
            // fixed colour, so the glyph stays legible across both light and dark themes and
            // across the highlighted/unhighlighted state within the same popup.
            Color iconColor = ThemeUtils.iconColor(background);
            icon = value.isDataMaskDefault()
                ? FontIcon.of(FontAwesomeSolid.MASK, BUTTON_ICON_SIZE, iconColor)
                : FontIcon.of(FontAwesomeSolid.BAN, BUTTON_ICON_SIZE, iconColor);
        }
        // Every icon (whichever glyph, or the blank placeholder) sits in the same padded box
        // rather than flush against the row's edge, so rows stay visually uniform regardless
        // of which glyph is showing (FontIcon already reports the same square footprint for
        // every glyph - this is about breathing room, not the reported size). No left padding
        // here: that gap already comes from the BorderLayout hgap between the two labels.
        JLabel iconLabel = new JLabel(icon);
        iconLabel.setBorder(BorderFactory.createEmptyBorder(2, 0, 2, HORIZONTAL_SPACING));
        panel.add(iconLabel, BorderLayout.EAST);

        return panel;
    }
}
