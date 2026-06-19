package com.kalix.ide.icons;

import org.kordamp.ikonli.Ikon;
import org.kordamp.ikonli.fontawesome6.FontAwesomeSolid;
import org.kordamp.ikonli.swing.FontIcon;

import javax.swing.Icon;
import javax.swing.UIManager;
import java.awt.Color;

/**
 * Theme-aware icons for context-menu items.
 *
 * <p>Per the context-menu style manifesto ({@code docs/context-menu-style.md}, §3),
 * icons are reserved for the universally-recognised clipboard and destructive
 * actions — Cut, Copy, Paste and Delete — and nowhere else. Funnelling every menu
 * icon through this one helper is what enforces "sparse icons only": there is no
 * convenient way to icon an arbitrary item, so the temptation never arises.
 *
 * <p>Note the deliberate absence of an icon for "Remove": only true destruction
 * (Delete) earns the trash glyph, which reinforces the Delete-vs-Remove distinction
 * visually (manifesto §2.5).
 */
public final class MenuIcons {

    /** Menu icons sit a touch smaller than the label text so they read as marks, not buttons. */
    private static final int SIZE = 14;

    private MenuIcons() {
    }

    public static Icon cut() {
        return icon(FontAwesomeSolid.CUT);
    }

    public static Icon copy() {
        return icon(FontAwesomeSolid.COPY);
    }

    public static Icon paste() {
        return icon(FontAwesomeSolid.PASTE);
    }

    public static Icon delete() {
        return icon(FontAwesomeSolid.TRASH);
    }

    private static Icon icon(Ikon glyph) {
        FontIcon fontIcon = FontIcon.of(glyph, SIZE);
        fontIcon.setIconColor(foregroundColor());
        return fontIcon;
    }

    /**
     * Picks an icon colour that reads against the current theme's menu background,
     * mirroring the toolbar's light/dark heuristic so icons feel of a piece.
     */
    private static Color foregroundColor() {
        Color background = UIManager.getColor("MenuItem.background");
        if (background == null) {
            background = UIManager.getColor("Menu.background");
        }
        if (background != null) {
            boolean dark = background.getRed() + background.getGreen() + background.getBlue() < 384;
            return dark ? Color.LIGHT_GRAY : Color.DARK_GRAY;
        }
        return Color.DARK_GRAY;
    }
}
