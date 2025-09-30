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
    private final IncrementalValidator incrementalValidator;
    private final ScheduledExecutorService scheduler;

    private ValidationResult currentValidationResult;
    private boolean validationEnabled = true;

    // Callback interface for validation completion
    public interface ValidationResultHandler {
        void onValidationCompleted(ValidationResult result);
    }

    private ValidationResultHandler resultHandler;

    public LinterOrchestrator(SchemaManager schemaManager) {
        this.schemaManager = schemaManager;
        this.linter = new ModelLinter(schemaManager);
        this.incrementalValidator = new IncrementalValidator(linter);
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
     * Perform validation on the given content.
     */
    public void performValidation(String content) {
        if (!validationEnabled || !schemaManager.isLintingEnabled()) {
            return;
        }

        // Perform validation in background
        scheduler.execute(() -> {
            try {
                ValidationResult result = incrementalValidator.validateIncremental(content);
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
        incrementalValidator.clearCache();

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
     * Check if validation is enabled.
     */
    public boolean isValidationEnabled() {
        return validationEnabled;
    }

    /**
     * Check if there are any validation results.
     */
    public boolean hasValidationResults() {
        return currentValidationResult != null && !currentValidationResult.isEmpty();
    }

    /**
     * Check if there are any validation errors.
     */
    public boolean hasValidationErrors() {
        return currentValidationResult != null && currentValidationResult.hasErrors();
    }

    /**
     * Clean up resources.
     */
    public void dispose() {
        scheduler.shutdown();
    }
}