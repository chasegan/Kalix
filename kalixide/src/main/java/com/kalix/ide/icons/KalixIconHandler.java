package com.kalix.ide.icons;

import org.kordamp.ikonli.Ikon;
import org.kordamp.ikonli.fontawesome6.FontAwesomeSolidIkonHandler;

/**
 * Ikonli {@link org.kordamp.ikonli.IkonHandler} for {@link KalixIcon} constants.
 *
 * <p>Extends Ikonli's FontAwesome 6 solid handler so it inherits the font resource location and
 * loading ({@code fa-solid-900.ttf}, FontAwesome 6.5.2 Free Solid) — the same font that backs the
 * standard {@code FontAwesomeSolid.*} icons. We only override icon-name matching so our
 * {@code kxi-}-prefixed descriptions resolve to {@link KalixIcon} values.
 *
 * <p>Discovered automatically at runtime via {@code ServiceLoader}; see
 * {@code META-INF/services/org.kordamp.ikonli.IkonHandler}.
 */
public class KalixIconHandler extends FontAwesomeSolidIkonHandler {

    @Override
    public boolean supports(String description) {
        return description != null && description.startsWith("kxi-");
    }

    @Override
    public Ikon resolve(String description) {
        for (KalixIcon icon : KalixIcon.values()) {
            if (icon.getDescription().equals(description)) {
                return icon;
            }
        }
        throw new IllegalArgumentException("Unknown Kalix icon description: " + description);
    }
}
