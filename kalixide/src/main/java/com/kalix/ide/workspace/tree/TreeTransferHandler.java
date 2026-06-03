package com.kalix.ide.workspace.tree;

import javax.swing.JComponent;
import javax.swing.JTree;
import javax.swing.TransferHandler;
import java.awt.datatransfer.Transferable;
import java.io.File;
import java.nio.file.Path;
import java.util.List;

/**
 * Drag-and-drop for the project tree.
 *
 * <p>As a drag <em>source</em> it exports the selected files/folders as a JVM-local
 * {@link TreeFileTransferable}. As a drop <em>target</em> it accepts such a drag onto a folder
 * node and moves (or, when the user requests COPY via a modifier key, copies) the dragged entries
 * into that folder after a confirmation; the filesystem watcher then reflects the result.
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
        if (!support.isDrop() || !support.isDataFlavorSupported(TreeFileTransferable.FLAVOR)) {
            return false;
        }
        File targetDir = targetDir(support);
        List<File> dragged = draggedFiles(support);
        if (targetDir == null || dragged == null || dragged.isEmpty()) {
            return false;
        }
        Path target = targetDir.toPath().toAbsolutePath().normalize();
        boolean anyValid = false;
        for (File f : dragged) {
            Path p = f.toPath().toAbsolutePath().normalize();
            if (target.startsWith(p)) {
                return false; // dropping a folder into itself or one of its descendants
            }
            if (!target.equals(p.getParent())) {
                anyValid = true; // at least one entry would actually move (not a same-folder no-op)
            }
        }
        return anyValid;
    }

    @Override
    public boolean importData(TransferSupport support) {
        if (!canImport(support)) {
            return false;
        }
        File targetDir = targetDir(support);
        List<File> dragged = draggedFiles(support);
        if (targetDir == null || dragged == null || dragged.isEmpty()) {
            return false;
        }
        boolean copy = (support.getDropAction() & COPY) == COPY;
        return fileOps.moveInto(dragged, targetDir, copy);
    }

    /** The folder to drop into: the target node if it is a directory, else its parent. */
    private File targetDir(TransferSupport support) {
        if (support.getDropLocation() instanceof JTree.DropLocation loc
                && loc.getPath() != null
                && loc.getPath().getLastPathComponent() instanceof FileTreeNode node) {
            File f = node.getFile();
            return f.isDirectory() ? f : f.getParentFile();
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private List<File> draggedFiles(TransferSupport support) {
        try {
            return (List<File>) support.getTransferable().getTransferData(TreeFileTransferable.FLAVOR);
        } catch (Exception ex) {
            return null;
        }
    }
}
