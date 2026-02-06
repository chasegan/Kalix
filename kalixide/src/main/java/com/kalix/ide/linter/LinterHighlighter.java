package com.kalix.ide.linter;

import com.kalix.ide.linter.model.ValidationIssue;
import com.kalix.ide.linter.model.ValidationResult;
import com.kalix.ide.linter.model.ValidationRule;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.text.BadLocationException;
import javax.swing.text.DefaultHighlighter;
import javax.swing.text.Highlighter;
import java.awt.Color;
import java.util.ArrayList;
import java.util.List;

/**
 * Manages visual highlighting of validation issues in RSyntaxTextArea.
 * Provides different highlight styles for errors and warnings.
 */
public class LinterHighlighter {

    private static final Logger logger = LoggerFactory.getLogger(LinterHighlighter.class);

    private final RSyntaxTextArea textArea;
    private final List<Object> highlights = new ArrayList<>();

    // Highlight painters for different severity levels
    private final DefaultHighlighter.DefaultHighlightPainter errorPainter;
    private final DefaultHighlighter.DefaultHighlightPainter warningPainter;

    public LinterHighlighter(RSyntaxTextArea textArea) {
        this.textArea = textArea;
        this.errorPainter = new DefaultHighlighter.DefaultHighlightPainter(new Color(255, 0, 0, 30));
        this.warningPainter = new DefaultHighlighter.DefaultHighlightPainter(new Color(255, 140, 0, 20));
    }

    /**
     * Update highlights based on validation result.
     */
    public void updateHighlights(ValidationResult result) {
        // Clear existing highlights
        clearHighlights();

        if (result == null) {
            return;
        }

        // Add highlights for each issue
        for (ValidationIssue issue : result.getIssues()) {
            addHighlightForIssue(issue);
        }

        // Force repaint
        textArea.repaint();
    }

    /**
     * Add highlight for a specific validation issue.
     */
    private void addHighlightForIssue(ValidationIssue issue) {
        try {
            int lineNumber = issue.getLineNumber() - 1; // Convert to 0-based
            if (lineNumber < 0 || lineNumber >= textArea.getLineCount()) {
                return;
            }

            int startOffset = textArea.getLineStartOffset(lineNumber);
            int endOffset = textArea.getLineEndOffset(lineNumber);

            // Avoid highlighting the newline character
            if (endOffset > startOffset && endOffset > 0) {
                endOffset--;
            }

            // Skip empty lines
            if (endOffset <= startOffset) {
                return;
            }

            // Choose painter based on severity
            Highlighter.HighlightPainter painter =
                issue.getSeverity() == ValidationRule.Severity.ERROR ? errorPainter : warningPainter;

            // Add highlight
            Object highlight = textArea.getHighlighter().addHighlight(startOffset, endOffset, painter);
            highlights.add(highlight);

        } catch (BadLocationException e) {
            logger.warn("Could not add highlight for line {}", issue.getLineNumber(), e);
        }
    }

    /**
     * Clear all validation highlights.
     */
    public void clearHighlights() {
        Highlighter highlighter = textArea.getHighlighter();
        for (Object highlight : highlights) {
            highlighter.removeHighlight(highlight);
        }
        highlights.clear();
    }

    /**
     * Cleanup resources.
     */
    public void dispose() {
        clearHighlights();
    }

}