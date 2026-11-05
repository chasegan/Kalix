package com.kalix.ide.linter.validators;

import com.kalix.ide.linter.LinterSchema;
import com.kalix.ide.linter.model.ValidationIssue;
import com.kalix.ide.linter.model.ValidationResult;
import com.kalix.ide.linter.parsing.INIModelParser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Entries that appear twice are wrong twice. A repeated key or bare entry is reported
 * on the line that repeats it, pointing back to the first, as the engine's refusal
 * does; a duplicated bad {@code [outputs]} reference or missing input file is reported
 * on every line, since each line is wrong in its own right.
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
    void duplicateDataAliasIsADuplicateKey() {
        String model = """
                [data]
                rain = a.csv
                evap = b.csv
                rain = c.csv
                """;
        INIModelParser.ParsedModel parsed = INIModelParser.parse(model);
        ValidationResult result = new ValidationResult();
        new UniqueNameValidator().validate(parsed, LinterSchema.loadDefault(), result, null);

        assertEquals(List.of(4), linesOf(result, "duplicate_key"), "the repeat, pointing back to the first");
        // The alias map itself still resolves last-wins, as the IDE's own parser does.
        assertEquals("c.csv", parsed.getInputFileAliases().get("rain"));
    }

    /** The engine keys an aliased line by its alias, so one file under two aliases is fine. */
    @Test
    void oneFileUnderTwoAliasesIsNotADuplicate() {
        String model = """
                [data]
                rain = a.csv
                evap = a.csv
                a.csv
                """;
        INIModelParser.ParsedModel parsed = INIModelParser.parse(model);
        ValidationResult result = new ValidationResult();
        new UniqueNameValidator().validate(parsed, LinterSchema.loadDefault(), result, null);

        assertEquals(List.of(), linesOf(result, "duplicate_key"));
    }

    @Test
    void duplicateBarePathIsADuplicateKey() {
        String model = """
                [data]
                a.csv
                b.csv
                a.csv
                """;
        INIModelParser.ParsedModel parsed = INIModelParser.parse(model);
        ValidationResult result = new ValidationResult();
        new UniqueNameValidator().validate(parsed, LinterSchema.loadDefault(), result, null);

        assertEquals(List.of(4), linesOf(result, "duplicate_key"));
    }

    @Test
    void duplicateOutputEntryIsADuplicateKey() {
        String model = """
                [outputs]
                node.a.dsflow
                node.b.dsflow
                node.a.dsflow
                """;
        INIModelParser.ParsedModel parsed = INIModelParser.parse(model);
        ValidationResult result = new ValidationResult();
        new UniqueNameValidator().validate(parsed, LinterSchema.loadDefault(), result, null);

        assertEquals(List.of(4), linesOf(result, "duplicate_key"));
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
