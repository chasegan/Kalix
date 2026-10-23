package com.kalix.ide.logging;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;
import java.util.logging.LogManager;
import java.util.logging.Logger;

/**
 * Installs the IDE's logging configuration: SLF4J over java.util.logging (slf4j-jdk14). The
 * {@code logging.properties} beside this class sets the handler, the line format
 * ({@link LogLineFormatter}) and the per-package levels.
 */
public final class Logging {

    private Logging() {
    }

    /** Reads {@code logging.properties} into the log manager. Call first thing in {@code main}. */
    public static void install() {
        try (InputStream in = Logging.class.getResourceAsStream("logging.properties")) {
            if (in == null) {
                throw new IOException("logging.properties is not beside Logging on the classpath");
            }
            byte[] config = in.readAllBytes();
            LogManager.getLogManager().readConfiguration(new ByteArrayInputStream(config));
            // readConfiguration sets a level only on loggers that already exist under exactly that
            // name. A package rule such as com.kalix.ide.cli.level only becomes a logger when a
            // child is created after this call, so a logger created earlier (a static field, before
            // main runs) would keep the root as its parent. Creating each rule's logger now
            // re-parents those.
            Properties properties = new Properties();
            properties.load(new ByteArrayInputStream(config));
            for (String key : properties.stringPropertyNames()) {
                if (key.endsWith(".level") && !key.startsWith("java.util.logging.")) {
                    Logger.getLogger(key.substring(0, key.length() - ".level".length()));
                }
            }
        } catch (IOException e) {
            // Nothing to log with yet: say so on stderr and run with java.util.logging's defaults.
            System.err.println("KalixIDE: logging configuration not applied: " + e.getMessage());
        }
    }
}
