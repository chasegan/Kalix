package com.kalix.ide.linter;

import com.kalix.ide.linter.events.ValidationEventManager;
import com.kalix.ide.linter.managers.LinterOrchestrator;
import com.kalix.ide.linter.model.ValidationIssue;
import com.kalix.ide.linter.model.ValidationResult;
import com.kalix.ide.linter.ui.ErrorNavigationManager;
import com.kalix.ide.linter.ui.LinterTooltipManager;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Manages real-time linting integration with RSyntaxTextArea.
 * Coordinates between validation, UI updates, and user interactions.
 */
public class LinterManager implements SchemaManager.LintingStateChangeListener,
                                      ValidationEventManager.ValidationTrigger,
                                      LinterOrchestrator.ValidationResultHandler {

    private static final Logger logger = LoggerFactory.getLogger(LinterManager.class);

    private final RSyntaxTextArea textArea;
    private final SchemaManager schemaManager;

    // Delegated responsibilities
    private final LinterOrchestrator orchestrator;
    private ValidationEventManager eventManager;
    private final LinterTooltipManager tooltipManager;
    private final ErrorNavigationManager navigationManager;
    private final LinterHighlighter highlighter;

    // Issue tracking for UI integration
    private final ConcurrentHashMap<Integer, ValidationIssue> issuesByLine;

    // Base directory supplier for resolving relative paths
    private Supplier<File> baseDirectorySupplier;

    // External validation listeners
    private final List<ValidationCompletionListener> validationListeners = new ArrayList<>();

    /**
     * Listener interface for validation completion events.
     */
    public interface ValidationCompletionListener {
        void onValidationCompleted(ValidationResult result, long validationTimeMs);
    }

    /**
     * Constructor with dependency injection.
     * Use LinterComponentFactory.createLinterManager() to create instances.
     */
    public LinterManager(
            RSyntaxTextArea textArea,
            SchemaManager schemaManager,
            LinterOrchestrator orchestrator,
            LinterHighlighter highlighter,
            LinterTooltipManager tooltipManager,
            ErrorNavigationManager navigationManager,
            ConcurrentHashMap<Integer, ValidationIssue> issuesByLine) {

        this.textArea = textArea;
        this.schemaManager = schemaManager;
        this.orchestrator = orchestrator;
        this.highlighter = highlighter;
        this.tooltipManager = tooltipManager;
        this.navigationManager = navigationManager;
        this.issuesByLine = issuesByLine;

        initialize();
    }

    /**
     * Set the event manager (used by factory for proper wiring).
     */
    public void setEventManager(ValidationEventManager eventManager) {
        this.eventManager = eventManager;
    }

    private void initialize() {
        // Register as listener for schema manager changes
        schemaManager.addLintingStateChangeListener(this);

        // Set up orchestrator callback
        orchestrator.setValidationResultHandler(this);

    }

    /**
     * Sets the base directory supplier for resolving relative file paths during validation.
     *
     * @param baseDirectorySupplier Supplier that returns the base directory (typically the current model file's directory)
     */
    public void setBaseDirectorySupplier(Supplier<File> baseDirectorySupplier) {
        this.baseDirectorySupplier = baseDirectorySupplier;
    }

    /**
     * ValidationTrigger implementation - called by ValidationEventManager.
     * Always performs full validation for maximum accuracy and simplicity.
     */
    @Override
    public void triggerValidation() {
        String content = textArea.getText();
        if (content.trim().isEmpty()) {
            orchestrator.clearValidation();
        } else {
            // Get base directory from supplier if available
            File baseDirectory = (baseDirectorySupplier != null) ? baseDirectorySupplier.get() : null;
            orchestrator.performValidation(content, baseDirectory);
        }
    }


    /**
     * ValidationResultHandler implementation - called by LinterOrchestrator.
     */
    @Override
    public void onValidationCompleted(ValidationResult result) {
        // Clear previous issues
        issuesByLine.clear();

        // Index issues by line number
        for (ValidationIssue issue : result.getIssues()) {
            issuesByLine.put(issue.getLineNumber(), issue);
        }

        // Update visual feedback
        highlighter.updateHighlights(result);        // Notify external listeners
        long validationTime = orchestrator.getLastValidationTimeMs();
        for (ValidationCompletionListener listener : validationListeners) {
            try {
                listener.onValidationCompleted(result, validationTime);
            } catch (Exception e) {
                logger.error("Error notifying validation listener", e);
            }
        }
    }

    /**
     * Get current validation result.
     */
    public ValidationResult getCurrentValidationResult() {
        return orchestrator.getCurrentValidationResult();
    }

    /**
     * Enable or disable real-time validation.
     */
    public void setValidationEnabled(boolean enabled) {
        orchestrator.setValidationEnabled(enabled);
        if (!enabled) {
            issuesByLine.clear();
            highlighter.clearHighlights();
        } else {
            // When re-enabling linting, trigger validation to show any existing errors
            validateNow();
        }
    }

    /**
     * Manually trigger validation (useful for preference changes).
     */
    public void validateNow() {
        eventManager.validateNow();
    }

    /**
     * Get issues for a specific line number.
     */
    public ValidationIssue getIssueForLine(int lineNumber) {
        return issuesByLine.get(lineNumber);
    }

    /**
     * Get all current validation issues.
     */
    public List<ValidationIssue> getAllIssues() {
        ValidationResult result = getCurrentValidationResult();
        return result != null ? result.getIssues() : List.of();
    }

    /**
     * Navigate to next validation error.
     */
    public void goToNextError() {
        navigationManager.goToNextError(getCurrentValidationResult());
    }

    /**
     * Navigate to previous validation error.
     */
    public void goToPreviousError() {
        navigationManager.goToPreviousError(getCurrentValidationResult());
    }

    /**
     * Get the last validation time in milliseconds.
     * Returns 0 if no validation has been performed yet.
     */
    public long getLastValidationTimeMs() {
        return orchestrator.getLastValidationTimeMs();
    }

    /**
     * Add a validation completion listener.
     */
    public void addValidationListener(ValidationCompletionListener listener) {
        if (listener != null && !validationListeners.contains(listener)) {
            validationListeners.add(listener);
        }
    }

    /**
     * Remove a validation completion listener.
     */
    public void removeValidationListener(ValidationCompletionListener listener) {
        validationListeners.remove(listener);
    }

    /**
     * Handle linting enabled state changes from SchemaManager.
     */
    @Override
    public void onLintingEnabledChanged(boolean enabled) {
        setValidationEnabled(enabled);
    }

    /**
     * Cleanup resources when the manager is no longer needed.
     */
    public void dispose() {
        // Unregister from schema manager
        schemaManager.removeLintingStateChangeListener(this);

        // Dispose delegated components
        eventManager.dispose();
        tooltipManager.dispose();
        orchestrator.dispose();
        highlighter.dispose();

    }
}