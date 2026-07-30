package com.kalix.ide.workspace.tree;

import com.kalix.ide.io.FsWatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.DropMode;
import javax.swing.JPopupMenu;
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
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The project file tree: a {@link JTree} over a lazily-loaded filesystem model, kept live by
 * a native directory watcher (FSEvents on macOS via {@code io.methvin:directory-watcher}).
 *
 * <p>Provides full-width row hover, path tooltips, a right-click context menu (Open, Reveal,
 * New File/Folder, Rename, Delete, Refresh), and open-on-double-click / Enter (zip archives
 * are unzipped instead of opened). Files are opened through the supplied consumer (which adds
 * them as editor tabs).
 *
 * <p>All model mutations happen on the EDT; watcher callbacks marshal onto it.
 */
public class ProjectTree extends JTree {

    private static final Logger logger = LoggerFactory.getLogger(ProjectTree.class);

    // Per-level indent (px = left + right). Tighter than FlatLaf's default 7+11=18 for a
    // more compact, VSCode-like tree. Applied per-component so other JTrees are unaffected.
    private static final int LEFT_CHILD_INDENT = 6;
    private static final int RIGHT_CHILD_INDENT = 8;

    private final TreeHost host;

    private DefaultTreeModel model;
    private final FsWatcher fsWatcher = new FsWatcher(this::applyFsEvent);
    private int hoveredRow = -1;
    // Whether dotfiles/folders are shown. Read live by every FileTreeNode at (re)load time via the
    // supplier handed to it, so toggling re-filters on the next directory sync (see setShowHidden).
    private boolean showHidden = true;
    private final TreeFileOperations fileOps;
    private final TreeContextMenu contextMenu;

    public ProjectTree(TreeHost host) {
        this.host = host;

        // Hide the root node: the open folder's name is shown in the panel header instead, so
        // the tree shows the folder's contents directly (VSCode-style). Root handles stay on so
        // top-level directories remain expandable.
        setRootVisible(false);
        setShowsRootHandles(true);
        getSelectionModel().setSelectionMode(TreeSelectionModel.DISCONTIGUOUS_TREE_SELECTION);
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

        this.fileOps = new TreeFileOperations(this, host::activeFile, host::pathMoved);
        this.contextMenu = new TreeContextMenu(this, fileOps, host);

        // Drag selected entries out; accept drops onto a folder node (move, or copy with a
        // modifier held). The DnD subsystem renders the move/copy/no-drop cursor itself.
        setDragEnabled(true);
        setDropMode(DropMode.ON);
        setTransferHandler(new TreeTransferHandler(this, fileOps));

        installMouseAndKeyHandlers();
    }

