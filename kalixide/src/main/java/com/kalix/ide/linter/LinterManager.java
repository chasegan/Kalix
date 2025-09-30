package com.kalix.ide.linter;

import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.BadLocationException;
import java.awt.*;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionAdapter;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Manages real-time linting integration with RSyntaxTextArea.
 * Provides debounced validation, visual feedback, and tooltip integration.
 */
public class LinterManager {

    private static final Logger logger = LoggerFactory.getLogger(LinterManager.class);
    private static final int VALIDATION_DELAY_MS = 300;

    private final RSyntaxTextArea textArea;
    private final SchemaManager schemaManager;
    private final ModelLinter linter;
    private final IncrementalValidator incrementalValidator;
    private final ScheduledExecutorService scheduler;

    // Validation state
    private ValidationResult currentValidationResult;
    private final ConcurrentHashMap<Integer, ValidationResult.ValidationIssue> issuesByLine = new ConcurrentHashMap<>();
    private Timer validationTimer;
    private boolean validationEnabled = true;

    // UI integration
    private LinterHighlighter highlighter;

    public LinterManager(RSyntaxTextArea textArea, SchemaManager schemaManager) {
        this.textArea = textArea;
        this.schemaManager = schemaManager;
        this.linter = new ModelLinter(schemaManager);
        this.incrementalValidator = new IncrementalValidator(linter);
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "LinterManager-Validation");
            t.setDaemon(true);
            return t;
        });

        initialize();
    }

    private void initialize() {
        // Create highlighter for visual feedback
        highlighter = new LinterHighlighter(textArea);

        // Add document listener for real-time validation
        textArea.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                scheduleValidation();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                scheduleValidation();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                scheduleValidation();
            }
        });

        // Add mouse motion listener for tooltips
        textArea.addMouseMotionListener(new MouseMotionAdapter() {
            @Override
            public void mouseMoved(MouseEvent e) {
                showTooltipForPosition(e.getPoint());
            }
        });

        logger.debug("LinterManager initialized for text area");
    }

    /**
     * Schedule validation with debouncing to avoid excessive validation calls.
     */
    private void scheduleValidation() {
        if (!validationEnabled || !schemaManager.isLintingEnabled()) {
            return;
        }

        // Cancel previous timer if running
        if (validationTimer != null && validationTimer.isRunning()) {
            validationTimer.stop();
        }

        // Start new timer
        validationTimer = new Timer(VALIDATION_DELAY_MS, e -> performValidation());
        validationTimer.setRepeats(false);
        validationTimer.start();
    }

    /**
     * Perform validation in background thread.
     */
    private void performValidation() {
        if (!validationEnabled || !schemaManager.isLintingEnabled()) {
            return;
        }

        String content = textArea.getText();
        if (content.trim().isEmpty()) {
            clearValidationResults();
            return;
        }

        // Perform validation in background
        scheduler.execute(() -> {
            try {
                ValidationResult result = incrementalValidator.validateIncremental(content);

                // Update UI on EDT
                SwingUtilities.invokeLater(() -> {
                    updateValidationResults(result);
                });

            } catch (Exception e) {
                logger.error("Error during validation", e);
            }
        });
    }

    /**
     * Update UI with validation results.
     */
    private void updateValidationResults(ValidationResult result) {
        this.currentValidationResult = result;

        // Clear previous issues
        issuesByLine.clear();

        // Index issues by line number
        for (ValidationResult.ValidationIssue issue : result.getIssues()) {
            issuesByLine.put(issue.getLineNumber(), issue);
        }

        // Update visual feedback
        highlighter.updateHighlights(result);

        logger.debug("Validation completed: {} errors, {} warnings",
                    result.getErrors().size(), result.getWarnings().size());
    }

    /**
     * Clear all validation results and visual feedback.
     */
    private void clearValidationResults() {
        currentValidationResult = null;
        issuesByLine.clear();
        highlighter.clearHighlights();
        incrementalValidator.clearCache();
    }

    /**
     * Show tooltip for validation issues at the given position.
     */
    private void showTooltipForPosition(Point point) {
        try {
            int offset = textArea.viewToModel2D(point);
            int line = textArea.getLineOfOffset(offset) + 1; // Convert to 1-based line numbers

            ValidationResult.ValidationIssue issue = issuesByLine.get(line);
            if (issue != null) {
                String tooltip = formatTooltip(issue);
                textArea.setToolTipText(tooltip);
            } else {
                textArea.setToolTipText(null);
            }

        } catch (BadLocationException e) {
            textArea.setToolTipText(null);
        }
    }

    /**
     * Format validation issue as tooltip text.
     */
    private String formatTooltip(ValidationResult.ValidationIssue issue) {
        String severityIcon = issue.getSeverity() == ValidationRule.Severity.ERROR ? "❌" : "⚠️";
        return String.format("<html><b>%s %s</b><br/>%s</html>",
                           severityIcon,
                           issue.getSeverity().name(),
                           issue.getMessage());
    }

    /**
     * Get current validation result.
     */
    public ValidationResult getCurrentValidationResult() {
        return currentValidationResult;
    }

    /**
     * Enable or disable real-time validation.
     */
    public void setValidationEnabled(boolean enabled) {
        this.validationEnabled = enabled;
        if (!enabled) {
            clearValidationResults();
        } else {
            scheduleValidation();
        }
    }

    /**
     * Manually trigger validation (useful for preference changes).
     */
    public void validateNow() {
        if (validationEnabled && schemaManager.isLintingEnabled()) {
            performValidation();
        }
    }

    /**
     * Get issues for a specific line number.
     */
    public ValidationResult.ValidationIssue getIssueForLine(int lineNumber) {
        return issuesByLine.get(lineNumber);
    }

    /**
     * Get all current validation issues.
     */
    public List<ValidationResult.ValidationIssue> getAllIssues() {
        return currentValidationResult != null ?
               currentValidationResult.getIssues() :
               List.of();
    }

    /**
     * Navigate to next validation error.
     */
    public void goToNextError() {
        if (currentValidationResult == null || !currentValidationResult.hasErrors()) {
            return;
        }

        List<ValidationResult.ValidationIssue> errors = currentValidationResult.getErrors();
        int currentLine = textArea.getCaretLineNumber() + 1; // Convert to 1-based

        // Find next error after current line
        ValidationResult.ValidationIssue nextError = null;
        for (ValidationResult.ValidationIssue error : errors) {
            if (error.getLineNumber() > currentLine) {
                nextError = error;
                break;
            }
        }

        // If no error found after current line, wrap to first error
        if (nextError == null && !errors.isEmpty()) {
            nextError = errors.get(0);
        }

        if (nextError != null) {
            goToLine(nextError.getLineNumber());
        }
    }

    /**
     * Navigate to previous validation error.
     */
    public void goToPreviousError() {
        if (currentValidationResult == null || !currentValidationResult.hasErrors()) {
            return;
        }

        List<ValidationResult.ValidationIssue> errors = currentValidationResult.getErrors();
        int currentLine = textArea.getCaretLineNumber() + 1; // Convert to 1-based

        // Find previous error before current line
        ValidationResult.ValidationIssue prevError = null;
        for (int i = errors.size() - 1; i >= 0; i--) {
            ValidationResult.ValidationIssue error = errors.get(i);
            if (error.getLineNumber() < currentLine) {
                prevError = error;
                break;
            }
        }

        // If no error found before current line, wrap to last error
        if (prevError == null && !errors.isEmpty()) {
            prevError = errors.get(errors.size() - 1);
        }

        if (prevError != null) {
            goToLine(prevError.getLineNumber());
        }
    }

    /**
     * Navigate to specific line number.
     */
    private void goToLine(int lineNumber) {
        try {
            int line = lineNumber - 1; // Convert to 0-based
            if (line >= 0 && line < textArea.getLineCount()) {
                int offset = textArea.getLineStartOffset(line);
                textArea.setCaretPosition(offset);
                textArea.requestFocus();
            }
        } catch (BadLocationException e) {
            logger.warn("Could not navigate to line {}", lineNumber, e);
        }
    }

    /**
     * Cleanup resources when the manager is no longer needed.
     */
    public void dispose() {
        if (validationTimer != null && validationTimer.isRunning()) {
            validationTimer.stop();
        }

        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(1, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }

        highlighter.dispose();
        logger.debug("LinterManager disposed");
    }
}