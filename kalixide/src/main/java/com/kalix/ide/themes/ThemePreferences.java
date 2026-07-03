package com.kalix.ide.themes;

import com.kalix.ide.preferences.PreferenceKeys;

import java.util.Optional;

/**
 * Theme preference storage: stable ids, the "follow" default for node and
 * syntax themes, and one-time migration of legacy stored values.
 *
 * <p><b>Stored values.</b> {@code ui.theme} holds a {@link KalixTheme#id()
 * theme id}. {@code ui.nodeTheme} and {@code ui.syntaxTheme} hold either a
 * theme id (an explicit user choice) or {@value #FOLLOW} (the default), which
 * means "follow the application theme" — the effective node/syntax palette is
 * then the one linked to the current application theme, and switching the
 * application theme re-styles the map and editor along with it.
 *
 * <p><b>Migration.</b> Older versions stored display names ("Sunset Warmth")
 * in {@code ui.theme}/{@code ui.nodeTheme} and enum names ("SUNSET_WARMTH")
 * in {@code ui.syntaxTheme}. On first read any such legacy value is resolved
 * and the preference rewritten as the id, so users keep their explicit
 * choices. A value that resolves to nothing (notably the historical node-theme
 * default "Vibrant", which never existed as a theme) is rewritten to
 * {@value #FOLLOW} — the old code silently fell back to Light in that case, so
 * nothing meaningful is lost.
 */
public final class ThemePreferences {

    /** Sentinel stored in node/syntax theme preferences: follow the application theme. */
    public static final String FOLLOW = "follow";

    private ThemePreferences() {
    }

    // ========== Application theme (ui.theme) ==========

    /**
     * The stored application theme, migrating a legacy display name to its id
     * on first read. Unknown values resolve (and are rewritten) to the
     * registry default.
     */
    public static KalixTheme applicationTheme() {
        String stored = PreferenceKeys.UI_THEME.get();

        Optional<KalixTheme> byId = ThemeRegistry.byId(stored);
        if (byId.isPresent()) {
            return byId.get();
        }

        KalixTheme resolved = ThemeRegistry.byLegacyDisplayName(stored)
            .orElseGet(ThemeRegistry::defaultTheme);
        PreferenceKeys.UI_THEME.set(resolved.id());
        return resolved;
    }

    public static void storeApplicationTheme(KalixTheme theme) {
        PreferenceKeys.UI_THEME.set(theme.id());
    }

    // ========== Node theme (ui.nodeTheme) ==========

    /**
     * The explicitly chosen node theme, or empty when following the
     * application theme. Legacy display names ("Sunset Warmth") and enum names
     * ("SUNSET_WARMTH") are migrated to ids; unresolvable values become
     * {@value #FOLLOW}.
     */
    public static Optional<KalixTheme> explicitNodeTheme() {
        String stored = PreferenceKeys.UI_NODE_THEME.get();
        if (FOLLOW.equals(stored)) {
            return Optional.empty();
        }

        Optional<KalixTheme> byId = ThemeRegistry.byId(stored);
        if (byId.isPresent()) {
            return byId;
        }

        Optional<KalixTheme> legacy = ThemeRegistry.byLegacyDisplayName(stored)
            .or(() -> legacyNodeEnumName(stored));
        PreferenceKeys.UI_NODE_THEME.set(legacy.map(KalixTheme::id).orElse(FOLLOW));
        return legacy;
    }

    private static Optional<KalixTheme> legacyNodeEnumName(String stored) {
        try {
            return ThemeRegistry.byNodeTheme(NodeTheme.Theme.valueOf(stored.toUpperCase()));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /** True when the node theme follows the application theme. */
    public static boolean isNodeThemeFollowing() {
        return explicitNodeTheme().isEmpty();
    }

    /** The node palette to use right now (explicit choice, else the application theme's). */
    public static NodeTheme.Theme effectiveNodeTheme() {
        return explicitNodeTheme().orElseGet(ThemePreferences::applicationTheme).nodeTheme();
    }

    public static void storeNodeTheme(KalixTheme theme) {
        PreferenceKeys.UI_NODE_THEME.set(theme.id());
    }

    public static void storeNodeThemeFollow() {
        PreferenceKeys.UI_NODE_THEME.set(FOLLOW);
    }

    // ========== Syntax theme (ui.syntaxTheme) ==========

    /**
     * The explicitly chosen syntax theme, or empty when following the
     * application theme. Legacy enum names ("LIGHT", "ONE_DARK") and display
     * names are migrated to ids; unresolvable values become {@value #FOLLOW}.
     */
    public static Optional<KalixTheme> explicitSyntaxTheme() {
        String stored = PreferenceKeys.UI_SYNTAX_THEME.get();
        if (FOLLOW.equals(stored)) {
            return Optional.empty();
        }

        Optional<KalixTheme> byId = ThemeRegistry.byId(stored);
        if (byId.isPresent()) {
            return byId;
        }

        Optional<KalixTheme> legacy = ThemeRegistry.byLegacyDisplayName(stored)
            .or(() -> legacySyntaxEnumName(stored));
        PreferenceKeys.UI_SYNTAX_THEME.set(legacy.map(KalixTheme::id).orElse(FOLLOW));
        return legacy;
    }

    private static Optional<KalixTheme> legacySyntaxEnumName(String stored) {
        try {
            return ThemeRegistry.bySyntaxTheme(SyntaxTheme.Theme.valueOf(stored.toUpperCase()));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /** True when the syntax theme follows the application theme. */
    public static boolean isSyntaxThemeFollowing() {
        return explicitSyntaxTheme().isEmpty();
    }

    /** The syntax palette to use right now (explicit choice, else the application theme's). */
    public static SyntaxTheme.Theme effectiveSyntaxTheme() {
        return explicitSyntaxTheme().orElseGet(ThemePreferences::applicationTheme).syntaxTheme();
    }

    public static void storeSyntaxTheme(KalixTheme theme) {
        PreferenceKeys.UI_SYNTAX_THEME.set(theme.id());
    }

    public static void storeSyntaxThemeFollow() {
        PreferenceKeys.UI_SYNTAX_THEME.set(FOLLOW);
    }
}
