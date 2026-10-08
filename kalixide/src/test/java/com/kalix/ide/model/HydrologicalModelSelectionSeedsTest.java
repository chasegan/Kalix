package com.kalix.ide.model;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pins {@link HydrologicalModel#getSelectionSeedNodes()}: selected nodes seed, and
 * selected links seed their two ends only when no node is selected.
 */
class HydrologicalModelSelectionSeedsTest {

    // a -> s -> j
    private HydrologicalModel model;
    private ModelLink aToS;
    private ModelLink sToJ;

    @BeforeEach
    void setUp() {
        model = new HydrologicalModel();
        model.addNode(new ModelNode("a", "gr4j", 0, 0));
        model.addNode(new ModelNode("s", "gr4j", 10, 0));
        model.addNode(new ModelNode("j", "outlet", 20, 0));
        aToS = new ModelLink("a", "s", true);
        sToJ = new ModelLink("s", "j", true);
        model.addLink(aToS);
        model.addLink(sToJ);
    }

    @Test
    void nothingSelectedGivesNoSeeds() {
        assertEquals(Set.of(), model.getSelectionSeedNodes());
    }

    @Test
    void selectedNodesAreSeeds() {
        model.selectNode("a", false);
        model.selectNode("j", true);

        assertEquals(Set.of("a", "j"), model.getSelectionSeedNodes());
    }

    @Test
    void loneSelectedLinkSeedsBothEnds() {
        model.selectLink(sToJ, false);

        assertEquals(Set.of("s", "j"), model.getSelectionSeedNodes());
    }

    @Test
    void linksTouchingASelectedNodeAddNothing() {
        // What a rectangle around s selects: the node and both links touching it.
        model.selectNode("s", false);
        model.selectLink(aToS, true);
        model.selectLink(sToJ, true);

        assertEquals(Set.of("s"), model.getSelectionSeedNodes());
    }

    @Test
    void linkAwayFromTheSelectedNodesAddsNothing() {
        // What a rectangle around a selects when the link s -> j also crosses it.
        model.selectNode("a", false);
        model.selectLink(sToJ, true);

        assertEquals(Set.of("a"), model.getSelectionSeedNodes());
    }

    @Test
    void severalSelectedLinksSeedAllTheirEnds() {
        model.selectLink(aToS, false);
        model.selectLink(sToJ, true);

        assertEquals(Set.of("a", "s", "j"), model.getSelectionSeedNodes());
    }

    @Test
    void namesNoLongerInTheModelAreNotSeeds() {
        // The only selected node is gone, so the link seeds; its removed end does not.
        model.selectNode("a", false);
        model.selectLink(sToJ, true);
        model.removeNode("a");
        model.removeNode("j");

        assertEquals(Set.of("s"), model.getSelectionSeedNodes());
    }

    @Test
    void selectedLinkNoLongerInTheModelSeedsNothing() {
        model.selectLink(sToJ, false);
        model.removeLink(sToJ);

        assertEquals(Set.of(), model.getSelectionSeedNodes());
    }

    @Test
    void seedsAreACopy() {
        model.selectNode("a", false);

        model.getSelectionSeedNodes().add("j");

        assertEquals(Set.of("a"), model.getSelectedNodes());
    }
}
