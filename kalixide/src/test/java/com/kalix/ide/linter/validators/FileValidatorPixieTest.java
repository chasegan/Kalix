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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A Pixie dataset is two files but {@code [data]} names one of them, so the
 * linter carries the two mistakes that arrangement invites: naming the
 * {@code .pxb} half, and naming a {@code .pxt} whose {@code .pxb} is absent.
 * Both mirror errors the engine raises at load time — catching them here means
 * the modeller sees them in the editor rather than as a failed run.
 */
class FileValidatorPixieTest {

    private static final String SCHEMA_JSON = """
            {
              "version": "0.0.1",
              "sections": {
                "data": { "required": false, "validation": "file_paths" }
              },
              "validation_rules": {
                "file_paths": {
                  "severity": "error",
                  "description": "All input file paths must exist",
                  "check": "file_exists"
                }
              }
            }
            """;

    private static LinterSchema loadSchema(Path dir) throws Exception {
        Path schemaFile = dir.resolve("schema.json");
        Files.writeString(schemaFile, SCHEMA_JSON);
        return LinterSchema.loadFromFile(schemaFile);
    }

    /** Runs the validator over a `[data]` section naming a single file. */
    private static List<ValidationIssue> validate(Path dir, String dataEntry) throws Exception {
        LinterSchema schema = loadSchema(dir);
        INIModelParser.ParsedModel model =
                INIModelParser.parse("[data]\n" + dataEntry + "\n");
        ValidationResult result = new ValidationResult();
        new FileValidator().validate(model, schema, result, dir.toFile());
        return result.getIssues();
    }

    private static void writePixiePair(Path dir, String base) throws Exception {
        Files.writeString(dir.resolve(base + ".pxt"), "metadata");
        Files.writeString(dir.resolve(base + ".pxb"), "binary");
    }

    @Test
    void completePixiePairIsAccepted(@TempDir Path dir) throws Exception {
        writePixiePair(dir, "climate");
        assertEquals(List.of(), validate(dir, "climate.pxt"));
    }

    @Test
    void namingThePxbHalfIsReported(@TempDir Path dir) throws Exception {
        writePixiePair(dir, "climate");

        List<ValidationIssue> issues = validate(dir, "climate.pxb");

        assertEquals(1, issues.size());
        ValidationIssue issue = issues.get(0);
        assertEquals("pixie_binary_named", issue.getRuleName());
        assertTrue(issue.getMessage().contains("climate.pxt"),
                "message should name the .pxt to use instead: " + issue.getMessage());
    }

    /**
     * The .pxb being present is precisely why "does not exist" would be the
     * wrong complaint — the file is right there, it is just the wrong half.
     */
    @Test
    void namingThePxbHalfIsNotReportedAsMissing(@TempDir Path dir) throws Exception {
        writePixiePair(dir, "climate");

        List<ValidationIssue> issues = validate(dir, "climate.pxb");

        assertTrue(issues.stream().noneMatch(i -> "file_not_found".equals(i.getRuleName())),
                "a present .pxb should not also be reported as missing");
    }

    @Test
    void missingCompanionIsReported(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("climate.pxt"), "metadata"); // no .pxb sibling

        List<ValidationIssue> issues = validate(dir, "climate.pxt");

        assertEquals(1, issues.size());
        ValidationIssue issue = issues.get(0);
        assertEquals("pixie_companion_missing", issue.getRuleName());
        assertTrue(issue.getMessage().contains("climate.pxb"),
                "message should name the missing companion: " + issue.getMessage());
    }

    /** A missing .pxt is an ordinary missing file, not a companion problem. */
    @Test
    void missingPxtIsReportedAsMissingFile(@TempDir Path dir) throws Exception {
        List<ValidationIssue> issues = validate(dir, "climate.pxt");

        assertEquals(1, issues.size());
        assertEquals("file_not_found", issues.get(0).getRuleName());
    }

    @Test
    void csvSourcesAreUnaffected(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("climate.csv"), "date,value\n");
        assertEquals(List.of(), validate(dir, "climate.csv"));
    }
}
