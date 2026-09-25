package com.kalix.ide.managers;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pins the Timeseries tree filter grammar (#397). */
class SeriesFilterTest {

    private static boolean matches(String filter, String seriesName) throws Exception {
        return SeriesFilter.parse(filter).matches(seriesName, "Run_1");
    }

    @Test
    void blankTextAndALoneBangAreNoFilter() throws Exception {
        assertSame(SeriesFilter.NONE, SeriesFilter.parse(""));
        assertSame(SeriesFilter.NONE, SeriesFilter.parse("   "));
        assertSame(SeriesFilter.NONE, SeriesFilter.parse("!"));
        assertSame(SeriesFilter.NONE, SeriesFilter.parse(" ! "));
        assertFalse(SeriesFilter.NONE.isActive());
        assertTrue(SeriesFilter.NONE.matches("node.a.ds_1", "Run_1"));
    }

    @Test
    void plainTextIsACaseInsensitiveSubstring() throws Exception {
        assertTrue(matches("flow_3", "node.inflow_3.ds_1"));
        assertTrue(matches("INFLOW", "node.inflow_3.ds_1"));
        assertTrue(matches("node.inflow", "node.inflow_3.ds_1"));
        assertTrue(matches("ds_1", "node.inflow_3.ds_10"));
        assertFalse(matches("outflow", "node.inflow_3.ds_1"));
    }

    @Test
    void wildcardsArePartAligned() throws Exception {
        assertTrue(matches("inflow_*.ds_1", "node.inflow_3.ds_1"));
        assertFalse(matches("inflow_*.ds_1", "node.inflow_3.ds_10"));
        assertFalse(matches("flow_*.ds_1", "node.inflow_3.ds_1"));
        assertTrue(matches("*flow_*.ds_1", "node.inflow_3.ds_1"));
        assertTrue(matches("ds_?", "node.inflow_3.ds_1"));
        assertFalse(matches("ds_?", "node.inflow_3.ds_10"));
        assertTrue(matches("Node.*", "node.inflow_3.ds_1"));
    }

    @Test
    void starCrossesDots() throws Exception {
        assertTrue(matches("node*ds_1", "node.inflow_3.ds_1"));
        assertTrue(matches("*", "node.inflow_3.ds_1"));
    }

    @Test
    void wildcardLiteralsAreNotRegex() throws Exception {
        assertFalse(matches("node.a*", "nodeXa.ds_1"));
        assertTrue(matches("a+b*", "node.a+b.ds_1"));
    }

    @Test
    void spacesMeanOr() throws Exception {
        assertTrue(matches("outflow inflow", "node.inflow_3.ds_1"));
        assertTrue(matches("inflow outflow", "node.outflow_1.ds_1"));
        assertFalse(matches("inflow outflow", "node.gr4j_1.ds_1"));
    }

    @Test
    void bangExcludesWhateverKindOfTerm() throws Exception {
        assertFalse(matches("inflow !inflow_3", "node.inflow_3.ds_1"));
        assertTrue(matches("inflow !inflow_3", "node.inflow_4.ds_1"));
        assertFalse(matches("inflow !*_3.*", "node.inflow_3.ds_1"));
        assertFalse(matches("inflow !/_\\d\\./", "node.inflow_3.ds_1"));
    }

    @Test
    void negativeOnlyFiltersShowEverythingElse() throws Exception {
        SeriesFilter f = SeriesFilter.parse("!dummy_*");
        assertTrue(f.isActive());
        assertTrue(f.matches("node.inflow_3.ds_1", "Run_1"));
        assertFalse(f.matches("node.dummy_1.ds_1", "Run_1"));
    }

    @Test
    void termsAlsoMatchTheSourceLabel() throws Exception {
        SeriesFilter f = SeriesFilter.parse("run_2");
        assertTrue(f.matches("node.a.ds_1", "Run_2"));
        assertFalse(f.matches("node.a.ds_1", "Run_1"));
        assertFalse(SeriesFilter.parse("!Run_2").matches("node.a.ds_1", "Run_2"));
        assertTrue(SeriesFilter.parse("flows.*").matches("node.a.ds_1", "flows.csv"));
        assertTrue(SeriesFilter.parse("ds_1").matches("node.a.ds_1", null));
    }

    @Test
    void regexUsesFindAndIgnoresCase() throws Exception {
        assertTrue(matches("/inflow_(3|4)\\.ds_1$/", "node.inflow_3.ds_1"));
        assertFalse(matches("/inflow_(3|4)\\.ds_1$/", "node.inflow_3.ds_10"));
        assertTrue(matches("/^NODE\\./", "node.inflow_3.ds_1"));
    }

    @Test
    void regexUsesEscapedSlashesAndSitsAmongOtherTerms() throws Exception {
        assertTrue(matches("/x\\/y/", "node.x/y.ds_1"));
        assertTrue(matches("/nomatch/ inflow", "node.inflow_3.ds_1"));
        assertTrue(matches("//", "node.inflow_3.ds_1"));
    }

    @Test
    void spacesInNamesCanBeQuotedEscapedOrMatchedInARegex() throws Exception {
        // The engine accepts node names with spaces.
        assertTrue(matches("\"qu art\"", "node.qu art.ds_1"));
        assertTrue(matches("qu\\ art", "node.qu art.ds_1"));
        assertFalse(matches("qu\\ art", "node.quart.ds_1"));
        assertTrue(matches("\"qu art.*\"", "node.qu art.ds_1"));
        assertTrue(matches("/qu art/", "node.qu art.ds_1"));
        assertTrue(matches("/a b|inflow/", "node.inflow_3.ds_1"));
        assertTrue(matches("/qu\\sart/", "node.qu art.ds_1"));
        assertTrue(matches("qu?art.*", "node.qu art.ds_1"));
        assertTrue(SeriesFilter.parse("\"my flows\"").matches("node.a.ds_1", "my flows.csv"));
        assertFalse(matches("!\"qu art\"", "node.qu art.ds_1"));
    }

    @Test
    void quotesMakeTextNotRegexAndBackslashesAreDropped() throws Exception {
        assertTrue(SeriesFilter.parse("\"/x\"").matches("node.a.ds_1", "dir/x.csv"));
        assertTrue(SeriesFilter.parse("\"/\"").matches("node.a.ds_1", "dir/x.csv"));
        assertTrue(matches("\"a\\\"b\"", "node.a\"b.ds_1"));
        assertTrue(matches("inflow\\", "node.inflow\\.ds_1"));
    }

    @Test
    void trailingAndRepeatedSpacesAreHarmless() throws Exception {
        assertTrue(matches("inflow  ", "node.inflow_3.ds_1"));
        assertTrue(matches("  /inflow/   outflow  ", "node.inflow_3.ds_1"));
        assertTrue(matches("inflow !", "node.inflow_3.ds_1"));
        assertTrue(matches("! inflow", "node.inflow_3.ds_1"));
        assertFalse(matches("! inflow", "node.gr4j_1.ds_1"));
        assertSame(SeriesFilter.NONE, SeriesFilter.parse("\"\""));
    }

    @Test
    void badSyntaxIsReported() {
        assertMessage("Regex not closed: end it with /", "/inflow");
        assertMessage("Quote not closed: end it with \"", "\"qu art");
        assertMessage("Put a space after the closing / of a regex", "/a/b");
        assertMessage("Put a space after the closing quote", "\"a\"b");
        assertThrows(SeriesFilter.SyntaxException.class, () -> SeriesFilter.parse("/(/"));
        assertThrows(SeriesFilter.SyntaxException.class, () -> SeriesFilter.parse("!/"));
    }

    private static void assertMessage(String expected, String filter) {
        assertEquals(expected,
            assertThrows(SeriesFilter.SyntaxException.class, () -> SeriesFilter.parse(filter)).getMessage());
    }
}
