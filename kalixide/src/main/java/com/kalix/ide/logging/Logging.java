package com.kalix.ide.logging;

import java.io.IOException;
import java.io.InputStream;
import java.util.logging.LogManager;

/**
 * Installs the IDE's logging configuration. SLF4J calls reach java.util.logging through the
 * slf4j-jdk14 binding; {@code logging.properties} on the classpath sets the handler, the line
 * format ({@link LogLineFormatter}) and the per-package levels.
 */
public final class Logging {

    private Logging() {
    }

    /** Reads {@code /logging.properties} into the log manager. Call first thing in {@code main}. */
    public static void install() {
        try (InputStream in = Logging.class.getResourceAsStream("/logging.properties")) {
            if (in == null) {
                throw new IOException("/logging.properties is not on the classpath");
            }
            LogManager.getLogManager().readConfiguration(in);
        } catch (IOException e) {
            // Nothing to log with yet: say so on stderr and run with java.util.logging's defaults.
            System.err.println("KalixIDE: logging configuration not applied: " + e.getMessage());
        }
    }
}
