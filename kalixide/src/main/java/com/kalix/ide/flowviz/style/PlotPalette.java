package com.kalix.ide.flowviz.style;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;

/**
 * A named, ordered set of exactly {@link #SLOT_COUNT} {@link LineStyle}s — the
 * "palette" a user builds and applies globally to every plot.
 *
 * <h2>Why slot indices, not colours</h2>
 * A series is assigned a slot <em>index</em> by {@code SeriesSlotManager}, never
 * a resolved colour. The colour/stroke a series draws with is the index looked
 * up in the <em>active</em> palette at render time. That indirection is what
 * makes editing a palette entry — or switching the active palette — propagate
 * instantly to every plot showing that series.
 *
 * <h2>Immutability</h2>
 * Every mutating helper returns a new instance; the {@code entries} list is
 * defensively copied and unmodifiable. Built-in palettes carry
 * {@code builtIn == true} and are presented read-only in the editor —
 * {@link #asUserCopy(String)} forks an editable copy (the editor's Duplicate
 * action).
 */
public record PlotPalette(String name, boolean builtIn, List<LineStyle> entries) {

    /** Number of line styles in every palette. Series slot indices wrap modulo this. */
    public static final int SLOT_COUNT = 10;

    /** Name of the built-in palette; see {@link #builtInOriginal()}. */
    public static final String ORIGINAL_NAME = "Original";

    public PlotPalette {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("palette name must not be blank");
        }
        if (entries == null || entries.size() != SLOT_COUNT) {
            throw new IllegalArgumentException(
                "palette must have exactly " + SLOT_COUNT + " entries, got "
                    + (entries == null ? "null" : entries.size()));
        }
        entries = List.copyOf(entries);  // immutable + defensive; also rejects null elements
    }

    /**
     * Returns the style for {@code slot}, wrapping modulo {@link #SLOT_COUNT}.
     *
     * <p>Wrapping means any non-negative slot index resolves — this reproduces
     * the colour wrap-around the IDE has always applied once more than ten
     * series are shown at once.</p>
     */
    public LineStyle entryAt(int slot) {
        return entries.get(Math.floorMod(slot, SLOT_COUNT));
    }

    /** Returns a copy with {@code slot}'s style replaced (slot wraps modulo {@link #SLOT_COUNT}). */
    public PlotPalette withEntry(int slot, LineStyle style) {
        List<LineStyle> copy = new ArrayList<>(entries);
        copy.set(Math.floorMod(slot, SLOT_COUNT), style);
        return new PlotPalette(name, builtIn, copy);
    }

    /** Returns a copy renamed to {@code newName}; all other fields unchanged. */
    public PlotPalette withName(String newName) {
        return new PlotPalette(newName, builtIn, entries);
    }

    /**
     * Forks an editable, non-built-in copy under {@code newName}.
     *
     * <p>This is the only way a built-in palette's styles can be changed: the
     * editor's Duplicate action calls it, then edits the returned copy.</p>
     */
    public PlotPalette asUserCopy(String newName) {
        return new PlotPalette(newName, false, entries);
    }

    /**
     * The built-in {@code Original} palette: the ten categorical "tab10" colours
     * the IDE has always used, each at {@link StrokeStyle#DEFAULT}.
     *
     * <p>Marked {@code builtIn} (read-only). Because its colours and stroke match
     * the renderer's historical hard-coded behaviour, plots look identical to
     * pre-palette builds whenever {@code Original} is the active palette.</p>
     */
    public static PlotPalette builtInOriginal() {
        int[] rgb = {
            0x1f77b4,  // Blue
            0xff7f0e,  // Orange
            0x2ca02c,  // Green
            0xd62728,  // Red
            0x9467bd,  // Purple
            0x8c564b,  // Brown
            0xe377c2,  // Pink
            0x7f7f7f,  // Gray
            0xbcbd22,  // Yellow-green
            0x17becf   // Cyan
        };
        List<LineStyle> styles = new ArrayList<>(SLOT_COUNT);
        for (int hex : rgb) {
            styles.add(new LineStyle(new Color(hex), StrokeStyle.DEFAULT));
        }
        return new PlotPalette(ORIGINAL_NAME, true, styles);
    }
}
