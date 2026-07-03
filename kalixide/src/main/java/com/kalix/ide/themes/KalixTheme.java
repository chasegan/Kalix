package com.kalix.ide.themes;

import com.kalix.ide.themes.unified.UnifiedThemeDefinition;

/**
 * One Kalix theme: a stable identifier plus the three coordinated pieces that
 * make up its look — the application (FlatLaf) definition, the map node
 * palette, and the editor syntax palette.
 *
 * <p>The {@link #id() id} is the value stored in preferences and must never
 * change once released; the display name is free to evolve. Dark-ness is
 * classified solely by the application definition's flag (note that Botanical
 * lives among the dark-accented themes in the UI but is a light theme).
 *
 * <p>Instances are created only by {@link ThemeRegistry}.
 */
public final class KalixTheme {

    private final String id;
    private final UnifiedThemeDefinition definition;
    private final NodeTheme.Theme nodeTheme;
    private final SyntaxTheme.Theme syntaxTheme;

    KalixTheme(String id, UnifiedThemeDefinition definition,
               NodeTheme.Theme nodeTheme, SyntaxTheme.Theme syntaxTheme) {
        this.id = id;
        this.definition = definition;
        this.nodeTheme = nodeTheme;
        this.syntaxTheme = syntaxTheme;
    }

    /** Stable kebab-case identifier (e.g. "sunset-warmth"), used in preferences. */
    public String id() {
        return id;
    }

    /** Human-readable name shown in the UI (e.g. "Sunset Warmth"). */
    public String displayName() {
        return definition.getName();
    }

    /** Whether this is a dark theme, per the application definition's flag. */
    public boolean isDark() {
        return definition.isDark();
    }

    /** The FlatLaf application theme definition. */
    public UnifiedThemeDefinition definition() {
        return definition;
    }

    /** The matching map node-colour theme. */
    public NodeTheme.Theme nodeTheme() {
        return nodeTheme;
    }

    /** The matching editor syntax-colour theme. */
    public SyntaxTheme.Theme syntaxTheme() {
        return syntaxTheme;
    }

    @Override
    public String toString() {
        return "KalixTheme{" + id + "}";
    }
}
