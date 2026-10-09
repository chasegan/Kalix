package com.kalix.ide.model;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins {@link HydrologicalModel#selectNodes(java.util.Collection)}: it adds to the
 * selection, ignores names that are not nodes, and notifies listeners once per call
 * and only when the selection changed.
 */
class HydrologicalModelSelectNodesTest {

    private HydrologicalModel model;
    private List<ModelChangeEvent> events;

    @BeforeEach
    void setUp() {
        model = new HydrologicalModel();
        model.addNode(new ModelNode("a", "gr4j", 0, 0));
        model.addNode(new ModelNode("b", "gr4j", 10, 0));
        model.addNode(new ModelNode("c", "outlet", 20, 0));
        events = new ArrayList<>();
        model.addChangeListener(events::add);
    }

    @Test
    void selectsEveryNamedNode() {
        model.selectNodes(List.of("a", "c"));

        assertEquals(Set.of("a", "c"), model.getSelectedNodes());
    }

    @Test
    void addsToTheExistingSelection() {
        model.selectNode("a", false);

        model.selectNodes(List.of("b"));

        assertEquals(Set.of("a", "b"), model.getSelectedNodes());
    }

    @Test
    void keepsSelectedLinks() {
        ModelLink link = new ModelLink("a", "b", true);
        model.addLink(link);
        model.selectLink(link, false);

        model.selectNodes(List.of("c"));

        assertTrue(model.isLinkSelected(link), "a bulk select must not clear the link selection");
    }

    @Test
    void ignoresNamesThatAreNotNodes() {
        model.selectNodes(List.of("a", "missing"));

        assertEquals(Set.of("a"), model.getSelectedNodes());
    }

    @Test
    void firesOneEventForManyNodes() {
        model.selectNodes(List.of("a", "b", "c"));

        assertEquals(1, events.size());
        assertEquals(ModelChangeEvent.Type.NODE_SELECTED, events.get(0).getType());
        assertNull(events.get(0).getEntityId(), "a bulk event names no single node");
        assertEquals(0, events.get(0).getAffectedNodeCount(),
            "a selection change must not report nodes as modified");
    }

    @Test
    void firesNoEventWhenNothingChanges() {
        model.selectNodes(List.of("a", "b"));
        events.clear();

        model.selectNodes(List.of("a", "b"));
        model.selectNodes(List.of("missing"));
        model.selectNodes(List.of());

        assertTrue(events.isEmpty(), "an unchanged selection must not notify listeners");
    }
}
