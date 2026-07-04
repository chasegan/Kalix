package com.kalix.ide.linter.validators;

import com.kalix.ide.linter.LinterSchema;
import com.kalix.ide.linter.model.ValidationIssue;
import com.kalix.ide.linter.model.ValidationResult;
import com.kalix.ide.linter.parsing.INIModelParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Verifies the missing-required-property marker stays inside the section's
 * line range (review, linter section): startLine+1 could point past EOF when
 * the section header is the document's last line.
 */
class SectionValidatorLineClampTest {

    // Minimal schema whose [kalix] section REQUIRES a version property
    // (the default schema has no required section properties to exercise this).
    private static final String SCHEMA_JSON = """
            {
              "version": "0.0.1",
              "sections": {
                "kalix": {
                  "required": true,
                  "properties": {
                    "version": { "required": true, "type": "version" }
                  }
                }
              }
            }
            """;

    private static LinterSchema loadSchema(Path dir) throws Exception {
        Path schemaFile = dir.resolve("schema.json");
        Files.writeString(schemaFile, SCHEMA_JSON);
        return LinterSchema.loadFromFile(schemaFile);
    }

    private static List<ValidationIssue> missingPropertyIssues(LinterSchema schema, String content) {
        INIModelParser.ParsedModel model = INIModelParser.parse(content);
        ValidationResult result = new ValidationResult();
        new SectionValidator().validate(model, schema, result, null);
        return result.getIssues().stream()
                .filter(i -> i.getMessage().startsWith("Missing required property"))
                .toList();
    }

    @Test
    void headerOnLastLineReportsOnTheHeader(@TempDir Path dir) throws Exception {
        LinterSchema schema = loadSchema(dir);

        // Section header is the last line (line 3); startLine+1 = 4 is past EOF.
        List<ValidationIssue> issues = missingPropertyIssues(schema, "; a comment\n\n[kalix]");

        assertEquals(1, issues.size());
        assertEquals(3, issues.get(0).getLineNumber(),
                "marker must be clamped into the section's line range, not past EOF");
    }

    @Test
    void sectionWithOtherPropertiesReportsWithinTheSection(@TempDir Path dir) throws Exception {
        LinterSchema schema = loadSchema(dir);

        List<ValidationIssue> issues = missingPropertyIssues(schema, "[kalix]\nother = 1\n");

        assertEquals(1, issues.size());
        assertEquals(2, issues.get(0).getLineNumber(),
                "marker sits on the line after the header when the section has one");
    }
}
