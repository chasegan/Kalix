package com.kalix.ide.interaction;

import com.kalix.ide.model.ModelLink;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LinkRulesTest {

    // a -> b -> c, and d on its own
    private static final List<ModelLink> LINKS = List.of(
        new ModelLink("a", "b", true),
        new ModelLink("b", "c", true));

    @Test
    void allowsNewLinkBetweenUnconnectedNodes() {
        assertTrue(LinkRules.allows(LINKS, "c", "d"));
        assertTrue(LinkRules.allows(LINKS, "d", "a"));
    }

    @Test
    void allowsSecondRouteToTheSameNode() {
        // a -> c alongside a -> b -> c is a branch, not a loop.
        assertTrue(LinkRules.allows(LINKS, "a", "c"));
    }

    @Test
    void refusesSelfLink() {
        assertFalse(LinkRules.allows(LINKS, "a", "a"));
    }

    @Test
    void refusesDuplicateLink() {
        assertFalse(LinkRules.allows(LINKS, "a", "b"));
    }

    @Test
    void refusesLoops() {
        assertFalse(LinkRules.allows(LINKS, "b", "a"));
        assertFalse(LinkRules.allows(LINKS, "c", "a"), "loop through an intermediate node");
    }
}
