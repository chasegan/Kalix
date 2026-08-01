package com.kalix.ide.linter.events;

import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.Timer;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

/**
 * Manages validation events with debouncing to avoid excessive validation calls.
 * Handles document change detection and triggers validation after a configurable delay.
 */
public class ValidationEventManager {

    private static final Logger logger = LoggerFactory.getLogger(ValidationEventManager.class);
    private static final int VALIDATION_DELAY_MS = 300;

    private final ValidationTrigger validationTrigger;

    // Single debounce timer, restarted per document change. Allocating a new
    // javax.swing.Timer per keystroke registered and discarded a timer object
    // on every edit for no benefit.
    private final Timer validationTimer;

    // The document listened to and the listener added to it, retained so dispose()
    // can detach them. An orphaned listener kept scheduling validation against a
    // disposed orchestrator (RejectedExecutionException per keystroke) after the
    // linter was re-initialised on the same text area.
    private final javax.swing.text.Document document;
    private final DocumentListener documentListener;

    public ValidationEventManager(RSyntaxTextArea textArea, ValidationTrigger validationTrigger) {
        this.validationTrigger = validationTrigger;
        this.validationTimer = new Timer(VALIDATION_DELAY_MS, e -> {
            try {
                validationTrigger.triggerValidation();
            } catch (Exception ex) {
                logger.error("Error during validation trigger", ex);
            }
        });
        this.validationTimer.setRepeats(false);
        this.document = textArea.getDocument();
        this.documentListener = createDocumentListener();
        this.document.addDocumentListener(documentListener);
    }

    /**
     * Interface for classes that can trigger validation.
     */
    public interface ValidationTrigger {
        void triggerValidation();
    }

    private DocumentListener createDocumentListener() {
        return new DocumentListener() {
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
        };
    }


    private void scheduleValidation() {
        // Restart the debounce window (cancels any pending fire)
        validationTimer.restart();
    }

    /**
     * Trigger immediate validation without delay.
     */
    public void validateNow() {
        validationTimer.stop();
        validationTrigger.triggerValidation();
    }

    /**
     * Clean up resources: stop any pending validation and detach the document
     * listener added in the constructor.
     */
    public void dispose() {
        validationTimer.stop();
        document.removeDocumentListener(documentListener);
    }
}