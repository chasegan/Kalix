package com.kalix.ide.flowviz.style;

import java.awt.BasicStroke;

/**
 * A curated thickness + {@link DashStyle} combination.
 *
 * <p>Each constant is one item in the palette editor's single "combined stroke"
 * dropdown — the design deliberately offers a small hand-picked set rather than
 * the full thickness x dash matrix.</p>
 *
 * <p>Adding a constant here automatically extends the dropdown; existing
 * persisted palettes are unaffected because palettes store the enum name.</p>
 */
public enum StrokeStyle {
    THIN("Thin", 1.0f, DashStyle.SOLID),
    SOLID("Solid", 1.5f, DashStyle.SOLID),
    BOLD("Bold", 2.5f, DashStyle.SOLID),
    DASHED("Dashed", 1.5f, DashStyle.DASHED),
    BOLD_DASHED("Bold dashed", 2.5f, DashStyle.DASHED),
    DOTTED("Dotted", 1.5f, DashStyle.DOTTED),
    BOLD_DOTTED("Bold dotted", 2.5f, DashStyle.DOTTED);

    /**
     * The stroke every slot of a fresh palette starts with. Its 1.5px solid line
     * matches the fixed stroke the renderer used before palettes existed, so the
     * built-in {@code Default} palette reproduces the historical appearance.
     */
    public static final StrokeStyle DEFAULT = SOLID;

    private final String displayName;
    private final float thickness;
    private final DashStyle dash;

    StrokeStyle(String displayName, float thickness, DashStyle dash) {
        this.displayName = displayName;
        this.thickness = thickness;
        this.dash = dash;
    }

    public String getDisplayName() {
        return displayName;
    }

    public float getThickness() {
        return thickness;
    }

    public DashStyle getDash() {
        return dash;
    }

    /**
     * Builds the AWT stroke for full-resolution line rendering.
     *
     * <p>Round caps and joins match the existing renderer; round caps are also
     * what make {@link DashStyle#DOTTED} render as dots rather than tiny dashes.
     * A new instance is returned each call — {@code BasicStroke} is immutable but
     * the dash array it is built from must not be shared.</p>
     */
    public BasicStroke toBasicStroke() {
        float[] dashArray = dash.dashArray();
        if (dashArray == null) {
            return new BasicStroke(thickness, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND);
        }
        return new BasicStroke(thickness, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND,
                10.0f, dashArray, 0.0f);
    }

    /**
     * Resolves a display name back to its constant, falling back to
     * {@link #DEFAULT} for an unknown name.
     */
    public static StrokeStyle fromDisplayName(String displayName) {
        for (StrokeStyle style : values()) {
            if (style.displayName.equals(displayName)) {
                return style;
            }
        }
        return DEFAULT;
    }
}
