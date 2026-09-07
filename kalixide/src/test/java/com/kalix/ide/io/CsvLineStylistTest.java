package com.kalix.ide.io;

import com.kalix.ide.io.CsvLineStylist.Role;
import com.kalix.ide.io.CsvLineStylist.Span;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the shared line segmentation both text surfaces colour by: header vs
 * data roles, the date axis, importer-parity missing markers, quote handling,
 * res.csv marker lines, and the contiguous-coverage invariant.
 */
class CsvLineStylistTest {

    private final CsvLineStylist stylist = new CsvLineStylist(',', '"');

    private static List<Role> roles(List<Span> spans) {
        return spans.stream().map(Span::role).toList();
    }

    private static void assertCoversContiguously(String line, List<Span> spans) {
        int expectedStart = spans.isEmpty() ? line.length() : spans.get(0).start();
        for (Span span : spans) {
            assertEquals(expectedStart, span.start(), "spans must be contiguous");
            assertTrue(span.endExclusive() > span.start());
            expectedStart = span.endExclusive();
        }
        if (!spans.isEmpty()) {
            assertEquals(0, spans.get(0).start());
            assertEquals(line.length(), spans.get(spans.size() - 1).endExclusive());
        }
    }

    @Test
    void headerLineFieldsAreAllHeader() {
        List<Span> spans = stylist.style("date,flow,level", true);
        assertEquals(List.of(Role.HEADER, Role.DELIMITER, Role.HEADER, Role.DELIMITER, Role.HEADER),
            roles(spans));
        assertCoversContiguously("date,flow,level", spans);
    }

    @Test
    void dataLineRolesDateValueMissing() {
        List<Span> spans = stylist.style("2020-01-01,1.5,na", false);
        assertEquals(List.of(Role.DATE_AXIS, Role.DELIMITER, Role.VALUE, Role.DELIMITER, Role.MISSING),
            roles(spans));
    }

    @Test
    void quotedDelimiterStaysInsideItsField() {
        String line = "2020-01-01,\"a,b\",2";
        List<Span> spans = stylist.style(line, false);
        assertEquals(List.of(Role.DATE_AXIS, Role.DELIMITER, Role.VALUE, Role.DELIMITER, Role.VALUE),
            roles(spans));
        assertEquals("\"a,b\"", line.substring(spans.get(2).start(), spans.get(2).endExclusive()));
    }

    @Test
    void quotedEmptyIsStillMissing() {
        List<Span> spans = stylist.style("2020-01-01,\"\"", false);
        assertEquals(Role.MISSING, spans.get(2).role(), "\"\" is empty: importer parity");
    }

    @Test
    void resCsvMarkerLinesAreOneMarkerSpan() {
        assertEquals(List.of(Role.MARKER), roles(stylist.style("EOH", false)));
        assertEquals(List.of(Role.MARKER), roles(stylist.style("EOM", false)));
        assertEquals(List.of(Role.MARKER), roles(stylist.style("EOC", false)));
    }

    @Test
    void emptyFieldsLeaveOnlyDelimiters() {
        String line = "a,,b";
        List<Span> spans = stylist.style(line, false);
        assertEquals(List.of(Role.DATE_AXIS, Role.DELIMITER, Role.DELIMITER, Role.VALUE), roles(spans));
        assertCoversContiguously(line, spans);
    }

    @Test
    void emptyLineHasNoSpans() {
        assertTrue(stylist.style("", false).isEmpty());
    }
}
