package com.kalix.ide.managers.optimisation;

import com.kalix.ide.components.KalixIniTextArea;
import com.kalix.ide.windows.optimisation.OptimisationGuiBuilder;
import com.kalix.ide.windows.optimisation.OptimisationUIConstants;
import com.kalix.ide.models.optimisation.OptimisationConfigModel;
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

    private final KalixIniTextArea configEditor;
    private final OptimisationGuiBuilder guiBuilder;
    private final RTextScrollPane configScrollPane;

    private Supplier<File> workingDirectorySupplier;
    private Consumer<String> statusUpdater;
    private Consumer<String> configStatusCallback;
    private Runnable onIniManuallyEdited;
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
        this.configEditor = new KalixIniTextArea(
            OptimisationUIConstants.CONFIG_TEXT_AREA_ROWS,
            OptimisationUIConstants.CONFIG_TEXT_AREA_COLUMNS
        );

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
     * Sets the callback invoked when the user edits the INI text directly (typing,
     * pasting, or loading a config file). This is the trigger for locking the GUI
     * form for the current optimisation.
     *
     * @param callback the callback to invoke on a genuine INI edit
     */
    public void setOnIniManuallyEditedCallback(Runnable callback) {
        this.onIniManuallyEdited = callback;
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
     *
     * <p>A change while {@code isUpdatingEditor} is false is a genuine user edit
     * (typing or pasting) as opposed to a programmatic update. Such an edit marks
     * the configuration as modified and triggers the INI lock for the current
     * optimisation.</p>
     */
    private void onConfigTextChanged() {
        if (!isUpdatingEditor) {
            isConfigModified = true;
            if (configStatusCallback != null) {
                configStatusCallback.accept(OptimisationUIConstants.CONFIG_STATUS_MODIFIED);
            }
            if (onIniManuallyEdited != null) {
                onIniManuallyEdited.run();
            }
            logger.debug("Configuration marked as modified by direct INI edit");
        }
    }

    /**
     * Loads a configuration from the given optimisation info.
     *
     * <p>Restores both per-node config representations: the INI editor from the
     * config snapshot, and the GUI form from the structured config model.</p>
     *
     * @param info The optimisation info containing the config
     */
    public void loadConfiguration(OptimisationInfo info) {
        isUpdatingEditor = true;
        String iniText = resolveIniText(info);
        configEditor.setText(iniText);
        lastLoadedConfig = iniText;
        isConfigModified = false;
        if (configStatusCallback != null) {
            configStatusCallback.accept(OptimisationUIConstants.CONFIG_STATUS_ORIGINAL);
        }
        isUpdatingEditor = false;

        // Restore the GUI form from this node's structured model. A null model
        // (e.g. an optimisation created before this field existed) leaves the
        // form untouched.
        if (info != null) {
            guiBuilder.loadFromModel(info.getConfigModel());
        }

        // Update editability based on status
        boolean isEditable = info == null || !info.hasStartedRunning();
        configEditor.setEditable(isEditable);
    }

    /**
     * Determines the INI text to display for an optimisation.
     *
     * <p>For a locked optimisation the stored config snapshot is canonical. For an
     * unlocked optimisation the INI is a derived view, regenerated from the GUI
     * config model so it always reflects the form.</p>
     *
     * @param info the optimisation, or null
     * @return the INI text to show in the editor
     */
    private String resolveIniText(OptimisationInfo info) {
        if (info == null) {
            return "";
        }
        if (!info.isIniLocked() && info.getConfigModel() != null) {
            return guiBuilder.generateConfigText(info.getConfigModel());
        }
        return info.getConfigSnapshot() != null ? info.getConfigSnapshot() : "";
    }

    /**
     * Regenerates the INI editor text from the current GUI form state, keeping the
     * Config INI tab in sync for an unlocked optimisation. The update is
     * programmatic and does not trigger the INI lock.
     */
    public void regenerateIniFromGui() {
        setConfiguration(guiBuilder.generateConfigText(guiBuilder.captureToModel()));
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
                    configStatusCallback.accept(OptimisationUIConstants.CONFIG_STATUS_ORIGINAL);
                }
                // Loading external INI is direct INI configuration: lock the form,
                // since the file may contain settings the GUI cannot represent.
                if (onIniManuallyEdited != null) {
                    onIniManuallyEdited.run();
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
     * Requires:
     * <ul>
     *   <li>[optimisation] section</li>
     *   <li>[parameters] section</li>
     *   <li>At least one [term.NAME] section</li>
     *   <li>An {@code objective_expression} key (inside [optimisation])</li>
     * </ul>
     *
     * @return true if valid, false otherwise
     */
    public boolean validateConfiguration() {
        String config = getCurrentConfig();

        if (config == null || config.trim().isEmpty()) {
            return false;
        }

        String configLower = config.toLowerCase();
        boolean hasOptimisation = configLower.contains("[optimisation]");
        boolean hasParameters = configLower.contains("[parameters]");
        boolean hasTerm = configLower.contains("[term.");
        boolean hasObjectiveExpression = configLower.contains("objective_expression");

        if (!hasOptimisation || !hasParameters) {
            logger.warn("Configuration missing required sections [optimisation] or [parameters]");
            return false;
        }
        if (!hasTerm) {
            logger.warn("Configuration missing required [term.NAME] section (need at least one)");
            return false;
        }
        if (!hasObjectiveExpression) {
            logger.warn("Configuration missing required objective_expression in [optimisation]");
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
     * <p>Captures both per-node representations: the GUI form into the structured
     * config model, and the INI editor text into the config snapshot.</p>
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

        String configText;
        if (optInfo.isIniLocked()) {
            // INI text is canonical — save the editor content verbatim.
            configText = configEditor.getText();
            optInfo.setConfigSnapshot(configText);
        } else {
            // GUI form is canonical — capture it and regenerate the INI from it.
            OptimisationConfigModel model = guiBuilder.captureToModel();
            optInfo.setConfigModel(model);
            configText = guiBuilder.generateConfigText(model);
            optInfo.setConfigSnapshot(configText);
        }

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