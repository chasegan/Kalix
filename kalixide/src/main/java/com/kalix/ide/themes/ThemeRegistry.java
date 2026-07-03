package com.kalix.ide.themes;

import com.kalix.ide.themes.unified.UnifiedThemeLoader;

import java.util.List;
import java.util.Optional;

/**
 * The single ordered registry of all built-in themes.
 *
 * <p>Everything that needs "the list of themes" — the preferences combos, the
 * theme manager, the snapshot test — derives it from here, in this order.
 * The colours themselves live in {@code resources/themes/<id>.properties}
 * (see the README there); adding a theme means adding that file plus exactly
 * one entry here linking it to its node and syntax palettes.
 */
public final class ThemeRegistry {

    private static final List<KalixTheme> THEMES = List.of(
        theme("light", NodeTheme.Theme.LIGHT, SyntaxTheme.Theme.LIGHT),
        theme("keylime", NodeTheme.Theme.KEYLIME, SyntaxTheme.Theme.KEYLIME),
        theme("lapland", NodeTheme.Theme.LAPLAND, SyntaxTheme.Theme.LAPLAND),
        theme("nemo", NodeTheme.Theme.NEMO, SyntaxTheme.Theme.NEMO),
        theme("sunset-warmth", NodeTheme.Theme.SUNSET_WARMTH, SyntaxTheme.Theme.SUNSET_WARMTH),
        theme("botanical", NodeTheme.Theme.BOTANICAL, SyntaxTheme.Theme.BOTANICAL),
        theme("dracula", NodeTheme.Theme.DRACULA, SyntaxTheme.Theme.DRACULA),
        theme("one-dark", NodeTheme.Theme.ONE_DARK, SyntaxTheme.Theme.ONE_DARK),
        theme("obsidian", NodeTheme.Theme.OBSIDIAN, SyntaxTheme.Theme.OBSIDIAN),
        theme("sanne", NodeTheme.Theme.SANNE, SyntaxTheme.Theme.SANNE)
    );

    private static KalixTheme theme(String id, NodeTheme.Theme nodeTheme, SyntaxTheme.Theme syntaxTheme) {
        return new KalixTheme(id, UnifiedThemeLoader.load(id), nodeTheme, syntaxTheme);
    }

    private ThemeRegistry() {
    }

    /** All themes, in the order they appear in the UI. */
    public static List<KalixTheme> all() {
        return THEMES;
    }

    /**
     * The default theme ("light"), used whenever a stored preference cannot be
     * resolved to any known theme.
     */
    public static KalixTheme defaultTheme() {
        return THEMES.get(0);
    }

    /** Look a theme up by its stable id. */
    public static Optional<KalixTheme> byId(String id) {
        if (id == null) {
            return Optional.empty();
        }
        return THEMES.stream().filter(theme -> theme.id().equals(id)).findFirst();
    }

    /** Look a theme up by its stable id, falling back to {@link #defaultTheme()}. */
    public static KalixTheme byIdOrDefault(String id) {
        return byId(id).orElseGet(ThemeRegistry::defaultTheme);
    }

    /**
     * Look a theme up by the display name that older preference files stored
     * (e.g. "Sunset Warmth"). Used only for one-time preference migration.
     */
    public static Optional<KalixTheme> byLegacyDisplayName(String displayName) {
        if (displayName == null) {
            return Optional.empty();
        }
        return THEMES.stream().filter(theme -> theme.displayName().equals(displayName)).findFirst();
    }

    /** The theme whose node palette is the given one (the linkage is 1:1). */
    public static Optional<KalixTheme> byNodeTheme(NodeTheme.Theme nodeTheme) {
        return THEMES.stream().filter(theme -> theme.nodeTheme() == nodeTheme).findFirst();
    }

    /** The theme whose syntax palette is the given one (the linkage is 1:1). */
    public static Optional<KalixTheme> bySyntaxTheme(SyntaxTheme.Theme syntaxTheme) {
        return THEMES.stream().filter(theme -> theme.syntaxTheme() == syntaxTheme).findFirst();
    }
}
