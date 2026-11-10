package com.kalix.ide.interaction;

import com.kalix.ide.model.ClusterIndex;
import com.kalix.ide.model.ModelNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A cluster spelled two ways (ADR-0010 §6.2) is one cluster on the menu, named with the
 * index's spelling, so Add writes that spelling and the two come back together.
 *
 * <p>The model: north (n1 as {@code north}, n2 as {@code North}) and loose x.
 */
class ClusterMenuRulesCaseTest {

    private static final ClusterIndex INDEX = ClusterIndex.of(List.of(
            node("n1", "north"), node("n2", "North"), node("x", null)));

    private static ModelNode node(String name, String cluster) {
        return new ModelNode(name, "storage", 0, 0, null, cluster);
    }

    private static ClusterMenuRules rules(String... selected) {
        return ClusterMenuRules.of(List.of(selected), INDEX, Set.of());
    }

    @Test
    void addOffersOneTargetWithTheIndexSpelling() {
        assertEquals(List.of("north"), rules("x").addTargets());
    }

    @Test
    void membersSpelledDifferentlyAreOneWholeClusterToRename() {
        assertEquals("north", rules("n1", "n2").renameTarget());
    }

    @Test
    void membersSpelledDifferentlyAreOneClusterToDisband() {
        assertEquals(List.of("north"), rules("n1", "n2").disbandTargets());
    }
}
