package com.kalix.ide.linter.schema;

import com.kalix.ide.linter.LinterSchema;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the precomputed allowed-params union (review #74): getAllowedParams()
 * sits on the per-property validation hot path and must return one immutable
 * precomputed set, not a freshly merged HashSet per call.
 */
class NodeTypeDefinitionAllowedParamsTest {

    @Test
    void unionCoversRequiredOptionalAndDsnodeParams() {
        NodeTypeDefinition def = new NodeTypeDefinition();
        def.requiredParams.add("type");
        def.requiredParams.add("loc");
        def.optionalParams.add("harmony_fraction");
        def.dsnodeParams.add("ds_1");
        def.sealAllowedParams();

        assertEquals(Set.of("type", "loc", "harmony_fraction", "ds_1"), def.getAllowedParams());
    }

    @Test
    void repeatedCallsReturnTheSameImmutableSet() {
        NodeTypeDefinition def = new NodeTypeDefinition();
        def.requiredParams.add("type");
        def.sealAllowedParams();

        Set<String> first = def.getAllowedParams();
        assertSame(first, def.getAllowedParams(), "must not allocate a new set per call");
        assertThrows(UnsupportedOperationException.class, () -> first.add("x"));
    }

    @Test
    void schemaLoadSealsEveryNodeType() {
        LinterSchema schema = LinterSchema.loadDefault();
        for (NodeTypeDefinition def : schema.getNodeTypes().values()) {
            Set<String> params = def.getAllowedParams();
            assertSame(params, def.getAllowedParams(),
                    "node type " + def.name + " must have a sealed allowed-params set");
            assertTrue(params.containsAll(def.requiredParams));
            assertTrue(params.containsAll(def.optionalParams));
            assertTrue(params.containsAll(def.dsnodeParams));
        }
    }

    @Test
    void schemaMapGettersReturnStableUnmodifiableViews() {
        LinterSchema schema = LinterSchema.loadDefault();
        assertSame(schema.getNodeTypes(), schema.getNodeTypes());
        assertSame(schema.getSections(), schema.getSections());
        assertSame(schema.getValidationRules(), schema.getValidationRules());
        assertSame(schema.getDataTypes(), schema.getDataTypes());
        assertThrows(UnsupportedOperationException.class, () -> schema.getNodeTypes().clear());
    }
}
