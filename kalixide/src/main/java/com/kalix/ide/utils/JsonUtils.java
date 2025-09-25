package com.kalix.ide.utils;

/**
 * Utility class for JSON string manipulation and formatting operations.
 *
 * This class provides low-level JSON string utilities that complement
 * the higher-level protocol operations in JsonStdioProtocol.
 */
public final class JsonUtils {

    // Prevent instantiation
    private JsonUtils() {
        throw new UnsupportedOperationException("Utility class should not be instantiated");
    }

    /**
     * Flattens JSON by removing line breaks and normalizing whitespace.
     *
     * This method is useful for preparing JSON strings for single-line
     * transmission over STDIO protocols where line breaks might interfere
     * with message parsing.
     *
     * @param json the JSON string to flatten
     * @return flattened JSON string with normalized whitespace, or empty string if input is null/empty
     */
    public static String flattenJson(String json) {
        if (json == null || json.trim().isEmpty()) {
            return "";
        }
        return json.replaceAll("\\s+", " ").replaceAll("\\n|\\r", "");
    }
}