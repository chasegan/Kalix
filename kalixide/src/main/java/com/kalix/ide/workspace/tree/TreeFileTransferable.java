package com.kalix.ide.workspace.tree;

import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.io.File;
import java.util.List;

/**
 * The payload for a drag started in the project tree: the dragged files/folders, in row order.
 *
 * <p>It advertises two flavors, both yielding the same {@code List<File>}:
 * <ul>
 *   <li>a JVM-local {@link #FLAVOR}, which lets in-app drop targets recognise a project-tree drag
 *       and react specially — a folder node moves/copies the files, the editor inserts their
 *       relative paths; and
 *   <li>{@link DataFlavor#javaFileListFlavor}, so the same drag is understood by generic file-drop
 *       targets (the Run Manager, FlowViz, the main window's open-model handler) and by other
 *       applications / the OS — e.g. dropping into Finder.
 * </ul>
 *
 * <p>Because every tree drag now satisfies {@code javaFileListFlavor} too, an in-app target that
 * wants tree-specific behaviour must check {@link #FLAVOR} <em>before</em> falling back to the OS
 * file flavor (as {@code FileDropManager} does); targets that key off {@code javaFileListFlavor}
 * alone simply treat the drag as an ordinary file drop.
 */
public final class TreeFileTransferable implements Transferable {

    /** JVM-local flavor identifying a project-tree drag; the transfer data is a {@code List<File>}. */
    public static final DataFlavor FLAVOR = new DataFlavor(
        DataFlavor.javaJVMLocalObjectMimeType + ";class=" + List.class.getName(),
        "Kalix project-tree files");

    // The JVM-local flavor is listed first (the preferred, app-aware representation); the OS file
    // flavor follows so external targets and generic file-drop handlers still understand the drag.
    private static final DataFlavor[] FLAVORS = { FLAVOR, DataFlavor.javaFileListFlavor };

    private final List<File> files;

    public TreeFileTransferable(List<File> files) {
        this.files = List.copyOf(files);
    }

    @Override
    public DataFlavor[] getTransferDataFlavors() {
        return FLAVORS.clone();
    }

    @Override
    public boolean isDataFlavorSupported(DataFlavor flavor) {
        return FLAVOR.equals(flavor) || DataFlavor.javaFileListFlavor.equals(flavor);
    }

    @Override
    public List<File> getTransferData(DataFlavor flavor) throws UnsupportedFlavorException {
        if (FLAVOR.equals(flavor) || DataFlavor.javaFileListFlavor.equals(flavor)) {
            return files;
        }
        throw new UnsupportedFlavorException(flavor);
    }
}
