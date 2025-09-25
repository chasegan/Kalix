package com.kalix.ide.cli;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.PrintStream;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Consumer;

/**
 * Simple logging utility for STDIO protocol operations.
 * Provides structured logging with different levels and optional IDE integration.
 */
public class StdioLogger {
    private static final Logger logger = LoggerFactory.getLogger(StdioLogger.class);
    
    public enum LogLevel {
        DEBUG(0, "DEBUG"),
        INFO(1, "INFO"),
        WARN(2, "WARN"),
        ERROR(3, "ERROR");
        
        private final int level;
        private final String name;
        
        LogLevel(int level, String name) {
            this.level = level;
            this.name = name;
        }
        
        public int getLevel() { return level; }
        public String getName() { return name; }
    }
    
    private static final DateTimeFormatter TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
    
    private LogLevel currentLevel = LogLevel.INFO;
    private PrintStream outputStream = System.out;
    private PrintStream errorStream = System.err;
    private Consumer<String> ideLogCallback;
    
    // Ring buffer for recent log entries (for IDE display)
    private final ConcurrentLinkedQueue<LogEntry> recentEntries = new ConcurrentLinkedQueue<>();
    private final int maxRecentEntries = 1000;
    
    /**
     * Represents a log entry.
     */
    public static class LogEntry {
        private final LocalDateTime timestamp;
        private final LogLevel level;
        private final String message;
        private final Throwable exception;
        
        public LogEntry(LocalDateTime timestamp, LogLevel level, String message, Throwable exception) {
            this.timestamp = timestamp;
            this.level = level;
            this.message = message;
            this.exception = exception;
        }
        
        public LocalDateTime getTimestamp() { return timestamp; }
        public LogLevel getLevel() { return level; }
        public String getMessage() { return message; }
        public Throwable getException() { return exception; }
        
        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append(timestamp.format(TIMESTAMP_FORMAT));
            sb.append(" [").append(level.getName()).append("] ");
            sb.append(message);
            if (exception != null) {
                sb.append(" - ").append(exception.getMessage());
            }
            return sb.toString();
        }
    }
    
    /**
     * Sets the minimum log level.
     */
    public void setLogLevel(LogLevel level) {
        this.currentLevel = level;
    }
    
    /**
     * Sets the output stream for normal log messages.
     */
    public void setOutputStream(PrintStream stream) {
        this.outputStream = stream;
    }
    
    /**
     * Sets the error stream for error and warning messages.
     */
    public void setErrorStream(PrintStream stream) {
        this.errorStream = stream;
    }
    
    /**
     * Sets a callback for IDE log display.
     */
    public void setIdeLogCallback(Consumer<String> callback) {
        this.ideLogCallback = callback;
    }
    
    /**
     * Logs a debug message.
     */
    public void debug(String message) {
        log(LogLevel.DEBUG, message, null);
    }
    
    /**
     * Logs a debug message with exception.
     */
    public void debug(String message, Throwable exception) {
        log(LogLevel.DEBUG, message, exception);
    }
    
    /**
     * Logs an info message.
     */
    public void info(String message) {
        log(LogLevel.INFO, message, null);
    }
    
    /**
     * Logs an info message with exception.
     */
    public void info(String message, Throwable exception) {
        log(LogLevel.INFO, message, exception);
    }
    
    /**
     * Logs a warning message.
     */
    public void warn(String message) {
        log(LogLevel.WARN, message, null);
    }
    
    /**
     * Logs a warning message with exception.
     */
    public void warn(String message, Throwable exception) {
        log(LogLevel.WARN, message, exception);
    }
    
    /**
     * Logs an error message.
     */
    public void error(String message) {
        log(LogLevel.ERROR, message, null);
    }
    
    /**
     * Logs an error message with exception.
     */
    public void error(String message, Throwable exception) {
        log(LogLevel.ERROR, message, exception);
    }
    
    /**
     * Main logging method.
     */
    private void log(LogLevel level, String message, Throwable exception) {
        if (level.getLevel() < currentLevel.getLevel()) {
            return; // Below current log level
        }
        
        LocalDateTime timestamp = LocalDateTime.now();
        LogEntry entry = new LogEntry(timestamp, level, message, exception);
        
        // Add to recent entries
        addToRecentEntries(entry);
        
        // Format the log message
        String formattedMessage = entry.toString();
        
        // Output to console
        PrintStream stream = (level == LogLevel.ERROR || level == LogLevel.WARN) ? errorStream : outputStream;
        stream.println(formattedMessage);
        
        // If there's an exception, print stack trace
        if (exception != null) {
            exception.printStackTrace(stream);
        }
        
        // Notify IDE callback if set
        if (ideLogCallback != null) {
            try {
                ideLogCallback.accept(formattedMessage);
            } catch (Exception e) {
                // Don't let IDE callback failures affect logging
                logger.warn("Error in IDE log callback: {}", e.getMessage());
            }
        }
    }
    
    /**
     * Adds an entry to the recent entries buffer.
     */
    private void addToRecentEntries(LogEntry entry) {
        recentEntries.offer(entry);
        
        // Remove old entries if buffer is full
        while (recentEntries.size() > maxRecentEntries) {
            recentEntries.poll();
        }
    }
    
    /**
     * Gets recent log entries for IDE display.
     */
    public LogEntry[] getRecentEntries() {
        return recentEntries.toArray(new LogEntry[0]);
    }
    
    /**
     * Gets recent log entries of a specific level or higher.
     */
    public LogEntry[] getRecentEntries(LogLevel minLevel) {
        return recentEntries.stream()
                .filter(entry -> entry.getLevel().getLevel() >= minLevel.getLevel())
                .toArray(LogEntry[]::new);
    }
    
    /**
     * Clears recent log entries.
     */
    public void clearRecentEntries() {
        recentEntries.clear();
    }
    
    /**
     * Gets the current log level.
     */
    public LogLevel getLogLevel() {
        return currentLevel;
    }
    
    /**
     * Checks if a log level is enabled.
     */
    public boolean isEnabled(LogLevel level) {
        return level.getLevel() >= currentLevel.getLevel();
    }
    
    // Singleton instance for global use
    private static final StdioLogger INSTANCE = new StdioLogger();
    
    /**
     * Gets the global logger instance.
     */
    public static StdioLogger getInstance() {
        return INSTANCE;
    }
    
    /**
     * Convenience methods for global logging.
     */
    public static void logDebug(String message) {
        INSTANCE.debug(message);
    }
    
    public static void logDebug(String message, Throwable exception) {
        INSTANCE.debug(message, exception);
    }
    
    public static void logInfo(String message) {
        INSTANCE.info(message);
    }
    
    public static void logInfo(String message, Throwable exception) {
        INSTANCE.info(message, exception);
    }
    
    public static void logWarn(String message) {
        INSTANCE.warn(message);
    }
    
    public static void logWarn(String message, Throwable exception) {
        INSTANCE.warn(message, exception);
    }
    
    public static void logError(String message) {
        INSTANCE.error(message);
    }
    
    public static void logError(String message, Throwable exception) {
        INSTANCE.error(message, exception);
    }
}