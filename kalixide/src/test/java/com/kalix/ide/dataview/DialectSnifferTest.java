package com.kalix.ide.dataview;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DialectSnifferTest {

    private static CsvDialect sniff(String content) {
        return DialectSniffer.sniff(content.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void detectsCommaWithHeader() {
        CsvDialect d = sniff("Date,flow_a,flow_b\n2020-01-01,1.5,2.5\n");
        assertEquals(',', d.delimiter());
        assertTrue(d.hasHeaderRow());
        assertEquals("LF", d.lineEndingLabel());
        assertEquals(0, d.bomLength());
    }

    @Test
    void detectsSemicolonAndCrlf() {
        CsvDialect d = sniff("name;value\r\nx;1.0\r\n");
        assertEquals(';', d.delimiter());
        assertEquals("CRLF", d.lineEndingLabel());
        assertTrue(d.hasHeaderRow());
    }

    @Test
    void detectsTabAndPipe() {
        assertEquals('\t', sniff("a\tb\tc\n1\t2\t3\n").delimiter());
        assertEquals('|', sniff("a|b|c\n1|2|3\n").delimiter());
    }

    @Test
    void headerlessDateValueRowsAreNotMistakenForAHeader() {
        // The date is non-numeric, but the numeric value column decides.
        CsvDialect d = sniff("2020-01-01,1.5\n2020-01-02,2.5\n");
        assertFalse(d.hasHeaderRow());
    }

    @Test
    void scientificNotationCountsAsNumeric() {
        CsvDialect d = sniff("Date,flow\n2020-01-01,1.234E+05\n");
        assertTrue(d.hasHeaderRow());
    }

    @Test
    void utf8BomIsDetectedAndSkipped() {
        byte[] bom = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
        byte[] body = "a,b\n1,2\n".getBytes(StandardCharsets.UTF_8);
        byte[] all = new byte[bom.length + body.length];
        System.arraycopy(bom, 0, all, 0, bom.length);
        System.arraycopy(body, 0, all, bom.length, body.length);

        CsvDialect d = DialectSniffer.sniff(all);
        assertEquals(3, d.bomLength());
        assertEquals(StandardCharsets.UTF_8, d.charset());
        assertEquals(',', d.delimiter());
    }

    @Test
    void utf16BomIsDetectedForHonestReporting() {
        byte[] head = {(byte) 0xFF, (byte) 0xFE, 'a', 0};
        CsvDialect d = DialectSniffer.sniff(head);
        assertEquals(2, d.bomLength());
        assertEquals(StandardCharsets.UTF_16LE, d.charset());
    }

    @Test
    void delimitersInsideQuotesAreNotCounted() {
        CsvDialect d = sniff("\"x;y;z\",b\n1,2\n");
        assertEquals(',', d.delimiter());
    }
}
