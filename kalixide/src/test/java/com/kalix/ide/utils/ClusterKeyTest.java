package com.kalix.ide.utils;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * A cluster's key is its name matched ignoring case, as node names are (ADR-0010 §6.2),
 * so every spelling of one cluster makes one key, and a map keyed by it finds the cluster
 * from any spelling (ADR-0003 §2.1).
 */
class ClusterKeyTest {

    @Test
    void namesDifferingOnlyInCaseHaveOneKey() {
        assertEquals(new ClusterKey("teemburra"), new ClusterKey("Teemburra"));
        assertEquals(new ClusterKey("teemburra"), new ClusterKey("TEEMBURRA"));
        assertNotEquals(new ClusterKey("teemburra"), new ClusterKey("teemburra_2"));
    }

    @Test
    void theKeyIsTheNameFoldedAsNodeNamesAre() {
        assertEquals(EngineNames.nodeKey("Teemburra"), new ClusterKey("Teemburra").value());
    }

    @Test
    void aNullNameIsRefusedByName() {
        NullPointerException refused = assertThrows(NullPointerException.class, () -> new ClusterKey(null));
        assertEquals("cluster name", refused.getMessage());
    }

    @Test
    void aMapKeyedByItIsFoundFromAnySpelling() {
        Map<ClusterKey, String> shown = Map.of(new ClusterKey("North"), "North");
        assertEquals("North", shown.get(new ClusterKey("NORTH")));
    }
}
