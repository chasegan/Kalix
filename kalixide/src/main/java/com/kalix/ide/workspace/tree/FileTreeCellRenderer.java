package com.kalix.ide.workspace.tree;

import com.kalix.ide.io.FileVisuals;

import javax.swing.JTree;
import javax.swing.tree.DefaultTreeCellRenderer;
import java.awt.Color;
import java.awt.Component;

/**
 * Renders project-tree rows in the Kalix file visual language (doctrine:
 * {@code manifestos/file-tree-colour.md}): recognised rows — model files, data files,
 * Source result exports, model folders — carry accent icons and full-strength text, while
 * model-less folders step down to the muted tier and unrecognised files a notch fainter.
 * All glyph and colour selection is delegated to {@link FileVisuals}, the shared mapping
 * also used by the file dialogs, so the two can never drift apart.
 */
public class FileTreeCellRenderer extends DefaultTreeCellRenderer {

    @Override
    public Component getTreeCellRendererComponent(JTree tree, Object value, boolean selected,
                                                  boolean expanded, boolean leaf, int row,
                                                  boolean hasFocus) {
        super.getTreeCellRendererComponent(tree, value, selected, expanded, leaf, row, hasFocus);
        if (value instanceof FileTreeNode node) {
            FileVisuals.Tier tier;
            if (node.isDirectory()) { // cached flag, no per-cell disk stat
                boolean prominent = node.containsModelFile();
                setIcon(FileVisuals.folderIcon(expanded, prominent));
                tier = FileVisuals.folderTier(prominent);
            } else {
                String name = node.getFile().getName();
                setIcon(FileVisuals.fileIcon(name));
                tier = FileVisuals.fileTier(name);
            }
            // FULL-tier rows keep the foreground super() just set; the rest step down.
            // Selected rows always keep the selection foreground (file-tree-colour §2.6).
            if (!selected) {
                Color faded = FileVisuals.tierColor(tier);
                if (faded != null) {
                    setForeground(faded);
                }
            }
        }
        return this;
    }
}
