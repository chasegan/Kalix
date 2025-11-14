package com.kalix.ide.linter.model;

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
     * Check if the result is empty (no issues).
     */
    public boolean isEmpty() {
        return issues.isEmpty();
    }

    @Override
    public String toString() {
        return String.format("ValidationResult{errors=%d, warnings=%d}",
                           getErrors().size(), getWarnings().size());
    }

}