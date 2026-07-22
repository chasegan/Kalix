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
 *
 * <p>Rows are colour-coded by {@link FileCategory}, with the colours owned by the application
 * theme ({@code Kalix.tree.*} keys in {@code resources/themes/*.properties}), falling back to
 * the plain tree colours when a theme omits them. The organising principle
 * (per {@code manifestos/file-tree-colour.md} §1.1): <em>recognised</em> rows (model files,
 * data files, model folders) carry full-strength text, everything else is muted — because
 * accent hues are usually lighter than the default foreground, prominence has to come from
 * de-emphasising the rest, not from colouring the important text.
 * <ul>
 *   <li><b>Model files</b> ({@code *.ini}): a node-link glyph in
 *       {@code Kalix.tree.modelFileColor}, full-strength text.</li>
 *   <li><b>Data files</b> ({@code *.csv}, {@code *.pxt}, {@code *.pxb}): their existing glyphs
 *       in {@code Kalix.tree.dataFileColor}, full-strength text.</li>
 *   <li><b>Source result exports</b> ({@code *.res.csv} — eWater Source's format): a chart
 *       glyph (matching the toolbar's plot icon) in {@code Kalix.tree.sourceResultFileColor},
 *       full-strength text.</li>
 *   <li><b>Model folders</b> (directories directly containing a model, per
 *       {@link FileTreeNode#containsModelFile()}): the default full-strength folder glyph and
 *       text.</li>
 *   <li><b>Model-less folders</b>: icon and text in {@code Kalix.tree.mutedForeground},
 *       a per-theme grey between {@code Tree.foreground} and {@code Tree.background}.</li>
 *   <li><b>Unrecognised files</b>: icon and text one step fainter again, in
 *       {@code Kalix.tree.faintForeground} (the muted grey pushed a further 10% toward
 *       the background).</li>
 * </ul>
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
            // Recognised rows keep the full-strength Tree.foreground super() just set; the
            // rest step down — folders to muted, unrecognised files a notch fainter — so
            // recognised rows carry the darkest (light themes) / brightest (dark themes)
            // text. Selected rows keep the selection foreground for contrast.
            if (!selected && !isRecognised(node)) {
                Color faded = node.isDirectory() ? mutedColor() : faintColor();
                if (faded != null) {
                    setForeground(faded);
                }
            }
        }
        return this;
    }

    private Icon iconFor(FileTreeNode node, boolean expanded) {
        Ikon glyph;
        Color color;
        if (node.isDirectory()) { // cached flag, no per-cell disk stat
            glyph = expanded ? FontAwesomeSolid.FOLDER_OPEN : FontAwesomeSolid.FOLDER;
            // Model folders get the full-strength folder colour; model-less ones are muted.
            color = node.containsModelFile()
                ? folderColor()
                : themed("Kalix.tree.mutedForeground", folderColor());
        } else {
            switch (FileCategory.of(node.getFile())) {
                case MODEL -> {
                    glyph = FontAwesomeSolid.PROJECT_DIAGRAM; // a node-link network, like the model
                    color = themed("Kalix.tree.modelFileColor", fileColor());
                }
                case DATA -> {
                    glyph = glyphForExtension(node.getFile().getName());
                    color = themed("Kalix.tree.dataFileColor", fileColor());
                }
                case SOURCE_RESULT -> {
                    glyph = FontAwesomeSolid.CHART_LINE; // same association as the plot toolbar icon
                    color = themed("Kalix.tree.sourceResultFileColor", fileColor());
                }
                default -> {
                    // Faint like the text, so an uncategorised file's icon doesn't out-pop it.
                    glyph = glyphForExtension(node.getFile().getName());
                    Color faint = faintColor();
                    color = faint != null ? faint : fileColor();
                }
            }
        }
        String key = glyph.getDescription() + "#" + color.getRGB();
        return iconCache.computeIfAbsent(key, k -> FontIcon.of(glyph, ICON_SIZE, color));
    }

    private static Ikon glyphForExtension(String name) {
        String lower = name.toLowerCase();
        if (lower.endsWith(".toml") || lower.endsWith(".json")) {
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
        if (lower.endsWith(".zip")) {
            return FontAwesomeSolid.FILE_ARCHIVE;
        }
        return FontAwesomeSolid.FILE;
    }

    /** Recognised rows — model files, data files, model folders — keep full-strength text. */
    private static boolean isRecognised(FileTreeNode node) {
        if (node.isDirectory()) {
            return node.containsModelFile();
        }
        return FileCategory.of(node.getFile()) != FileCategory.OTHER;
    }

    /** A themed {@code Kalix.tree.*} colour, or {@code fallback} for themes that omit the key. */
    private static Color themed(String key, Color fallback) {
        Color c = UIManager.getColor(key);
        return c != null ? c : fallback;
    }

    /** The muted (model-less folder) tone, or null when the theme defines neither tier. */
    private static Color mutedColor() {
        return UIManager.getColor("Kalix.tree.mutedForeground");
    }

    /** The faint (unrecognised file) tone, falling back to muted for themes without it. */
    private static Color faintColor() {
        Color c = UIManager.getColor("Kalix.tree.faintForeground");
        return c != null ? c : mutedColor();
    }

    private static Color folderColor() {
        // The same theme-aware grey as the toolbar and menu icons, so a full-strength
        // (model) folder glyph reads as part of the same icon family.
        return com.kalix.ide.utils.ThemeUtils.iconColor(UIManager.getColor("Tree.background"));
    }

    private static Color fileColor() {
        Color c = UIManager.getColor("Tree.foreground");
        if (c == null) {
            c = UIManager.getColor("Label.foreground");
        }
        return c != null ? c : Color.GRAY;
    }
}
