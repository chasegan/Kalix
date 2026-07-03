package com.kalix.ide.themes.unified;

import com.formdev.flatlaf.FlatPropertiesLaf;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import java.util.TreeMap;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Pins the exact FlatLaf properties generated for every built-in theme.
 *
 * <p>For each theme the full properties map handed to {@link FlatPropertiesLaf}
 * (including the {@code @baseTheme} marker) is dumped as sorted {@code key=value}
 * lines and compared byte-for-byte against a baseline in
 * {@code src/test/resources/themes/snapshots/}.
 *
 * <p>This is the safety net for refactoring the theme-generation pipeline: any
 * refactor must reproduce these baselines exactly. On a mismatch (or a missing
 * baseline) the actual dump is written to {@code build/theme-snapshots/} so it
 * can be diffed — and, for an <em>intentional</em> change, copied over the
 * baseline.
 */
class ThemePropertiesSnapshotTest {

    /** Mirrors the theme registry in {@code ThemeManager.getUnifiedThemeDefinition}. */
    private static final List<String> EXPECTED_THEME_NAMES = List.of(
        "Light", "Keylime", "Lapland", "Nemo", "Sunset Warmth",
        "Botanical", "Sanne", "Obsidian", "Dracula", "One Dark");

    static Stream<UnifiedThemeDefinition> themes() {
        return Stream.of(
            LightThemeDefinitions.createLightThemeRefactored(),
            LightThemeDefinitions.createKeylimeThemeRefactored(),
            LightThemeDefinitions.createLaplandThemeRefactored(),
            LightThemeDefinitions.createNemoThemeRefactored(),
            LightThemeDefinitions.createSunsetWarmthThemeRefactored(),
            DarkThemeDefinitions.createBotanicalThemeRefactored(),
            DarkThemeDefinitions.createSanneThemeRefactored(),
            DarkThemeDefinitions.createObsidianThemeRefactored(),
            DarkThemeDefinitions.createDraculaThemeRefactored(),
            DarkThemeDefinitions.createOneDarkThemeRefactored());
    }

    @Test
    void allRegisteredThemesAreSnapshotted() {
        List<String> actual = themes().map(UnifiedThemeDefinition::getName).toList();
        assertEquals(EXPECTED_THEME_NAMES, actual,
            "Theme list drifted from ThemeManager's registry — update both this test and the baselines");
    }

    @ParameterizedTest
    @MethodSource("themes")
    void generatedPropertiesMatchSnapshot(UnifiedThemeDefinition theme) throws IOException {
        FlatPropertiesLaf laf = ThemeCompatibilityAdapter.createApplicationTheme(theme);
        String actual = dump(laf.getProperties());

        // Always write the actual dump where it can be diffed / promoted to a baseline.
        Path actualFile = Path.of("build", "theme-snapshots", slug(theme.getName()) + ".properties");
        Files.createDirectories(actualFile.getParent());
        Files.writeString(actualFile, actual, StandardCharsets.UTF_8);

        String resource = "/themes/snapshots/" + slug(theme.getName()) + ".properties";
        try (InputStream in = getClass().getResourceAsStream(resource)) {
            assertNotNull(in, "Missing snapshot baseline " + resource
                + " — actual dump written to " + actualFile.toAbsolutePath());
            String expected = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            assertEquals(expected, actual, "Generated properties for theme '" + theme.getName()
                + "' changed — diff the baseline against " + actualFile.toAbsolutePath());
        }
    }

    private static String dump(Properties properties) {
        TreeMap<String, String> sorted = new TreeMap<>();
        for (String key : properties.stringPropertyNames()) {
            sorted.put(key, properties.getProperty(key));
        }
        StringBuilder sb = new StringBuilder();
        sorted.forEach((key, value) -> sb.append(key).append('=').append(value).append('\n'));
        return sb.toString();
    }

    private static String slug(String themeName) {
        return themeName.toLowerCase().replaceAll("[^a-z0-9]+", "-");
    }
}
