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
 * <p>There is no empty state: the region is kept collapsed whenever no folder is open, and
 * showing it always opens a folder first, so an empty tree is never displayed.
 */
public class ProjectTreePanel extends JPanel {

    private final ProjectTree tree;
    private final JLabel header = new JLabel();

    public ProjectTreePanel(Consumer<File> fileOpenConsumer) {
        super(new BorderLayout());

        header.setBorder(BorderFactory.createEmptyBorder(6, 10, 6, 10));
        header.setFont(header.getFont().deriveFont(Font.BOLD));
        header.setForeground(mutedForeground());

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

    /** Stops the directory watcher; call when the host window is disposed. */
    public void dispose() {
        tree.dispose();
    }

    @Override
    public void updateUI() {
        super.updateUI();
        if (header != null) {
            header.setForeground(mutedForeground());
        }
    }

    private static Color mutedForeground() {
        Color disabled = UIManager.getColor("Label.disabledForeground");
        if (disabled != null) {
            return disabled;
        }
        Color fg = UIManager.getColor("Label.foreground");
        return fg != null ? fg : Color.GRAY;
    }
}
