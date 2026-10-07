package com.kalix.ide.utils;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.stream.Collectors;

/**
 * A per-session log of the errors shown to the user, kept in memory: one timestamped line
 * per error, gone when the IDE closes. Thread-safe, because errors arrive from CLI and
 * executor threads as well as the EDT.
 */
public final class ErrorLog {

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    private final StringBuilder text = new StringBuilder();

    /** A log line: timestamp, two spaces, message, newline (always LF, matching editor buffers). */
    public static String format(LocalDateTime time, String message) {
        return time.format(STAMP) + "  " + message + "\n";
    }

    /**
     * Appends a timestamped line. Line breaks in the message (dialog text is often
     * multi-line) become spaces, so one error is always one line.
     */
    public synchronized void append(String message) {
        text.append(format(LocalDateTime.now(), oneLine(String.valueOf(message))));
    }

    /** The message's lines, each trimmed, joined by single spaces; blank lines vanish. */
    private static String oneLine(String message) {
        return message.lines().map(String::strip).filter(s -> !s.isEmpty()).collect(Collectors.joining(" "));
    }

    /** Everything logged so far; empty until the first error. */
    public synchronized String text() {
        return text.toString();
    }
}
