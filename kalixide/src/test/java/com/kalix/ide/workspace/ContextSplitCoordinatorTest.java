package com.kalix.ide.workspace;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The shared divider model: real changes persist exactly once, no-ops and
 * transient collapse artefacts (non-positive widths) never do.
 */
class ContextSplitCoordinatorTest {

    @Test
    void widthChangesArePersistedAndNoOpsAreNot() {
        List<String> persisted = new ArrayList<>();
        ContextSplitCoordinator c = new ContextSplitCoordinator(420, false,
            (w, col) -> persisted.add(w + ":" + col));

        c.setWidth(300);
        assertEquals(300, c.width());
        c.setWidth(300); // unchanged -> no persist
        c.setWidth(0);   // transient collapse artefact -> ignored
        c.setWidth(-5);
        assertEquals(300, c.width());
        assertEquals(List.of("300:false"), persisted);
    }

    @Test
    void collapseChangesArePersistedAndKeepTheRememberedWidth() {
        List<String> persisted = new ArrayList<>();
        ContextSplitCoordinator c = new ContextSplitCoordinator(420, false,
            (w, col) -> persisted.add(w + ":" + col));

        c.setCollapsed(true);
        assertTrue(c.isCollapsed());
        assertEquals(420, c.width(), "collapse keeps the remembered expanded width");
        c.setCollapsed(true); // unchanged -> no persist
        c.setCollapsed(false);
        assertFalse(c.isCollapsed());
        assertEquals(List.of("420:true", "420:false"), persisted);
    }
}
