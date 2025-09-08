package com.kalix.gui.windows;

import com.kalix.gui.cli.SessionManager;

/**
 * Interface for session tabs that can receive log messages.
 * This allows both SessionTab and JsonProtocolSessionTab to be used interchangeably.
 */
public interface LoggableSessionTab {
    
    /**
     * Adds a log message to the session's communication log.
     * 
     * @param direction the direction of communication ("GUI->CLI", "CLI->GUI", "SYSTEM")
     * @param message the message content
     */
    void addLogMessage(String direction, String message);
    
    /**
     * Gets the session ID for this tab.
     * 
     * @return the session ID
     */
    String getSessionId();
    
    /**
     * Updates the session information (default implementation does nothing for JSON protocol tabs).
     * 
     * @param session the updated session information
     */
    default void updateSession(SessionManager.KalixSession session) {
        // Default implementation does nothing - JSON protocol tabs don't need this
    }
}