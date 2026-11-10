package com.kalix.ide.linter.parsing;

import com.kalix.ide.utils.EngineNames;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Each key says which values it accepts (ADR-0010 §4), so the parser and the linter
 * can't disagree. A cluster name follows the node-name rule (ADR-0010 §6.2); the
 * others take any text, an empty aka meaning none.
 */
class AnnotationKeyValueTest {

    private static final List<String> VALID = List.of(
            "a", "farm", "farm_3", "Farm", "Teemburra", "3farm", "0202_dam", "_farm", "_spare", "FARM_3");

    private static final List<String> INVALID = List.of(
            "", "farm-3", "farm 3", "farm.north", "farm # note", "café", "farm\t3");

    @Test
    void aClusterIsLettersDigitsAndUnderscoresInEitherCase() {
        for (String name : VALID) {
            assertTrue(AnnotationKey.CLUSTER.isValidValue(name), name);
        }
    }

    @Test
    void aClusterIsNothingElse() {
        for (String name : INVALID) {
            assertFalse(AnnotationKey.CLUSTER.isValidValue(name), name);
        }
    }

    @Test
    void aClusterNameIsValidExactlyWhenANodeNameWouldBe() {
        for (String name : List.of(VALID, INVALID).stream().flatMap(List::stream).toList()) {
            assertEquals(EngineNames.isValidNodeName(name), AnnotationKey.CLUSTER.isValidValue(name), name);
        }
    }

    @Test
    void theOtherKeysAcceptAnyValue() {
        for (AnnotationKey key : List.of(AnnotationKey.AKA, AnnotationKey.REGION, AnnotationKey.LOC)) {
            assertTrue(key.isValidValue("Not A Bare Name!"), key.key());
            assertTrue(key.isValidValue(""), key.key());
        }
    }

    @Test
    void aClusterStatesItsRule() {
        assertEquals("Cluster names may contain only letters, digits and underscores",
                AnnotationKey.CLUSTER.valueRule());
    }

    @Test
    void aKeyThatAcceptsAnyValueHasNoRule() {
        for (AnnotationKey key : List.of(AnnotationKey.AKA, AnnotationKey.REGION, AnnotationKey.LOC)) {
            assertNull(key.valueRule(), key.key());
        }
    }
}
