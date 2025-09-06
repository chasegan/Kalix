package com.kalix.gui.handlers;

import com.kalix.gui.constants.AppConstants;
import com.kalix.gui.managers.FileOperationsManager;

import java.awt.Component;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.dnd.*;
import java.io.File;
import java.util.List;
import java.util.function.Consumer;

/**
 * Handles drag and drop operations for files in the application.
 * Provides functionality to drop Kalix model files onto the application window.
 */
public class FileDropHandler {
    
    private final FileOperationsManager fileOperations;
    private final Consumer<String> statusUpdateCallback;
    
    /**
     * Creates a new FileDropHandler instance.
     * 
     * @param fileOperations The file operations manager for handling dropped files
     * @param statusUpdateCallback Callback for status updates
     */
    public FileDropHandler(FileOperationsManager fileOperations, Consumer<String> statusUpdateCallback) {
        this.fileOperations = fileOperations;
        this.statusUpdateCallback = statusUpdateCallback;
    }
    
    /**
     * Sets up drag and drop functionality for the specified component.
     * 
     * @param component The component to enable drag and drop on
     */
    public void setupDragAndDrop(Component component) {
        new DropTarget(component, new KalixDropTargetListener());
    }
    
    /**
     * Checks if the drag event contains valid file data.
     * 
     * @param dtde The drag target drag event
     * @return true if valid files are being dragged
     */
    private boolean isValidFileDrop(DropTargetDragEvent dtde) {
        return dtde.isDataFlavorSupported(DataFlavor.javaFileListFlavor);
    }
    
    /**
     * Custom DropTargetListener implementation for handling file drops.
     */
    private class KalixDropTargetListener implements DropTargetListener {
        
        @Override
        public void dragEnter(DropTargetDragEvent dtde) {
            handleDragAction(dtde);
        }

        @Override
        public void dragOver(DropTargetDragEvent dtde) {
            handleDragAction(dtde);
        }

        @Override
        public void dropActionChanged(DropTargetDragEvent dtde) {
            handleDragAction(dtde);
        }

        @Override
        public void dragExit(DropTargetEvent dte) {
            // No action needed
        }

        @Override
        public void drop(DropTargetDropEvent dtde) {
            handleFileDrop(dtde);
        }
        
        /**
         * Handles drag actions by accepting or rejecting based on content.
         */
        private void handleDragAction(DropTargetDragEvent dtde) {
            if (isValidFileDrop(dtde)) {
                dtde.acceptDrag(DnDConstants.ACTION_COPY);
            } else {
                dtde.rejectDrag();
            }
        }
        
        /**
         * Handles the actual file drop operation.
         */
        private void handleFileDrop(DropTargetDropEvent dtde) {
            try {
                if (dtde.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
                    dtde.acceptDrop(DnDConstants.ACTION_COPY);
                    
                    Transferable transferable = dtde.getTransferable();
                    @SuppressWarnings("unchecked")
                    List<File> files = (List<File>) transferable.getTransferData(DataFlavor.javaFileListFlavor);
                    
                    if (!files.isEmpty()) {
                        // Process the first valid model file
                        for (File file : files) {
                            if (fileOperations.isKalixModelFile(file)) {
                                fileOperations.loadModelFile(file);
                                dtde.dropComplete(true);
                                return;
                            }
                        }
                        // No valid model files found
                        statusUpdateCallback.accept(AppConstants.STATUS_INVALID_DROP_FILES);
                        dtde.dropComplete(false);
                    } else {
                        dtde.dropComplete(false);
                    }
                } else {
                    dtde.rejectDrop();
                }
            } catch (Exception e) {
                statusUpdateCallback.accept(AppConstants.ERROR_PROCESSING_DROPPED_FILE + e.getMessage());
                dtde.dropComplete(false);
            }
        }
    }
}