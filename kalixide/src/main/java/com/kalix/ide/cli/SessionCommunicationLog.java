package com.kalix.ide.cli;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Maintains a raw communication log for a kalixcli session.
 * Records all messages between IDE and CLI with timestamps and direction.
 */
public class SessionCommunicationLog {
    
    private static final DateTimeFormatter TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");
    
    private final List<LogEntry> entries = new CopyOnWriteArrayList<>();
    private final String sessionKey;
    
    /**
     * Creates a new communication log for a session.
     * 
     * @param sessionKey the internal session key
     */
    public SessionCommunicationLog(String sessionKey) {
        this.sessionKey = sessionKey;
        // Log session start
        logSystemMessage("Session created: " + sessionKey);
    }
    
    /**
     * Represents a single log entry in the communication.
     */
    public static class LogEntry {
        private final LocalDateTime timestamp;
        private final Direction direction;
        private final Stream stream;
        private final String message;
        
        public LogEntry(LocalDateTime timestamp, Direction direction, Stream stream, String message) {
            this.timestamp = timestamp;
            this.direction = direction;
            this.stream = stream;
            this.message = message;
        }
        
        public LocalDateTime getTimestamp() { return timestamp; }
        public Direction getDirection() { return direction; }
        public Stream getStream() { return stream; }
        public String getMessage() { return message; }
        
        /**
         * Formats the log entry for display.
         */
        public String getFormattedEntry() {
            String timestampStr = timestamp.format(TIMESTAMP_FORMAT);
            String streamStr = (stream != Stream.SYSTEM) ? " (" + stream + ")" : "";
            return String.format("[%s] %s%s: %s", 
                timestampStr, direction, streamStr, message);
        }
        
        @Override
        public String toString() {
            return getFormattedEntry();
        }
    }
    
    /**
     * Communication direction.
     */
    public enum Direction {
        GUI_TO_CLI("IDE->CLI"),
        CLI_TO_GUI("CLI->IDE"),
        SYSTEM("SYSTEM");
        
        private final String displayName;
        
        Direction(String displayName) {
            this.displayName = displayName;
        }
        
        @Override
        public String toString() {
            return displayName;
        }
    }
    
    /**
     * Stream type for the message.
     */
    public enum Stream {
        STDOUT("STDOUT"),
        STDERR("STDERR"),
        STDIN("STDIN"),
        SYSTEM("SYSTEM");
        
        private final String displayName;
        
        Stream(String displayName) {
            this.displayName = displayName;
        }
        
        @Override
        public String toString() {
            return displayName;
        }
    }
    
    /**
     * Logs a message sent from IDE to CLI.
     * 
     * @param message the message content
     */
    public void logIdeToCli(String message) {
        entries.add(new LogEntry(LocalDateTime.now(), Direction.GUI_TO_CLI, Stream.STDIN, message));
    }
    
    /**
     * Logs a message received from CLI stdout.
     * 
     * @param message the message content
     */
    public void logCliToIdeStdout(String message) {
        entries.add(new LogEntry(LocalDateTime.now(), Direction.CLI_TO_GUI, Stream.STDOUT, message));
    }
    
    /**
     * Logs a message received from CLI stderr.
     * 
     * @param message the message content
     */
    public void logCliToIdeStderr(String message) {
        entries.add(new LogEntry(LocalDateTime.now(), Direction.CLI_TO_GUI, Stream.STDERR, message));
    }
    
    /**
     * Logs a system message (session events, errors, etc.).
     * 
     * @param message the message content
     */
    public void logSystemMessage(String message) {
        entries.add(new LogEntry(LocalDateTime.now(), Direction.SYSTEM, Stream.SYSTEM, message));
    }
    
    /**
     * Gets the internal session key this log belongs to.
     *
     * @return the session key
     */
    public String getSessionKey() {
        return sessionKey;
    }
    
    /**
     * Gets the formatted log as a single string.
     * 
     * @return formatted log content
     */
    public String getFormattedLog() {
        StringBuilder sb = new StringBuilder();
        sb.append("=== Communication Log for Session: ").append(sessionKey).append(" ===\n");
        for (LogEntry entry : entries) {
            sb.append(entry.getFormattedEntry()).append("\n");
        }
        return sb.toString();
    }
}