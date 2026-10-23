package com.kalix.ide.logging;

import org.junit.jupiter.api.Test;

import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pins the log line the IDE has always printed, now produced without logback. */
class LogLineFormatterTest {

    @Test
    void lineHasTimeThreadLevelLoggerAndMessage() {
        LogRecord record = new LogRecord(Level.FINE, "hello");
        record.setLoggerName("com.kalix.ide.cli.SessionManager");

        String line = new LogLineFormatter().format(record);

        String expected = "^\\d{2}:\\d{2}:\\d{2}\\.\\d{3} \\[" + Pattern.quote(Thread.currentThread().getName())
            + "\\] DEBUG com.kalix.ide.cli.SessionManager - hello\\R$";
        assertTrue(Pattern.compile(expected).matcher(line).matches(), line);
    }

    @Test
    void stackTraceFollowsTheLine() {
        LogRecord record = new LogRecord(Level.SEVERE, "failed");
        record.setLoggerName("a.B");
        record.setThrown(new IllegalStateException("boom"));

        String text = new LogLineFormatter().format(record);

        assertTrue(text.startsWith(text.substring(0, 12)) && text.contains(" ERROR a.B - failed"), text);
        assertTrue(text.contains("java.lang.IllegalStateException: boom"), text);
        assertTrue(text.contains("LogLineFormatterTest"), "stack frames present: " + text);
    }

    @Test
    void levelsAreNamedAsSlf4jNamesThem() {
        assertEquals("ERROR", LogLineFormatter.levelName(Level.SEVERE));
        assertEquals("WARN", LogLineFormatter.levelName(Level.WARNING));
        assertEquals("INFO", LogLineFormatter.levelName(Level.INFO));
        assertEquals("INFO", LogLineFormatter.levelName(Level.CONFIG));
        assertEquals("DEBUG", LogLineFormatter.levelName(Level.FINE));
        assertEquals("TRACE", LogLineFormatter.levelName(Level.FINEST));
    }

    @Test
    void loggerNamesShortenLikeLogbacks36() {
        // The two forms seen in the IDE's own output under logback.
        assertEquals("c.k.i.e.c.ContextCommandManager",
            LogLineFormatter.abbreviate("com.kalix.ide.editor.commands.ContextCommandManager"));
        assertEquals("c.k.ide.editor.AutoCompleteManager",
            LogLineFormatter.abbreviate("com.kalix.ide.editor.AutoCompleteManager"));
        assertEquals("com.kalix.ide.cli.SessionManager",
            LogLineFormatter.abbreviate("com.kalix.ide.cli.SessionManager"));
        assertEquals("", LogLineFormatter.abbreviate(null));
    }
}
