package com.kalix.ide.windows.optimisation;

import java.util.Map;

/**
 * Library of parameter type expressions for optimization configuration.
 * Maps parameter types to their default lin_range expressions.
 */
public class ParameterExpressionLibrary {

    // Map of parameter type to expression template (# is placeholder for counter)
    private static final Map<String, String> TYPE_EXPRESSIONS = Map.of(
        "constant", "lin_range(g(#), 0, 100)",
        "x1", "lin_range(g(#), 10, 2000)",
        "x2", "lin_range(g(#), -8, 6)",
        "x3", "lin_range(g(#), 10, 500)",
        "x4", "lin_range(g(#), 0.0001, 4.0)"
        // Additional parameter types (Sacramento, etc.) can be added here
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
