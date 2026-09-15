package com.kalix.ide.flowviz;

import com.kalix.ide.flowviz.rendering.LineShape;

import javax.swing.ButtonGroup;
import javax.swing.JComponent;
import javax.swing.JRadioButtonMenuItem;

import java.util.EnumMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * The line shape choices (one radio item per {@link LineShape}), shared by the plot's context
 * menu ("Line shape" submenu) and the toolbar's line shape button, so the two always offer the
 * same values under the same names.
 */
final class LineShapeMenu {

    private LineShapeMenu() {
    }

    /**
     * Adds one grouped radio item per shape to {@code menu} (a {@code JMenu} or
     * {@code JPopupMenu}); choosing one calls {@code onSelect}. Items are returned keyed by
     * shape, so callers tick the current shape without reading it back out of label text
     * (ADR-0003 §2.2).
     */
    static Map<LineShape, JRadioButtonMenuItem> addItems(JComponent menu, Consumer<LineShape> onSelect) {
        Map<LineShape, JRadioButtonMenuItem> items = new EnumMap<>(LineShape.class);
        ButtonGroup group = new ButtonGroup();
        for (LineShape shape : LineShape.values()) {
            JRadioButtonMenuItem item = new JRadioButtonMenuItem(shape.getDisplayName());
            item.addActionListener(e -> onSelect.accept(shape));
            group.add(item);
            menu.add(item);
            items.put(shape, item);
        }
        return items;
    }
}
