package com.kalix.ide.dataview;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Format dispatch: a Source .res.csv indexes only its data region (past EOH)
 * with column names from the extended header; anything unparseable falls back
 * to a plain CSV view rather than failing the open.
 */
class DataViewOpenerTest {

    /** Same shape as the SourceResCsvImporterTest fixture — the real Source layout. */
    private static final String RES_CSV_SAMPLE =
        "File version,3\n"
        + "Missing data value,-9999\n"
        + "EOM\n"
        + "Project name,Test Project\n"
        + "Source version,5.16.0\n"
        + "Field,Units,RunName,Name,Site,ElementName\n"
        + "EOC\n"
        + "2\n"
        + "1,ML,Latest Run,Flow at A,Site A,Downstream Flow,e9bf49ab-guid,\n"
        + "2,m,Latest Run,Level at B,Site B,Storage Level,e9bf49ab-guid,\n"
        + "Date,1>Site A>Downstream Flow,2>Site B>Storage Level\n"
        + "EOH\n"
        + "2020-01-01,1.5,10.0\n"
        + "2020-01-02,-9999,\n"
        + "2020-01-03,3.5,12.5\n";

    private static File write(String name, String content) throws IOException {
        Path dir = Files.createTempDirectory("kalix-opener-test");
        dir.toFile().deleteOnExit();
        Path file = dir.resolve(name);
        Files.writeString(file, content, StandardCharsets.UTF_8);
        file.toFile().deleteOnExit();
        return file.toFile();
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

    @Test
    void resCsvIndexesTheDataRegionWithHeaderSuppliedColumnNames() throws IOException {
        try (DataViewSession session = DataViewOpener.openFor(write("results.res.csv", RES_CSV_SAMPLE))) {
            await("indexing complete", session::isIndexingComplete);

            assertEquals(3, session.rowCount(), "only the data region past EOH is indexed");
            assertFalse(session.headerRowInData(), "column names come from the extended header");
            String[] columns = session.columnNames();
            assertEquals(3, columns.length);
            assertEquals("Date", columns[0]);
            // Names arrive cleansed by the shared header machinery, so a series is
            // labelled here exactly as it is in the run tree and FlowViz.
            assertEquals("flow_at_a", columns[1]);
            assertEquals("level_at_b", columns[2]);
            assertEquals(12, session.headerLinesBeforeData(),
                "extended header lines, for mapping data lines onto the full-file editor");

            session.requestRow(0);
            await("first data row", () -> session.rowIfLoaded(0) != null);
            assertArrayEquals(new String[] {"2020-01-01", "1.5", "10.0"}, session.rowIfLoaded(0));
        }
    }

    @Test
    void aResCsvWithoutMarkersFallsBackToAPlainCsvView() throws IOException {
        try (DataViewSession session = DataViewOpener.openFor(
                write("broken.res.csv", "Date,flow\n2020-01-01,1.5\n"))) {
            await("indexing complete", session::isIndexingComplete);
            assertEquals(2, session.rowCount(), "whole file indexed as plain CSV");
            assertTrue(session.headerRowInData());
        }
    }

    @Test
    void plainCsvOpensDirectly() throws IOException {
        try (DataViewSession session = DataViewOpener.openFor(
                write("flows.csv", "Date,flow\n2020-01-01,1.5\n"))) {
            await("indexing complete", session::isIndexingComplete);
            assertEquals(2, session.rowCount());
        }
    }
}
