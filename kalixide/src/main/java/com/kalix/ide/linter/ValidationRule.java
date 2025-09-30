package com.kalix.ide.linter;

/**
 * Represents a single validation rule with severity level and description.
 */
public class ValidationRule {

    public enum Severity {
        ERROR, WARNING
    }

    private final String name;
    private final String description;
    private final Severity severity;
    private final String pattern;
    private final String check;
    private boolean enabled;

    public ValidationRule(String name, String description, Severity severity, String pattern, String check) {
        this.name = name;
        this.description = description;
        this.severity = severity;
        this.pattern = pattern;
        this.check = check;
        this.enabled = true; // Default to enabled
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public Severity getSeverity() {
        return severity;
    }

    public String getPattern() {
        return pattern;
    }

    public String getCheck() {
        return check;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    @Override
    public String toString() {
        return String.format("ValidationRule{name='%s', severity=%s, enabled=%s}",
                           name, severity, enabled);
    }
}