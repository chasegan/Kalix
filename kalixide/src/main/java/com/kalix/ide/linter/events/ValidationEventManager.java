package com.kalix.ide.linter.events;

import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.BadLocationException;

/**
 * Manages validation events with debouncing to avoid excessive validation calls.
 * Handles document change detection and triggers validation after a configurable delay.
 */
public class ValidationEventManager {

    private static final Logger logger = LoggerFactory.getLogger(ValidationEventManager.class);
    private static final int VALIDATION_DELAY_MS = 300;

    private final RSyntaxTextArea textArea;
    private final ValidationTrigger validationTrigger;
    private Timer validationTimer;

    public ValidationEventManager(RSyntaxTextArea textArea, ValidationTrigger validationTrigger) {
        this.textArea = textArea;
        this.validationTrigger = validationTrigger;
        setupDocumentListener();
    }

    /**
     * Interface for classes that can trigger validation.
     */
    public interface ValidationTrigger {
        void triggerValidation();
        void triggerFullValidation(); // Force full validation when lines shift
    }

    private void setupDocumentListener() {
        textArea.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                handleDocumentChange(e, true);
                scheduleValidation();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                handleDocumentChange(e, false);
                scheduleValidation();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                scheduleValidation();
            }
        });
    }

    /**
     * Handle document changes and detect line number shifts.
     * @param e The document event
     * @param isInsert true for insert, false for remove
     */
    private void handleDocumentChange(DocumentEvent e, boolean isInsert) {
        try {
            // Check if the change involves newlines (line shifts)
            boolean hasLineShift = false;

            if (isInsert) {
                // For insertions, check if the inserted text contains newlines
                String insertedText = textArea.getDocument().getText(e.getOffset(), e.getLength());
                hasLineShift = insertedText.contains("\n");
            } else {
                // For removals, check if we're removing across line boundaries
                // This is trickier since we can't see the removed text, but we can
                // check if the removal length is significant or spans lines
                if (e.getLength() > 0) {
                    // Check if the removal could have involved newlines by examining
                    // the text around the change position
                    int offset = e.getOffset();

                    // Heuristic: if we're at the start of a line and removed multiple chars,
                    // or if the removal was substantial, assume it may have involved newlines
                    try {
                        int line = textArea.getLineOfOffset(offset);
                        int lineStart = textArea.getLineStartOffset(line);
                        hasLineShift = (offset == lineStart && e.getLength() > 1) || e.getLength() > 50;
                    } catch (BadLocationException ex) {
                        // If we can't determine line position, be conservative and assume line shift
                        hasLineShift = e.getLength() > 1;
                    }
                }
            }

            if (hasLineShift) {
                // Line numbers have shifted - force full validation to ensure accuracy
                logger.debug("Line shift detected - triggering full validation");
                validationTrigger.triggerFullValidation();
            }

        } catch (BadLocationException ex) {
            logger.warn("Error detecting line shifts", ex);
            // If we can't analyze the change, be conservative and trigger full validation
            validationTrigger.triggerFullValidation();
        }
    }

    private void scheduleValidation() {
        // Cancel any existing timer
        if (validationTimer != null && validationTimer.isRunning()) {
            validationTimer.stop();
        }

        // Schedule new validation with delay
        validationTimer = new Timer(VALIDATION_DELAY_MS, e -> {
            try {
                validationTrigger.triggerValidation();
            } catch (Exception ex) {
                logger.error("Error during validation trigger", ex);
            }
        });
        validationTimer.setRepeats(false);
        validationTimer.start();
    }

    /**
     * Trigger immediate validation without delay.
     */
    public void validateNow() {
        if (validationTimer != null && validationTimer.isRunning()) {
            validationTimer.stop();
        }
        validationTrigger.triggerValidation();
    }

    /**
     * Clean up resources.
     */
    public void dispose() {
        if (validationTimer != null && validationTimer.isRunning()) {
            validationTimer.stop();
        }
    }
}