package com.kalix.ide.linter.utils;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The downstream-link key rule ({@code ^ds_\d+$}), now a character scan: it runs on
 * every property key of every node, twice per lint pass.
 */
class ValidationUtilsDsParamTest {

    @Test
    void dsFollowedByDigitsIsALinkKey() {
        assertTrue(ValidationUtils.isDsNodeParam("ds_1"));
        assertTrue(ValidationUtils.isDsNodeParam("ds_12"));
        assertTrue(ValidationUtils.isDsNodeParam("ds_007"));
    }

    @Test
    void anythingElseIsNot() {
        assertFalse(ValidationUtils.isDsNodeParam("ds_1_outlet"));
        assertFalse(ValidationUtils.isDsNodeParam("ds_1_order"));
        assertFalse(ValidationUtils.isDsNodeParam("ds_"));
        assertFalse(ValidationUtils.isDsNodeParam("ds_x"));
        assertFalse(ValidationUtils.isDsNodeParam("ds1"));
        assertFalse(ValidationUtils.isDsNodeParam("xds_1"));
        assertFalse(ValidationUtils.isDsNodeParam("DS_1"));
        assertFalse(ValidationUtils.isDsNodeParam(""));
    }
}
