package com.kalix.ide.interaction;

import com.kalix.ide.model.ModelNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The find box matches on name or aka and lists an annotated node as "aka (name)". */
class MapSearchManagerTest {

    private static final ModelNode PLAIN = new ModelNode("gauge_1", "gauge", 0, 0, null);
    private static final ModelNode NAMED = new ModelNode("teemburra", "storage", 0, 0, "Teemburra Dam");

    @Test
    void filterMatchesNameOrAkaCaseInsensitively() {
        assertTrue(MapSearchManager.matches(PLAIN, "gauge"));
        assertTrue(MapSearchManager.matches(NAMED, "teem"));
        assertTrue(MapSearchManager.matches(NAMED, "dam"));
        assertFalse(MapSearchManager.matches(PLAIN, "dam"));
        assertTrue(MapSearchManager.matches(PLAIN, ""));
    }

    @Test
    void listTextShowsTheAkaWithTheNameInBrackets() {
        assertEquals("gauge_1", MapSearchManager.listText(PLAIN));
        assertEquals("Teemburra Dam (teemburra)", MapSearchManager.listText(NAMED));
    }
}
