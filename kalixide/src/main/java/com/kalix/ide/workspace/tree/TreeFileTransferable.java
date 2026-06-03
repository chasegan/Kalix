package com.kalix.ide.workspace.tree;

import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.io.File;
import java.util.List;

/**
 * The payload for a drag started in the project tree: the dragged files/folders, in row order.
 *
 * <p>It uses a JVM-local {@link DataFlavor} (not {@link DataFlavor#javaFileListFlavor}) so the
 * payload is only meaningful to drop targets inside this application. That lets those targets
 * tell a project-tree drag apart from an OS file drop and react accordingly — a folder node
 * moves/copies the files, the editor inserts their relative paths — rather than falling into the
 * generic "open dropped file" handling.
 */
public final class TreeFileTransferable implements Transferable {

    /** JVM-local flavor identifying a project-tree drag; the transfer data is a {@code List<File>}. */
    public static final DataFlavor FLAVOR = new DataFlavor(
        DataFlavor.javaJVMLocalObjectMimeType + ";class=" + List.class.getName(),
        "Kalix project-tree files");

    private static final DataFlavor[] FLAVORS = { FLAVOR };

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
        return FLAVOR.equals(flavor);
    }

    @Override
    public List<File> getTransferData(DataFlavor flavor) throws UnsupportedFlavorException {
        if (!FLAVOR.equals(flavor)) {
            throw new UnsupportedFlavorException(flavor);
        }
        return files;
    }
}
