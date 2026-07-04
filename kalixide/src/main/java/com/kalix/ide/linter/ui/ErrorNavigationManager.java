package com.kalix.ide.linter.ui;

import com.kalix.ide.linter.model.ValidationIssue;
import com.kalix.ide.linter.model.ValidationResult;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;

import javax.swing.text.BadLocationException;
import java.util.Comparator;
import java.util.List;

/**
 * Manages navigation between validation errors in the text editor.
 * Provides methods to jump to next/previous errors with wraparound.
 */
public class ErrorNavigationManager {

    private final RSyntaxTextArea textArea;

    public ErrorNavigationManager(RSyntaxTextArea textArea) {
        this.textArea = textArea;
    }

    /**
     * Navigate to next validation error.
     */
    public void goToNextError(ValidationResult validationResult) {
        List<ValidationIssue> errors = errorsSortedByLine(validationResult);
        if (errors.isEmpty()) {
            return;
        }

        int currentLine = textArea.getCaretLineNumber() + 1; // Convert to 1-based

        // Find next error after current line; wrap to the first (lowest-line) error
        ValidationIssue nextError = errors.get(0);
        for (ValidationIssue error : errors) {
            if (error.getLineNumber() > currentLine) {
                nextError = error;
                break;
            }
        }

        goToLine(nextError.getLineNumber());
    }

    /**
     * Navigate to previous validation error.
     */
    public void goToPreviousError(ValidationResult validationResult) {
        List<ValidationIssue> errors = errorsSortedByLine(validationResult);
        if (errors.isEmpty()) {
            return;
        }

        int currentLine = textArea.getCaretLineNumber() + 1; // Convert to 1-based

        // Find previous error before current line; wrap to the last (highest-line) error
        ValidationIssue prevError = errors.get(errors.size() - 1);
        for (int i = errors.size() - 1; i >= 0; i--) {
            ValidationIssue error = errors.get(i);
            if (error.getLineNumber() < currentLine) {
                prevError = error;
                break;
            }
        }

        goToLine(prevError.getLineNumber());
    }

    /**
     * Errors ordered by line number. Validators append issues in validator-insertion
     * order, so the raw list is not line-sorted; scanning it directly could skip
     * errors or jump backwards.
     */
    private static List<ValidationIssue> errorsSortedByLine(ValidationResult validationResult) {
        if (validationResult == null) {
            return List.of();
        }
        List<ValidationIssue> errors = new java.util.ArrayList<>(validationResult.getErrors());
        errors.sort(Comparator.comparingInt(ValidationIssue::getLineNumber));
        return errors;
    }

    /**
     * Navigate to a specific line number.
     */
    private void goToLine(int lineNumber) {
        try {
            int zeroBasedLine = lineNumber - 1; // Convert to 0-based
            if (zeroBasedLine >= 0 && zeroBasedLine < textArea.getLineCount()) {
                int lineStart = textArea.getLineStartOffset(zeroBasedLine);
                int lineEnd = textArea.getLineEndOffset(zeroBasedLine);

                // Set caret to beginning of line and select the entire line
                textArea.setCaretPosition(lineStart);
                textArea.select(lineStart, lineEnd - 1); // -1 to exclude newline

                // Ensure the line is visible (modelToView2D returns null before the
                // component has been laid out, e.g. in tests)
                java.awt.geom.Rectangle2D view = textArea.modelToView2D(lineStart);
                if (view != null) {
                    textArea.scrollRectToVisible(view.getBounds());
                }
            }
        } catch (BadLocationException e) {
            // Line number out of bounds, ignore
        }
    }
}