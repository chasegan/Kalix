package com.kalix.ide.workspace;

import com.kalix.ide.workspace.tree.ProjectTree;

import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.UIManager;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Font;
import java.io.File;
import java.util.function.Consumer;

/**
 * The left-hand project region: a small header showing the open folder's name above the live
 * {@link ProjectTree}. Both are inside this panel, so collapsing the region (via
 * {@code WorkspacePanel}) hides the header and tree together.
 *
 * <p>The header uses the default label font in bold on the menu bar's background colour, so it
 * reads as part of the menu's solid style rather than inventing a new visual language.
 *
 * <p>There is no empty state: the region is kept collapsed whenever no folder is open, and
 * showing it always opens a folder first, so an empty tree is never displayed.
 */
public class ProjectTreePanel extends JPanel {

    private final ProjectTree tree;
    private final JLabel header = new JLabel();

    public ProjectTreePanel(Consumer<File> fileOpenConsumer) {
        super(new BorderLayout());

        header.setBorder(BorderFactory.createEmptyBorder(6, 10, 6, 10));
        applyHeaderStyle();

        tree = new ProjectTree(fileOpenConsumer);
        JScrollPane scroll = new JScrollPane(tree);
        scroll.setBorder(null);
        scroll.putClientProperty("JScrollPane.smoothScrolling", Boolean.TRUE);

        add(header, BorderLayout.NORTH);
        add(scroll, BorderLayout.CENTER);
    }

    /** Opens the given folder: loads the tree and sets the header to the folder name. */
    public void openFolder(File root) {
        tree.openFolder(root);
        String name = root.getName();
        header.setText(name.isEmpty() ? root.getAbsolutePath() : name);
        header.setToolTipText(root.getAbsolutePath());
    }

    /** @return the currently open root folder, or {@code null} if none */
    public File getRootFile() {
        return tree.getRootFile();
    }

    /** Reveals and selects the given file in the tree, if it lies within the open folder. */
    public void selectFile(File file) {
        tree.selectFile(file);
    }

    /** Stops the directory watcher; call when the host window is disposed. */
    public void dispose() {
        tree.dispose();
    }

    @Override
    public void updateUI() {
        super.updateUI();
        // Re-apply the header style after a look-and-feel/theme change (explicitly set colours
        // and fonts do not track the theme on their own).
        if (header != null) {
            applyHeaderStyle();
        }
    }

    /**
     * Styles the header: the menu bar's background with a bold default-label font, so it reads
     * as part of the menu's solid style. Colour and font are copied into plain (non-UIResource)
     * instances so the label UI does not overwrite them with defaults on a theme change.
     */
    private void applyHeaderStyle() {
        Color background = UIManager.getColor("MenuBar.background");
        if (background != null) {
            header.setOpaque(true);
            header.setBackground(new Color(background.getRGB()));
        }
        Font base = UIManager.getFont("Label.font");
        if (base != null) {
            header.setFont(base.deriveFont(Font.BOLD));
        }
    }
}
