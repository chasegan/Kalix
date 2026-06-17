package com.kalix.ide.editor;

import com.kalix.ide.workspace.tree.TreeFileTransferable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.*;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.dnd.*;
import java.io.File;
import java.util.List;

/**
 * Manages file drag-and-drop functionality for components.
 * Extracted from EnhancedTextEditor to eliminate code duplication and improve maintainability.
 */
public class FileDropManager {
    private static final Logger logger = LoggerFactory.getLogger(FileDropManager.class);
    
    public interface FileDropHandler {
        void onFileDropped(File file);
    }

    /**
     * Handles a drag originating from the project tree (a {@link TreeFileTransferable}), as
     * opposed to an OS file drop. Receives the dragged files plus the component and point where
     * they were dropped, so it can act at the drop location (e.g. insert paths there).
     */
    public interface PathDropHandler {
        void onTreeFilesDropped(List<File> files, Component target, Point location);
    }

    private final FileDropHandler fileDropHandler;
    private PathDropHandler pathDropHandler;

    /**
     * Creates a new FileDropManager.
     *
     * @param fileDropHandler the handler to call when files are dropped
     */
    public FileDropManager(FileDropHandler fileDropHandler) {
        this.fileDropHandler = fileDropHandler;
    }

    /**
     * Sets the handler for drags originating from the project tree. When set, such drags are
     * routed here instead of the generic open-file behaviour. Null (the default) disables it.
     */
    public void setPathDropHandler(PathDropHandler pathDropHandler) {
        this.pathDropHandler = pathDropHandler;
    }
    
    /**
     * Sets up drag and drop functionality for the specified components.
     * 
     * @param components the components to enable drag and drop on
     */
    public void setupDragAndDrop(Component... components) {
        for (Component component : components) {
            new DropTarget(component, createDropTargetListener());
        }
    }
    
    /**
     * Creates a reusable DropTargetListener for file operations.
     */
    private DropTargetListener createDropTargetListener() {
        return new DropTargetListener() {
            @Override
            public void dragEnter(DropTargetDragEvent dtde) {
                if (dtde.isDataFlavorSupported(DataFlavor.javaFileListFlavor)
                        || (pathDropHandler != null
                            && dtde.isDataFlavorSupported(TreeFileTransferable.FLAVOR))) {
                    dtde.acceptDrag(DnDConstants.ACTION_COPY);
                } else {
                    dtde.rejectDrag();
                }
            }
            
            @Override
            public void dragOver(DropTargetDragEvent dtde) {
                // Accept the drag - no additional logic needed
            }
            
            @Override
            public void dropActionChanged(DropTargetDragEvent dtde) {
                // Handle action change if needed - currently no additional logic
            }
            
            @Override
            public void dragExit(DropTargetEvent dte) {
                // Handle drag exit if needed - currently no additional logic
            }
            
            @Override
            public void drop(DropTargetDropEvent dtde) {
                handleFileDrop(dtde);
            }
        };
    }
    
    /**
     * Handles the file drop operation.
     */
    private void handleFileDrop(DropTargetDropEvent dtde) {
        try {
            Transferable transferable = dtde.getTransferable();

            // A drag from the project tree carries the local tree flavor (and not the OS file
            // flavor); route it to the path-drop handler so it acts at the drop location.
            if (pathDropHandler != null && transferable.isDataFlavorSupported(TreeFileTransferable.FLAVOR)) {
                dtde.acceptDrop(DnDConstants.ACTION_COPY);
                @SuppressWarnings("unchecked")
                List<File> files = (List<File>) transferable.getTransferData(TreeFileTransferable.FLAVOR);
                Component target = dtde.getDropTargetContext().getComponent();
                pathDropHandler.onTreeFilesDropped(files, target, dtde.getLocation());
                dtde.dropComplete(true);
                return;
            }

            dtde.acceptDrop(DnDConstants.ACTION_COPY);

            if (transferable.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
                @SuppressWarnings("unchecked")
                List<File> files = (List<File>) transferable.getTransferData(DataFlavor.javaFileListFlavor);

                // Open every dropped file in its own tab (any text-based file, not just .ini),
                // mirroring the file tree and the main-window drop. Directories aren't openable
                // as documents, so skip them.
                int opened = 0;
                if (fileDropHandler != null) {
                    for (File file : files) {
                        if (file.isFile()) {
                            fileDropHandler.onFileDropped(file);
                            opened++;
                        }
                    }
                }
                dtde.dropComplete(opened > 0);
            } else {
                dtde.dropComplete(false);
            }
        } catch (Exception e) {
            logger.error("Error handling file drop: {}", e.getMessage());
            dtde.dropComplete(false);
        }
    }
}