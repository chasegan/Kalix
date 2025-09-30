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
import java.awt.*;
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
    private final ErrorHighlightPainter errorPainter;
    private final WarningHighlightPainter warningPainter;

    public LinterHighlighter(RSyntaxTextArea textArea) {
        this.textArea = textArea;
        this.errorPainter = new ErrorHighlightPainter();
        this.warningPainter = new WarningHighlightPainter();
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

    /**
     * Custom highlight painter for error messages with red underline.
     */
    private static class ErrorHighlightPainter extends DefaultHighlighter.DefaultHighlightPainter {

        public ErrorHighlightPainter() {
            super(new Color(255, 0, 0, 30)); // Light red background
        }

        @Override
        public void paint(Graphics g, int offs0, int offs1, Shape bounds,
                         javax.swing.text.JTextComponent c) {

            // Paint light background
            super.paint(g, offs0, offs1, bounds, c);

            // Draw red squiggly underline
            drawSquigglyUnderline(g, bounds, Color.RED);
        }
    }

    /**
     * Custom highlight painter for warning messages with orange underline.
     */
    private static class WarningHighlightPainter extends DefaultHighlighter.DefaultHighlightPainter {

        public WarningHighlightPainter() {
            super(new Color(255, 140, 0, 20)); // Light orange background
        }

        @Override
        public void paint(Graphics g, int offs0, int offs1, Shape bounds,
                         javax.swing.text.JTextComponent c) {

            // Paint light background
            super.paint(g, offs0, offs1, bounds, c);

            // Draw orange squiggly underline
            drawSquigglyUnderline(g, bounds, new Color(255, 140, 0));
        }
    }

    /**
     * Draw a squiggly underline in the specified color.
     */
    private static void drawSquigglyUnderline(Graphics g, Shape bounds, Color color) {
        Rectangle rect = bounds.getBounds();
        Graphics2D g2d = (Graphics2D) g.create();

        try {
            g2d.setColor(color);
            g2d.setStroke(new BasicStroke(1.0f));

            int y = rect.y + rect.height - 2; // Position near bottom of line
            int x = rect.x;
            int endX = rect.x + rect.width;

            // Draw squiggly line
            for (int i = x; i < endX - 3; i += 4) {
                g2d.drawLine(i, y, i + 2, y - 1);
                g2d.drawLine(i + 2, y - 1, i + 4, y);
            }

        } finally {
            g2d.dispose();
        }
    }
}