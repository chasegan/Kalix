package com.kalix.ide.utils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/**
 * A per-session log of the errors shown to the user: a plain temp file, created on the first
 * error and removed when the JVM exits. Thread-safe, because errors arrive from CLI and
 * executor threads as well as the EDT.
 */
public final class ErrorLog {

    private static final Logger logger = LoggerFactory.getLogger(ErrorLog.class);
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    private final File directory;
    private File file;

    /** @param directory where the log file is created */
    public ErrorLog(File directory) {
        this.directory = directory;
    }

    /** A log line: timestamp, two spaces, message, newline (always LF, matching editor buffers). */
    public static String format(LocalDateTime time, String message) {
        return time.format(STAMP) + "  " + message + "\n";
    }

    /**
     * Appends a timestamped line, creating the file on first use. Line breaks in the message
     * (dialog text is often multi-line) become spaces, so one error is always one line.
     *
     * @return the line written, or null if the file could not be written
     */
    public synchronized String append(String message) {
        try {
            if (file == null) {
                File created;
                do { // a six-character uid is short, so never reuse another IDE's log
                    String uid = UUID.randomUUID().toString().substring(0, 6);
                    created = new File(directory, "kalix-log-" + uid + ".txt");
                } while (created.exists());
                created.deleteOnExit();
                file = created;
            }
            String line = format(LocalDateTime.now(), String.valueOf(message).replaceAll("\\s*\\R\\s*", " "));
            Files.writeString(file.toPath(), line, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            return line;
        } catch (IOException | RuntimeException e) {
            logger.warn("Could not write error log {}", file, e);
            return null;
        }
    }

    /** Everything logged so far, or null if there is no log file or it cannot be read. */
    public synchronized String read() {
        if (file == null) {
            return null;
        }
        try {
            return Files.readString(file.toPath());
        } catch (IOException e) {
            return null;
        }
    }

    /** The log file, or null until the first error has been written. */
    public synchronized File file() {
        return file;
    }
}
