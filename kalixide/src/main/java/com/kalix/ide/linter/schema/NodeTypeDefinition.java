package com.kalix.ide.linter.schema;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

    /** The highest N among this type's {@code ds_N} params, or 0 if it has none. */
    public int maxDsOutlet() {
        int max = 0;
        for (String param : dsnodeParams) {
            Matcher m = DS_PARAM.matcher(param);
            if (m.matches()) {
                max = Math.max(max, Integer.parseInt(m.group(1)));
            }
        }
        return max;
    }

    private static final Pattern DS_PARAM = Pattern.compile("ds_(\\d{1,9})");
}