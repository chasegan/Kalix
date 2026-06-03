package com.kalix.ide.managers;

import com.kalix.ide.constants.AppConstants;
import com.kalix.ide.document.DocumentManager;
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
 *
 * <p>Opening and "new" create documents through a factory and make them active, so each
 * file lives in its own tab; if a file is already open it is simply focused. Saving acts
 * on the active document. The backing file lives on the {@link KalixDocument}, not on this
 * manager, so the same manager serves whichever document is active.
 */
public class FileOperationsManager {

    private final Component parentComponent;
    private final DocumentManager documentManager;
    private final Supplier<KalixDocument> documentFactory;
    private final Consumer<String> statusUpdateCallback;
    private final Consumer<String> addRecentFileCallback;
    private final Runnable fileChangedCallback;
    private final FileWatcherManager fileWatcherManager;

    /**
     * Creates a new FileOperationsManager instance.
     *
     * @param parentComponent The parent component for dialogs
     * @param documentManager The document set / active-document owner
     * @param documentFactory Creates and registers a fresh (configured) document
     * @param statusUpdateCallback Callback for status updates
     * @param addRecentFileCallback Callback for adding recent files
     * @param fileChangedCallback Callback when the active document's file identity changes
     *                            without an active-document switch (e.g. Save As, reload)
     * @param fileWatcherManager The file watcher manager for coordinating auto-reload
     */
    public FileOperationsManager(Component parentComponent,
                               DocumentManager documentManager,
                               Supplier<KalixDocument> documentFactory,
                               Consumer<String> statusUpdateCallback,
                               Consumer<String> addRecentFileCallback,
                               Runnable fileChangedCallback,
                               FileWatcherManager fileWatcherManager) {
        this.parentComponent = parentComponent;
        this.documentManager = documentManager;
        this.documentFactory = documentFactory;
        this.statusUpdateCallback = statusUpdateCallback;
        this.addRecentFileCallback = addRecentFileCallback;
        this.fileChangedCallback = fileChangedCallback;
        this.fileWatcherManager = fileWatcherManager;
    }

    /**
     * @return the active document that save operations act upon
     */
    private KalixDocument document() {
        return documentManager.getActiveDocument();
    }

    /**
     * Creates a new untitled document in its own tab and makes it active.
     */
    public void newModel() {
        KalixDocument document = documentFactory.get();
        document.setText(AppConstants.DEFAULT_MODEL_TEXT);
        document.setFile(null);

        // Clear last opened file preference since user is starting fresh
        PreferenceManager.setOsString(PreferenceKeys.LAST_OPENED_FILE, "");

        documentManager.setActiveDocument(document);
        document.parseModelFromText(true);
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
     * Opens a model file in a new tab, or focuses its existing tab if already open.
     *
     * @param file The file to load
     */
    public void loadModelFile(File file) {
        // If the file is already open, just focus its tab.
        KalixDocument existing = documentManager.findByFile(file);
        if (existing != null) {
            documentManager.setActiveDocument(existing);
            statusUpdateCallback.accept("Switched to: " + file.getName());
            return;
        }

        final String content;
        try {
            content = Files.readString(file.toPath());
        } catch (IOException e) {
            showFileOpenError(file, e);
            return;
        }

        // Create the document only after a successful read, so a failed open leaves no
        // empty tab behind.
        KalixDocument document = documentFactory.get();
        document.setText(content);
        document.setFile(file);

        // Add to recent files and remember as last opened for session restoration.
        addRecentFileCallback.accept(file.getAbsolutePath());
        PreferenceManager.setOsString(PreferenceKeys.LAST_OPENED_FILE, file.getAbsolutePath());

        documentManager.setActiveDocument(document);
        document.parseModelFromText(true);

        String format = getFileFormat(file.getName());
        statusUpdateCallback.accept(String.format("Opened %s model: %s (%s format)",
            format, file.getName(), format));
    }

    /**
     * Reloads, in place, the content of the open document backed by the given file.
     * Does nothing if no open document is backed by that exact file — it must never
     * write one file's content into a different document. Used for external-change reload.
     *
     * @param file The file whose content should be re-read
     */
    public void reloadFile(File file) {
        KalixDocument document = documentManager.findByFile(file);
        if (document == null) {
            return; // no open document backs this file; nothing to reload
        }
        try {
            String content = Files.readString(file.toPath());
            document.setText(content); // setText resets dirty state
            document.parseModelFromText(true);
            statusUpdateCallback.accept("File reloaded: " + file.getName());
        } catch (IOException e) {
            statusUpdateCallback.accept("Failed to reload file: " + file.getName());
        }
    }
    
    /**
     * Saves the current model to the previously opened file.
     * If no file is currently open, delegates to saveAsModel().
     */
    public void saveModel() {
        KalixDocument document = document();
        if (document == null) {
            return;
        }
        File currentFile = document.getFile();
        if (currentFile == null) {
            // No current file, prompt for save as
            saveAsModel();
            return;
        }

        try {
            // Prevent file watcher from reloading the file we're about to save
            fileWatcherManager.ignoreNextChange(currentFile);

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
        if (document == null) {
            return;
        }
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

            // Refuse to save onto a file already open in a different tab — that would
            // leave two documents backed by the same file (ambiguous for findByFile,
            // the watcher, and session save/restore).
            KalixDocument other = documentManager.findByFile(selectedFile);
            if (other != null && other != document) {
                JOptionPane.showMessageDialog(parentComponent,
                    "\"" + selectedFile.getName() + "\" is already open in another tab.\n\n"
                        + "Close that tab first, or choose a different name.",
                    "File Already Open", JOptionPane.WARNING_MESSAGE);
                return;
            }

            try {
                // Prevent file watcher from reloading the file we're about to save
                fileWatcherManager.ignoreNextChange(selectedFile);

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
        File currentFile = getCurrentFile();
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
        KalixDocument document = document();
        return document != null ? document.getFile() : null;
    }

    /**
     * Gets the directory of the current file as the working directory.
     * This is used to set the working directory for KalixCLI processes
     * so that relative paths in model files are resolved correctly.
     *
     * <p>Returns {@code null} when there is no active document — these accessors are
     * exposed as long-lived suppliers to background windows (RunManager, the CLI task
     * manager) that may query them during the transient no-active-document window.
     *
     * @return The directory of the current file, or null if none
     */
    public File getCurrentWorkingDirectory() {
        KalixDocument document = document();
        return document != null ? document.getWorkingDirectory() : null;
    }
}