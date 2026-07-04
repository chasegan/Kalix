package com.kalix.ide.themes.unified;

import java.util.Map;
import java.util.Properties;
import java.util.Set;

/**
 * A fully resolved application theme: a name, a dark flag, and the complete
 * FlatLaf property map. Built by {@link UnifiedThemeLoader} from the theme's
 * {@code resources/themes/<id>.properties} file and consumed by
 * {@link ThemeCompatibilityAdapter} to create the look and feel.
 */
public class UnifiedThemeDefinition {

    private final String name;
    private final boolean isDark;
    private final Map<String, String> properties;
    private final Set<String> explicitKeys;

    /**
     * @param properties   the complete resolved property map (theme file over
     *                     {@code themes/defaults.properties})
     * @param explicitKeys the keys the theme's own file sets, as opposed to values
     *                     inherited from the shared defaults — see
     *                     {@link #definesExplicitly(String)}
     */
    public UnifiedThemeDefinition(String name, boolean isDark, Map<String, String> properties,
                                  Set<String> explicitKeys) {
        this.name = name;
        this.isDark = isDark;
        this.properties = properties;
        this.explicitKeys = Set.copyOf(explicitKeys);
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
     * Whether the theme's own properties file sets {@code key}, rather than
     * inheriting it from {@code themes/defaults.properties}.
     *
     * <p>Used for runtime tweaks that historically derived a value (e.g.
     * {@code TabbedPane.selectedBackground} from {@code Panel.background}): a
     * theme that sets the key explicitly must win over the derivation, while a
     * theme that merely inherits the legacy default keeps the derived value.</p>
     */
    public boolean definesExplicitly(String key) {
        return explicitKeys.contains(key);
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
