package com.kalix.ide.utils;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonUtilsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void nullOrBlankFlattensToEmptyString() {
        assertEquals("", JsonUtils.flattenJson(null));
        assertEquals("", JsonUtils.flattenJson(""));
        assertEquals("", JsonUtils.flattenJson("   \n\t "));
    }

    @Test
    void structuralWhitespaceIsRemoved() throws Exception {
        String pretty = "{\n  \"a\": 1,\n  \"b\": [\n    2,\n    3\n  ]\n}";
        String flat = JsonUtils.flattenJson(pretty);
        assertTrue(flat.indexOf('\n') < 0, "no newlines should remain");
        assertEquals(MAPPER.readTree(pretty), MAPPER.readTree(flat), "content must be preserved");
    }

    @Test
    void whitespaceInsideStringValuesIsPreserved() throws Exception {
        // The original textual collapse corrupted this: internal runs of spaces and newlines
        // inside a string literal were destroyed. Structural flattening must keep them exact.
        String json = "{ \"desc\": \"flow   gauge\\n2\" }";
        String flat = JsonUtils.flattenJson(json);
        assertEquals("flow   gauge\n2", MAPPER.readTree(flat).get("desc").asText());
    }

    @Test
    void invalidJsonThrows() {
        assertThrows(IllegalArgumentException.class, () -> JsonUtils.flattenJson("{not valid"));
        assertThrows(IllegalArgumentException.class, () -> JsonUtils.flattenJson("just a bare word"));
    }
}
