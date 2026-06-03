package com.kalix.ide.workspace.tree;

import com.kalix.ide.io.FsWatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.JOptionPane;
import javax.swing.JPopupMenu;
import javax.swing.JMenuItem;
import javax.swing.JTree;
import javax.swing.SwingUtilities;
import javax.swing.ToolTipManager;
import javax.swing.UIManager;
import javax.swing.event.TreeExpansionEvent;
import javax.swing.event.TreeWillExpandListener;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import javax.swing.tree.TreeSelectionModel;
import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * The project file tree: a {@link JTree} over a lazily-loaded filesystem model, kept live by
 * a native directory watcher (FSEvents on macOS via {@code io.methvin:directory-watcher}).
 *
 * <p>Provides full-width row hover, path tooltips, a right-click context menu (Open, Reveal,
 * New File/Folder, Rename, Delete, Refresh), and open-on-double-click / Enter. Files are
 * opened through the supplied consumer (which adds them as editor tabs).
 *
 * <p>All model mutations happen on the EDT; watcher callbacks marshal onto it.
 */
public class ProjectTree extends JTree {

    private static final Logger logger = LoggerFactory.getLogger(ProjectTree.class);

    // Per-level indent (px = left + right). Tighter than FlatLaf's default 7+11=18 for a
    // more compact, VSCode-like tree. Applied per-component so other JTrees are unaffected.
    private static final int LEFT_CHILD_INDENT = 6;
    private static final int RIGHT_CHILD_INDENT = 8;

    private final Consumer<File> fileOpenConsumer;

    private DefaultTreeModel model;
    private final FsWatcher fsWatcher = new FsWatcher(this::applyFsEvent);
    private int hoveredRow = -1;

    public ProjectTree(Consumer<File> fileOpenConsumer) {
        this.fileOpenConsumer = fileOpenConsumer;

        // Hide the root node: the open folder's name is shown in the panel header instead, so
        // the tree shows the folder's contents directly (VSCode-style). Root handles stay on so
        // top-level directories remain expandable.
        setRootVisible(false);
        setShowsRootHandles(true);
        getSelectionModel().setSelectionMode(TreeSelectionModel.SINGLE_TREE_SELECTION);
        setCellRenderer(new FileTreeCellRenderer());
        ToolTipManager.sharedInstance().registerComponent(this);

        // Lazy-load a directory's children the first time it is expanded.
        addTreeWillExpandListener(new TreeWillExpandListener() {
            @Override
            public void treeWillExpand(TreeExpansionEvent event) {
                Object last = event.getPath().getLastPathComponent();
                if (last instanceof FileTreeNode node && !node.isLoaded()) {
                    node.ensureLoaded();
                    model.nodeStructureChanged(node);
                }
            }

            @Override
            public void treeWillCollapse(TreeExpansionEvent event) {
                // no-op
            }
        });

        installMouseAndKeyHandlers();
    }

    /**
     * Opens (or switches to) the given folder as the tree's root, (re)starting the watcher.
     */
    public void openFolder(File root) {
        fsWatcher.stop();

        FileTreeNode rootNode = new FileTreeNode(root);
        rootNode.ensureLoaded();
        model = new DefaultTreeModel(rootNode);
        setModel(model);
        // Expand the (hidden) root so its children show as the top-level rows.
        expandPath(new TreePath(rootNode));

        fsWatcher.watch(root.toPath());
    }

    /**
     * Stops watching and releases resources. Call when disposing the host window.
     */
    public void dispose() {
        fsWatcher.stop();
    }

    public File getRootFile() {
        if (model != null && model.getRoot() instanceof FileTreeNode node) {
            return node.getFile();
        }
        return null;
    }

    @Override
    public void updateUI() {
        super.updateUI();
        // The look-and-feel reinstalls default indents on UI change; re-apply our tighter ones.
        if (getUI() instanceof javax.swing.plaf.basic.BasicTreeUI treeUi) {
            treeUi.setLeftChildIndent(LEFT_CHILD_INDENT);
            treeUi.setRightChildIndent(RIGHT_CHILD_INDENT);
        }
    }

