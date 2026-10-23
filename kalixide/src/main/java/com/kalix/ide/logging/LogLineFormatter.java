package com.kalix.ide.logging;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.logging.Formatter;
import java.util.logging.Level;
import java.util.logging.LogRecord;

/**
 * One log line per record: {@code HH:mm:ss.SSS [thread] LEVEL logger - message}, with a stack
 * trace appended when there is one. Levels are named as SLF4J names them, and the logger name
 * is shortened the way logback's {@code %logger{36}} does, so the output reads as it always has.
 * The thread is the one calling the handler, so the handler must publish synchronously, as
 * {@code ConsoleHandler} does.
 */
public final class LogLineFormatter extends Formatter {

    static final int LOGGER_WIDTH = 36;

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    @Override
    public String format(LogRecord record) {
        StringBuilder line = new StringBuilder(160);
        line.append(TIME.format(record.getInstant().atZone(ZoneId.systemDefault())))
            // A LogRecord carries a thread id, not a name. ConsoleHandler publishes on the
            // logging thread, so the current thread is the one that logged.
            .append(" [").append(Thread.currentThread().getName()).append("] ")
            .append(String.format("%-5s", levelName(record.getLevel())))
            .append(' ').append(abbreviate(record.getLoggerName()))
            .append(" - ").append(formatMessage(record))
            .append(System.lineSeparator());
        if (record.getThrown() != null) {
            StringWriter trace = new StringWriter();
            record.getThrown().printStackTrace(new PrintWriter(trace));
            line.append(trace);
        }
        return line.toString();
    }

    /** SLF4J's names for the levels slf4j-jdk14 maps onto: SEVERE, WARNING, INFO, FINE, FINEST. */
    static String levelName(Level level) {
        int value = level.intValue();
        if (value >= Level.SEVERE.intValue()) {
            return "ERROR";
        }
        if (value >= Level.WARNING.intValue()) {
            return "WARN";
        }
        if (value >= Level.CONFIG.intValue()) {
            return "INFO";
        }
        if (value >= Level.FINE.intValue()) {
            return "DEBUG";
        }
        return "TRACE";
    }

    /**
     * Shortens package segments to their initial, leftmost first, until the name fits in
     * {@link #LOGGER_WIDTH} characters; the class name is never shortened.
     */
    static String abbreviate(String name) {
        if (name == null) {
            return "";
        }
        if (name.length() <= LOGGER_WIDTH) {
            return name;
        }
        String[] parts = name.split("\\.");
        int length = name.length();
        for (int i = 0; i < parts.length - 1 && length > LOGGER_WIDTH; i++) {
            if (parts[i].length() > 1) {
                length -= parts[i].length() - 1;
                parts[i] = parts[i].substring(0, 1);
            }
        }
        return String.join(".", parts);
    }
}
