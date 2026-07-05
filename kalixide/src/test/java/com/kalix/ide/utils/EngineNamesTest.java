package com.kalix.ide.utils;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pins {@link EngineNames} to the Rust engine's sanitisation rule
 * ({@code fn sanitize_name}, {@code src/misc/misc_functions.rs}): lowercase
 * first, then replace every character outside {@code [a-z0-9_]} with '_'.
 *
 * <p>The mixed-case pairs are the regression cases for the historical bug of
 * replacing before lowercasing ({@code MyData.csv} used to become
 * {@code __ata_csv} instead of {@code mydata_csv}).
 */
class EngineNamesTest {

    @Test
    void sanitizeLowercasesBeforeReplacing() {
        // The order matters: replace-then-lowercase would give "__ata_csv".
        assertEquals("mydata_csv", EngineNames.sanitize("MyData.csv"));
        assertEquals("gs123456_flow", EngineNames.sanitize("GS123456 Flow"));
        assertEquals("climate", EngineNames.sanitize("CLIMATE"));
    }

    @Test
    void sanitizeMapsDisallowedCharactersToUnderscore() {
        assertEquals("patterns_csv", EngineNames.sanitize("patterns.csv"));
        assertEquals("my_data_csv", EngineNames.sanitize("my.data.csv"));
        assertEquals("a_b_c", EngineNames.sanitize("a-b c"));
        assertEquals("flow__ml_d_", EngineNames.sanitize("flow (ML/d)"));
        assertEquals("d_j_", EngineNames.sanitize("déjà")); // é/à are outside [a-z0-9_]
    }

    @Test
    void sanitizeKeepsAllowedCharactersVerbatim(){
        assertEquals("already_safe_123", EngineNames.sanitize("already_safe_123"));
        assertEquals("", EngineNames.sanitize(""));
    }

    @Test
    void sanitizeMapsOneSupplementaryCharacterToOneUnderscore() {
        // Rust maps per character (code point); a surrogate pair must not become two underscores.
        assertEquals("a_b", EngineNames.sanitize("a💧b")); // 💧
    }

    @Test
    void sanitizeFileNameUsesTheFinalPathComponent() {
        assertEquals("patterns_csv", EngineNames.sanitizeFileName("/data/patterns.csv"));
        assertEquals("mydata_csv", EngineNames.sanitizeFileName("./inputs/MyData.csv"));
        assertEquals("my_data_csv", EngineNames.sanitizeFileName("^/inputs/my.data.csv"));
        assertEquals("flow_data_csv", EngineNames.sanitizeFileName("flow_data.csv"));
    }
}
