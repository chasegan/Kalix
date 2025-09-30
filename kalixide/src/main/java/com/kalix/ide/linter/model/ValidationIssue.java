package com.kalix.ide.linter.model;

/**
 * Represents a single validation issue found during linting.
 */
public class ValidationIssue {
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

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;

        ValidationIssue that = (ValidationIssue) obj;
        return lineNumber == that.lineNumber &&
               message.equals(that.message) &&
               severity == that.severity &&
               ruleName.equals(that.ruleName);
    }

    @Override
    public int hashCode() {
        int result = lineNumber;
        result = 31 * result + message.hashCode();
        result = 31 * result + severity.hashCode();
        result = 31 * result + ruleName.hashCode();
        return result;
    }
}