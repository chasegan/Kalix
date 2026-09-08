package com.kalix.ide.dataview;

import org.junit.jupiter.api.Test;

import javax.swing.SwingUtilities;
import java.awt.Dimension;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DataViewPanelTest {

    private static DataViewSession session(String content) throws IOException {
        Path file = Files.createTempFile("kalix-panel-test", ".csv");
        file.toFile().deleteOnExit();
        Files.writeString(file, content, StandardCharsets.UTF_8);
        DataViewSession session = DataViewSession.open(file);
        await("indexing complete", session::isIndexingComplete);
        return session;
    }

    private static void await(String what, BooleanSupplier condition) {
        long deadline = System.currentTimeMillis() + 10_000;
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("Timed out waiting for: " + what);
            }
            try {
                Thread.sleep(5);
            } catch (InterruptedException e) {
                throw new AssertionError(e);
            }
        }
    }

    private static <T> T onEdt(Supplier<T> call) {
        AtomicReference<T> result = new AtomicReference<>();
        try {
            SwingUtilities.invokeAndWait(() -> result.set(call.get()));
        } catch (InterruptedException | InvocationTargetException e) {
            throw new AssertionError(e);
        }
        return result.get();
    }

    @Test
    void statusStripDeclaresTheSniffedDialect() throws IOException {
        try (DataViewSession s = session("Date,flow\n2020-01-01,1.5\n2020-01-02,2.5\n")) {
            DataViewPanel panel = onEdt(() -> new DataViewPanel(s));
            String text = onEdt(panel::getStatusText);
            assertTrue(text.contains("delimiter \",\""), text);
            assertTrue(text.contains("quote \""), text);
            assertTrue(text.contains("UTF-8"), text);
            assertTrue(text.contains("LF"), text);
            assertTrue(text.contains("2 rows"), "header excluded from the count: " + text);
        }
    }

    @Test
    void tableCarriesTheHouseGridStyling() throws IOException {
        try (DataViewSession s = session("a,b\n1,2\n")) {
            DataViewPanel panel = onEdt(() -> new DataViewPanel(s));
            // FlatLaf hides the grid at 0,0 spacing even with setShowGrid(true).
            assertEquals(new Dimension(1, 1), onEdt(() -> panel.getTable().getIntercellSpacing()));
        }
    }

    @Test
    void replaceSessionSwapsTheTableModel() throws IOException {
        try (DataViewSession first = session("Date,flow\n2020-01-01,1.5\n")) {
            DataViewPanel panel = onEdt(() -> new DataViewPanel(first));
            await("first model populated", () -> onEdt(() -> panel.getTable().getRowCount()) == 1);

            try (DataViewSession fresh = session("1,2\n3,4\n5,6\n")) { // headerless: 3 data rows
                onEdt(() -> {
                    panel.replaceSession(fresh);
                    return null;
                });
                await("swapped model populated", () -> onEdt(() -> panel.getTable().getRowCount()) == 3);
            }
        }
    }
}
