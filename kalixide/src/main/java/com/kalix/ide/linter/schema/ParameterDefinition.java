package com.kalix.ide.linter.schema;

/**
 * Defines a parameter within a node type including its type, constraints, and validation rules.
 */
public class ParameterDefinition {
    public String name;
    public String type;
    public String description;
    public Integer count; // For number_sequence type
    public Double min;    // For number/integer types
    public Double max;    // For number/integer types
    public String pattern; // For custom validation
}