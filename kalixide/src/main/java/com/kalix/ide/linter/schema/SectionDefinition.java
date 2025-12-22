package com.kalix.ide.linter.schema;

import java.util.HashMap;
import java.util.Map;

/**
 * Defines a section in the linter schema including its properties and validation rules.
 */
public class SectionDefinition {
    public String name;
    public boolean required;
    public String validation; // Optional validation rule name (e.g., "file_paths", "output_references")
    public Map<String, PropertyDefinition> properties = new HashMap<>();

    /**
     * Defines a property within a section.
     */
    public static class PropertyDefinition {
        public String name;
        public boolean required;
        public String type;    // Optional type (e.g., "version")
        public String pattern; // Optional regex pattern for validation
    }

    /**
     * Gets a property definition by name.
     */
    public PropertyDefinition getProperty(String propertyName) {
        return properties.get(propertyName);
    }

    /**
     * Checks if a property is required.
     */
    public boolean isPropertyRequired(String propertyName) {
        PropertyDefinition prop = properties.get(propertyName);
        return prop != null && prop.required;
    }
}
