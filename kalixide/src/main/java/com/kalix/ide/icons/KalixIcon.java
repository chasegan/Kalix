package com.kalix.ide.icons;

import org.kordamp.ikonli.Ikon;

/**
 * Custom {@link Ikon} constants for FontAwesome 6 glyphs that ship inside the
 * {@code ikonli-fontawesome6-pack} font files but are <em>not</em> exposed by that pack's
 * {@code FontAwesomeSolid} enum (the pack's enum mirrors the FontAwesome 5 icon set).
 *
 * <p>Each constant maps a descriptive name to the glyph's Unicode codepoint in the bundled
 * {@code fa-solid-900.ttf} (FontAwesome 6.5.2 Free, Solid). Rendering is wired up by
 * {@link KalixIconHandler}, which reuses Ikonli's own FA6 solid font loader, so these behave
 * exactly like {@code FontAwesomeSolid.*} at call sites:
 * {@code FontIcon.of(KalixIcon.FOLDER_TREE, size)}.
 *
 * <p>To add another FA6-only icon, look up its codepoint (e.g. from the FontAwesome 6.5.2
 * stylesheet) and add a constant here with a {@code kxi-} prefixed description.
 */
public enum KalixIcon implements Ikon {

    /** FontAwesome 6 "folder-tree" (free, solid) — a hierarchical directory tree. */
    FOLDER_TREE("kxi-folder-tree", 0xf802),

    /** FontAwesome 6 "file-circle-plus" (free, solid) — a document with a plus badge. */
    FILE_CIRCLE_PLUS("kxi-file-circle-plus", 0xe494);

    private final String description;
    private final int code;

    KalixIcon(String description, int code) {
        this.description = description;
        this.code = code;
    }

    @Override
    public String getDescription() {
        return description;
    }

    @Override
    public int getCode() {
        return code;
    }
}
