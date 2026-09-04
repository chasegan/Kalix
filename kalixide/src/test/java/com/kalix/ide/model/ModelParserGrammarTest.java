package com.kalix.ide.model;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the map parser's grammar now that it scans lines by hand instead of
 * matching regexes: the same lines are, and are not, nodes and links as before.
 */
class ModelParserGrammarTest {

    private static ModelParser.ParseResult parse(String... lines) {
        return ModelParser.parse(String.join("\n", lines) + "\n");
    }

    @Test
    void nodeNeedsTypeAndTwoNumericCoordinates() {
        assertEquals(1, parse("[node.a]", "type = gauge", "loc = 1.5e2, -20").getNodes().size());
        assertTrue(parse("[node.a]", "loc = 1, 2").getNodes().isEmpty(), "no type");
        assertTrue(parse("[node.a]", "type = gauge").getNodes().isEmpty(), "no loc");
        assertTrue(parse("[node.a]", "type = gauge", "loc = 1, 2, 3").getNodes().isEmpty(), "three values");
        assertTrue(parse("[node.a]", "type = gauge", "loc = 1").getNodes().isEmpty(), "one value");
        assertTrue(parse("[node.a]", "type = gauge", "loc = 1,").getNodes().isEmpty(), "empty second value");
        assertTrue(parse("[node.a]", "type =", "loc = 1, 2").getNodes().isEmpty(), "empty type");
    }

    @Test
    void coordinatesUseTheEngineSpellingNotJavaSpelling() {
        // Double.parseDouble would accept these; the engine and the old regex do not.
        assertTrue(parse("[node.a]", "type = gauge", "loc = NaN, 1").getNodes().isEmpty());
        assertTrue(parse("[node.a]", "type = gauge", "loc = 0x1p3, 1").getNodes().isEmpty());
        assertTrue(parse("[node.a]", "type = gauge", "loc = 1d, 1").getNodes().isEmpty());
        assertTrue(parse("[node.a]", "type = gauge", "loc = 1.2.3, 1").getNodes().isEmpty(), "right characters, not a number");
    }

    @Test
    void lastLocAndTypeWin() {
        ModelParser.ParseResult result = parse("[node.a]", "type = gauge", "loc = 1, 2", "type = inflow", "loc = 3, 4");
        assertEquals("inflow", result.getNodes().get(0).getType());
        assertEquals(3.0, result.getNodes().get(0).getX());
    }

    @Test
    void keysAreExactAndCaseSensitive() {
        assertTrue(parse("[node.a]", "Type = gauge", "loc = 1, 2").getNodes().isEmpty());
        assertTrue(parse("[node.a]", "type = gauge", "Loc = 1, 2").getNodes().isEmpty());
        assertTrue(parse("[node.a]", "type = gauge", "refloc = 1, 2").getNodes().isEmpty());
        assertEquals(1, parse("[node.a]", "type=gauge", "loc=1,2").getNodes().size(), "spacing is free");
    }

    @Test
    void downstreamLinksAreDsFollowedByDigitsOnly() {
        ModelParser.ParseResult result = parse(
                "[node.a]", "type = gauge", "loc = 1, 2",
                "ds_1 = b", "ds_2 = c", "ds_01 = d", "ds_1_outlet = 3", "ds_ = e", "ds_x = f", "ds_3 =",
                "[node.b]", "type = gauge", "loc = 1, 2",
                "[node.c]", "type = gauge", "loc = 1, 2",
                "[node.d]", "type = gauge", "loc = 1, 2");
        List<String> links = result.getLinks().stream()
                .map(l -> l.getDownstreamTerminus() + (l.isPrimary() ? "!" : "")).toList();
        assertEquals(List.of("b!", "c", "d"), links, "ds_01 is a link but not primary; ds_1_outlet, ds_, ds_x, empty are not links");
    }

    @Test
    void nonNodeHeaderClosesTheNodeScope() {
        ModelParser.ParseResult result = parse("[node.a]", "type = gauge", "[outputs]", "loc = 1, 2");
        assertTrue(result.getNodes().isEmpty(), "loc after [outputs] does not belong to node.a");
    }

    @Test
    void crlfParsesLikeLf() {
        String lf = "[node.a]\ntype = gauge\nloc = 1, 2\nds_1 = b\n[node.b]\ntype = gauge\nloc = 3, 4\n";
        ModelParser.ParseResult a = ModelParser.parse(lf);
        ModelParser.ParseResult b = ModelParser.parse(lf.replace("\n", "\r\n"));
        assertEquals(a.getNodes().size(), b.getNodes().size());
        assertEquals(a.getLinks().size(), b.getLinks().size());
        assertEquals(a.getNodes().get(1).getY(), b.getNodes().get(1).getY());
        assertFalse(b.getNodes().get(1).getType().endsWith("\r"));
    }
}
