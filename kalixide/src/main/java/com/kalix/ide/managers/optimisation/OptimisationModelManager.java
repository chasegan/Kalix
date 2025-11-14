package com.kalix.ide.managers.optimisation;

import com.kalix.ide.models.optimisation.OptimisationInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.JComponent;
import javax.swing.JOptionPane;
import java.util.function.Consumer;

/**
 * Manages model-related operations for optimisation.
 * Handles copying optimised models to the main editor.
 */
public class OptimisationModelManager {

    private static final Logger logger = LoggerFactory.getLogger(OptimisationModelManager.class);

    private final Consumer<String> modelTextSetter;
    private Consumer<String> statusUpdater;

    /**
     * Creates a new OptimisationModelManager.
     *
     * @param modelTextSetter Consumer to set the model text in the main editor
     */
    public OptimisationModelManager(Consumer<String> modelTextSetter) {
        this.modelTextSetter = modelTextSetter;
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
     * Copies the optimised model to the main editor.
     *
     * @param optInfo The optimisation info
     * @param parent The parent component for dialogs
     */
    public void copyOptimisedModelToMain(OptimisationInfo optInfo, JComponent parent) {
        if (optInfo == null || optInfo.getResult() == null) {
            JOptionPane.showMessageDialog(parent,
                "No optimisation result available",
                "Error",
                JOptionPane.WARNING_MESSAGE);
            return;
        }

        String optimisedModel = optInfo.getResult().getOptimisedModelIni();
        if (optimisedModel == null || optimisedModel.isEmpty()) {
            JOptionPane.showMessageDialog(parent,
                "No optimised model found",
                "Error",
                JOptionPane.WARNING_MESSAGE);
            return;
        }

        // Confirm with user
        int response = JOptionPane.showConfirmDialog(parent,
            "This will replace the current model in the main editor.\nAre you sure you want to continue?",
            "Replace Current Model",
            JOptionPane.YES_NO_OPTION,
            JOptionPane.WARNING_MESSAGE);

        if (response == JOptionPane.YES_OPTION) {
            // Set the model text in the main editor
            modelTextSetter.accept(optimisedModel);

            if (statusUpdater != null) {
                statusUpdater.accept("Optimised model copied to main editor");
            }
            logger.info("Copied optimised model to main editor");
        }
    }
}