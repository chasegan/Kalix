package com.kalix.ide.utils;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Utility class for JSON string manipulation and formatting operations.
 *
 * This class provides low-level JSON string utilities that complement
 * the higher-level protocol operations in JsonStdioProtocol.
 */
public final class JsonUtils {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    // Prevent instantiation
    private JsonUtils() {
        throw new UnsupportedOperationException("Utility class should not be instantiated");
    }

    /**
     * Flattens JSON onto a single line for STDIO transmission.
     *
     * <p>The input is parsed and re-serialized compactly, so structural
     * whitespace (indentation, line breaks) is dropped while whitespace
     * <em>inside</em> string values is preserved exactly. A textual
     * whitespace collapse cannot tell the two apart and silently corrupts
     * string literals, so this parses the JSON structurally instead.
     *
     * @param json the JSON string to flatten
     * @return compact single-line JSON, or an empty string if the input is null/empty
     * @throws IllegalArgumentException if the input is not valid JSON
     */
    public static String flattenJson(String json) {
        if (json == null || json.trim().isEmpty()) {
            return "";
        }
        try {
            return OBJECT_MAPPER.readTree(json).toString();
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Cannot flatten invalid JSON: " + e.getOriginalMessage(), e);
        }
    }
}
