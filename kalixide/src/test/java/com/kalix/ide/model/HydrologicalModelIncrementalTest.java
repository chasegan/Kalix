package com.kalix.ide.model;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the incremental-update semantics of
 * {@link HydrologicalModel#parseFromIniTextIncremental(String)}: the
 * no-change short-circuit, and add/modify/remove detection with a single
 * consolidated change event carrying affected counts.
 *
 * <p>Converted from the ad-hoc {@code main()} harnesses that used to live in
 * {@code src/main} ({@code IncrementalUpdateTest}, {@code StatusReportingTest}).
 */
class HydrologicalModelIncrementalTest {

    private static final String TWO_NODES =
        "[node.node1]\n" +
        "type = gr4j\n" +
        "loc = 10, 20\n" +
        "\n" +
        "[node.node2]\n" +
        "type = outlet\n" +
        "loc = 30, 40\n";

    private HydrologicalModel model;
    private List<ModelChangeEvent> events;

    @BeforeEach
    void setUp() {
        model = new HydrologicalModel();
        events = new ArrayList<>();
        model.addChangeListener(events::add);
    }

    @Test
    void initialIncrementalParsePopulatesModel() {
        model.parseFromIniTextIncremental(TWO_NODES);

        assertEquals(2, model.getStatistics().getNodeCount());
        assertEquals(0, model.getStatistics().getLinkCount());
        assertNotNull(model.getNode("node1"));
        assertNotNull(model.getNode("node2"));
        assertTrue(events.stream().anyMatch(
            e -> e.getType() == ModelChangeEvent.Type.MODEL_RELOADED),
            "initial parse should fire a MODEL_RELOADED event");
    }

    @Test
    void identicalReparseFiresNoEvents() {
        model.parseFromIniTextIncremental(TWO_NODES);
        events.clear();

        model.parseFromIniTextIncremental(TWO_NODES);

        assertTrue(events.isEmpty(), "unchanged text must not fire change events");
        assertEquals(2, model.getStatistics().getNodeCount());
    }

    @Test
    void addedNodeFiresSingleConsolidatedEvent() {
        model.parseFromIniTextIncremental(TWO_NODES);
        events.clear();

        model.parseFromIniTextIncremental(TWO_NODES +
            "\n[node.node3]\n" +
            "type = storage\n" +
            "loc = 50, 60\n");

        assertEquals(3, model.getStatistics().getNodeCount());
        assertNotNull(model.getNode("node3"));
        assertEquals(1, events.size(), "expected one consolidated event");
        assertEquals(ModelChangeEvent.Type.MODEL_RELOADED, events.get(0).getType());
        assertEquals(1, events.get(0).getAffectedNodeCount());
    }

    @Test
    void modifiedNodeLocationIsDetected() {
        model.parseFromIniTextIncremental(TWO_NODES);
        events.clear();

        model.parseFromIniTextIncremental(TWO_NODES.replace("loc = 10, 20", "loc = 15, 25"));

        assertEquals(1, events.size(), "expected one consolidated event");
        assertEquals(1, events.get(0).getAffectedNodeCount());
        ModelNode node1 = model.getNode("node1");
        assertEquals(15.0, node1.getX(), 1e-9);
        assertEquals(25.0, node1.getY(), 1e-9);
    }

    @Test
    void removedNodeIsDetected() {
        model.parseFromIniTextIncremental(TWO_NODES);
        events.clear();

        model.parseFromIniTextIncremental(
            "[node.node1]\n" +
            "type = gr4j\n" +
            "loc = 10, 20\n");

        assertEquals(1, model.getStatistics().getNodeCount());
        assertNull(model.getNode("node2"));
        assertEquals(1, events.size(), "expected one consolidated event");
        assertEquals(1, events.get(0).getAffectedNodeCount());
    }
}
