package com.kalix.gui.editor;

import javax.swing.*;
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
    
    public interface FileDropHandler {
        void onFileDropped(File file);
    }
    
    private final FileDropHandler fileDropHandler;
    
    /**
     * Creates a new FileDropManager.
     * 
     * @param fileDropHandler the handler to call when files are dropped
     */
    public FileDropManager(FileDropHandler fileDropHandler) {
        this.fileDropHandler = fileDropHandler;
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
                if (dtde.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
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
            dtde.acceptDrop(DnDConstants.ACTION_COPY);
            
            Transferable transferable = dtde.getTransferable();
            if (transferable.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
                @SuppressWarnings("unchecked")
                List<File> files = (List<File>) transferable.getTransferData(DataFlavor.javaFileListFlavor);
                
                if (!files.isEmpty()) {
                    File file = files.get(0); // Take the first file
                    String fileName = file.getName().toLowerCase();
                    
                    // Only accept .ini and .toml files
                    if (isAcceptedFileType(fileName)) {
                        if (fileDropHandler != null) {
                            fileDropHandler.onFileDropped(file);
                        }
                        dtde.dropComplete(true);
                    } else {
                        System.out.println("Rejected file: " + fileName + " (only .ini and .toml files are accepted)");
                        dtde.dropComplete(false);
                    }
                } else {
                    dtde.dropComplete(false);
                }
            } else {
                dtde.dropComplete(false);
            }
        } catch (Exception e) {
            System.err.println("Error handling file drop: " + e.getMessage());
            dtde.dropComplete(false);
        }
    }
    
    /**
     * Checks if the file type is accepted for dropping.
     * 
     * @param fileName the file name (should be lowercase)
     * @return true if the file type is accepted
     */
    private boolean isAcceptedFileType(String fileName) {
        return fileName.endsWith(".ini") || fileName.endsWith(".toml");
    }
}