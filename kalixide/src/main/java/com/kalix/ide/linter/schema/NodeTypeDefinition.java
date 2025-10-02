package com.kalix.ide.linter.schema;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Defines a node type in the linter schema including its parameters and validation rules.
 */
public class NodeTypeDefinition {
    public String name;
    public String description;
    public Set<String> requiredParams = new HashSet<>();
    public Set<String> optionalParams = new HashSet<>();
    public Set<String> dsnodeParams = new HashSet<>();
    public Set<String> allowedOutputs = new HashSet<>();
    public Map<String, ParameterDefinition> parameterDefinitions = new HashMap<>();

    public Set<String> getAllowedParams() {
        Set<String> all = new HashSet<>();
        all.addAll(requiredParams);
        all.addAll(optionalParams);
        all.addAll(dsnodeParams);
        return all;
    }

    public ParameterDefinition getParameterDefinition(String paramName) {
        return parameterDefinitions.get(paramName);
    }
}