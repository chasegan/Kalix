package com.kalix.ide.workspace;

import com.kalix.ide.workspace.tree.ProjectTree;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.UIManager;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.GridBagLayout;
import java.io.File;
import java.util.function.Consumer;

/**
 * The left-hand project tree region. Shows an empty state ("No folder open" + an Open Folder
 * button, à la VSCode) until a folder is opened, then the live {@link ProjectTree}.
 *
 * <p>Opening files is delegated to the supplied consumer (which adds editor tabs); the empty
 * state's button triggers the host's Open Folder action.
 */
public class ProjectTreePanel extends JPanel {

    private static final String CARD_EMPTY = "empty";
    private static final String CARD_TREE = "tree";

    private final CardLayout cards = new CardLayout();
    private final ProjectTree tree;
    private final JLabel placeholder = new JLabel("No folder open");

    public ProjectTreePanel(Consumer<File> fileOpenConsumer, Runnable openFolderAction) {
        setLayout(cards);

        add(buildEmptyState(openFolderAction), CARD_EMPTY);

        tree = new ProjectTree(fileOpenConsumer);
        JScrollPane scroll = new JScrollPane(tree);
        scroll.setBorder(null);
        scroll.putClientProperty("JScrollPane.smoothScrolling", Boolean.TRUE);
        add(scroll, CARD_TREE);

        cards.show(this, CARD_EMPTY);
    }

    /** Opens the given folder as the tree root and shows the tree. */
    public void openFolder(File root) {
        tree.openFolder(root);
        cards.show(this, CARD_TREE);
    }

    /** @return the currently open root folder, or {@code null} if none */
    public File getRootFile() {
        return tree.getRootFile();
    }

    /** Stops the directory watcher; call when the host window is disposed. */
    public void dispose() {
        tree.dispose();
    }

    private JPanel buildEmptyState(Runnable openFolderAction) {
        JPanel empty = new JPanel(new GridBagLayout());
        empty.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));

        JPanel column = new JPanel();
        column.setOpaque(false);
        column.setLayout(new BoxLayout(column, BoxLayout.Y_AXIS));

        placeholder.setAlignmentX(Component.CENTER_ALIGNMENT);
        placeholder.setForeground(mutedForeground());

        JButton openButton = new JButton("Open Folder...");
        openButton.setAlignmentX(Component.CENTER_ALIGNMENT);
        openButton.addActionListener(e -> openFolderAction.run());

        column.add(placeholder);
        column.add(Box.createVerticalStrut(10));
        column.add(openButton);

        empty.add(column);
        return empty;
    }

    @Override
    public void updateUI() {
        super.updateUI();
        if (placeholder != null) {
            placeholder.setForeground(mutedForeground());
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
