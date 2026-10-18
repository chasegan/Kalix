package com.kalix.ide.rendering;

import com.kalix.ide.model.ModelNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The label button's cycle, and what each mode draws with and without hover. */
class NodeLabelModeTest {

    private static final ModelNode PLAIN = new ModelNode("gauge_1", "gauge", 0, 0, null);
    private static final ModelNode NAMED = new ModelNode("teemburra", "storage", 0, 0, "Teemburra Dam");

    @Test
    void cycleIsNamesThenAkaThenHidden() {
        assertEquals(NodeLabelMode.AKA, NodeLabelMode.NAMES.next());
        assertEquals(NodeLabelMode.HIDDEN, NodeLabelMode.AKA.next());
        assertEquals(NodeLabelMode.NAMES, NodeLabelMode.HIDDEN.next());
    }

    @Test
    void namesModeDrawsEveryName() {
        assertEquals(List.of("gauge_1"), NodeLabelMode.NAMES.labelLines(PLAIN, false));
        assertEquals(List.of("teemburra"), NodeLabelMode.NAMES.labelLines(NAMED, false));
        assertFalse(NodeLabelMode.NAMES.revealsOnHover());
        assertTrue(NodeLabelMode.NAMES.drawsLabels());
    }

    @Test
    void akaModeDrawsOnlyAkasAndHoverSwapsInTheName() {
        assertEquals(List.of(), NodeLabelMode.AKA.labelLines(PLAIN, false));
        assertEquals(List.of("Teemburra Dam"), NodeLabelMode.AKA.labelLines(NAMED, false));
        assertEquals(List.of("gauge_1"), NodeLabelMode.AKA.labelLines(PLAIN, true));
        assertEquals(List.of("teemburra"), NodeLabelMode.AKA.labelLines(NAMED, true));
        assertTrue(NodeLabelMode.AKA.revealsOnHover());
        assertTrue(NodeLabelMode.AKA.drawsLabels());
    }

    @Test
    void hiddenModeDrawsNothingAndHoverRevealsAkaThenName() {
        assertEquals(List.of(), NodeLabelMode.HIDDEN.labelLines(PLAIN, false));
        assertEquals(List.of(), NodeLabelMode.HIDDEN.labelLines(NAMED, false));
        assertEquals(List.of("gauge_1"), NodeLabelMode.HIDDEN.labelLines(PLAIN, true));
        assertEquals(List.of("Teemburra Dam", "teemburra"), NodeLabelMode.HIDDEN.labelLines(NAMED, true));
        assertTrue(NodeLabelMode.HIDDEN.revealsOnHover());
        assertFalse(NodeLabelMode.HIDDEN.drawsLabels());
    }

    @Test
    void keysRoundTripAndUnknownKeyIsNull() {
        for (NodeLabelMode mode : NodeLabelMode.values()) {
            assertEquals(mode, NodeLabelMode.fromKey(mode.key()));
        }
        assertNull(NodeLabelMode.fromKey("true"));
        assertNull(NodeLabelMode.fromKey(null));
    }
}
