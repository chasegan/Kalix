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
     * Logs a warning without showing a dialog to the user.
     * 
     * @param message warning message
     * @param context description of the situation
     */
    public static void logWarning(String message, String context) {
        logger.warn("Warning in {}: {}", context, message);
    }
}