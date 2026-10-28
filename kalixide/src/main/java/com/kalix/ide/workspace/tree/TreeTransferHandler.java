package com.kalix.ide.workspace.tree;

import javax.swing.JComponent;
import javax.swing.JTree;
import javax.swing.TransferHandler;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Drag-and-drop for the project tree.
 *
 * <p>As a drag <em>source</em> it exports the selected files/folders as a JVM-local
 * {@link TreeFileTransferable}. As a drop <em>target</em> it accepts two kinds of drag onto a
 * folder node, or onto empty space, which targets the open root:
 * <ul>
 *   <li>a drag from the tree itself moves the entries into that folder after a confirmation, or
 *       copies them when the user holds the copy modifier;
 *   <li>a drag from the operating system copies the files in. Always a copy: a target that
 *       accepts a move entitles the source application to delete the originals afterwards, and
 *       Windows Explorer does.
 * </ul>
 * The filesystem watcher then reflects the result.
 *
 * <p>The move/copy cursor and the no-drop cursor are rendered by the DnD subsystem itself, driven
 * by {@link #getSourceActions} and {@link #canImport} — nothing here sets the cursor.
 */
class TreeTransferHandler extends TransferHandler {

    private final ProjectTree tree;
    private final TreeFileOperations fileOps;

    TreeTransferHandler(ProjectTree tree, TreeFileOperations fileOps) {
        this.tree = tree;
        this.fileOps = fileOps;
    }

    // --- Source ---

    @Override
    public int getSourceActions(JComponent c) {
        return COPY_OR_MOVE;
    }

    @Override
    protected Transferable createTransferable(JComponent c) {
        List<File> files = tree.selectedFiles();
        return files.isEmpty() ? null : new TreeFileTransferable(files);
    }

    @Override
    protected void exportDone(JComponent source, Transferable data, int action) {
        // The move/copy is performed by importData on the target side, so there is nothing to
        // clean up here (and no risk of a MOVE action deleting the source behind our back).
    }

    // --- Target ---

    @Override
    public boolean canImport(TransferSupport support) {
        if (!support.isDrop()) {
            return false;
        }
        boolean internal = support.isDataFlavorSupported(TreeFileTransferable.FLAVOR);
        if (!internal && !support.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
            return false;
        }
        File targetDir = targetDir(support);
        if (targetDir == null) {
            return false;
        }
        if (!internal) {
            support.setDropAction(COPY);
        }
        List<File> dragged = draggedFiles(support, internal);
        if (dragged == null) {
            // Some platforms hand over an external file list only at drop time; accept
            // provisionally and let importData decide.
            return !internal;
        }
        boolean duplicateInPlace = internal && (support.getDropAction() & COPY) == COPY;
        return !actionable(dragged, targetDir, duplicateInPlace).isEmpty();
    }

    @Override
    public boolean importData(TransferSupport support) {
        if (!canImport(support)) {
            return false;
        }
        boolean internal = support.isDataFlavorSupported(TreeFileTransferable.FLAVOR);
        File targetDir = targetDir(support);
        List<File> dragged = draggedFiles(support, internal);
        if (targetDir == null || dragged == null) {
            return false;
        }
        if (!internal) {
            return fileOps.copyIn(actionable(dragged, targetDir, false), targetDir);
        }
        boolean copy = (support.getDropAction() & COPY) == COPY;
        return fileOps.moveInto(actionable(dragged, targetDir, copy), targetDir, copy);
    }

    /**
     * The dragged entries a drop into {@code targetDir} would act on, in drag order. Empty when
     * the drop must be refused: a folder cannot be dropped into itself or a descendant. An
     * entry already in the target folder is skipped, unless {@code duplicateInPlace}, an
     * in-tree copy, which duplicates it under a new name. A filesystem root is skipped: it has
     * no name to copy under. A drop that skips everything does nothing.
     */
    static List<File> actionable(List<File> dragged, File targetDir, boolean duplicateInPlace) {
        Path target = targetDir.toPath().toAbsolutePath().normalize();
        List<File> result = new ArrayList<>();
        for (File f : dragged) {
            Path p = f.toPath().toAbsolutePath().normalize();
            if (p.getNameCount() == 0) {
                continue;
            }
            if (target.startsWith(p)) {
                return List.of();
            }
            if (duplicateInPlace || !target.equals(p.getParent())) {
                result.add(f);
            }
        }
        return result;
    }

    /**
     * The folder to drop into: the target node if it is a directory, else its parent; a drop on
     * empty space targets the open root (ADR-0002 §4). Null when no folder is open.
     */
    private File targetDir(TransferSupport support) {
        if (support.getDropLocation() instanceof JTree.DropLocation loc
                && loc.getPath() != null
                && loc.getPath().getLastPathComponent() instanceof FileTreeNode node) {
            File f = node.getFile();
            return f.isDirectory() ? f : f.getParentFile();
        }
        return tree.getRootFile(); // empty space -> the open folder
    }

    /** The dragged files, or null when the transferable cannot supply them yet. */
    @SuppressWarnings("unchecked")
    private static List<File> draggedFiles(TransferSupport support, boolean internal) {
        DataFlavor flavor = internal ? TreeFileTransferable.FLAVOR : DataFlavor.javaFileListFlavor;
        try {
            return (List<File>) support.getTransferable().getTransferData(flavor);
        } catch (Exception ex) {
            return null;
        }
    }
}
