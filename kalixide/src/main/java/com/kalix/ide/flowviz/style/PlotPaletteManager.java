package com.kalix.ide.flowviz.style;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Owns the set of plot palettes — built-in and user-defined — the single globally
 * active selection, persistence, and change notification.
 *
 * <h2>Role</h2>
 * This is the authority for "which colours/strokes do plots use". A series is
 * assigned a slot <em>index</em> elsewhere ({@code SeriesSlotManager}, added in a
 * later phase); resolving that index against {@link #getActivePalette()} produces
 * the actual {@link LineStyle}. Switching the active palette, or editing it, is
 * therefore a global restyle of every plot.
 *
 * <h2>Built-in vs user palettes</h2>
 * Built-in palettes (currently just {@code Original}) are defined in code, always
 * present, read-only, and never persisted. User palettes are created by
 * {@link #duplicate}, are editable, and are saved to {@code kalix_prefs.json} via
 * the injected {@link PaletteStore}. Palette names are unique across both kinds
 * and act as the selection key.
 *
 * <h2>Change notification</h2>
 * Every mutation — active switch, duplicate, rename, delete, edit — fires the
 * registered {@link Runnable} listeners. A later phase wires those to repaint all
 * open plots.
 *
 * <p>Not thread-safe; expected to be used on the Swing event-dispatch thread.</p>
 */
public class PlotPaletteManager {

    private static final Logger logger = LoggerFactory.getLogger(PlotPaletteManager.class);

    /** Built-in, read-only palettes. Always present at the front of the list; never persisted. */
    private static final List<PlotPalette> BUILT_INS = List.of(PlotPalette.builtInOriginal());

    /** Lazily-created process-wide instance; see {@link #getInstance()}. */
    private static PlotPaletteManager instance;

    private final PaletteStore store;
    private final List<PlotPalette> userPalettes = new ArrayList<>();
    private final List<Runnable> changeListeners = new ArrayList<>();
    private String activePaletteName;

    /** Production constructor: persists to {@code kalix_prefs.json}. */
    public PlotPaletteManager() {
        this(new PreferencePaletteStore());
    }

    /**
     * Constructs a manager over an explicit {@link PaletteStore}, loading any
     * persisted palettes and active selection immediately. Used by tests with an
     * in-memory store.
     */
    public PlotPaletteManager(PaletteStore store) {
        this.store = store;

        for (PlotPalette loaded : store.loadUserPalettes()) {
            // A persisted palette colliding with a built-in (or another loaded
            // palette) would break name-as-key uniqueness — drop the duplicate.
            if (findPalette(loaded.name()) == null) {
                userPalettes.add(loaded);
            } else {
                logger.warn("Ignoring persisted palette with duplicate name '{}'", loaded.name());
            }
        }

        String stored = store.loadActivePaletteName();
        activePaletteName = (stored != null && findPalette(stored) != null)
            ? stored : PlotPalette.ORIGINAL_NAME;
    }

    /**
     * The process-wide palette manager, created on first access over a
     * {@link PreferencePaletteStore}.
     *
     * <p>Palettes are a global setting, so every plot surface shares this one
     * instance. Tests construct their own via {@link #PlotPaletteManager(PaletteStore)}
     * with an in-memory store rather than using this accessor.</p>
     */
    public static synchronized PlotPaletteManager getInstance() {
        if (instance == null) {
            instance = new PlotPaletteManager();
        }
        return instance;
    }

    // ==== Queries ====

    /** All palettes, built-ins first then user palettes in creation order. Unmodifiable. */
    public List<PlotPalette> getPalettes() {
        List<PlotPalette> all = new ArrayList<>(BUILT_INS);
        all.addAll(userPalettes);
        return Collections.unmodifiableList(all);
    }

    /** The palette with the given name, or {@code null} if there is none. */
    public PlotPalette getPalette(String name) {
        return findPalette(name);
    }

    /** Whether a palette with the given name exists (built-in or user). */
    public boolean hasPalette(String name) {
        return findPalette(name) != null;
    }

    /** Whether the named palette is a read-only built-in. */
    public boolean isBuiltIn(String name) {
        return BUILT_INS.stream().anyMatch(p -> p.name().equals(name));
    }

    /** The name of the globally active palette. */
    public String getActivePaletteName() {
        return activePaletteName;
    }

    /** The globally active palette; falls back to the built-in default if unresolved. */
    public PlotPalette getActivePalette() {
        PlotPalette active = findPalette(activePaletteName);
        return active != null ? active : BUILT_INS.get(0);
    }

    // ==== Mutations ====

    /**
     * Makes {@code name} the globally active palette.
     *
     * @throws IllegalArgumentException if no palette has that name
     */
    public void setActivePalette(String name) {
        if (findPalette(name) == null) {
            throw new IllegalArgumentException("No palette named '" + name + "'");
        }
        if (!name.equals(activePaletteName)) {
            activePaletteName = name;
            store.saveActivePaletteName(activePaletteName);
            fireChange();
        }
    }

    /**
     * Creates an editable copy of an existing palette under a new, unique name, and
     * adds it to the user palettes.
     *
     * @return the newly created palette
     * @throws IllegalArgumentException if the source is unknown or the new name is
     *                                  blank or already in use
     */
    public PlotPalette duplicate(String sourceName, String newName) {
        PlotPalette source = findPalette(sourceName);
        if (source == null) {
            throw new IllegalArgumentException("No palette named '" + sourceName + "'");
        }
        String trimmed = requireFreeName(newName);

        PlotPalette copy = source.asUserCopy(trimmed);
        userPalettes.add(copy);
        store.saveUserPalettes(userPalettes);
        fireChange();
        return copy;
    }

    /**
     * Renames a user palette. If it was the active palette, the active selection
     * follows the rename.
     *
     * @throws IllegalArgumentException if the palette is unknown or built-in, or the
     *                                  new name is blank or already in use
     */
    public void rename(String oldName, String newName) {
        int index = userPaletteIndex(oldName);
        String trimmed = newName == null ? "" : newName.trim();
        if (trimmed.equals(oldName)) {
            return;  // no-op rename
        }
        requireFreeName(trimmed);

        userPalettes.set(index, userPalettes.get(index).withName(trimmed));
        if (oldName.equals(activePaletteName)) {
            activePaletteName = trimmed;
            store.saveActivePaletteName(activePaletteName);
        }
        store.saveUserPalettes(userPalettes);
        fireChange();
    }

    /**
     * Deletes a user palette. If it was the active palette, the active selection
     * reverts to the built-in default.
     *
     * @throws IllegalArgumentException if the palette is unknown or built-in
     */
    public void delete(String name) {
        int index = userPaletteIndex(name);
        userPalettes.remove(index);
        if (name.equals(activePaletteName)) {
            activePaletteName = PlotPalette.ORIGINAL_NAME;
            store.saveActivePaletteName(activePaletteName);
        }
        store.saveUserPalettes(userPalettes);
        fireChange();
    }

    /**
     * Replaces an existing user palette with an edited version. The palette is
     * matched by {@link PlotPalette#name()}, so this is used to commit slot edits
     * (the editor must not change the name via this method — use {@link #rename}).
     *
     * @throws IllegalArgumentException if no user palette has that name, or the
     *                                  supplied palette is flagged built-in
     */
    public void updatePalette(PlotPalette palette) {
        if (palette.builtIn()) {
            throw new IllegalArgumentException(
                "Cannot update built-in palette '" + palette.name() + "'");
        }
        int index = userPaletteIndex(palette.name());
        userPalettes.set(index, palette);
        store.saveUserPalettes(userPalettes);
        fireChange();
    }

    // ==== Change listeners ====

    /** Registers a listener invoked after any change to the palettes or active selection. */
    public void addChangeListener(Runnable listener) {
        changeListeners.add(listener);
    }

    /** Removes a previously registered listener. */
    public void removeChangeListener(Runnable listener) {
        changeListeners.remove(listener);
    }

    private void fireChange() {
        // Iterate a copy: a listener may add or remove listeners while reacting.
        for (Runnable listener : new ArrayList<>(changeListeners)) {
            listener.run();
        }
    }

    // ==== Internals ====

    private PlotPalette findPalette(String name) {
        for (PlotPalette p : BUILT_INS) {
            if (p.name().equals(name)) {
                return p;
            }
        }
        for (PlotPalette p : userPalettes) {
            if (p.name().equals(name)) {
                return p;
            }
        }
        return null;
    }

    /** Index of a user palette by name; rejects unknown names and built-ins. */
    private int userPaletteIndex(String name) {
        if (isBuiltIn(name)) {
            throw new IllegalArgumentException("Palette '" + name + "' is built-in and read-only");
        }
        for (int i = 0; i < userPalettes.size(); i++) {
            if (userPalettes.get(i).name().equals(name)) {
                return i;
            }
        }
        throw new IllegalArgumentException("No user palette named '" + name + "'");
    }

    /** Validates a proposed new name is non-blank and unused; returns it trimmed. */
    private String requireFreeName(String name) {
        String trimmed = name == null ? "" : name.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("Palette name must not be blank");
        }
        if (findPalette(trimmed) != null) {
            throw new IllegalArgumentException("A palette named '" + trimmed + "' already exists");
        }
        return trimmed;
    }
}
