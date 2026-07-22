package com.kalix.ide.io;

import com.kalix.ide.utils.ThemeUtils;
import org.kordamp.ikonli.Ikon;
import org.kordamp.ikonli.fontawesome6.FontAwesomeSolid;
import org.kordamp.ikonli.swing.FontIcon;

import javax.swing.Icon;
import javax.swing.UIManager;
import java.awt.Color;
import java.util.HashMap;
import java.util.Map;

/**
 * The single home of the Kalix file visual language: which glyph and colour a file or folder
 * gets, wherever files are rendered (the project tree, the file dialogs). Doctrine in
 * {@code manifestos/file-tree-colour.md}: recognised entries carry accent icons and
 * full-strength text; everything else steps down through the muted and faint grey tiers
 * (§1.1, §2.1–2.4). Keeping the mapping here means the tree and the dialogs cannot drift
 * apart.
 *
 * <p>All colours resolve through {@code Kalix.tree.*} theme keys (plus the shared
 * {@link ThemeUtils#iconColor} icon grey), so a theme change restyles every user of this
 * class automatically. Icons are cached by glyph + colour; the cache is only touched on the
 * EDT (renderers), so no synchronisation is needed.
 */
public final class FileVisuals {

    /** Icon size used everywhere files are listed (tree rows, dialog rows). */
    public static final int ICON_SIZE = 14;

    /** Text/icon strength tiers, strongest first (file-tree-colour §2.1). */
    public enum Tier {FULL, MUTED, FAINT}

    private static final Map<String, Icon> ICON_CACHE = new HashMap<>();

    private FileVisuals() {
        throw new UnsupportedOperationException("Utility class");
    }

    // --- Icons ---

    /** The themed icon for a file (not a folder) with the given name. */
    public static Icon fileIcon(String name) {
        Ikon glyph;
        Color color;
        switch (FileCategory.ofName(name)) {
            case MODEL -> {
                glyph = FontAwesomeSolid.PROJECT_DIAGRAM; // a node-link network, like the model
                color = themed("Kalix.tree.modelFileColor", fullStrengthFileColor());
            }
            case DATA -> {
                glyph = glyphForExtension(name);
                color = themed("Kalix.tree.dataFileColor", fullStrengthFileColor());
            }
            case SOURCE_RESULT -> {
                glyph = FontAwesomeSolid.CHART_LINE; // same association as the plot toolbar icon
                color = themed("Kalix.tree.sourceResultFileColor", fullStrengthFileColor());
            }
            default -> {
                // Faint like the text, so an uncategorised file's icon doesn't out-pop it.
                glyph = glyphForExtension(name);
                Color faint = tierColor(Tier.FAINT);
                color = faint != null ? faint : fullStrengthFileColor();
            }
        }
        return cachedIcon(glyph, color);
    }

    /**
     * The themed folder icon. {@code prominent} folders (e.g. model folders) get the
     * full-strength shared icon grey; the rest are muted (§2.4).
     */
    public static Icon folderIcon(boolean expanded, boolean prominent) {
        Ikon glyph = expanded ? FontAwesomeSolid.FOLDER_OPEN : FontAwesomeSolid.FOLDER;
        Color full = ThemeUtils.iconColor(UIManager.getColor("Tree.background"));
        Color color = prominent ? full : themed("Kalix.tree.mutedForeground", full);
        return cachedIcon(glyph, color);
    }

    // --- Text tiers ---

    /** The text tier for a file (not a folder) with the given name: FULL if recognised, else FAINT. */
    public static Tier fileTier(String name) {
        return FileCategory.ofName(name) != FileCategory.OTHER ? Tier.FULL : Tier.FAINT;
    }

    /** The text tier for a folder: FULL when prominent (contains a model), else MUTED. */
    public static Tier folderTier(boolean prominent) {
        return prominent ? Tier.FULL : Tier.MUTED;
    }

    /**
     * The foreground colour for a tier, or {@code null} for {@link Tier#FULL} (meaning:
     * keep the component's default full-strength foreground) and for themes that define
     * neither grey key. FAINT falls back to MUTED when a theme omits it.
     */
    public static Color tierColor(Tier tier) {
        return switch (tier) {
            case FULL -> null;
            case MUTED -> UIManager.getColor("Kalix.tree.mutedForeground");
            case FAINT -> {
                Color c = UIManager.getColor("Kalix.tree.faintForeground");
                yield c != null ? c : UIManager.getColor("Kalix.tree.mutedForeground");
            }
        };
    }

    // --- Internals ---

    private static Ikon glyphForExtension(String name) {
        String lower = name.toLowerCase();
        if (lower.endsWith(".toml") || lower.endsWith(".json")) {
            return FontAwesomeSolid.FILE_CODE;
        }
        if (lower.endsWith(".csv")) {
            return FontAwesomeSolid.FILE_CSV;
        }
        if (lower.endsWith(".pxt") || lower.endsWith(".pxb")) {
            return FontAwesomeSolid.DATABASE;
        }
        if (lower.endsWith(".md") || lower.endsWith(".txt") || lower.endsWith(".log")) {
            return FontAwesomeSolid.FILE_ALT;
        }
        if (lower.endsWith(".zip")) {
            return FontAwesomeSolid.FILE_ARCHIVE;
        }
        return FontAwesomeSolid.FILE;
    }

    private static Icon cachedIcon(Ikon glyph, Color color) {
        String key = glyph.getDescription() + "#" + color.getRGB();
        return ICON_CACHE.computeIfAbsent(key, k -> FontIcon.of(glyph, ICON_SIZE, color));
    }

    /** A themed {@code Kalix.tree.*} colour, or {@code fallback} for themes that omit the key. */
    private static Color themed(String key, Color fallback) {
        Color c = UIManager.getColor(key);
        return c != null ? c : fallback;
    }

    private static Color fullStrengthFileColor() {
        Color c = UIManager.getColor("Tree.foreground");
        if (c == null) {
            c = UIManager.getColor("Label.foreground");
        }
        return c != null ? c : Color.GRAY;
    }
}