    // --- Hover (full-width overlay) ---

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        if (hoveredRow >= 0 && hoveredRow < getRowCount() && !isRowSelected(hoveredRow)) {
            Rectangle bounds = getRowBounds(hoveredRow);
            if (bounds != null) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setColor(hoverColor());
                g2.fillRect(0, bounds.y, getWidth(), bounds.height);
                g2.dispose();
            }
        }
    }

    private static Color hoverColor() {
        Color base = UIManager.getColor("Tree.selectionBackground");
        if (base == null) {
            base = Color.GRAY;
        }
        // Subtle translucent wash so the row text remains legible.
        return new Color(base.getRed(), base.getGreen(), base.getBlue(), 40);
    }

    // --- Tooltips ---

    @Override
    public String getToolTipText(MouseEvent event) {
        FileTreeNode node = nodeAt(event.getX(), event.getY());
        return node != null ? node.getFile().getAbsolutePath() : null;
    }

    // --- Mouse / keyboard ---

    private void installMouseAndKeyHandlers() {
        addMouseMotionListener(new MouseAdapter() {
            @Override
            public void mouseMoved(MouseEvent e) {
                setHoveredRow(getRowForLocation(e.getX(), e.getY()));
            }
        });
        addMouseListener(new MouseAdapter() {
            @Override
            public void mouseExited(MouseEvent e) {
                setHoveredRow(-1);
            }

            @Override
            public void mousePressed(MouseEvent e) {
                maybeShowPopup(e);
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                maybeShowPopup(e);
            }

            @Override
            public void mouseClicked(MouseEvent e) {
                if (SwingUtilities.isLeftMouseButton(e) && e.getClickCount() == 2) {
                    FileTreeNode node = nodeAt(e.getX(), e.getY());
                    if (node != null && node.getFile().isFile()) {
                        fileOpenConsumer.accept(node.getFile());
                    }
                }
            }
        });

        // Enter opens the selected file.
        getInputMap().put(javax.swing.KeyStroke.getKeyStroke("ENTER"), "openSelected");
        getActionMap().put("openSelected", new javax.swing.AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                FileTreeNode node = selectedNode();
                if (node != null && node.getFile().isFile()) {
                    fileOpenConsumer.accept(node.getFile());
                }
            }
        });

        // F2 renames the selected entry (except the root).
        getInputMap().put(javax.swing.KeyStroke.getKeyStroke("F2"), "renameSelected");
        getActionMap().put("renameSelected", new javax.swing.AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                FileTreeNode node = selectedNode();
                if (node != null && !isRoot(node)) {
                    rename(node.getFile());
                }
            }
        });

        // Delete removes the selected entry (except the root).
        getInputMap().put(javax.swing.KeyStroke.getKeyStroke("DELETE"), "deleteSelected");
        getActionMap().put("deleteSelected", new javax.swing.AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                FileTreeNode node = selectedNode();
                if (node != null && !isRoot(node)) {
                    delete(node.getFile());
                }
            }
        });
    }

    private boolean isRoot(FileTreeNode node) {
        return model != null && node == model.getRoot();
    }

    private void setHoveredRow(int row) {
        if (row != hoveredRow) {
            hoveredRow = row;
            repaint();
        }
    }

    private void maybeShowPopup(MouseEvent e) {
        if (!e.isPopupTrigger()) {
            return;
        }
        int row = getRowForLocation(e.getX(), e.getY());
        if (row >= 0) {
            setSelectionRow(row);
        }
        FileTreeNode node = selectedNode();
        if (node != null) {
            buildContextMenu(node).show(this, e.getX(), e.getY());
        }
    }

    // --- Context menu ---

    private JPopupMenu buildContextMenu(FileTreeNode node) {
        File file = node.getFile();
        JPopupMenu menu = new JPopupMenu();

        if (file.isFile()) {
            JMenuItem open = new JMenuItem("Open");
            open.addActionListener(e -> fileOpenConsumer.accept(file));
            menu.add(open);
            menu.addSeparator();
        }

        JMenuItem reveal = new JMenuItem("Reveal in File Manager");
        reveal.addActionListener(e -> reveal(file));
        menu.add(reveal);
        menu.addSeparator();

        JMenuItem newFile = new JMenuItem("New File...");
        newFile.addActionListener(e -> createChild(node, false));
        menu.add(newFile);

        JMenuItem newFolder = new JMenuItem("New Folder...");
        newFolder.addActionListener(e -> createChild(node, true));
        menu.add(newFolder);

        menu.addSeparator();

        JMenuItem rename = new JMenuItem("Rename...");
        rename.addActionListener(e -> rename(file));
        menu.add(rename);

        JMenuItem delete = new JMenuItem("Delete");
        delete.addActionListener(e -> delete(file));
        menu.add(delete);

        menu.addSeparator();

        JMenuItem refresh = new JMenuItem("Refresh");
        refresh.addActionListener(e -> refresh(node));
        menu.add(refresh);

        return menu;
    }

    private void reveal(File file) {
        try {
            File target = file.isDirectory() ? file : file.getParentFile();
            com.kalix.ide.utils.FileManagerLauncher.openFileManagerAt(target);
        } catch (Exception ex) {
            logger.warn("Failed to reveal {}: {}", file, ex.getMessage());
        }
    }

    /** The directory to create new entries in: the node itself if a directory, else its parent. */
    private static File targetDirectory(FileTreeNode node) {
        File f = node.getFile();
        return f.isDirectory() ? f : f.getParentFile();
    }

    private void createChild(FileTreeNode node, boolean directory) {
        File dir = targetDirectory(node);
        if (dir == null) {
            return;
        }
        String name = JOptionPane.showInputDialog(this,
            directory ? "New folder name:" : "New file name:",
            directory ? "New Folder" : "New File",
            JOptionPane.PLAIN_MESSAGE);
        if (name == null || name.isBlank()) {
            return;
        }
        File target = new File(dir, name.trim());
        try {
            boolean created = directory ? target.mkdir() : target.createNewFile();
            if (!created) {
                JOptionPane.showMessageDialog(this,
                    "Could not create \"" + name + "\" (it may already exist).",
                    "Create Failed", JOptionPane.WARNING_MESSAGE);
            }
            // The watcher will add the node; no manual model change required.
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(this,
                "Failed to create \"" + name + "\": " + ex.getMessage(),
                "Create Failed", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void rename(File file) {
        String name = (String) JOptionPane.showInputDialog(this,
            "New name:", "Rename",
            JOptionPane.PLAIN_MESSAGE, null, null, file.getName());
        if (name == null || name.isBlank() || name.equals(file.getName())) {
            return;
        }
        File target = new File(file.getParentFile(), name.trim());
        if (!file.renameTo(target)) {
            JOptionPane.showMessageDialog(this,
                "Could not rename \"" + file.getName() + "\".",
                "Rename Failed", JOptionPane.WARNING_MESSAGE);
        }
        // The watcher reports delete + create; the tree re-syncs automatically.
    }

    private void delete(File file) {
        int choice = JOptionPane.showConfirmDialog(this,
            "Delete \"" + file.getName() + "\"?" + (file.isDirectory() ? "\n\nThis folder and its contents will be deleted." : "")
                + "\n\nThis cannot be undone.",
            "Delete", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        if (choice != JOptionPane.YES_OPTION) {
            return;
        }
        try {
            if (file.isDirectory()) {
                // Delete depth-first (children before parents). Files.delete throws on
                // failure, so a locked/read-only entry surfaces as an error rather than a
                // silently half-deleted folder.
                try (Stream<java.nio.file.Path> walk = Files.walk(file.toPath())) {
                    java.util.List<java.nio.file.Path> paths =
                        walk.sorted(Comparator.reverseOrder()).collect(java.util.stream.Collectors.toList());
                    for (java.nio.file.Path path : paths) {
                        Files.delete(path);
                    }
                }
            } else {
                Files.delete(file.toPath());
            }
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(this,
                "Failed to delete \"" + file.getName() + "\": " + ex.getMessage(),
                "Delete Failed", JOptionPane.ERROR_MESSAGE);
        }
        // The watcher reports the deletion; the tree re-syncs automatically.
    }

    private void refresh(FileTreeNode node) {
        FileTreeNode dir = node.getFile().isDirectory() ? node : (FileTreeNode) node.getParent();
        if (dir != null) {
            dir.ensureLoaded();
            resyncDirectory(dir);
        }
    }

    // --- Filesystem watcher ---

    /**
     * Applies a filesystem change to the tree. Delivered on the EDT by {@link FsWatcher}.
     */
    private void applyFsEvent(FsWatcher.Event event) {
        if (model == null) {
            return;
        }
        switch (event.kind()) {
            case CREATE:
            case DELETE: {
                File parent = event.path().toFile().getParentFile();
                FileTreeNode node = findNode(parent);
                if (node != null && node.isLoaded()) {
                    resyncDirectory(node);
                }
                break;
            }
            case OVERFLOW: {
                // Coarse recovery: re-sync the root's loaded subtree.
                resyncDirectory((FileTreeNode) model.getRoot());
                break;
            }
            case MODIFY:
            default:
                // Content change; tree structure is unaffected.
                break;
        }
    }

    /**
     * Re-synchronises a loaded directory node's children with what is on disk: removes nodes
     * for entries that disappeared and inserts nodes for new entries at their sorted position,
     * preserving existing nodes (and their expansion state).
     */
    private void resyncDirectory(FileTreeNode dirNode) {
        if (dirNode == null || !dirNode.isLoaded()) {
            return;
        }
        File[] entries = dirNode.getFile().listFiles();
        Set<File> onDisk = new HashSet<>();
        if (entries != null) {
            for (File f : entries) {
                if (!FileTreeNode.isHidden(f)) {
                    onDisk.add(f);
                }
            }
        }

        // Remove nodes whose file no longer exists.
        for (int i = dirNode.getChildCount() - 1; i >= 0; i--) {
            FileTreeNode child = (FileTreeNode) dirNode.getChildAt(i);
            if (!onDisk.contains(child.getFile())) {
                model.removeNodeFromParent(child);
            }
        }

        // Insert nodes for new files at their sorted position.
        for (File f : onDisk) {
            if (childFor(dirNode, f) == null) {
                model.insertNodeInto(new FileTreeNode(f), dirNode, sortedIndex(dirNode, f));
            }
        }
    }

    private static int sortedIndex(FileTreeNode dirNode, File file) {
        int count = dirNode.getChildCount();
        for (int i = 0; i < count; i++) {
            FileTreeNode child = (FileTreeNode) dirNode.getChildAt(i);
            if (FileTreeNode.FILE_ORDER.compare(file, child.getFile()) < 0) {
                return i;
            }
        }
        return count;
    }

    // --- Node lookup helpers ---

    /** Finds the (already-built) node for a file by walking only loaded children. */
    private FileTreeNode findNode(File target) {
        if (target == null || model == null) {
            return null;
        }
        FileTreeNode root = (FileTreeNode) model.getRoot();
        File rootFile = root.getFile();
        if (target.equals(rootFile)) {
            return root;
        }
        java.nio.file.Path rel;
        try {
            rel = rootFile.toPath().relativize(target.toPath());
        } catch (IllegalArgumentException ex) {
            return null; // target not under root
        }
        FileTreeNode current = root;
        for (java.nio.file.Path segment : rel) {
            File childFile = new File(current.getFile(), segment.toString());
            FileTreeNode next = childFor(current, childFile);
            if (next == null) {
                return null;
            }
            current = next;
        }
        return current;
    }

    private static FileTreeNode childFor(FileTreeNode parent, File file) {
        for (int i = 0; i < parent.getChildCount(); i++) {
            FileTreeNode child = (FileTreeNode) parent.getChildAt(i);
            if (child.getFile().equals(file)) {
                return child;
            }
        }
        return null;
    }

    private FileTreeNode selectedNode() {
        TreePath path = getSelectionPath();
        return path != null && path.getLastPathComponent() instanceof FileTreeNode node ? node : null;
    }

    private FileTreeNode nodeAt(int x, int y) {
        TreePath path = getPathForLocation(x, y);
        return path != null && path.getLastPathComponent() instanceof FileTreeNode node ? node : null;
    }
}
