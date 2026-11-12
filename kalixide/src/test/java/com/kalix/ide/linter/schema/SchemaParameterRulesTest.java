package com.kalix.ide.linter.schema;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kalix.ide.linter.LinterSchema;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Holds the shipped schema's parameter rules (exclusive_params,
 * overridden_params, requires_one_of) to the parameters they name. The loader
 * reads them leniently, so a misspelt key or parameter name would otherwise
 * give a rule that never fires, with no error.
 */
class SchemaParameterRulesTest {

    private static final Set<String> NODE_TYPE_KEYS = Set.of(
            "description", "allowed_outputs", "required_params", "optional_params", "dsnode_params",
            "parameters", "exclusive_params", "overridden_params", "requires_one_of");

    @Test
    void nodeTypesUseOnlyTheKeysTheLoaderReads() throws Exception {
        JsonNode types = new ObjectMapper().readTree(LinterSchema.getDefaultSchemaContent()).path("node_types");
        List<String> unknown = new ArrayList<>();
        for (Iterator<Map.Entry<String, JsonNode>> it = types.fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> type = it.next();
            type.getValue().fieldNames().forEachRemaining(key -> {
                if (!NODE_TYPE_KEYS.contains(key)) {
                    unknown.add(type.getKey() + "." + key);
                }
            });
        }
        assertEquals(List.of(), unknown, "keys the loader would silently ignore");
    }

    @Test
    void everyRuleNamesParametersTheNodeTypeAllows() {
        List<String> problems = new ArrayList<>();
        int rules = 0;
        for (NodeTypeDefinition type : LinterSchema.loadDefault().getNodeTypes().values()) {
            Set<String> allowed = type.getAllowedParams();
            for (List<String> group : type.exclusiveParams) {
                rules++;
                if (group.size() < 2) {
                    problems.add(type.name + ": exclusive group " + group + " needs two or more parameters");
                }
                group.stream().filter(p -> !allowed.contains(p))
                        .forEach(p -> problems.add(type.name + ": exclusive_params names unknown " + p));
            }
            for (NodeTypeDefinition.OverriddenParams rule : type.overriddenParams) {
                rules++;
                ParameterDefinition flag = type.getParameterDefinition(rule.whenTrue());
                if (!allowed.contains(rule.whenTrue()) || flag == null || !"boolean".equals(flag.type)) {
                    problems.add(type.name + ": overridden_params when_true '" + rule.whenTrue()
                            + "' is not a boolean parameter");
                }
                if (rule.params().isEmpty()) {
                    problems.add(type.name + ": overridden_params under " + rule.whenTrue() + " lists no params");
                }
                rule.params().stream().filter(p -> !allowed.contains(p))
                        .forEach(p -> problems.add(type.name + ": overridden_params names unknown " + p));
            }
            for (NodeTypeDefinition.RequiresOneOf rule : type.requiresOneOf) {
                rules++;
                if (!allowed.contains(rule.param())) {
                    problems.add(type.name + ": requires_one_of param '" + rule.param() + "' is unknown");
                }
                if (rule.oneOf().isEmpty()) {
                    problems.add(type.name + ": requires_one_of for " + rule.param() + " lists nothing");
                }
                rule.oneOf().stream().filter(p -> !allowed.contains(p))
                        .forEach(p -> problems.add(type.name + ": requires_one_of names unknown " + p));
            }
        }
        assertEquals(List.of(), problems);
        assertTrue(rules >= 3, "the storage rules must have loaded, or this test checks nothing");
    }
}
