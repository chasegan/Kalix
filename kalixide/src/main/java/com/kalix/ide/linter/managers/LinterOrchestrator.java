package com.kalix.ide.linter.managers;

import com.kalix.ide.linter.ModelLinter;
import com.kalix.ide.linter.SchemaManager;
import com.kalix.ide.linter.model.ValidationResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.SwingUtilities;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Orchestrates the validation process by coordinating between different components.
 * Handles the actual validation execution and result processing.
 */
public class LinterOrchestrator {

    private static final Logger logger = LoggerFactory.getLogger(LinterOrchestrator.class);

    private final SchemaManager schemaManager;
    private final ModelLinter linter;
    private final ScheduledExecutorService scheduler;

    // Written on the validation thread, read from the EDT: must be volatile for
    // cross-thread visibility (JMM).
    private volatile ValidationResult currentValidationResult;
    private boolean validationEnabled = true;
    private volatile long lastValidationTimeMs = 0;

    // Generation counter for coalescing: each performValidation() bumps it, and a
    // queued validation that starts with a stale generation returns immediately.
    // Prevents an unbounded backlog of full validations (each with FileValidator
    // disk stats) when requests arrive faster than they complete.
    private final AtomicLong validationGeneration = new AtomicLong();

    // Callback interface for validation completion
    public interface ValidationResultHandler {
        void onValidationCompleted(ValidationResult result);
    }

    private volatile ValidationResultHandler resultHandler;

    public LinterOrchestrator(SchemaManager schemaManager) {
        this(schemaManager, new ModelLinter(schemaManager));
    }

    /**
     * Package-private seam for tests that need to control validation timing.
     */
    LinterOrchestrator(SchemaManager schemaManager, ModelLinter linter) {
        this.schemaManager = schemaManager;
        this.linter = linter;
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

        long generation = validationGeneration.incrementAndGet();

        // Perform validation in background
        scheduler.execute(() -> {
            try {
                // Superseded while queued: a newer request is (or will be) behind
                // us on this single-threaded scheduler - skip the stale work.
                if (generation != validationGeneration.get()) {
                    return;
                }

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
     * Clear all validation state. Also supersedes any queued validation so a
     * stale result cannot overwrite the cleared state.
     */
    public void clearValidation() {
        validationGeneration.incrementAndGet();
        ValidationResult cleared = new ValidationResult();
        currentValidationResult = cleared;

        if (resultHandler != null) {
            SwingUtilities.invokeLater(() -> resultHandler.onValidationCompleted(cleared));
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