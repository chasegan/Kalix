package com.kalix.ide.dataview;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RowBlockParserTest {

    private static final CsvDialect DIALECT =
        new CsvDialect(',', '"', StandardCharsets.UTF_8, 0, false, "LF");

    private static SeekableByteChannel channelOf(String content) throws IOException {
        Path file = Files.createTempFile("kalix-parser-test", ".csv");
        file.toFile().deleteOnExit();
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return Files.newByteChannel(file, StandardOpenOption.READ);
    }

    private static List<String[]> parse(String content, long offset, int maxRows) throws IOException {
        try (SeekableByteChannel ch = channelOf(content)) {
            return RowBlockParser.parse(ch, offset, DIALECT, maxRows);
        }
    }

    @Test
    void parsesQuotedFieldsEscapesAndEmbeddedStructure() throws IOException {
        List<String[]> rows = parse("a,\"b,c\",\"d\"\"e\",\"f\ng\"\nh,i,j,k\n", 0, 10);
        assertEquals(2, rows.size());
        assertArrayEquals(new String[] {"a", "b,c", "d\"e", "f\ng"}, rows.get(0));
        assertArrayEquals(new String[] {"h", "i", "j", "k"}, rows.get(1));
    }

    @Test
    void crlfIsNormalisedOutsideQuotes() throws IOException {
        List<String[]> rows = parse("a,b\r\nc,d\r\n", 0, 10);
        assertArrayEquals(new String[] {"a", "b"}, rows.get(0));
        assertArrayEquals(new String[] {"c", "d"}, rows.get(1));
    }

    @Test
    void maxRowsBoundsTheParse() throws IOException {
        List<String[]> rows = parse("1\n2\n3\n4\n", 0, 2);
        assertEquals(2, rows.size());
        assertEquals("2", rows.get(1)[0]);
    }

    @Test
    void finalRowWithoutTrailingNewlineIsKept() throws IOException {
        List<String[]> rows = parse("a,b\nc,d", 0, 10);
        assertEquals(2, rows.size());
        assertArrayEquals(new String[] {"c", "d"}, rows.get(1));
    }

    @Test
    void trailingDelimiterYieldsAnEmptyFinalField() throws IOException {
        List<String[]> rows = parse("a,\n", 0, 10);
        assertArrayEquals(new String[] {"a", ""}, rows.get(0));
    }

    @Test
    void parsesFromAMidFileRowBoundary() throws IOException {
        String content = "a,b\nc,d\ne,f\n";
        List<String[]> rows = parse(content, 4, 10); // offset of "c"
        assertEquals(2, rows.size());
        assertArrayEquals(new String[] {"c", "d"}, rows.get(0));
    }

    @Test
    void multiByteUtf8SurvivesFieldDecoding() throws IOException {
        List<String[]> rows = parse("name,unit\nflöde,m³/s\n", 0, 10);
        assertArrayEquals(new String[] {"flöde", "m³/s"}, rows.get(1));
    }

    @Test
    void aRunawayRowIsTruncatedInsteadOfAccumulatingTheFile() throws IOException {
        // A stray unbalanced quote makes one logical row span everything after it;
        // the parser must bail at its byte cap rather than buffer the remainder
        // (which for a 1GB file would OOM the whole IDE).
        StringBuilder sb = new StringBuilder("\"unclosed\n");
        String filler = "x".repeat(1024);
        while (sb.length() < RowBlockParser.MAX_BLOCK_BYTES + 2_000_000) {
            sb.append(filler).append('\n');
        }
        List<String[]> rows = parse(sb.toString(), 0, 10);
        assertEquals(1, rows.size(), "everything after the stray quote is one (truncated) row");
        assertTrue(rows.get(0)[0].length() <= RowBlockParser.MAX_BLOCK_BYTES + 128 * 1024,
            "accumulation stops near the cap");
    }
}
