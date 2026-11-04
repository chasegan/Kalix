package com.kalix.ide.linter.parsing;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Each key says which values it accepts (ADR-0010 §4), so the parser and the linter
 * can't disagree. A cluster is a strict bare name (ADR-0010 §6.2); the others take any
 * text, an empty aka meaning none.
 */
class AnnotationKeyValueTest {

    @Test
    void aClusterIsAStrictBareName() {
        for (String name : List.of("a", "farm", "farm_3", "a1_b2", "f_")) {
            assertTrue(AnnotationKey.CLUSTER.isValidValue(name), name);
        }
    }

    @Test
    void aClusterIsNothingElse() {
        for (String name : List.of("", "Farm", "3farm", "_farm", "farm-3", "farm 3", "farm.north", "farm # note")) {
            assertFalse(AnnotationKey.CLUSTER.isValidValue(name), name);
        }
    }

    @Test
    void theOtherKeysAcceptAnyValue() {
        for (AnnotationKey key : List.of(AnnotationKey.AKA, AnnotationKey.REGION, AnnotationKey.LOC)) {
            assertTrue(key.isValidValue("Not A Bare Name!"), key.key());
            assertTrue(key.isValidValue(""), key.key());
        }
    }
}
