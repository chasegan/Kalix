package com.kalix.ide.flowviz.style;

import java.util.List;

/**
 * Persistence boundary for {@link PlotPaletteManager}.
 *
 * <p>Abstracting persistence behind this interface keeps the manager free of any
 * dependency on the static {@code PreferenceManager}, so it can be unit-tested
 * against an in-memory fake without touching {@code kalix_prefs.json} on disk.
 * The production implementation is {@link PreferencePaletteStore}.</p>
 *
 * <p>Only user-defined palettes are persisted — built-in palettes live in code
 * and are re-created on every launch.</p>
 */
public interface PaletteStore {

    /** Loads the persisted user palettes; malformed entries are skipped. Never null. */
    List<PlotPalette> loadUserPalettes();

    /** Persists the complete set of user palettes, replacing whatever was stored. */
    void saveUserPalettes(List<PlotPalette> palettes);

    /** Loads the persisted active-palette name, or {@code null} if none was stored. */
    String loadActivePaletteName();

    /** Persists the name of the globally active palette. */
    void saveActivePaletteName(String name);
}
