package com.kalix.ide.themes.unified;

import java.util.Map;
import java.util.Properties;

/**
 * A fully resolved application theme: a name, a dark flag, and the complete
 * FlatLaf property map. Built by {@link ExactColorTheme} and consumed by
 * {@link ThemeCompatibilityAdapter} to create the look and feel.
 */
public class UnifiedThemeDefinition {

    private final String name;
    private final boolean isDark;
    private final Map<String, String> properties;

    public UnifiedThemeDefinition(String name, boolean isDark, Map<String, String> properties) {
        this.name = name;
        this.isDark = isDark;
        this.properties = properties;
    }

    /**
     * Get the theme name
     */
    public String getName() {
        return name;
    }

    /**
     * Whether this is a dark theme (drives FlatLaf's base defaults selection)
     */
    public boolean isDark() {
        return isDark;
    }

    /**
     * Generate application theme properties
     */
    public Properties generateApplicationProperties() {
        Properties props = new Properties();
        properties.forEach(props::setProperty);
        return props;
    }

    @Override
    public String toString() {
        return String.format("UnifiedThemeDefinition{name='%s', isDark=%s}", name, isDark);
    }
}
