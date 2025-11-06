package com.kalix.ide.managers.optimisation;

import com.kalix.ide.components.KalixIniTextArea;
import com.kalix.ide.windows.optimisation.OptimisationGuiBuilder;
import com.kalix.ide.models.optimisation.OptimisationInfo;
import org.fife.ui.rtextarea.RTextScrollPane;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Manages optimisation configuration editing, validation, and persistence.
 * Handles both GUI-based and text-based configuration editing.
 */
public class OptimisationConfigManager {

    private static final Logger logger = LoggerFactory.getLogger(OptimisationConfigManager.class);

    // Configuration constants
    private static final int TEXT_AREA_ROWS = 20;
    private static final int TEXT_AREA_COLUMNS = 80;
    public static final String CONFIG_STATUS_ORIGINAL = "Original";
    public static final String CONFIG_STATUS_MODIFIED = "Modified";

    private final KalixIniTextArea configEditor;
    private final OptimisationGuiBuilder guiBuilder;
    private final JLabel configStatusLabel;
    private final RTextScrollPane configScrollPane;

    private Supplier<File> workingDirectorySupplier;
    private Consumer<String> statusUpdater;
    private boolean isUpdatingEditor = false;
    private String lastLoadedConfig = "";

    /**
     * Creates a new OptimisationConfigManager.
     *
     * @param workingDirectorySupplier Supplier for the working directory
     * @param modelTextSupplier Supplier for the current model text
     */
    public OptimisationConfigManager(Supplier<File> workingDirectorySupplier,
                                    Supplier<String> modelTextSupplier) {
        this.workingDirectorySupplier = workingDirectorySupplier;

        // Initialize text editor first
        this.configEditor = new KalixIniTextArea(TEXT_AREA_ROWS, TEXT_AREA_COLUMNS);

        // Initialize GUI builder with config text consumer
        this.guiBuilder = new OptimisationGuiBuilder(
            configText -> {
                isUpdatingEditor = true;
                configEditor.setText(configText);
                isUpdatingEditor = false;
            },
            workingDirectorySupplier
        );

        // Continue with initialization
        this.configEditor.setText("");

        // Initialize status label
        this.configStatusLabel = new JLabel(CONFIG_STATUS_ORIGINAL);

        // Create scroll pane
        this.configScrollPane = new RTextScrollPane(configEditor);

        // Setup change listener
        setupDocumentListener();
    }

    /**
     * Gets the configuration editor component.
     *
     * @return The text editor component
     */
    public KalixIniTextArea getConfigEditor() {
        return configEditor;
    }

    /**
     * Gets the scroll pane containing the editor.
     *
     * @return The scroll pane
     */
    public RTextScrollPane getConfigScrollPane() {
        return configScrollPane;
    }

    /**
     * Gets the GUI builder for configuration.
     *
     * @return The GUI builder
     */
    public OptimisationGuiBuilder getGuiBuilder() {
        return guiBuilder;
    }

    /**
     * Gets the status label component.
     *
     * @return The status label
     */
    public JLabel getConfigStatusLabel() {
        return configStatusLabel;
    }

