package com.kalix.ide.linter.validators;

import com.kalix.ide.linter.LinterSchema;
import com.kalix.ide.linter.model.ValidationIssue;
import com.kalix.ide.linter.model.ValidationResult;
import com.kalix.ide.linter.parsing.INIModelParser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Entries that appear twice are wrong twice. Before this, a duplicated {@code [data]}
 * alias was logged and never linted, and a duplicated bad {@code [outputs]} reference
 * or missing input file was reported on its last line only, because the line lookup
 * was keyed by the entry's text.
 */
class DuplicateEntryLintTest {

    private static List<Integer> linesOf(ValidationResult result, String ruleName) {
        return result.getIssues().stream()
                .filter(i -> ruleName.equals(i.getRuleName()))
                .map(ValidationIssue::getLineNumber)
                .sorted()
                .toList();
    }

    @Test
    void duplicateDataAliasIsADuplicateProperty() {
        String model = """
                [data]
                rain = a.csv
                evap = b.csv
                rain = c.csv
                """;
        INIModelParser.ParsedModel parsed = INIModelParser.parse(model);
        ValidationResult result = new ValidationResult();
        new DuplicatePropertyValidator().validate(parsed, LinterSchema.loadDefault(), result, null);

        assertEquals(List.of(2, 4), linesOf(result, "duplicate_property"));
        // The alias map itself still resolves last-wins, as the engine does.
        assertEquals("c.csv", parsed.getInputFileAliases().get("rain"));
    }

    @Test
    void duplicateBadOutputReferenceIsReportedOnEveryLine() {
        String model = """
                [node.real]
                type = confluence
                loc = 1, 2

                [outputs]
                node.ghost.dsflow
                node.real.dsflow
                node.ghost.dsflow
                """;
        INIModelParser.ParsedModel parsed = INIModelParser.parse(model);
        ValidationResult result = new ValidationResult();
        new ReferenceValidator().validate(parsed, LinterSchema.loadDefault(), result, null);

        assertEquals(List.of(6, 8), linesOf(result, "invalid_node_reference"));
    }

    @Test
    void duplicateMissingInputFileIsReportedOnEveryLine() {
        String model = """
                [data]
                nowhere/missing.csv
                also = nowhere/missing.csv
                """;
        INIModelParser.ParsedModel parsed = INIModelParser.parse(model);
        ValidationResult result = new ValidationResult();
        new FileValidator().validate(parsed, LinterSchema.loadDefault(), result, null);

        assertEquals(List.of(2, 3), linesOf(result, "file_not_found"));
    }
}
