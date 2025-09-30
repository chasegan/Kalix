package com.kalix.ide.linter.events;

import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

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
    }

    private void setupDocumentListener() {
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