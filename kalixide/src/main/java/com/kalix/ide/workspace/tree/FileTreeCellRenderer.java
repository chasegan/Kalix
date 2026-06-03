package com.kalix.ide.workspace.tree;

import org.kordamp.ikonli.Ikon;
import org.kordamp.ikonli.fontawesome6.FontAwesomeSolid;
import org.kordamp.ikonli.swing.FontIcon;

import javax.swing.Icon;
import javax.swing.JTree;
import javax.swing.UIManager;
import javax.swing.tree.DefaultTreeCellRenderer;
import java.awt.Color;
import java.awt.Component;
import java.util.HashMap;
import java.util.Map;

/**
 * Renders project-tree rows with theme-coloured, file-type-specific icons (via Ikonli
 * FontAwesome). Folders use open/closed folder glyphs; files map by extension. Icons are
 * cached by glyph + colour so they survive repaints and rebuild automatically on a theme
 * change (the colour key changes).
 */
public class FileTreeCellRenderer extends DefaultTreeCellRenderer {

    private static final int ICON_SIZE = 14;

    private final Map<String, Icon> iconCache = new HashMap<>();

    @Override
    public Component getTreeCellRendererComponent(JTree tree, Object value, boolean selected,
                                                  boolean expanded, boolean leaf, int row,
                                                  boolean hasFocus) {
        super.getTreeCellRendererComponent(tree, value, selected, expanded, leaf, row, hasFocus);
        if (value instanceof FileTreeNode node) {
            setIcon(iconFor(node, expanded));
        }
        return this;
    }

    private Icon iconFor(FileTreeNode node, boolean expanded) {
        Ikon glyph;
        Color color;
        if (node.isDirectory()) { // cached flag, no per-cell disk stat
            glyph = expanded ? FontAwesomeSolid.FOLDER_OPEN : FontAwesomeSolid.FOLDER;
            color = folderColor();
        } else {
            glyph = glyphForExtension(node.getFile().getName());
            color = fileColor();
        }
        String key = glyph.getDescription() + "#" + color.getRGB();
        return iconCache.computeIfAbsent(key, k -> FontIcon.of(glyph, ICON_SIZE, color));
    }

    private static Ikon glyphForExtension(String name) {
        String lower = name.toLowerCase();
        if (lower.endsWith(".ini") || lower.endsWith(".toml") || lower.endsWith(".json")) {
            return FontAwesomeSolid.FILE_CODE;
        }
        if (lower.endsWith(".csv")) {
            return FontAwesomeSolid.FILE_CSV;
        }
        if (lower.endsWith(".pxt") || lower.endsWith(".pxb")) {
            return FontAwesomeSolid.DATABASE;
        }
        if (lower.endsWith(".md") || lower.endsWith(".txt") || lower.endsWith(".log")) {
            return FontAwesomeSolid.FILE_ALT;
        }
        return FontAwesomeSolid.FILE;
    }

    private static Color folderColor() {
        Color c = UIManager.getColor("Tree.icon.expandControlColor");
        return c != null ? c : fileColor();
    }

    private static Color fileColor() {
        Color c = UIManager.getColor("Tree.foreground");
        if (c == null) {
            c = UIManager.getColor("Label.foreground");
        }
        return c != null ? c : Color.GRAY;
    }
}
