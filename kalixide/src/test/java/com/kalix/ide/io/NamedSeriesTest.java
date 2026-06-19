package com.kalix.ide.io;

import com.kalix.ide.flowviz.data.TimeSeriesData;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class NamedSeriesTest {

    private static TimeSeriesData data() {
        return new TimeSeriesData(new LocalDateTime[] { LocalDateTime.now() }, new double[] { 1.0 });
    }

    @Test
    void flatConstructorKeepsSingleSegmentPath() {
        NamedSeries ns = new NamedSeries("flow", data());
        assertEquals("flow", ns.name());
        assertEquals(List.of("flow"), ns.path());
    }

    @Test
    void dottedSplitsRunStyleNameIntoSegments() {
        NamedSeries ns = NamedSeries.dotted("node.x.dsflow", data());
        // Name stays whole for the legend; path nests like the in-memory run.
        assertEquals("node.x.dsflow", ns.name());
        assertEquals(List.of("node", "x", "dsflow"), ns.path());
    }

    @Test
    void dottedWithoutDotIsSingleSegment() {
        assertEquals(List.of("flow"), NamedSeries.dotted("flow", data()).path());
    }

    @Test
    void dottedDropsBlankSegments() {
        assertEquals(List.of("node", "x"), NamedSeries.dotted("node..x.", data()).path());
    }

    @Test
    void dottedAllBlankFallsBackToName() {
        assertEquals(List.of("."), NamedSeries.dotted(".", data()).path());
    }
}
