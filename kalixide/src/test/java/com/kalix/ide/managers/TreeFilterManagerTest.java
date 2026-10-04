package com.kalix.ide.managers;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins what the filter box does with text that parses and text that does not,
 * driving {@link TreeFilterManager#apply} directly instead of through the
 * debounce timer. Headless: a JTextField needs no display.
 */
class TreeFilterManagerTest {

    @Test
    void invalidTextKeepsTheLastValidFilterAndShowsWhy() {
        AtomicInteger rebuilds = new AtomicInteger();
        TreeFilterManager m = new TreeFilterManager(rebuilds::incrementAndGet);

        m.apply("inflow");
        SeriesFilter inflow = m.getFilter();
        assertTrue(inflow.isActive());
        assertEquals(1, rebuilds.get());
        assertFalse(m.isShowingError());

        // A regex left open while typing: red, reason in the tooltip, tree untouched.
        m.apply("inflow /ds_");
        assertSame(inflow, m.getFilter());
        assertEquals(1, rebuilds.get());
        assertTrue(m.isShowingError());
        assertEquals("Regex not closed: end it with /", m.getTooltip());

        // Closing it applies the new filter and clears the error.
        m.apply("inflow /ds_1/");
        assertTrue(m.getFilter().isActive());
        assertEquals(2, rebuilds.get());
        assertFalse(m.isShowingError());
        assertTrue(m.getTooltip().startsWith("Show series matching every term"));
    }

    @Test
    void clearingTheTextRemovesTheFilterAndAnyError() {
        AtomicInteger rebuilds = new AtomicInteger();
        TreeFilterManager m = new TreeFilterManager(rebuilds::incrementAndGet);

        m.apply("\"qu art");
        assertTrue(m.isShowingError());
        assertSame(SeriesFilter.NONE, m.getFilter());

        m.apply("");
        assertFalse(m.isShowingError());
        assertSame(SeriesFilter.NONE, m.getFilter());
        assertFalse(m.isFiltering());
        assertEquals(1, rebuilds.get());
    }

    @Test
    void halfTypedTextIsNoFilterAndNoError() {
        TreeFilterManager m = new TreeFilterManager(() -> { });
        m.apply("inflow !");
        assertFalse(m.isShowingError());
        assertTrue(m.isFiltering());
        m.apply("!");
        assertFalse(m.isShowingError());
        assertFalse(m.isFiltering());
    }
}
