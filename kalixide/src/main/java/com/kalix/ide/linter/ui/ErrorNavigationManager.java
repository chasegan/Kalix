package com.kalix.ide.linter.ui;

import com.kalix.ide.linter.model.ValidationIssue;
import com.kalix.ide.linter.model.ValidationResult;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;

import javax.swing.text.BadLocationException;
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
        if (validationResult == null || !validationResult.hasErrors()) {
            return;
        }

        List<ValidationIssue> errors = validationResult.getErrors();
        int currentLine = textArea.getCaretLineNumber() + 1; // Convert to 1-based

        // Find next error after current line
        ValidationIssue nextError = null;
        for (ValidationIssue error : errors) {
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
    public void goToPreviousError(ValidationResult validationResult) {
        if (validationResult == null || !validationResult.hasErrors()) {
            return;
        }

        List<ValidationIssue> errors = validationResult.getErrors();
        int currentLine = textArea.getCaretLineNumber() + 1; // Convert to 1-based

        // Find previous error before current line
        ValidationIssue prevError = null;
        for (int i = errors.size() - 1; i >= 0; i--) {
            ValidationIssue error = errors.get(i);
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

                // Ensure the line is visible
                textArea.scrollRectToVisible(textArea.modelToView(lineStart));
            }
        } catch (BadLocationException e) {
            // Line number out of bounds, ignore
        }
    }
}