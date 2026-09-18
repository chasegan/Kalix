package com.kalix.ide.interaction;

import com.kalix.ide.model.ModelLink;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class LinkRulesTest {

    // a -> b -> c, and d on its own
    private static final List<ModelLink> LINKS = List.of(
        new ModelLink("a", "b", true),
        new ModelLink("b", "c", true));

    @Test
    void allowsNewLinkBetweenUnconnectedNodes() {
        assertNull(LinkRules.refusal(LINKS, "c", "d"));
        assertNull(LinkRules.refusal(LINKS, "d", "a"));
    }

    @Test
    void allowsSecondRouteToTheSameNode() {
        // a -> c alongside a -> b -> c is a branch, not a loop.
        assertNull(LinkRules.refusal(LINKS, "a", "c"));
    }

    @Test
    void refusesSelfLink() {
        assertNotNull(LinkRules.refusal(LINKS, "a", "a"));
    }

    @Test
    void refusesDuplicateLink() {
        assertNotNull(LinkRules.refusal(LINKS, "a", "b"));
    }

    @Test
    void refusesLoops() {
        assertNotNull(LinkRules.refusal(LINKS, "b", "a"));
        assertNotNull(LinkRules.refusal(LINKS, "c", "a"), "loop through an intermediate node");
    }
}
