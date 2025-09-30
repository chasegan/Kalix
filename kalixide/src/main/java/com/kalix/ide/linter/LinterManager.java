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
public class LinterManager implements SchemaManager.LintingStateChangeListener {

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
        // Register as listener for schema manager changes
        schemaManager.addLintingStateChangeListener(this);
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

        // Override tooltip behavior to show only our validation messages
        setupCustomTooltips();

        logger.debug("LinterManager initialized for text area");
    }

    /**
     * Setup custom tooltip behavior that only shows our validation messages.
     */
    private void setupCustomTooltips() {
        // Completely disable RSyntaxTextArea's tooltip system
        textArea.setToolTipSupplier(null);
        textArea.setToolTipText(null);

        // Unregister from tooltip manager completely
        ToolTipManager.sharedInstance().unregisterComponent(textArea);

        // Create our own custom tooltip popup
        setupCustomTooltipPopup();

        logger.debug("Custom tooltip system initialized");
    }

    private JWindow tooltipWindow;
    private JLabel tooltipLabel;

    /**
     * Setup a completely custom tooltip popup that bypasses Swing's ToolTipManager.
     */
    private void setupCustomTooltipPopup() {
        // Create custom tooltip window
        tooltipWindow = new JWindow();
        tooltipLabel = new JLabel();
        tooltipLabel.setOpaque(true);
        tooltipLabel.setBackground(new Color(255, 255, 225)); // Light yellow background
        tooltipLabel.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(Color.BLACK, 1),
            BorderFactory.createEmptyBorder(2, 4, 2, 4)
        ));
        tooltipWindow.add(tooltipLabel);

        // Add mouse motion listener
        textArea.addMouseMotionListener(new MouseMotionAdapter() {
            private Timer hideTimer;

            @Override
            public void mouseMoved(MouseEvent e) {
                // Cancel any existing hide timer
                if (hideTimer != null && hideTimer.isRunning()) {
                    hideTimer.stop();
                }

                String tooltip = getValidationTooltipForPosition(e.getPoint());

                if (tooltip != null) {
                    showCustomTooltip(tooltip, e.getLocationOnScreen());
                } else {
                    // Hide tooltip after a short delay to prevent flickering
                    hideTimer = new Timer(100, evt -> hideCustomTooltip());
                    hideTimer.setRepeats(false);
                    hideTimer.start();
                }
            }
        });

        // Hide tooltip when mouse exits text area
        textArea.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseExited(java.awt.event.MouseEvent e) {
                hideCustomTooltip();
            }
        });
    }

    private void showCustomTooltip(String text, Point screenLocation) {
        tooltipLabel.setText(text);
        tooltipWindow.pack();

        // Position tooltip slightly offset from mouse
        int x = screenLocation.x + 10;
        int y = screenLocation.y + 20;

        // Adjust position if tooltip would go off screen
        Dimension screenSize = Toolkit.getDefaultToolkit().getScreenSize();
        if (x + tooltipWindow.getWidth() > screenSize.width) {
            x = screenLocation.x - tooltipWindow.getWidth() - 5;
        }
        if (y + tooltipWindow.getHeight() > screenSize.height) {
            y = screenLocation.y - tooltipWindow.getHeight() - 5;
        }

        tooltipWindow.setLocation(x, y);
        tooltipWindow.setVisible(true);
    }

    private void hideCustomTooltip() {
        if (tooltipWindow != null) {
            tooltipWindow.setVisible(false);
        }
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
     * Get validation tooltip for the given position, or null if no validation issue.
     */
    private String getValidationTooltipForPosition(Point point) {
        try {
            int offset = textArea.viewToModel2D(point);
            int line = textArea.getLineOfOffset(offset) + 1; // Convert to 1-based line numbers

            ValidationResult.ValidationIssue issue = issuesByLine.get(line);
            if (issue != null) {
                return formatTooltip(issue);
            }

        } catch (BadLocationException e) {
            // Invalid position, no tooltip
        }

        return null; // No validation issue at this position
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

        // Clean up custom tooltip
        hideCustomTooltip();
        if (tooltipWindow != null) {
            tooltipWindow.dispose();
            tooltipWindow = null;
        }

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