package com.kalix.ide.logging;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.logging.ConsoleHandler;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogManager;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * logging.properties really configures the log manager. java.util.logging reports a
 * misnamed handler or formatter class on stderr and carries on with its defaults, so without
 * this a typo would ship silently. readConfiguration resets the handlers first, so the
 * assertions hold whatever logged earlier in the fork; the configuration then stays installed,
 * which only changes how later tests' captured log output looks.
 */
class LoggingTest {

    @Test
    void installAppliesTheHandlerFormatterAndLevels() {
        Logging.install();

        Logger root = LogManager.getLogManager().getLogger("");
        Handler[] handlers = root.getHandlers();
        assertEquals(1, handlers.length, Arrays.toString(handlers));
        assertTrue(handlers[0] instanceof ConsoleHandler, handlers[0].toString());
        assertTrue(handlers[0].getFormatter() instanceof LogLineFormatter, String.valueOf(handlers[0].getFormatter()));
        assertEquals(Level.INFO, root.getLevel());
        assertEquals(Level.FINE, Logger.getLogger("com.kalix.ide.cli").getLevel());
        assertEquals(Level.WARNING, Logger.getLogger("com.formdev.flatlaf").getLevel());
    }

    @Test
    void packageLevelsReachLoggersCreatedBeforeInstall() {
        Logger early = Logger.getLogger("com.kalix.ide.cli.CreatedBeforeInstall");

        Logging.install();

        assertTrue(early.isLoggable(Level.FINE), "parented under com.kalix.ide.cli after install");
    }
}
