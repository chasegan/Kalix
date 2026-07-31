package com.kalix.ide.managers.optimisation;

import com.kalix.ide.document.DocumentLabels;
import com.kalix.ide.document.OpenModel;
import com.kalix.ide.document.ModelWriteBack;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.JComponent;
import javax.swing.JOptionPane;
import java.util.function.Consumer;

/**
 * Manages model-related operations for optimisation.
 * Handles copying optimised models back to the model they were optimised from.
 */
public class OptimisationModelManager {

    private static final Logger logger = LoggerFactory.getLogger(OptimisationModelManager.class);

    private final ModelWriteBack modelWriteBack;
    private Consumer<String> statusUpdater;

    /**
     * Creates a new OptimisationModelManager.
     *
     * @param modelWriteBack Writes the optimised text back into a specific open model
     */
    public OptimisationModelManager(ModelWriteBack modelWriteBack) {
        this.modelWriteBack = modelWriteBack;
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
     * Writes the optimised model back into the model it was optimised from.
     *
     * <p>The destination is the optimisation's recorded target, not the active tab.
     * Previously this wrote to whichever document happened to be in front, so
     * optimising model A and then switching to model B silently overwrote B.</p>
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

        OpenModel target = optInfo.getTargetModel();
        String targetLabel = DocumentLabels.labelForClosed(optInfo.getTargetFile());
        if (target == null) {
            JOptionPane.showMessageDialog(parent,
                "This optimisation has no recorded target model, so there is nowhere to copy it back to.\n"
                    + "Use \"Save Results\" to write the optimised model to a file instead.",
                "No Target Model",
                JOptionPane.WARNING_MESSAGE);
            return;
        }

        // Name the destination: the whole point is that it may not be the tab in front.
        int response = JOptionPane.showConfirmDialog(parent,
            "This will replace the contents of '" + targetLabel + "' with the optimised model.\n"
                + "Are you sure you want to continue?",
            "Replace " + targetLabel,
            JOptionPane.YES_NO_OPTION,
            JOptionPane.WARNING_MESSAGE);

        if (response != JOptionPane.YES_OPTION) {
            return;
        }

        // The target may have been closed while the optimisation ran — an ordinary
        // outcome, so say so plainly rather than silently retargeting another tab.
        boolean written = modelWriteBack != null && modelWriteBack.writeTo(target, optimisedModel);
        if (!written) {
            JOptionPane.showMessageDialog(parent,
                "'" + targetLabel + "' is not open, so the optimised model was not copied.\n"
                    + "Open it and try again, or use \"Save Results\" to write it to a file.",
                "Model Not Open",
                JOptionPane.WARNING_MESSAGE);
            logger.info("Copy-back skipped: target model '{}' is no longer open", targetLabel);
            return;
        }

        if (statusUpdater != null) {
            statusUpdater.accept("Optimised model copied to " + targetLabel);
        }
        logger.info("Copied optimised model into '{}'", targetLabel);
    }
}