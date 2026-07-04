package com.kalix.ide.themes.unified;

import com.kalix.ide.themes.ThemeRegistry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link UnifiedThemeDefinition#definesExplicitly(String)} — the
 * distinction between keys a theme's own file sets and keys inherited from
 * {@code themes/defaults.properties}.
 *
 * <p>The distinction drives runtime derivations in {@code ThemeManager}:
 * {@code TabbedPane.selectedBackground} is derived from {@code Panel.background}
 * only for themes that don't set it themselves, so the assertions here pin the
 * two sides of that behaviour against real registered themes.</p>
 */
class UnifiedThemeDefinitionTest {

    @Test
    void lightInheritsTabSelectedBackgroundFromDefaults() {
        UnifiedThemeDefinition light = ThemeRegistry.byId("light").orElseThrow().definition();

        // light.properties does not set the key; the resolved map still carries
        // the defaults.properties fallback (that is what the snapshot pins) but
        // it must not count as an explicit choice, so the historical runtime
        // derivation from Panel.background continues to apply.
        assertFalse(light.definesExplicitly("TabbedPane.selectedBackground"));
        assertNotNull(light.generateApplicationProperties()
            .getProperty("TabbedPane.selectedBackground"));
    }

    @Test
    void darkThemesDefineTabSelectedBackgroundExplicitly() {
        for (String id : new String[] {"dracula", "one-dark", "obsidian", "sanne", "kalix-dark"}) {
            UnifiedThemeDefinition theme = ThemeRegistry.byId(id).orElseThrow().definition();
            assertTrue(theme.definesExplicitly("TabbedPane.selectedBackground"),
                id + " defines --tab-selected-bg in its design mock and must win over the derivation");
        }
    }

    @Test
    void metaKeysAreNeverExplicit() {
        UnifiedThemeDefinition theme = ThemeRegistry.byId("dracula").orElseThrow().definition();
        assertFalse(theme.definesExplicitly("displayName"));
        assertFalse(theme.definesExplicitly("@baseTheme"));
    }
}
