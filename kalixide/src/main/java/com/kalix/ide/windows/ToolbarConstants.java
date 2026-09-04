package com.kalix.ide.windows;

import javax.swing.AbstractButton;
import java.awt.Dimension;

/**
 * Sizing and shared option lists for the plot and stats toolbars ({@link PlotToolbarBuilder},
 * {@link StatsToolbarBuilder}), pulled into one place so the two builders draw from a single
 * definition instead of one owning the constants and the other reaching across as
 * {@code PlotToolbarBuilder.FIELD}. Same pattern as {@link com.kalix.ide.constants.UIConstants}.
 */
public final class ToolbarConstants {

    // Prevent instantiation
    private ToolbarConstants() {
        throw new UnsupportedOperationException("Constants class should not be instantiated");
    }

    public static final int BUTTON_ICON_SIZE = 14;
    static final Dimension WIDE_DROPDOWN_SIZE = new Dimension(150, 25);
    static final Dimension NARROW_DROPDOWN_SIZE = new Dimension(80, 25);
    public static final int HORIZONTAL_SPACING = 5;
    static final Dimension BUTTON_SIZE = new Dimension(28, 28);

    /**
     * Pins a button to the toolbar's shared square footprint, so every icon and toggle
     * lines up regardless of the glyph and border it would otherwise size itself from.
     * All three bounds are set because a {@link javax.swing.JToolBar} honours the maximum
     * when it lays out, and the minimum when space runs short.
     */
    static void applyButtonSizing(AbstractButton button) {
        button.setPreferredSize(BUTTON_SIZE);
        button.setMinimumSize(BUTTON_SIZE);
        button.setMaximumSize(BUTTON_SIZE);
    }

    /** Aggregation period options for time series data. */
    static final String[] AGGREGATION_OPTIONS = {
        "Original",
        "Daily",
        "Monthly",
        "Annual (Jan-Dec)",
        "Annual (Feb-Jan)",
        "Annual (Mar-Feb)",
        "Annual (Apr-Mar)",
        "Annual (May-Apr)",
        "Annual (Jun-May)",
        "Annual (Jul-Jun)",
        "Annual (Aug-Jul)",
        "Annual (Sep-Aug)",
        "Annual (Oct-Sep)",
        "Annual (Nov-Oct)",
        "Annual (Dec-Nov)"
    };

    /** Aggregation method options. */
    static final String[] AGGREGATION_METHOD_OPTIONS = {"Sum", "Min", "Max", "Mean"};
}
