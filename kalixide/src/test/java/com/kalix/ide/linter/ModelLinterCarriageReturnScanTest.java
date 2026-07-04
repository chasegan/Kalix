package com.kalix.ide.linter;

import com.kalix.ide.linter.model.ValidationIssue;
import com.kalix.ide.linter.model.ValidationResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the single-pass standalone-CR scan (review #74): same diagnostics
 * as the old regex + per-match O(n) line recount, without the O(n^2) blowup.
 */
class ModelLinterCarriageReturnScanTest {

    private static List<ValidationIssue> scan(String content) {
        ValidationResult result = new ValidationResult();
        ModelLinter.checkForStandaloneCarriageReturns(content, result);
        return result.getIssues();
    }

    @Test
    void cleanLfContentHasNoIssues() {
        assertTrue(scan("[kalix]\nversion = 1.0.0\n").isEmpty());
    }

    @Test
    void crlfLineEndingsAreNotFlagged() {
        assertTrue(scan("[kalix]\r\nversion = 1.0.0\r\n").isEmpty(),
                "\\r\\n is a legitimate line ending, not a standalone CR");
    }

    @Test
    void standaloneCrIsReportedWithLineAndColumn() {
        // Line 2, column 8 ("version" is 7 chars, CR at index 7 of the line)
        List<ValidationIssue> issues = scan("[kalix]\nversion\r = 1.0.0\n");

        assertEquals(1, issues.size());
        ValidationIssue issue = issues.get(0);
        assertEquals(2, issue.getLineNumber());
        assertTrue(issue.getMessage().contains("column 8"),
                "expected column 8, got: " + issue.getMessage());
    }

    @Test
    void everyStandaloneCrIsReported() {
        List<ValidationIssue> issues = scan("a\rb\nc\rd\re\n");
        assertEquals(3, issues.size());
        assertEquals(1, issues.get(0).getLineNumber());
        assertEquals(2, issues.get(1).getLineNumber());
        assertEquals(2, issues.get(2).getLineNumber());
    }

    @Test
    void trailingCrAtEndOfContentIsReported() {
        List<ValidationIssue> issues = scan("line one\r");
        assertEquals(1, issues.size());
        assertEquals(1, issues.get(0).getLineNumber());
        assertTrue(issues.get(0).getMessage().contains("column 9"));
    }
}
