package com.kalix.gui.managers;

import com.kalix.gui.MapPanel;
import com.kalix.gui.constants.AppConstants;
import com.kalix.gui.editor.EnhancedTextEditor;

import javax.swing.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.Component;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.function.Consumer;

/**
 * Manages file operations including opening, saving, and file validation.
 * Handles file dialogs and integrates with the text editor and map panel.
 */
public class FileOperationsManager {
    
    private final Component parentComponent;
    private final EnhancedTextEditor textEditor;
    private final MapPanel mapPanel;
    private final Consumer<String> statusUpdateCallback;
    private final Consumer<String> addRecentFileCallback;
    
    /**
     * Creates a new FileOperationsManager instance.
     * 
     * @param parentComponent The parent component for dialogs
     * @param textEditor The text editor component
     * @param mapPanel The map panel component
     * @param statusUpdateCallback Callback for status updates
     * @param addRecentFileCallback Callback for adding recent files
     */
    public FileOperationsManager(Component parentComponent, 
                               EnhancedTextEditor textEditor,
                               MapPanel mapPanel,
                               Consumer<String> statusUpdateCallback,
                               Consumer<String> addRecentFileCallback) {
        this.parentComponent = parentComponent;
        this.textEditor = textEditor;
        this.mapPanel = mapPanel;
        this.statusUpdateCallback = statusUpdateCallback;
        this.addRecentFileCallback = addRecentFileCallback;
    }
    
    /**
     * Creates a new model by clearing the text editor and map panel.
     */
    public void newModel() {
        textEditor.setText(AppConstants.NEW_MODEL_TEXT);
        mapPanel.clearModel();
        statusUpdateCallback.accept(AppConstants.STATUS_NEW_MODEL_CREATED);
    }
    
    /**
     * Shows an open file dialog and loads the selected model file.
     */
    public void openModel() {
        JFileChooser fileChooser = createFileChooser();
        
        int result = fileChooser.showOpenDialog(parentComponent);
        if (result == JFileChooser.APPROVE_OPTION) {
            File selectedFile = fileChooser.getSelectedFile();
            loadModelFile(selectedFile);
        }
    }
    
    /**
     * Loads a model file from the specified file path.
     * 
     * @param filePath The absolute path to the file to load
     */
    public void loadModelFile(String filePath) {
        loadModelFile(new File(filePath));
    }
    
    /**
     * Loads a model file from the specified File object.
     * 
     * @param file The file to load
     */
    public void loadModelFile(File file) {
        try {
            String content = Files.readString(file.toPath());
            
            // Set content in text editor
            textEditor.setText(content);
            
            // Determine file format
            String format = getFileFormat(file.getName());
            
            // Update status
            String statusMessage = String.format("Opened %s model: %s (%s format)", 
                format, file.getName(), format);
            statusUpdateCallback.accept(statusMessage);
            
            // Add to recent files
            addRecentFileCallback.accept(file.getAbsolutePath());
            
            // Clear the map panel
            mapPanel.clearModel();
            
        } catch (IOException e) {
            showFileOpenError(file, e);
        }
    }
    
    /**
     * Placeholder for save functionality.
     */
    public void saveModel() {
        statusUpdateCallback.accept(AppConstants.STATUS_SAVE_NOT_IMPLEMENTED);
    }
    
    /**
     * Placeholder for save as functionality.
     */
    public void saveAsModel() {
        statusUpdateCallback.accept(AppConstants.STATUS_SAVE_AS_NOT_IMPLEMENTED);
    }
    
    /**
     * Checks if a file is a valid Kalix model file.
     * 
     * @param file The file to check
     * @return true if the file is a valid model file
     */
    public boolean isKalixModelFile(File file) {
        if (!file.isFile()) return false;
        
        String fileName = file.getName().toLowerCase();
        for (String extension : AppConstants.SUPPORTED_MODEL_EXTENSIONS) {
            if (fileName.endsWith(extension)) {
                return true;
            }
        }
        return false;
    }
    
    /**
     * Creates a configured file chooser for model files.
     */
    private JFileChooser createFileChooser() {
        JFileChooser fileChooser = new JFileChooser();
        fileChooser.setDialogTitle("Open Kalix Model");
        
        // Set file filters for supported model formats
        FileNameExtensionFilter allModelsFilter = new FileNameExtensionFilter(
            AppConstants.MODEL_FILES_DESCRIPTION, "ini", "toml");
        FileNameExtensionFilter iniFilter = new FileNameExtensionFilter(
            AppConstants.INI_FILES_DESCRIPTION, "ini");
        FileNameExtensionFilter tomlFilter = new FileNameExtensionFilter(
            AppConstants.TOML_FILES_DESCRIPTION, "toml");
        
        fileChooser.setFileFilter(allModelsFilter);
        fileChooser.addChoosableFileFilter(iniFilter);
        fileChooser.addChoosableFileFilter(tomlFilter);
        
        return fileChooser;
    }
    
    /**
     * Determines the file format based on the filename.
     * 
     * @param fileName The name of the file
     * @return A string describing the file format
     */
    private String getFileFormat(String fileName) {
        String lowerName = fileName.toLowerCase();
        if (lowerName.endsWith(AppConstants.INI_EXTENSION)) {
            return "INI";
        } else if (lowerName.endsWith(AppConstants.TOML_EXTENSION)) {
            return "TOML";
        } else {
            return "unknown";
        }
    }
    
    /**
     * Shows an error dialog for file opening failures.
     * 
     * @param file The file that failed to open
     * @param e The exception that occurred
     */
    private void showFileOpenError(File file, IOException e) {
        JOptionPane.showMessageDialog(
            parentComponent,
            AppConstants.ERROR_OPENING_FILE + e.getMessage(),
            AppConstants.ERROR_FILE_OPEN,
            JOptionPane.ERROR_MESSAGE
        );
        statusUpdateCallback.accept(AppConstants.ERROR_FAILED_TO_OPEN + file.getName());
    }
}