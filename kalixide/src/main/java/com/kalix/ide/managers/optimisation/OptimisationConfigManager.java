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
    private final RTextScrollPane configScrollPane;

    private Supplier<File> workingDirectorySupplier;
    private Consumer<String> statusUpdater;
    private Consumer<String> configStatusCallback;
    private boolean isUpdatingEditor = false;

    // Configuration state tracking
    private boolean isConfigModified = false;
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
     * Sets the callback for config status changes.
     *
     * @param callback The callback to invoke when config status changes
     */
    public void setConfigStatusCallback(Consumer<String> callback) {
        this.configStatusCallback = callback;
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
            isConfigModified = true;
            if (configStatusCallback != null) {
                configStatusCallback.accept(CONFIG_STATUS_MODIFIED);
            }
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
        isConfigModified = false;
        if (configStatusCallback != null) {
            configStatusCallback.accept(CONFIG_STATUS_ORIGINAL);
        }
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
                isConfigModified = false;
                if (configStatusCallback != null) {
                    configStatusCallback.accept(CONFIG_STATUS_ORIGINAL);
                }

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

        // Check for required sections - case-insensitive since backend accepts both
        String configLower = config.toLowerCase();
        boolean hasOptimisation = configLower.contains("[optimisation]");
        boolean hasParameters = configLower.contains("[parameters]");

        if (!hasOptimisation || !hasParameters) {
            logger.warn("Configuration missing required sections [optimisation] or [parameters]");
            return false;
        }

        return true;
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
     * Saves the current configuration to an OptimisationInfo object.
     * Only saves if the optimization hasn't started running yet.
     *
     * @param optInfo The OptimisationInfo to save config to
     * @param sessionKey The session key
     * @param sessionManager The session manager to update config in
     */
    public void saveCurrentConfigToOptimisation(OptimisationInfo optInfo, String sessionKey,
                                                OptimisationSessionManager sessionManager) {
        if (optInfo == null || optInfo.hasStartedRunning()) {
            return;
        }

        // Save the config text from the editor (it's the source of truth)
        String configText = configEditor.getText();
        optInfo.setConfigSnapshot(configText);

        // Also update the stored config in session manager
        if (sessionManager != null && sessionKey != null) {
            sessionManager.updateOptimisationConfig(sessionKey, configText);
        }
    }

    /**
     * Parses the [outputs] section from model text and returns a list of output series names.
     *
     * @param modelText The INI model text
     * @return List of output series names (one per line in [outputs] section)
     */
    public java.util.List<String> parseOutputsSection(String modelText) {
        java.util.List<String> outputs = new java.util.ArrayList<>();

        String[] lines = modelText.split("\\r?\\n");
        boolean inOutputsSection = false;

        for (String line : lines) {
            String trimmedLine = line.trim();

            // Check if we're entering the [outputs] section
            if (trimmedLine.equalsIgnoreCase("[outputs]")) {
                inOutputsSection = true;
                continue;
            }

            // Check if we're entering a new section (leaving [outputs])
            if (trimmedLine.startsWith("[") && trimmedLine.endsWith("]") && !trimmedLine.equalsIgnoreCase("[outputs]")) {
                inOutputsSection = false;
                continue;
            }

            // If we're in the outputs section and the line is not empty or a comment
            if (inOutputsSection && !trimmedLine.isEmpty() && !trimmedLine.startsWith("#") && !trimmedLine.startsWith(";")) {
                outputs.add(trimmedLine);
            }
        }

        return outputs;
    }

    /**
     * Updates the simulated series combo box options from the current model's [outputs] section.
     *
     * @param modelTextSupplier Supplier for the current model text
     */
    public void updateSimulatedSeriesOptionsFromModel(Supplier<String> modelTextSupplier) {
        if (modelTextSupplier == null || guiBuilder == null) {
            return;
        }

        String modelText = modelTextSupplier.get();
        if (modelText == null || modelText.isEmpty()) {
            return;
        }

        java.util.List<String> outputSeries = parseOutputsSection(modelText);
        guiBuilder.updateSimulatedSeriesOptions(outputSeries);
    }
}