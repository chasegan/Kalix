package com.kalix.ide.managers;

import com.kalix.ide.constants.AppConstants;
import com.kalix.ide.document.KalixDocument;
import com.kalix.ide.preferences.PreferenceManager;
import com.kalix.ide.preferences.PreferenceKeys;

import javax.swing.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.Component;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Manages file operations including opening, saving, and file validation.
 * Operates on the active {@link KalixDocument}: the backing file lives on the
 * document, not on this manager, so the same manager serves whichever document
 * is currently active.
 */
public class FileOperationsManager {

    private final Component parentComponent;
    private final Supplier<KalixDocument> activeDocumentSupplier;
    private final Consumer<String> statusUpdateCallback;
    private final Consumer<String> addRecentFileCallback;
    private final Runnable fileChangedCallback;
    private final Runnable modelUpdateCallback;
    private final FileWatcherManager fileWatcherManager;

    /**
     * Creates a new FileOperationsManager instance.
     *
     * @param parentComponent The parent component for dialogs
     * @param activeDocumentSupplier Supplies the document file operations act upon
     * @param statusUpdateCallback Callback for status updates
     * @param addRecentFileCallback Callback for adding recent files
     * @param fileChangedCallback Callback when current file changes (load/new/save as)
     * @param modelUpdateCallback Callback to trigger model parsing after file loads
     * @param fileWatcherManager The file watcher manager for coordinating auto-reload
     */
    public FileOperationsManager(Component parentComponent,
                               Supplier<KalixDocument> activeDocumentSupplier,
                               Consumer<String> statusUpdateCallback,
                               Consumer<String> addRecentFileCallback,
                               Runnable fileChangedCallback,
                               Runnable modelUpdateCallback,
                               FileWatcherManager fileWatcherManager) {
        this.parentComponent = parentComponent;
        this.activeDocumentSupplier = activeDocumentSupplier;
        this.statusUpdateCallback = statusUpdateCallback;
        this.addRecentFileCallback = addRecentFileCallback;
        this.fileChangedCallback = fileChangedCallback;
        this.modelUpdateCallback = modelUpdateCallback;
        this.fileWatcherManager = fileWatcherManager;
    }

    /**
     * @return the document that file operations currently act upon
     */
    private KalixDocument document() {
        return activeDocumentSupplier.get();
    }
    
