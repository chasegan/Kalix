package com.kalix.ide.linter.managers;

import com.kalix.ide.linter.*;
import com.kalix.ide.linter.model.ValidationResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;

/**
 * Orchestrates the validation process by coordinating between different components.
 * Handles the actual validation execution and result processing.
 */
public class LinterOrchestrator {

    private static final Logger logger = LoggerFactory.getLogger(LinterOrchestrator.class);

    private final SchemaManager schemaManager;
    private final ModelLinter linter;
    private final ScheduledExecutorService scheduler;

    private ValidationResult currentValidationResult;
    private boolean validationEnabled = true;
    private volatile long lastValidationTimeMs = 0;

    // Callback interface for validation completion
    public interface ValidationResultHandler {
        void onValidationCompleted(ValidationResult result);
    }

    private ValidationResultHandler resultHandler;

    public LinterOrchestrator(SchemaManager schemaManager) {
        this.schemaManager = schemaManager;
        this.linter = new ModelLinter(schemaManager);
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "LinterManager-Validation");
            t.setDaemon(true);
            return t;
        });
    }

    public void setValidationResultHandler(ValidationResultHandler handler) {
        this.resultHandler = handler;
    }

    /**
     * Perform validation on the given content with a base directory for resolving relative paths.
     * Always performs full validation for maximum accuracy and simplicity.
     *
     * @param content The model content to validate
     * @param baseDirectory The base directory for resolving relative file paths (null to use current directory)
     */
    public void performValidation(String content, java.io.File baseDirectory) {
        if (!validationEnabled || !schemaManager.isLintingEnabled()) {
            return;
        }

        // Perform validation in background
        scheduler.execute(() -> {
            try {
                // Capture timing
                long startTime = System.nanoTime();
                ValidationResult result = linter.validate(content, baseDirectory);
                long endTime = System.nanoTime();

                lastValidationTimeMs = (endTime - startTime) / 1_000_000; // Convert to milliseconds
                currentValidationResult = result;

                // Notify handler on EDT
                if (resultHandler != null) {
                    SwingUtilities.invokeLater(() -> resultHandler.onValidationCompleted(result));
                }

            } catch (Exception e) {
                logger.error("Error during validation", e);
            }
        });
    }

    /**
     * Clear all validation state.
     */
    public void clearValidation() {
        currentValidationResult = new ValidationResult();

        if (resultHandler != null) {
            SwingUtilities.invokeLater(() -> resultHandler.onValidationCompleted(currentValidationResult));
        }
    }

    /**
     * Get current validation result.
     */
    public ValidationResult getCurrentValidationResult() {
        return currentValidationResult;
    }


    /**
     * Enable or disable validation.
     */
    public void setValidationEnabled(boolean enabled) {
        this.validationEnabled = enabled;
        if (!enabled) {
            clearValidation();
        }
    }

    /**
     * Get the last validation time in milliseconds.
     * Returns 0 if no validation has been performed yet.
     */
    public long getLastValidationTimeMs() {
        return lastValidationTimeMs;
    }

    /**
     * Clean up resources.
     */
    public void dispose() {
        scheduler.shutdown();
    }
}