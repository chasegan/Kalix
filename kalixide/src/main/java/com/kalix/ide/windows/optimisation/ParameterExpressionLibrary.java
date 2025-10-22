package com.kalix.ide.windows.optimisation;

import java.util.Map;

/**
 * Library of parameter type expressions for optimization configuration.
 * Maps parameter types to their default lin_range expressions.
 */
public class ParameterExpressionLibrary {

    // Map of parameter type to expression template (# is placeholder for counter)
    private static final Map<String, String> TYPE_EXPRESSIONS = Map.ofEntries(
        // GR4J parameters
        Map.entry("constant", "lin_range(g(#),0,100)"),
        Map.entry("x1", "lin_range(g(#),1,1500)"),
        Map.entry("x2", "lin_range(g(#),-10,6)"),
        Map.entry("x3", "lin_range(g(#),1,500)"),
        Map.entry("x4", "lin_range(g(#),0.5,4)"),

        // Sacramento parameters
        Map.entry("adimp", "log_range(g(#),1E-05,0.15)"),
        Map.entry("lzfpm", "log_range(g(#),1,300)"),
        Map.entry("lzfsm", "log_range(g(#),1,350)"),
        Map.entry("lzpk", "log_range(g(#),0.001,0.6)"),
        Map.entry("lzpkonlzsk", "log_range(g(#),0.001,1)"),
        Map.entry("lzsk", "log_range(g(#),0.001,0.9)"),
        Map.entry("lztwm", "log_range(g(#),10,600)"),
        Map.entry("pctim", "log_range(g(#),1E-05,0.11)"),
        Map.entry("pfree", "log_range(g(#),0.01,0.5)"),
        Map.entry("rexp", "log_range(g(#),1,6)"),
        Map.entry("sarva", "log_range(g(#),1E-05,0.11)"),
        Map.entry("sarvaonpctim", "log_range(g(#),0.0001,1)"),
        Map.entry("side", "log_range(g(#),1E-05,0.1)"),
        Map.entry("ssout", "log_range(g(#),1E-05,0.1)"),
        Map.entry("uzfwm", "log_range(g(#),5,155)"),
        Map.entry("uzk", "log_range(g(#),0.1,1)"),
        Map.entry("uztwm", "log_range(g(#),12,180)"),
        Map.entry("zperc", "log_range(g(#),1,600)"),
        Map.entry("laguh", "lin_range(g(#),0,3)")
    );

    /**
     * Detects the parameter type from the parameter name.
     *
     * Rules:
     * - If name starts with "c." → type is "constant"
     * - Otherwise, type is the substring after the last "."
     *
     * @param paramName The full parameter name (e.g., "node.mygr4jnode.x1")
     * @return The detected parameter type (e.g., "x1") or null if unrecognized
     */
    public static String detectParameterType(String paramName) {
        if (paramName.startsWith("c.")) {
            return "constant";
        }

        int lastDot = paramName.lastIndexOf('.');
        if (lastDot >= 0 && lastDot < paramName.length() - 1) {
            return paramName.substring(lastDot + 1);
        }

        return null; // Could not determine type
    }

    /**
     * Generates an expression for the given parameter name using the counter value.
     *
     * @param paramName The parameter name
     * @param counterValue The counter value to replace # in the template
     * @return The generated expression (e.g., "lin_range(g(1), 10, 2000)")
     * @throws UnrecognizedParameterTypeException if the parameter type is not in the library
     */
    public static String generateExpression(String paramName, int counterValue)
            throws UnrecognizedParameterTypeException {
        String type = detectParameterType(paramName);

        if (type == null) {
            throw new UnrecognizedParameterTypeException(
                "Could not determine parameter type from name: " + paramName);
        }

        String template = TYPE_EXPRESSIONS.get(type);
        if (template == null) {
            throw new UnrecognizedParameterTypeException(
                "Parameter type not recognized: " + type);
        }

        return template.replace("#", String.valueOf(counterValue));
    }

    /**
     * Checks if a parameter type is recognized in the library.
     *
     * @param paramName The parameter name
     * @return true if the type is recognized, false otherwise
     */
    public static boolean isParameterTypeRecognized(String paramName) {
        String type = detectParameterType(paramName);
        return type != null && TYPE_EXPRESSIONS.containsKey(type);
    }

    /**
     * Exception thrown when a parameter type is not recognized.
     */
    public static class UnrecognizedParameterTypeException extends Exception {
        public UnrecognizedParameterTypeException(String message) {
            super(message);
        }
    }
}
