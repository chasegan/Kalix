package com.kalix.ide.dialogs;

import com.kalix.ide.preferences.ui.PreferencePage;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTree;
import javax.swing.KeyStroke;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import javax.swing.tree.TreeSelectionModel;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Professional preferences dialog with tree-based navigation.
 *
 * <p>Both the navigation tree and the card panel are built from one ordered list
 * of {@link PreferencePage}s: each page's {@link PreferencePage#treePath()} places
 * it in the tree (category nodes are created in encounter order), and selecting
 * its tree node shows its component, mapped by identity — a page cannot appear in
 * the tree without its content, or vice versa.
 *
 * <p>Preferences apply immediately as the user changes them; free-text fields
 * additionally commit on every dialog-close path via
 * {@link PreferencePage#commitPendingEdits()}.
 */
public class PreferencesDialog extends JDialog {

    private final List<PreferencePage> pages;
    private final PreferencePage initialPage;

    // Main components
    private JTree preferencesTree;
    private JPanel contentPanel;
    private CardLayout cardLayout;

    /** Tree node for each page, for programmatic selection. */
    private final Map<PreferencePage, DefaultMutableTreeNode> pageTreeNodes = new IdentityHashMap<>();

    /** A page's leaf entry in the navigation tree. */
    private record PageTreeItem(PreferencePage page) {
        /** The tree displays the last segment of the page's tree path. */
        @Override
        public String toString() {
            String path = page.treePath();
            return path.substring(path.lastIndexOf('/') + 1);
        }
    }

    /**
     * Creates a new preferences dialog.
     *
     * @param parent      the owning frame
     * @param pages       the pages, in tree order
     * @param initialPage the page selected when the dialog opens (must be in {@code pages})
     */
    public PreferencesDialog(JFrame parent, List<PreferencePage> pages, PreferencePage initialPage) {
        super(parent, "Preferences", true);
        this.pages = List.copyOf(pages);
        this.initialPage = initialPage;

        initializeDialog(parent);
    }

    /**
     * Initializes the dialog layout and components.
     */
    private void initializeDialog(JFrame parent) {
        setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
        setLayout(new BorderLayout());

        // Create tree navigation
        createPreferencesTree();
        JScrollPane treeScrollPane = new JScrollPane(preferencesTree);
        treeScrollPane.setPreferredSize(new Dimension(200, 0));
        treeScrollPane.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 5));

        // Create content panel with one card per page, keyed by page id
        cardLayout = new CardLayout();
        contentPanel = new JPanel(cardLayout);
        contentPanel.setBorder(BorderFactory.createEmptyBorder(10, 5, 10, 10));
        for (PreferencePage page : pages) {
            contentPanel.add(page.component(), page.id());
        }

        // Create main split pane
        JSplitPane splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT);
        splitPane.setLeftComponent(treeScrollPane);
        splitPane.setRightComponent(contentPanel);
        splitPane.setDividerLocation(200);
        splitPane.setDividerSize(6);
        splitPane.setBorder(null);

        add(splitPane, BorderLayout.CENTER);

        // Create button panel
        add(createButtonPanel(), BorderLayout.SOUTH);

        // Set dialog properties
        setSize(700, 500);
        setLocationRelativeTo(parent);
        setResizable(true);

        // Add Escape key binding to close dialog
        getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
            .put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "closeDialog");
        getRootPane().getActionMap().put("closeDialog", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                dispose();
            }
        });

        // Select the initial page (its selection listener shows the matching card)
        DefaultMutableTreeNode initialNode = pageTreeNodes.get(initialPage);
        if (initialNode != null) {
            preferencesTree.setSelectionPath(new TreePath(initialNode.getPath()));
        }
    }

    /**
     * Creates the preferences tree from the page list. Each page's tree path
     * places it under category nodes created on demand, in encounter order, so
     * the tree structure and the page list can never drift apart.
     */
    private void createPreferencesTree() {
        DefaultMutableTreeNode root = new DefaultMutableTreeNode("Preferences");
        Map<String, DefaultMutableTreeNode> categoryNodes = new HashMap<>();

        for (PreferencePage page : pages) {
            String[] segments = page.treePath().split("/");

            // Walk/create the category chain (all segments but the last)
            DefaultMutableTreeNode parent = root;
            StringBuilder categoryPath = new StringBuilder();
            for (int i = 0; i < segments.length - 1; i++) {
                if (categoryPath.length() > 0) {
                    categoryPath.append('/');
                }
                categoryPath.append(segments[i]);
                DefaultMutableTreeNode existing = categoryNodes.get(categoryPath.toString());
                if (existing == null) {
                    existing = new DefaultMutableTreeNode(segments[i]);
                    parent.add(existing);
                    categoryNodes.put(categoryPath.toString(), existing);
                }
                parent = existing;
            }

            DefaultMutableTreeNode leaf = new DefaultMutableTreeNode(new PageTreeItem(page));
            parent.add(leaf);
            pageTreeNodes.put(page, leaf);
        }

        preferencesTree = new JTree(new DefaultTreeModel(root));
        preferencesTree.setRootVisible(false);
        preferencesTree.setShowsRootHandles(true);
        preferencesTree.getSelectionModel().setSelectionMode(TreeSelectionModel.SINGLE_TREE_SELECTION);

        // Expand all nodes
        expandAllNodes();

        // Show the selected page's card; category nodes leave the current card in place
        preferencesTree.addTreeSelectionListener(e -> {
            DefaultMutableTreeNode node = (DefaultMutableTreeNode) preferencesTree.getLastSelectedPathComponent();
            if (node != null && node.getUserObject() instanceof PageTreeItem item) {
                cardLayout.show(contentPanel, item.page().id());
            }
        });

        // Add context menu for tree
        createTreeContextMenu();
    }

    /**
     * Creates a context menu for the preferences tree.
     */
    private void createTreeContextMenu() {
        JPopupMenu contextMenu = new JPopupMenu();

        JMenuItem expandAllItem = new JMenuItem("Expand all");
        expandAllItem.addActionListener(e -> expandAllNodes());
        contextMenu.add(expandAllItem);

        JMenuItem collapseAllItem = new JMenuItem("Collapse all");
        collapseAllItem.addActionListener(e -> collapseAllNodes());
        contextMenu.add(collapseAllItem);

        preferencesTree.setComponentPopupMenu(contextMenu);
    }

    /**
     * Expands all nodes in the preferences tree.
     */
    private void expandAllNodes() {
        for (int i = 0; i < preferencesTree.getRowCount(); i++) {
            preferencesTree.expandRow(i);
        }
    }

    /**
     * Collapses all nodes in the preferences tree.
     */
    private void collapseAllNodes() {
        for (int i = preferencesTree.getRowCount() - 1; i >= 0; i--) {
            preferencesTree.collapseRow(i);
        }
    }

    /**
     * Creates the button panel.
     */
    private JPanel createButtonPanel() {
        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttonPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        JButton closeButton = new JButton("Close");
        closeButton.addActionListener(e -> dispose());

        buttonPanel.add(closeButton);
        getRootPane().setDefaultButton(closeButton);

        return buttonPanel;
    }

    /**
     * Shows the preferences dialog (modal; returns when it closes). Preferences
     * apply immediately as the user changes them, so there is no result to report.
     */
    public void showDialog() {
        setVisible(true);
    }

    /**
     * Cleanup when dialog is closed.
     */
    @Override
    public void dispose() {
        // Commit free-text fields on every close path (Close button, Escape, window decoration)
        pages.forEach(PreferencePage::commitPendingEdits);
        // Release any listeners the pages registered
        pages.forEach(PreferencePage::dispose);
        super.dispose();
    }
}
