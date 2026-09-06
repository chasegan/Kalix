package com.kalix.ide.dataview;

import org.junit.jupiter.api.Test;

import javax.swing.SwingUtilities;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

class VirtualDataTableModelTest {

    private static Path bigCsv(int dataRows) throws IOException {
        StringBuilder sb = new StringBuilder("Date,flow\n");
        for (int i = 0; i < dataRows; i++) {
            sb.append("2020-01-01,").append(i).append(".5\n");
        }
        Path file = Files.createTempFile("kalix-tablemodel-test", ".csv");
        file.toFile().deleteOnExit();
        Files.writeString(file, sb.toString(), StandardCharsets.UTF_8);
        return file;
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

    /** Model methods run on the EDT, as JTable would call them. */
    private static <T> T onEdt(java.util.function.Supplier<T> call) {
        AtomicReference<T> result = new AtomicReference<>();
        try {
            SwingUtilities.invokeAndWait(() -> result.set(call.get()));
        } catch (InterruptedException | InvocationTargetException e) {
            throw new AssertionError(e);
        }
        return result.get();
    }

    @Test
    void headerBecomesColumnNamesNotRowZero() throws IOException {
        try (DataViewSession session = DataViewSession.open(bigCsv(10))) {
            VirtualDataTableModel model = onEdt(() -> new VirtualDataTableModel(session));
            await("model sees all data rows", () -> onEdt(model::getRowCount) == 10);

            assertEquals(2, (int) onEdt(model::getColumnCount));
            assertEquals("Date", onEdt(() -> model.getColumnName(0)));
            assertEquals("flow", onEdt(() -> model.getColumnName(1)));
            assertEquals("0.5", onEdt(() -> model.getValueAt(0, 1)), "view row 0 is the first DATA row");
            assertFalse(onEdt(() -> model.isCellEditable(0, 0)));
        }
    }

    @Test
    void unloadedCellsArePlaceholdersThatFillInAsynchronously() throws IOException {
        try (DataViewSession session = DataViewSession.open(bigCsv(3000))) {
            VirtualDataTableModel model = onEdt(() -> new VirtualDataTableModel(session));
            await("model sees all data rows", () -> onEdt(model::getRowCount) == 3000);

            // View row 2500 = file row 2501, in an unloaded block: the first ask
            // returns the placeholder and schedules the fetch...
            assertNull(onEdt(() -> model.getValueAt(2500, 1)));
            // ...and the block's arrival makes the same ask answerable.
            await("cell fills in", () -> onEdt(() -> model.getValueAt(2500, 1)) != null);
            assertEquals("2500.5", onEdt(() -> model.getValueAt(2500, 1)));
        }
    }

    @Test
    void rowCountGrowsToTheFullFileViaEvents() throws IOException {
        try (DataViewSession session = DataViewSession.open(bigCsv(20_000))) {
            // Constructed immediately - typically mid-index; events (or the
            // constructor snapshot, if indexing won the race) must converge on
            // the full count without any further prompting.
            VirtualDataTableModel model = onEdt(() -> new VirtualDataTableModel(session));
            await("row count converges", () -> onEdt(model::getRowCount) == 20_000);
        }
    }
}
