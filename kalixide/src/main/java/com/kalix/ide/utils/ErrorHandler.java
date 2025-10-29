package com.kalix.ide.utils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.SwingUtilities;
import java.awt.Component;
import java.util.function.Consumer;

/**
 * Centralized error handling utility for the Kalix IDE application.
 * Provides consistent error logging, reporting, and user notification.
 */
public class ErrorHandler {
    
    private static final Logger logger = LoggerFactory.getLogger(ErrorHandler.class);
    
    /**
     * Handles an exception with logging and user notification.
     * 
     * @param parent the parent component for dialogs
     * @param exception the exception that occurred
     * @param userMessage user-friendly error message
     * @param context description of what operation failed
     */
    public static void handleError(Component parent, Exception exception, String userMessage, String context) {
        // Log the technical details
        logger.error("Error in {}: {}", context, exception.getMessage(), exception);
        
        // Show user-friendly message
        if (SwingUtilities.isEventDispatchThread()) {
            DialogUtils.showError(parent, userMessage);
        } else {
            SwingUtilities.invokeLater(() -> DialogUtils.showError(parent, userMessage));
        }
    }
    
    /**
     * Handles an exception with logging, user notification, and status update.
     * 
     * @param parent the parent component for dialogs
     * @param exception the exception that occurred
     * @param userMessage user-friendly error message
     * @param context description of what operation failed
     * @param statusUpdater callback to update application status
     */
    public static void handleError(Component parent, Exception exception, String userMessage, 
                                 String context, Consumer<String> statusUpdater) {
        // Log the technical details
        logger.error("Error in {}: {}", context, exception.getMessage(), exception);
        
        // Update status
        if (statusUpdater != null) {
            statusUpdater.accept("Error: " + userMessage);
        }
        
        // Show user-friendly message
        if (SwingUtilities.isEventDispatchThread()) {
            DialogUtils.showError(parent, userMessage);
        } else {
            SwingUtilities.invokeLater(() -> DialogUtils.showError(parent, userMessage));
        }
    }
    
    /**
     * Logs a warning without showing a dialog to the user.
     * 
     * @param message warning message
     * @param context description of the situation
     */
    public static void logWarning(String message, String context) {
        logger.warn("Warning in {}: {}", context, message);
    }
    
    /**
     * Logs an info message.
     * 
     * @param message info message
     * @param context description of the operation
     */
    public static void logInfo(String message, String context) {
    }
    
    /**
     * Handles a recoverable error that doesn't require user interruption.
     * Logs the error and optionally updates status.
     * 
     * @param exception the exception that occurred
     * @param context description of what operation failed
     * @param statusUpdater optional callback to update application status
     */
    public static void handleRecoverableError(Exception exception, String context, Consumer<String> statusUpdater) {
        logger.warn("Recoverable error in {}: {}", context, exception.getMessage(), exception);
        
        if (statusUpdater != null) {
            statusUpdater.accept("Warning: " + exception.getMessage());
        }
    }
}