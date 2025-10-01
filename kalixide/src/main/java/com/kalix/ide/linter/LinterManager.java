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

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

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

    /**
     * Constructor with dependency injection.
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
     * Legacy constructor for backward compatibility.
     */
    @Deprecated
    public LinterManager(RSyntaxTextArea textArea, SchemaManager schemaManager) {
        this.textArea = textArea;
        this.schemaManager = schemaManager;

        // Create dependencies using factory
        this.issuesByLine = new ConcurrentHashMap<>();
        this.orchestrator = new LinterOrchestrator(schemaManager);
        this.eventManager = new ValidationEventManager(textArea, this);
        this.tooltipManager = new LinterTooltipManager(textArea, issuesByLine);
        this.navigationManager = new ErrorNavigationManager(textArea);
        this.highlighter = new LinterHighlighter(textArea);

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

        logger.debug("LinterManager initialized for text area");
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
            orchestrator.performValidation(content);
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
        highlighter.updateHighlights(result);

        logger.debug("Validation completed: {} errors, {} warnings",
                    result.getErrors().size(), result.getWarnings().size());
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
     * Handle linting enabled state changes from SchemaManager.
     */
    @Override
    public void onLintingEnabledChanged(boolean enabled) {
        setValidationEnabled(enabled);
        logger.debug("Linting enabled state changed to: {}", enabled);
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

        logger.debug("LinterManager disposed");
    }
}