    /**
     * Opens (or switches to) the given folder as the tree's root, (re)starting the watcher.
     */
    public void openFolder(File root) {
        fsWatcher.stop();

        FileTreeNode rootNode = new FileTreeNode(root, this::isShowHidden);
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

    /** @return whether hidden (dot-prefixed) entries are shown in the tree. */
    public boolean isShowHidden() {
        return showHidden;
    }

    /**
     * Shows or hides hidden (dot-prefixed) entries. Re-syncs every already-loaded directory with
     * disk so hidden entries appear/disappear in place, preserving existing nodes and their
     * expansion state. A no-op if the value is unchanged.
     */
    public void setShowHidden(boolean show) {
        if (show == showHidden) {
            return;
        }
        showHidden = show;
        if (model != null) {
            resyncLoadedSubtree((FileTreeNode) model.getRoot());
        }
    }

    /** Re-syncs this directory and, recursively, every loaded descendant directory with disk. */
    private void resyncLoadedSubtree(FileTreeNode node) {
        if (node == null || !node.isLoaded()) {
            return;
        }
        resyncDirectory(node);
        for (int i = 0; i < node.getChildCount(); i++) {
            FileTreeNode child = (FileTreeNode) node.getChildAt(i);
            if (child.isDirectory()) {
                if (child.isLoaded()) {
                    resyncLoadedSubtree(child);
                } else {
                    // Not loaded, so there is nothing to resync — but this deep resync runs
                    // when events may have been missed (OVERFLOW) or the filter changed, so
                    // the cached model-folder flag can be stale too.
                    child.invalidateContainsModelFile();
                    model.nodeChanged(child);
                }
            }
        }
    }

    /**
     * Selects and reveals the node for the given file if it lies within the open folder,
     * lazily loading and expanding ancestor directories as needed. Clears the selection if the
     * file is not under the open folder (e.g. opened from elsewhere) or no folder is open — so
     * the tree selection never shows a file other than the active one.
     */
    public void selectFile(File file) {
        FileTreeNode node = (file == null) ? null : materializeNode(file);
        if (node == null) {
            clearSelection();
            return;
        }
        TreePath path = new TreePath(node.getPath());
        setSelectionPath(path);
        scrollPathToVisible(path);
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
                setHoveredRow(fullWidthRowAt(e.getY()));
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
                        openOrUnzip(node.getFile());
                    }
                }
            }
        });

        // Enter opens the selected file (or unzips it, if it's a zip archive).
        getInputMap().put(javax.swing.KeyStroke.getKeyStroke("ENTER"), "openSelected");
        getActionMap().put("openSelected", new javax.swing.AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                List<FileTreeNode> selection = selectedNodes();
                if (selection.size() == 1 && !selection.get(0).isDirectory()) {
                    openOrUnzip(selection.get(0).getFile());
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
                    fileOps.rename(node.getFile());
                }
            }
        });

        // Delete removes the selected entry (except the root).
        getInputMap().put(javax.swing.KeyStroke.getKeyStroke("DELETE"), "deleteSelected");
        getActionMap().put("deleteSelected", new javax.swing.AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                List<File> files = new ArrayList<>();
                for (FileTreeNode node : selectedNodes()) {
                    if (!isRoot(node)) {
                        files.add(node.getFile());
                    }
                }
                if (!files.isEmpty()) {
                    fileOps.delete(files);
                }
            }
        });
    }

    /** Unzips {@code file} if it's a zip archive, otherwise opens it as an editor tab. */
    private void openOrUnzip(File file) {
        if (TreeFileOperations.isZip(file)) {
            fileOps.unzipFile(file);
        } else {
            host.openFile(file);
        }
    }

    /** Whether this node is the (hidden) root — package-private for the context menu's
     *  root guards (per context-menu-style §4: rename/duplicate/delete never apply). */
    boolean isRoot(FileTreeNode node) {
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
        int row = fullWidthRowAt(e.getY());
        List<FileTreeNode> subject;
        JPopupMenu menu;
        if (row < 0) {
            // Empty space is not subject-less: the click acts on the open folder itself
            // (context-menu-style §4) — so New file…/New folder… etc. work at the root.
            clearSelection();
            menu = model == null ? null : contextMenu.buildFromRoot((FileTreeNode) model.getRoot());
        } else {
            if (!isRowSelected(row)) {
                // Right-clicking a row outside the current selection acts on that row alone;
                // right-clicking within a multi-selection keeps it, so the menu acts on all of
                // it.
                setSelectionRow(row);
            }
            subject = selectedNodes();
            menu = contextMenu.build(subject);
        }
        if (menu != null) {
            menu.show(this, e.getX(), e.getY());
        }
    }

    // --- View operations (invoked from the context menu) ---

    void refresh(FileTreeNode node) {
        FileTreeNode dir = node.getFile().isDirectory() ? node : (FileTreeNode) node.getParent();
        if (dir != null) {
            dir.ensureLoaded();
            resyncDirectory(dir);
        }
    }

    // --- Expand / collapse ---

    /** Recursively expands this node and all descendant directories (lazily loading them). */
    void expandSubtree(FileTreeNode node) {
        expandPath(new TreePath(node.getPath())); // loads children via the will-expand listener
        for (int i = 0; i < node.getChildCount(); i++) {
            FileTreeNode child = (FileTreeNode) node.getChildAt(i);
            // Don't descend into symlinked directories: a link back to an ancestor would recurse
            // forever (StackOverflowError). They can still be expanded manually.
            if (child.isDirectory() && !Files.isSymbolicLink(child.getFile().toPath())) {
                expandSubtree(child);
            }
        }
    }

    /** Recursively collapses all descendant directories of this node, then the node itself. */
    void collapseSubtree(FileTreeNode node) {
        for (int i = 0; i < node.getChildCount(); i++) {
            FileTreeNode child = (FileTreeNode) node.getChildAt(i);
            if (child.isDirectory()) {
                collapseSubtree(child);
            }
        }
        collapsePath(new TreePath(node.getPath()));
    }

    /** Collapses every top-level branch, leaving only the top-level rows (the root is hidden). */
    void collapseAll() {
        if (model == null) {
            return;
        }
        FileTreeNode root = (FileTreeNode) model.getRoot();
        for (int i = 0; i < root.getChildCount(); i++) {
            FileTreeNode child = (FileTreeNode) root.getChildAt(i);
            if (child.isDirectory()) {
                collapseSubtree(child);
            }
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
                if (node == null) {
                    break;
                }
                if (node.isLoaded()) {
                    resyncDirectory(node); // also refreshes the model-folder flag
                } else {
                    // Visible but never expanded: no children to resync, but the change may
                    // have added/removed a model file, so recolour the folder row.
                    node.invalidateContainsModelFile();
                    model.nodeChanged(node);
                }
                break;
            }
            case OVERFLOW: {
                // Coarse recovery: re-sync the root's loaded subtree. OVERFLOW means events were
                // dropped/coalesced, so a deep resync (not just the root's immediate children) is
                // exactly what's needed.
                resyncLoadedSubtree((FileTreeNode) model.getRoot());
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
        // The directory's contents changed, so its model-folder colouring may have too.
        dirNode.invalidateContainsModelFile();
        model.nodeChanged(dirNode);
        File[] entries = dirNode.getFile().listFiles();
        Set<File> onDisk = new HashSet<>();
        if (entries != null) {
            for (File f : entries) {
                if (showHidden || !FileTreeNode.isHidden(f)) {
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
                model.insertNodeInto(new FileTreeNode(f, this::isShowHidden), dirNode, sortedIndex(dirNode, f));
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
        // Canonicalize both sides: watcher event paths arrive symlink-resolved (macOS
        // /tmp → /private/tmp), while the tree is built from the raw root the user opened.
        // Relativizing the resolved forms yields the same segments regardless, so the walk
        // (which rebuilds child files from the raw root) still lands on the right nodes.
        Path rootReal = toReal(root.getFile().toPath());
        Path targetReal = toReal(target.toPath());
        if (targetReal.equals(rootReal)) {
            return root;
        }
        Path rel;
        try {
            rel = rootReal.relativize(targetReal);
        } catch (IllegalArgumentException ex) {
            return null; // different roots (e.g. another drive)
        }
        if (rel.getNameCount() == 0 || rel.startsWith("..")) {
            return null; // target not under root
        }
        FileTreeNode current = root;
        for (Path segment : rel) {
            File childFile = new File(current.getFile(), segment.toString());
            FileTreeNode next = childFor(current, childFile);
            if (next == null) {
                return null;
            }
            current = next;
        }
        return current;
    }

    /**
     * Resolves a path to its real (symlink-resolved) form. Falls back to a lexical
     * absolute-normalize if the path can't be resolved (e.g. it no longer exists).
     */
    private static Path toReal(Path path) {
        try {
            return path.toRealPath();
        } catch (IOException e) {
            return path.toAbsolutePath().normalize();
        }
    }

    /**
     * Like {@link #findNode}, but lazily loads (and notifies the model about) ancestor
     * directories so a not-yet-expanded file can be located. Returns null if the file is not
     * under the open folder.
     */
    private FileTreeNode materializeNode(File file) {
        if (model == null) {
            return null;
        }
        FileTreeNode root = (FileTreeNode) model.getRoot();
        java.nio.file.Path rootPath = root.getFile().toPath().toAbsolutePath().normalize();
        java.nio.file.Path target = file.toPath().toAbsolutePath().normalize();
        if (target.equals(rootPath)) {
            return root;
        }
        if (!target.startsWith(rootPath)) {
            return null; // not under the open folder
        }
        FileTreeNode current = root;
        for (java.nio.file.Path segment : rootPath.relativize(target)) {
            boolean wasLoaded = current.isLoaded();
            current.ensureLoaded();
            if (!wasLoaded) {
                model.nodeStructureChanged(current);
            }
            current = childFor(current, new File(current.getFile(), segment.toString()));
            if (current == null) {
                return null;
            }
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

    /** The selected nodes in row (top-to-bottom) order, so multi-selection actions are ordered. */
    private List<FileTreeNode> selectedNodes() {
        int[] rows = getSelectionRows();
        if (rows == null || rows.length == 0) {
            return List.of();
        }
        Arrays.sort(rows);
        List<FileTreeNode> nodes = new ArrayList<>(rows.length);
        for (int row : rows) {
            TreePath path = getPathForRow(row);
            if (path != null && path.getLastPathComponent() instanceof FileTreeNode node) {
                nodes.add(node);
            }
        }
        return nodes;
    }

    /** The selected files/folders in row (top-to-bottom) order — the drag payload. */
    List<File> selectedFiles() {
        return selectedNodes().stream().map(FileTreeNode::getFile).toList();
    }

    /**
     * The node at {@code (x, y)}, treating rows as full-width (matching the full-width
     * hover/selection painting) rather than {@link #getPathForLocation}, which only hits the
     * renderer's actual (label-width) bounds. {@code x} is otherwise unused, kept only so call
     * sites reporting a mouse position read naturally.
     */
    private FileTreeNode nodeAt(int x, int y) {
        int row = fullWidthRowAt(y);
        if (row < 0) {
            return null;
        }
        TreePath path = getPathForRow(row);
        return path != null && path.getLastPathComponent() instanceof FileTreeNode node ? node : null;
    }

    /**
     * The row at {@code y}, treating rows as full-width (matching the full-width hover/selection
     * painting) rather than {@link #getRowForLocation} which only hits the renderer's actual
     * (label-width) bounds. Returns -1 below the last row.
     */
    private int fullWidthRowAt(int y) {
        int row = getClosestRowForLocation(0, y);
        if (row < 0) {
            return -1;
        }
        Rectangle bounds = getRowBounds(row);
        return (bounds != null && y >= bounds.y && y < bounds.y + bounds.height) ? row : -1;
    }

    /**
     * Shows the right-click context menu for the given files (e.g. from an editor tab), as if
     * they were right-clicked in the tree, without changing the tree's own selection. Files
     * outside the open folder (or if no folder is open) are silently dropped.
     */
    public void showContextMenuForFiles(List<File> files, Component invoker, int x, int y) {
        List<FileTreeNode> nodes = files.stream()
                .map(this::materializeNode)
                .filter(java.util.Objects::nonNull)
                .toList();
        JPopupMenu menu = contextMenu.buildFromEditorTab(nodes);
        if (menu != null) {
            menu.show(invoker, x, y);
        }
    }
}
