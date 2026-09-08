package com.kalix.ide.dataview;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VirtualTextAreaTest {

    private static DataViewSession session(String content) throws IOException {
        Path file = Files.createTempFile("kalix-textarea-test", ".csv");
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

    @Test
    void preferredHeightTracksTheLineCount() throws IOException {
        try (DataViewSession s = session("a,b\nc,d\ne,f\n")) {
            VirtualTextArea area = new VirtualTextArea(s);
            int lineHeight = area.getFontMetrics(area.getFont()).getHeight();
            assertEquals(3L * lineHeight, area.getPreferredSize().height);
            assertEquals(lineHeight, area.getScrollableUnitIncrement(null, 0, 1));
        }
    }

    @Test
    void lineAtClampsToTheFile() throws IOException {
        try (DataViewSession s = session("a\nb\n")) {
            VirtualTextArea area = new VirtualTextArea(s);
            int lineHeight = area.getFontMetrics(area.getFont()).getHeight();
            assertEquals(0, area.lineAt(0));
            assertEquals(1, area.lineAt(lineHeight));
            assertEquals(1, area.lineAt(lineHeight * 50), "beyond the end clamps to the last line");
        }
    }

    @Test
    void selectionCopiesTheRawLines() throws IOException {
        try (DataViewSession s = session("Date,flow\n2020-01-01,1.5E+02\n2020-01-02,2.5\n")) {
            VirtualTextArea area = new VirtualTextArea(s);
            area.selectLines(0, 1);
            assertEquals("Date,flow\n2020-01-01,1.5E+02", area.selectedTextBlocking(),
                "raw bytes, scientific notation intact");
        }
    }

    @Test
    void replaceSessionTracksTheNewFileAndShowLineSelects() throws IOException {
        try (DataViewSession first = session("a\nb\n")) {
            VirtualTextArea area = new VirtualTextArea(first);
            int lineHeight = area.getFontMetrics(area.getFont()).getHeight();
            try (DataViewSession fresh = session("a\nb\nc\nd\ne\n")) {
                area.replaceSession(fresh);
                assertEquals(5L * lineHeight, area.getPreferredSize().height,
                    "geometry follows the fresh session");
                area.showLine(4); // unparented: scrolling is a no-op, selection still lands
                assertEquals(4, area.selectionStart());
            }
        }
    }

    @Test
    void resCsvHeaderLinesRenderAboveTheDataRegion() throws IOException {
        String resCsv = "File version,3\nEOM\nProject,P\n"
            + "Field,Units,RunName,Name,Site,ElementName\nEOC\n1\n"
            + "1,ML,Run,Flow,A,DS,guid,\nDate,1>A>DS\nEOH\n"
            + "2020-01-01,1.5\n2020-01-02,2.5\n";
        Path dir = Files.createTempDirectory("kalix-textarea-res");
        dir.toFile().deleteOnExit();
        Path file = dir.resolve("x.res.csv");
        file.toFile().deleteOnExit();
        Files.writeString(file, resCsv, StandardCharsets.UTF_8);
        try (DataViewSession s = DataViewOpener.openFor(file.toFile())) {
            await("indexing complete", s::isIndexingComplete);
            VirtualTextArea area = new VirtualTextArea(s);
            int lineHeight = area.getFontMetrics(area.getFont()).getHeight();
            assertEquals(11L * lineHeight, area.getPreferredSize().height,
                "9 header lines + 2 data lines: the WHOLE file is visible");
            area.selectLines(8, 9); // EOH and the first data row
            assertEquals("EOH\n2020-01-01,1.5", area.selectedTextBlocking(),
                "selection stitches header and data seamlessly");
            area.showLine(1); // data-region line 1 lands past the header
            assertEquals(10, area.selectionStart());
        }
    }

    @Test
    void fontSizeFollowsTheEditorPreference() throws IOException {
        try (DataViewSession s = session("a\n")) {
            VirtualTextArea area = new VirtualTextArea(s);
            assertEquals(com.kalix.ide.preferences.PreferenceKeys.EDITOR_FONT_SIZE.get().intValue(),
                area.getFont().getSize(), "editor parity: the same size preference as every text surface");
        }
    }

    @Test
    void selectAllActionSelectsEveryLine() throws IOException {
        try (DataViewSession s = session("a\nb\nc\n")) {
            VirtualTextArea area = new VirtualTextArea(s);
            area.getActionMap().get("select-all").actionPerformed(null);
            assertEquals(0, area.selectionStart());
            assertEquals(3, area.selectionEndExclusive());
        }
    }

    @Test
    void gutterWidthTracksTheDigitCount() throws IOException {
        try (DataViewSession small = session("a\nb\n")) {
            int narrow = new VirtualLineNumberGutter(new VirtualTextArea(small)).getPreferredSize().width;
            StringBuilder big = new StringBuilder();
            for (int i = 0; i < 12_000; i++) {
                big.append("x\n");
            }
            try (DataViewSession large = session(big.toString())) {
                int wide = new VirtualLineNumberGutter(new VirtualTextArea(large)).getPreferredSize().width;
                assertTrue(wide > narrow, "five digits need more room than one");
            }
        }
    }

    @Test
    void emptySelectionAndOversizeSelectionCopyNothing() throws IOException {
        try (DataViewSession s = session("a\nb\n")) {
            VirtualTextArea area = new VirtualTextArea(s);
            assertNull(area.selectedTextBlocking(), "no selection");
            area.selectLines(0, VirtualTextArea.MAX_COPY_LINES + 5L);
            assertNull(area.selectedTextBlocking(), "over the copy cap");
        }
    }
}
