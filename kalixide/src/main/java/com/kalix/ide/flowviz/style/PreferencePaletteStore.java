package com.kalix.ide.flowviz.style;

import com.kalix.ide.preferences.PreferenceKeys;
import com.kalix.ide.preferences.PreferenceManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * {@link PaletteStore} backed by {@code kalix_prefs.json} via {@link PreferenceManager}.
 *
 * <p>User palettes are stored under {@link PreferenceKeys#PLOT_PALETTES} as a
 * string list — one {@link PaletteCodec}-encoded line per palette — and the active
 * selection under {@link PreferenceKeys#PLOT_ACTIVE_PALETTE}. This is the file-based
 * (portable, team-shareable) preference tier, as specified for this feature.</p>
 */
public final class PreferencePaletteStore implements PaletteStore {

    @Override
    public List<PlotPalette> loadUserPalettes() {
        List<String> encoded = PreferenceManager.getFileStringList(
            PreferenceKeys.PLOT_PALETTES, Collections.emptyList());

        List<PlotPalette> palettes = new ArrayList<>(encoded.size());
        for (String line : encoded) {
            PaletteCodec.decode(line).ifPresent(palettes::add);
        }
        return palettes;
    }

    @Override
    public void saveUserPalettes(List<PlotPalette> palettes) {
        List<String> encoded = new ArrayList<>(palettes.size());
        for (PlotPalette palette : palettes) {
            encoded.add(PaletteCodec.encode(palette));
        }
        PreferenceManager.setFileStringList(PreferenceKeys.PLOT_PALETTES, encoded);
    }

    @Override
    public String loadActivePaletteName() {
        return PreferenceManager.getFileString(
            PreferenceKeys.PLOT_ACTIVE_PALETTE, PlotPalette.ORIGINAL_NAME);
    }

    @Override
    public void saveActivePaletteName(String name) {
        PreferenceManager.setFileString(PreferenceKeys.PLOT_ACTIVE_PALETTE, name);
    }
}
