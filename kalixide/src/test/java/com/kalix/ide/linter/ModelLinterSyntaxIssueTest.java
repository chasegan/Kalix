package com.kalix.ide.linter;

import com.kalix.ide.linter.model.ValidationIssue;
import com.kalix.ide.linter.model.ValidationResult;
import com.kalix.ide.linter.model.ValidationRule;
import com.kalix.ide.linter.parsing.INIModelParser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The parser's lexical findings reach the user as lint issues, routed through
 * their schema rules so severity and the enabled flag are honoured.
 */
class ModelLinterSyntaxIssueTest {

    /** The default schema, linting on, no preference system involved. */
    private static final class EnabledSchemaManager extends SchemaManager {
        private final LinterSchema schema = LinterSchema.loadDefault();

        @Override
        public LinterSchema getCurrentSchema() {
            return schema;
        }

        @Override
        public boolean isLintingEnabled() {
            return true;
        }
    }

    private static List<ValidationIssue> issuesFor(String rule, ValidationResult result) {
        return result.getIssues().stream().filter(i -> rule.equals(i.getRuleName())).toList();
    }

    @Test
    void semicolonLineIsAnErrorWithAFixItHint() {
        SchemaManager schemaManager = new EnabledSchemaManager();
        ModelLinter linter = new ModelLinter(schemaManager);

        ValidationResult result = linter.validate("[kalix]\n; not a comment\n\n[node.a]\ntype = gauge\nloc = 1, 2\n", null);

        List<ValidationIssue> issues = issuesFor(INIModelParser.RULE_SEMICOLON_COMMENT, result);
        assertEquals(1, issues.size());
        assertEquals(2, issues.get(0).getLineNumber());
        assertEquals(ValidationRule.Severity.ERROR, issues.get(0).getSeverity());
        assertTrue(issues.get(0).getMessage().contains("use '#'"));
    }

    @Test
    void semicolonLineIsReportedEvenWhenTheModelIsOtherwiseEmpty() {
        // The empty-model short-circuit must not hide the one line that is wrong.
        ModelLinter linter = new ModelLinter(new EnabledSchemaManager());
        ValidationResult result = linter.validate("; just this\n", null);
        assertEquals(1, issuesFor(INIModelParser.RULE_SEMICOLON_COMMENT, result).size());
    }

    @Test
    void malformedHeaderIsAnError() {
        ModelLinter linter = new ModelLinter(new EnabledSchemaManager());
        ValidationResult result = linter.validate("[node.a\ntype = gauge\nloc = 1, 2\n", null);

        List<ValidationIssue> issues = issuesFor(INIModelParser.RULE_MALFORMED_SECTION_HEADER, result);
        assertEquals(1, issues.size());
        assertEquals(1, issues.get(0).getLineNumber());
    }

    @Test
    void repeatedClusterLineIsAWarning() {
        ModelLinter linter = new ModelLinter(new EnabledSchemaManager());
        ValidationResult result = linter.validate(
                "#@cluster = north\n#@cluster = south\n[node.a]\ntype = gauge\nloc = 1, 2\n", null);

        List<ValidationIssue> issues = issuesFor(INIModelParser.RULE_ANNOTATION_DUPLICATE_CLUSTER, result);
        assertEquals(1, issues.size());
        assertEquals(2, issues.get(0).getLineNumber());
        assertEquals(ValidationRule.Severity.WARNING, issues.get(0).getSeverity());
    }

    @Test
    void clusterSpelledInAnotherCaseIsAWarning() {
        ModelLinter linter = new ModelLinter(new EnabledSchemaManager());
        ValidationResult result = linter.validate("#@cluster = north\n[node.a]\ntype = gauge\nloc = 1, 2\n"
                + "#@cluster = North\n[node.b]\ntype = gauge\nloc = 3, 4\n", null);

        List<ValidationIssue> issues = issuesFor(INIModelParser.RULE_ANNOTATION_MIXED_CASE_CLUSTER, result);
        assertEquals(1, issues.size());
        assertEquals(5, issues.get(0).getLineNumber());
        assertEquals(ValidationRule.Severity.WARNING, issues.get(0).getSeverity());
    }

    @Test
    void invalidClusterNameIsAWarning() {
        ModelLinter linter = new ModelLinter(new EnabledSchemaManager());
        ValidationResult result = linter.validate(
                "#@cluster = farm 3\n[node.a]\ntype = gauge\nloc = 1, 2\n", null);

        List<ValidationIssue> issues = issuesFor(INIModelParser.RULE_ANNOTATION_INVALID_CLUSTER, result);
        assertEquals(1, issues.size());
        assertEquals(1, issues.get(0).getLineNumber());
        assertEquals(ValidationRule.Severity.WARNING, issues.get(0).getSeverity());
    }

    @Test
    void disabledRuleIsSilent() {
        SchemaManager schemaManager = new EnabledSchemaManager();
        schemaManager.getCurrentSchema().getValidationRule(INIModelParser.RULE_SEMICOLON_COMMENT).setEnabled(false);
        ModelLinter linter = new ModelLinter(schemaManager);

        ValidationResult result = linter.validate("[kalix]\n; not a comment\n", null);
        assertTrue(issuesFor(INIModelParser.RULE_SEMICOLON_COMMENT, result).isEmpty());
    }
}
