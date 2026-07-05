package com.kalix.ide.flowviz.style;

import com.kalix.ide.preferences.PreferenceKeys;

import java.util.ArrayList;
import java.util.List;

/**
 * {@link PaletteStore} backed by {@code kalix_prefs.json} via the typed {@code Pref} constants.
 *
 * <p>User palettes are stored under {@link PreferenceKeys#PLOT_PALETTES} as a
 * string list — one {@link PaletteCodec}-encoded line per palette — and the active
 * selection under {@link PreferenceKeys#PLOT_ACTIVE_PALETTE}. This is the file-based
 * (portable, team-shareable) preference tier, as specified for this feature.</p>
 */
public final class PreferencePaletteStore implements PaletteStore {

    @Override
    public List<PlotPalette> loadUserPalettes() {
        List<String> encoded = PreferenceKeys.PLOT_PALETTES.get();

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
        PreferenceKeys.PLOT_PALETTES.set(encoded);
    }

    @Override
    public String loadActivePaletteName() {
        return PreferenceKeys.PLOT_ACTIVE_PALETTE.get();
    }

    @Override
    public void saveActivePaletteName(String name) {
        PreferenceKeys.PLOT_ACTIVE_PALETTE.set(name);
    }
}
