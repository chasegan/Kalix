package com.kalix.ide.linter.schema;

import java.util.Collections;
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

    // Union of required/optional/dsnode params, computed once after the schema
    // loads. getAllowedParams() sits on the per-property validation hot path;
    // merging a fresh HashSet per call was pure allocation churn.
    private Set<String> allowedParams;

    /**
     * Precompute the immutable allowed-parameter union. Called once by the
     * schema loader after the param sets are populated; the getter falls back
     * to computing it for instances built by hand (e.g. in tests).
     */
    public void sealAllowedParams() {
        Set<String> all = new HashSet<>();
        all.addAll(requiredParams);
        all.addAll(optionalParams);
        all.addAll(dsnodeParams);
        allowedParams = Collections.unmodifiableSet(all);
    }

    public Set<String> getAllowedParams() {
        if (allowedParams == null) {
            sealAllowedParams();
        }
        return allowedParams;
    }

    public ParameterDefinition getParameterDefinition(String paramName) {
        return parameterDefinitions.get(paramName);
    }
}