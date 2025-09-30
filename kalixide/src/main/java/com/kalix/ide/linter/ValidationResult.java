package com.kalix.ide.linter;

import java.util.ArrayList;
import java.util.List;

/**
 * Contains the results of validation including errors and warnings with line numbers.
 */
public class ValidationResult {

    private final List<ValidationIssue> issues = new ArrayList<>();

    /**
     * Add a validation issue to the result.
     */
    public void addIssue(ValidationIssue issue) {
        issues.add(issue);
    }

    /**
     * Add a validation issue to the result.
     */
    public void addIssue(int lineNumber, String message, ValidationRule.Severity severity, String ruleName) {
        issues.add(new ValidationIssue(lineNumber, message, severity, ruleName));
    }

    /**
     * Get all validation issues.
     */
    public List<ValidationIssue> getIssues() {
        return new ArrayList<>(issues);
    }

    /**
     * Get only error issues.
     */
    public List<ValidationIssue> getErrors() {
        return issues.stream()
                .filter(issue -> issue.getSeverity() == ValidationRule.Severity.ERROR)
                .toList();
    }

    /**
     * Get only warning issues.
     */
    public List<ValidationIssue> getWarnings() {
        return issues.stream()
                .filter(issue -> issue.getSeverity() == ValidationRule.Severity.WARNING)
                .toList();
    }

    /**
     * Check if validation has any errors.
     */
    public boolean hasErrors() {
        return issues.stream().anyMatch(issue -> issue.getSeverity() == ValidationRule.Severity.ERROR);
    }

    /**
     * Check if validation has any warnings.
     */
    public boolean hasWarnings() {
        return issues.stream().anyMatch(issue -> issue.getSeverity() == ValidationRule.Severity.WARNING);
    }

    /**
     * Get total number of issues.
     */
    public int getIssueCount() {
        return issues.size();
    }

    /**
     * Clear all issues.
     */
    public void clear() {
        issues.clear();
    }

    /**
     * Check if the validation passed (no errors).
     */
    public boolean isValid() {
        return !hasErrors();
    }

    @Override
    public String toString() {
        return String.format("ValidationResult{errors=%d, warnings=%d}",
                           getErrors().size(), getWarnings().size());
    }

    /**
     * Represents a single validation issue.
     */
    public static class ValidationIssue {
        private final int lineNumber;
        private final String message;
        private final ValidationRule.Severity severity;
        private final String ruleName;

        public ValidationIssue(int lineNumber, String message, ValidationRule.Severity severity, String ruleName) {
            this.lineNumber = lineNumber;
            this.message = message;
            this.severity = severity;
            this.ruleName = ruleName;
        }

        public int getLineNumber() {
            return lineNumber;
        }

        public String getMessage() {
            return message;
        }

        public ValidationRule.Severity getSeverity() {
            return severity;
        }

        public String getRuleName() {
            return ruleName;
        }

        @Override
        public String toString() {
            return String.format("Line %d: %s [%s]", lineNumber, message, severity);
        }
    }
}