    /**
     * Creates a new model by clearing the text editor and map panel.
     */
    public void newModel() {
        KalixDocument document = document();
        document.setText(AppConstants.DEFAULT_MODEL_TEXT);
        document.getMapPanel().clearModel();
        document.setFile(null); // Clear current file path for new model

        // Clear last opened file preference since user is starting fresh
        PreferenceManager.setOsString(PreferenceKeys.LAST_OPENED_FILE, "");

        statusUpdateCallback.accept(AppConstants.STATUS_NEW_MODEL_CREATED);
        fileChangedCallback.run(); // Notify title bar of file change

        // Trigger model parsing to update the map with the new default content
        modelUpdateCallback.run();
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

            KalixDocument document = document();

            // Set content in text editor
            document.setText(content);

            // Store the current file path for save functionality
            document.setFile(file);

            // Determine file format
            String format = getFileFormat(file.getName());
            
            // Update status
            String statusMessage = String.format("Opened %s model: %s (%s format)", 
                format, file.getName(), format);
            statusUpdateCallback.accept(statusMessage);
            
            // Add to recent files
            addRecentFileCallback.accept(file.getAbsolutePath());

            // Save as last opened file for session restoration
            PreferenceManager.setOsString(PreferenceKeys.LAST_OPENED_FILE, file.getAbsolutePath());

            // Clear the map panel
            document.getMapPanel().clearModel();

            // Notify title bar of file change
            fileChangedCallback.run();
            
            // Trigger model parsing since setText() suppresses document listeners
            // Model update callback will handle zoom-to-fit after parsing is complete
            modelUpdateCallback.run();
            
        } catch (IOException e) {
            showFileOpenError(file, e);
        }
    }
    
    /**
     * Saves the current model to the previously opened file.
     * If no file is currently open, delegates to saveAsModel().
     */
    public void saveModel() {
        KalixDocument document = document();
        File currentFile = document.getFile();
        if (currentFile == null) {
            // No current file, prompt for save as
            saveAsModel();
            return;
        }

        try {
            // Prevent file watcher from reloading the file we're about to save
            fileWatcherManager.ignoreNextChange();

            String content = document.getText();
            Files.writeString(currentFile.toPath(), content);

            // Reset dirty state
            document.setDirty(false);

            // Save as last opened file for session restoration
            PreferenceManager.setOsString(PreferenceKeys.LAST_OPENED_FILE, currentFile.getAbsolutePath());

            String statusMessage = String.format("Saved model: %s", currentFile.getName());
            statusUpdateCallback.accept(statusMessage);

        } catch (IOException e) {
            showFileSaveError(currentFile, e);
        }
    }
    
    /**
     * Shows a save dialog and saves the model to the selected location.
     */
    public void saveAsModel() {
        KalixDocument document = document();
        JFileChooser fileChooser = createFileChooser();
        fileChooser.setDialogTitle("Save Kalix Model");

        // Set default filename if there's a current file
        File currentFile = document.getFile();
        if (currentFile != null) {
            fileChooser.setSelectedFile(currentFile);
        } else {
            fileChooser.setSelectedFile(new File("model.ini"));
        }

        int result = fileChooser.showSaveDialog(parentComponent);
        if (result == JFileChooser.APPROVE_OPTION) {
            File selectedFile = fileChooser.getSelectedFile();

            // Add extension if not present
            String fileName = selectedFile.getName();
            if (!fileName.contains(".")) {
                // Default to .ini extension
                selectedFile = new File(selectedFile.getAbsolutePath() + ".ini");
            }

            try {
                // Prevent file watcher from reloading the file we're about to save
                fileWatcherManager.ignoreNextChange();

                String content = document.getText();
                Files.writeString(selectedFile.toPath(), content);

                // Update current file and reset dirty state
                document.setFile(selectedFile);
                document.setDirty(false);

                // Add to recent files
                addRecentFileCallback.accept(selectedFile.getAbsolutePath());

                // Save as last opened file for session restoration
                PreferenceManager.setOsString(PreferenceKeys.LAST_OPENED_FILE, selectedFile.getAbsolutePath());

                String statusMessage = String.format("Saved model as: %s", selectedFile.getName());
                statusUpdateCallback.accept(statusMessage);

                // Notify title bar of file change
                fileChangedCallback.run();

            } catch (IOException e) {
                showFileSaveError(selectedFile, e);
            }
        }
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

        // Set initial directory to current file's directory if available
        File currentFile = document().getFile();
        if (currentFile != null) {
            fileChooser.setCurrentDirectory(currentFile.getParentFile());
        }

        // Set file filters for supported model formats
        FileNameExtensionFilter allModelsFilter = new FileNameExtensionFilter(
            AppConstants.MODEL_FILES_DESCRIPTION, "ini");
        FileNameExtensionFilter iniFilter = new FileNameExtensionFilter(
            AppConstants.INI_FILES_DESCRIPTION, "ini");

        fileChooser.setFileFilter(allModelsFilter);
        fileChooser.addChoosableFileFilter(iniFilter);

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
    
    /**
     * Shows an error dialog for file saving failures.
     * 
     * @param file The file that failed to save
     * @param e The exception that occurred
     */
    private void showFileSaveError(File file, IOException e) {
        JOptionPane.showMessageDialog(
            parentComponent,
            "Failed to save file: " + e.getMessage(),
            "Save Error",
            JOptionPane.ERROR_MESSAGE
        );
        statusUpdateCallback.accept("Failed to save: " + file.getName());
    }
    
    /**
     * Gets the currently loaded file, if any.
     * 
     * @return The current file or null if no file is loaded
     */
    public File getCurrentFile() {
        return document().getFile();
    }

    /**
     * Gets the directory of the current file as the working directory.
     * This is used to set the working directory for KalixCLI processes
     * so that relative paths in model files are resolved correctly.
     *
     * @return The directory of the current file, or null if no file is loaded
     */
    public File getCurrentWorkingDirectory() {
        return document().getWorkingDirectory();
    }
}