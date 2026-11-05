package com.kalix.ide.linter;

import com.kalix.ide.linter.model.ValidationIssue;
import com.kalix.ide.linter.model.ValidationResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the engine refuses is linted the same way (#442): node names of letters, digits
 * and underscores, unique ignoring case; and a section header or key that appears once.
 */
class NodeNameLintTest {

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

    private static List<ValidationIssue> issues(String rule, String model) {
        ValidationResult result = new ModelLinter(new EnabledSchemaManager()).validate(model, null);
        return result.getIssues().stream().filter(i -> rule.equals(i.getRuleName())).toList();
    }

    @Test
    void nodeNameWithSpaceOrSymbolIsAnError() {
        List<ValidationIssue> found = issues("node_name_format",
            "[node.ok_1]\ntype = gauge\nloc = 0, 0\n\n[node.gr 4*j?]\ntype = gauge\nloc = 0, 1\n");
        assertEquals(1, found.size());
        assertEquals(5, found.get(0).getLineNumber());
        assertTrue(found.get(0).getMessage().contains("gr 4*j?"));
    }

    @Test
    void mixedCaseAndDigitLeadingNamesAreAccepted() {
        assertTrue(issues("node_name_format",
            "[node.MyGauge]\ntype = gauge\nloc = 0, 0\n\n[node.0001_in]\ntype = gauge\nloc = 0, 1\n").isEmpty());
    }

    @Test
    void aRepeatedNonNodeSectionIsAnError() {
        List<ValidationIssue> found = issues("duplicate_section",
            "[var.x]\ny = 1\n\n[node.a]\ntype = gauge\nloc = 0, 0\n\n[var.x]\ny = 2\n");
        assertEquals(1, found.size());
        assertEquals(8, found.get(0).getLineNumber());
        assertTrue(found.get(0).getMessage().contains("also defined on line 1"));
    }

    @Test
    void aRepeatedNodeSectionIsLeftToTheNodeRule() {
        assertTrue(issues("duplicate_section",
            "[node.a]\ntype = gauge\nloc = 0, 0\n\n[node.a]\ntype = gauge\nloc = 0, 1\n").isEmpty());
    }

    @Test
    void aRepeatedKeyInASectionIsAnError() {
        List<ValidationIssue> found = issues("duplicate_key",
            "[node.a]\ntype = gauge\nloc = 0, 0\nloc = 1, 1\n");
        assertEquals(1, found.size());
        assertEquals(4, found.get(0).getLineNumber());
        assertTrue(found.get(0).getMessage().contains("'loc'"));
    }

    @Test
    void aRepeatedKeyDifferingInCaseIsAnError() {
        List<ValidationIssue> found = issues("duplicate_key",
            "[node.a]\ntype = gauge\nloc = 0, 0\nInflow = 1\ninflow = 2\n");
        assertEquals(1, found.size());
        assertEquals(5, found.get(0).getLineNumber());
    }

    @Test
    void aContinuedValueIsNotARepeatedKey() {
        assertTrue(issues("duplicate_key",
            "[node.a]\ntype = gauge\nloc = 0, 0\ninflow = 1,\n    2\n").isEmpty());
    }

    @Test
    void namesDifferingOnlyInCaseAreDuplicates() {
        List<ValidationIssue> found = issues("duplicate_node_name",
            "[node.Dam]\ntype = gauge\nloc = 0, 0\n\n[node.dam]\ntype = gauge\nloc = 0, 1\n");
        assertEquals(2, found.size());
        assertTrue(found.get(0).getMessage().contains("Dam"));
    }
}
