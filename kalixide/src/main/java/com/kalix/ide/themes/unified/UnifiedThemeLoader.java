package com.kalix.ide.themes.unified;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

/**
 * Loads a {@link UnifiedThemeDefinition} from its {@code /themes/<id>.properties}
 * resource. The resource files ARE the themes — see {@code resources/themes/README.md}
 * for the format.
 *
 * <p>A theme file holds only the keys the theme sets explicitly; the shared
 * {@code themes/defaults.properties} supplies fallbacks for everything else.
 * Two meta keys are stripped before the map reaches FlatLaf: {@code displayName}
 * and {@code @baseTheme} (which also drives {@link UnifiedThemeDefinition#isDark()}).
 * The exact output for all built-in themes is pinned by
 * {@code ThemePropertiesSnapshotTest}.
 */
public final class UnifiedThemeLoader {

    /** Fallback values applied for keys a theme file omits. */
    private static final Map<String, String> DEFAULT_PROPERTIES = readResource("defaults");

    private UnifiedThemeLoader() {
    }

    /**
     * Load the theme with the given stable id (e.g. "sunset-warmth").
     *
     * @throws IllegalStateException if the resource is missing or malformed
     */
    public static UnifiedThemeDefinition load(String id) {
        Map<String, String> raw = readResource(id);

        String displayName = raw.remove("displayName");
        boolean dark = "dark".equalsIgnoreCase(raw.remove("@baseTheme"));
        if (displayName == null) {
            throw new IllegalStateException("Theme resource themes/" + id
                + ".properties has no displayName");
        }

        Map<String, String> properties = new HashMap<>(DEFAULT_PROPERTIES);

        // TitlePane keys have no unconditional defaults: themes without a custom
        // title bar must not emit them. Only supply the unifiedBackground default
        // when the theme opts in by setting TitlePane.background.
        if (raw.containsKey("TitlePane.background")) {
            properties.put("TitlePane.unifiedBackground", "false");
        }

        // Every key the theme file sets passes through verbatim.
        properties.putAll(raw);

        return new UnifiedThemeDefinition(displayName, dark, properties, raw.keySet());
    }

    private static Map<String, String> readResource(String name) {
        String path = "/themes/" + name + ".properties";
        try (InputStream in = UnifiedThemeLoader.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("Missing theme resource " + path);
            }
            Properties props = new Properties();
            props.load(new InputStreamReader(in, StandardCharsets.UTF_8));
            Map<String, String> map = new HashMap<>();
            for (String key : props.stringPropertyNames()) {
                map.put(key, props.getProperty(key));
            }
            return map;
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read theme resource " + path, e);
        }
    }
}