    /**
     * Sets up the document listener for tracking modifications.
     */
    private void setupDocumentListener() {
        configEditor.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                onConfigTextChanged();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                onConfigTextChanged();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                onConfigTextChanged();
            }
        });
    }

    /**
     * Handles configuration text changes.
     */
    private void onConfigTextChanged() {
        if (!isUpdatingEditor) {
            configStatusLabel.setText(CONFIG_STATUS_MODIFIED);
            logger.debug("Configuration marked as modified");
        }
    }

    /**
     * Loads a configuration from the given optimisation info.
     *
     * @param info The optimisation info containing the config
     */
    public void loadConfiguration(OptimisationInfo info) {
        isUpdatingEditor = true;
        if (info != null && info.getConfigSnapshot() != null) {
            configEditor.setText(info.getConfigSnapshot());
            lastLoadedConfig = info.getConfigSnapshot();
        } else {
            configEditor.setText("");
            lastLoadedConfig = "";
        }
        configStatusLabel.setText(CONFIG_STATUS_ORIGINAL);
        isUpdatingEditor = false;

        // Update editability based on status
        boolean isEditable = info == null || !info.hasStartedRunning();
        configEditor.setEditable(isEditable);
    }

    /**
     * Loads configuration from a file.
     *
     * @param parent The parent component for dialogs
     */
    public void loadConfigFromFile(JComponent parent) {
        JFileChooser fileChooser = new JFileChooser();
        fileChooser.setDialogTitle("Load Optimisation Configuration");

        // Set initial directory
        if (workingDirectorySupplier != null) {
            File workingDir = workingDirectorySupplier.get();
            if (workingDir != null) {
                fileChooser.setCurrentDirectory(workingDir);
            }
        }

        // Set file filter for INI files
        FileNameExtensionFilter filter =
            new FileNameExtensionFilter("INI Files (*.ini)", "ini");
        fileChooser.setFileFilter(filter);

        int result = fileChooser.showOpenDialog(parent);
        if (result == JFileChooser.APPROVE_OPTION) {
            File selectedFile = fileChooser.getSelectedFile();
            try {
                String content = Files.readString(selectedFile.toPath());
                setConfiguration(content);
                configStatusLabel.setText(CONFIG_STATUS_ORIGINAL);

                if (statusUpdater != null) {
                    statusUpdater.accept("Configuration loaded from " + selectedFile.getName());
                }
                logger.info("Loaded configuration from {}", selectedFile.getAbsolutePath());
            } catch (IOException ex) {
                JOptionPane.showMessageDialog(parent,
                    "Failed to load configuration: " + ex.getMessage(),
                    "Load Error",
                    JOptionPane.ERROR_MESSAGE);
                logger.error("Failed to load configuration from {}", selectedFile, ex);
            }
        }
    }

    /**
     * Saves the current configuration to a file.
     *
     * @param parent The parent component for dialogs
     * @param configToSave The configuration text to save
     */
    public void saveConfigToFile(JComponent parent, String configToSave) {
        // Check if config is empty
        if (configToSave == null || configToSave.trim().isEmpty()) {
            JOptionPane.showMessageDialog(parent,
                "Configuration is empty. Nothing to save.",
                "Empty Configuration",
                JOptionPane.WARNING_MESSAGE);
            return;
        }

        JFileChooser fileChooser = new JFileChooser();
        fileChooser.setDialogTitle("Save Optimisation Configuration");

        // Set initial directory
        if (workingDirectorySupplier != null) {
            File workingDir = workingDirectorySupplier.get();
            if (workingDir != null) {
                fileChooser.setCurrentDirectory(workingDir);
            }
        }

        // Set file filter for INI files
        FileNameExtensionFilter filter =
            new FileNameExtensionFilter("INI Files (*.ini)", "ini");
        fileChooser.setFileFilter(filter);

        // Suggest a default filename
        fileChooser.setSelectedFile(new File("optimisation_config.ini"));

        int result = fileChooser.showSaveDialog(parent);
        if (result == JFileChooser.APPROVE_OPTION) {
            File selectedFile = fileChooser.getSelectedFile();

            // Ensure .ini extension
            if (!selectedFile.getName().toLowerCase().endsWith(".ini")) {
                selectedFile = new File(selectedFile.getAbsolutePath() + ".ini");
            }

            // Check if file exists
            if (selectedFile.exists()) {
                int confirm = JOptionPane.showConfirmDialog(parent,
                    "File already exists. Overwrite?",
                    "Confirm Overwrite",
                    JOptionPane.YES_NO_OPTION);
                if (confirm != JOptionPane.YES_OPTION) {
                    return;
                }
            }

            try {
                Files.writeString(selectedFile.toPath(), configToSave);
                if (statusUpdater != null) {
                    statusUpdater.accept("Configuration saved to " + selectedFile.getName());
                }
                logger.info("Saved configuration to {}", selectedFile.getAbsolutePath());
            } catch (IOException ex) {
                JOptionPane.showMessageDialog(parent,
                    "Failed to save configuration: " + ex.getMessage(),
                    "Save Error",
                    JOptionPane.ERROR_MESSAGE);
                logger.error("Failed to save configuration to {}", selectedFile, ex);
            }
        }
    }

    /**
     * Gets the current configuration text.
     *
     * @return The configuration text
     */
    public String getCurrentConfig() {
        return configEditor.getText();
    }

    /**
     * Sets the configuration text.
     *
     * @param config The configuration text
     */
    public void setConfiguration(String config) {
        isUpdatingEditor = true;
        configEditor.setText(config);
        lastLoadedConfig = config;
        isUpdatingEditor = false;
    }

    /**
     * Generates configuration from the GUI builder.
     *
     * @return The generated configuration text
     */
    public String generateConfigFromGui() {
        return guiBuilder.generateConfigText();
    }

    /**
     * Updates the GUI builder from the text configuration.
     *
     * @param modelText The model text for context
     */
    public void updateGuiFromText(String modelText) {
        // Note: GUI builder currently doesn't support reverse parsing from text
        // This would require implementing a config parser in OptimisationGuiBuilder
        // For now, GUI must be manually configured
        logger.debug("GUI update from text not yet implemented");
    }

    /**
     * Validates the current configuration.
     *
     * @return true if valid, false otherwise
     */
    public boolean validateConfiguration() {
        String config = getCurrentConfig();

        // Basic validation - check not empty
        if (config == null || config.trim().isEmpty()) {
            return false;
        }

        // Check for required sections - these are the actual config sections
        boolean hasGeneral = config.contains("[General]");
        boolean hasAlgorithm = config.contains("[Algorithm]");
        boolean hasParameters = config.contains("[Parameters]");

        if (!hasGeneral || !hasAlgorithm || !hasParameters) {
            logger.warn("Configuration missing required sections [General], [Algorithm], or [Parameters]");
            return false;
        }

        return true;
    }

    /**
     * Checks if the configuration has been modified.
     *
     * @return true if modified, false otherwise
     */
    public boolean isConfigModified() {
        return CONFIG_STATUS_MODIFIED.equals(configStatusLabel.getText());
    }

    /**
     * Sets the status updater callback.
     *
     * @param statusUpdater The status updater
     */
    public void setStatusUpdater(Consumer<String> statusUpdater) {
        this.statusUpdater = statusUpdater;
    }

    /**
     * Sets the working directory supplier.
     *
     * @param supplier The directory supplier
     */
    public void setWorkingDirectorySupplier(Supplier<File> supplier) {
        this.workingDirectorySupplier = supplier;
    }

    /**
     * Gets whether the configuration editor is currently being updated programmatically.
     *
     * @return true if updating, false otherwise
     */
    public boolean isUpdatingEditor() {
        return isUpdatingEditor;
    }
}