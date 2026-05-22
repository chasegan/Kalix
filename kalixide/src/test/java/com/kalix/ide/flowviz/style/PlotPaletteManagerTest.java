package com.kalix.ide.flowviz.style;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link PlotPaletteManager} — palette library ownership, the global
 * active selection, persistence, and change notification.
 *
 * <p>Runs against an in-memory {@link PaletteStore} so nothing touches the real
 * {@code kalix_prefs.json}.</p>
 */
class PlotPaletteManagerTest {

    /** In-memory {@link PaletteStore} retaining state across manager instances. */
    private static final class InMemoryPaletteStore implements PaletteStore {
        private List<PlotPalette> palettes = new ArrayList<>();
        private String activeName;
        int saveUserPalettesCalls = 0;

        @Override
        public List<PlotPalette> loadUserPalettes() {
            return new ArrayList<>(palettes);
        }

        @Override
        public void saveUserPalettes(List<PlotPalette> p) {
            palettes = new ArrayList<>(p);
            saveUserPalettesCalls++;
        }

        @Override
        public String loadActivePaletteName() {
            return activeName;
        }

        @Override
        public void saveActivePaletteName(String name) {
            activeName = name;
        }
    }

    // ==== Defaults ====

    @Test
    void freshManagerExposesOnlyTheBuiltInDefault() {
        PlotPaletteManager mgr = new PlotPaletteManager(new InMemoryPaletteStore());

        assertEquals(1, mgr.getPalettes().size());
        assertEquals(PlotPalette.ORIGINAL_NAME, mgr.getActivePaletteName());
        assertTrue(mgr.getActivePalette().builtIn());
        assertTrue(mgr.isBuiltIn(PlotPalette.ORIGINAL_NAME));
    }

    // ==== Duplicate ====

    @Test
    void duplicateAddsAnEditableUserPaletteAndPersistsIt() {
        InMemoryPaletteStore store = new InMemoryPaletteStore();
        PlotPaletteManager mgr = new PlotPaletteManager(store);

        PlotPalette copy = mgr.duplicate(PlotPalette.ORIGINAL_NAME, "My Palette");

        assertEquals("My Palette", copy.name());
        assertFalse(copy.builtIn());
        assertEquals(2, mgr.getPalettes().size());
        assertEquals(1, store.loadUserPalettes().size(), "user palette must be persisted");
    }

    @Test
    void duplicateTrimsTheNewName() {
        PlotPaletteManager mgr = new PlotPaletteManager(new InMemoryPaletteStore());
        assertEquals("Trimmed", mgr.duplicate(PlotPalette.ORIGINAL_NAME, "  Trimmed  ").name());
    }

    @Test
    void duplicateRejectsBlankUnknownSourceAndDuplicateNames() {
        PlotPaletteManager mgr = new PlotPaletteManager(new InMemoryPaletteStore());

        assertThrows(IllegalArgumentException.class,
            () -> mgr.duplicate(PlotPalette.ORIGINAL_NAME, "   "));
        assertThrows(IllegalArgumentException.class,
            () -> mgr.duplicate("Nonexistent", "X"));
        assertThrows(IllegalArgumentException.class,
            () -> mgr.duplicate(PlotPalette.ORIGINAL_NAME, PlotPalette.ORIGINAL_NAME));
    }

    // ==== Active selection ====

    @Test
    void setActivePaletteSwitchesTheGlobalSelection() {
        PlotPaletteManager mgr = new PlotPaletteManager(new InMemoryPaletteStore());
        mgr.duplicate(PlotPalette.ORIGINAL_NAME, "My Palette");

        mgr.setActivePalette("My Palette");

        assertEquals("My Palette", mgr.getActivePaletteName());
        assertEquals("My Palette", mgr.getActivePalette().name());
    }

    @Test
    void setActivePaletteRejectsUnknownName() {
        PlotPaletteManager mgr = new PlotPaletteManager(new InMemoryPaletteStore());
        assertThrows(IllegalArgumentException.class, () -> mgr.setActivePalette("Nonexistent"));
    }

    // ==== Rename ====

    @Test
    void renameChangesAUserPaletteAndActiveSelectionFollows() {
        PlotPaletteManager mgr = new PlotPaletteManager(new InMemoryPaletteStore());
        mgr.duplicate(PlotPalette.ORIGINAL_NAME, "Old Name");
        mgr.setActivePalette("Old Name");

        mgr.rename("Old Name", "New Name");

        assertFalse(mgr.hasPalette("Old Name"));
        assertTrue(mgr.hasPalette("New Name"));
        assertEquals("New Name", mgr.getActivePaletteName(), "active selection follows the rename");
    }

    @Test
    void renameRejectsBuiltInAndCollidingNames() {
        PlotPaletteManager mgr = new PlotPaletteManager(new InMemoryPaletteStore());
        mgr.duplicate(PlotPalette.ORIGINAL_NAME, "A");
        mgr.duplicate(PlotPalette.ORIGINAL_NAME, "B");

        assertThrows(IllegalArgumentException.class,
            () -> mgr.rename(PlotPalette.ORIGINAL_NAME, "Renamed"));
        assertThrows(IllegalArgumentException.class, () -> mgr.rename("A", "B"));
        assertThrows(IllegalArgumentException.class, () -> mgr.rename("Nonexistent", "X"));
    }

    // ==== Delete ====

    @Test
    void deleteRemovesUserPaletteAndActiveRevertsToDefault() {
        PlotPaletteManager mgr = new PlotPaletteManager(new InMemoryPaletteStore());
        mgr.duplicate(PlotPalette.ORIGINAL_NAME, "Doomed");
        mgr.setActivePalette("Doomed");

        mgr.delete("Doomed");

        assertFalse(mgr.hasPalette("Doomed"));
        assertEquals(PlotPalette.ORIGINAL_NAME, mgr.getActivePaletteName(),
            "deleting the active palette reverts to the default");
    }

    @Test
    void deleteRejectsBuiltInAndUnknownNames() {
        PlotPaletteManager mgr = new PlotPaletteManager(new InMemoryPaletteStore());
        assertThrows(IllegalArgumentException.class, () -> mgr.delete(PlotPalette.ORIGINAL_NAME));
        assertThrows(IllegalArgumentException.class, () -> mgr.delete("Nonexistent"));
    }

    // ==== Update (slot edits) ====

    @Test
    void updatePaletteReplacesAUserPaletteInPlace() {
        PlotPaletteManager mgr = new PlotPaletteManager(new InMemoryPaletteStore());
        PlotPalette original = mgr.duplicate(PlotPalette.ORIGINAL_NAME, "Editable");

        LineStyle edit = new LineStyle(java.awt.Color.MAGENTA, StrokeStyle.BOLD_DASHED);
        mgr.updatePalette(original.withEntry(0, edit));

        assertEquals(edit, mgr.getPalette("Editable").entryAt(0));
        assertEquals(2, mgr.getPalettes().size(), "update must not add a palette");
    }

    @Test
    void updatePaletteRejectsBuiltInAndUnknownPalettes() {
        PlotPaletteManager mgr = new PlotPaletteManager(new InMemoryPaletteStore());

        assertThrows(IllegalArgumentException.class,
            () -> mgr.updatePalette(PlotPalette.builtInOriginal()));
        assertThrows(IllegalArgumentException.class,
            () -> mgr.updatePalette(PlotPalette.builtInOriginal().asUserCopy("Unknown")));
    }

    // ==== Change notification ====

    @Test
    void everyMutationNotifiesListeners() {
        PlotPaletteManager mgr = new PlotPaletteManager(new InMemoryPaletteStore());
        int[] count = {0};
        mgr.addChangeListener(() -> count[0]++);

        mgr.duplicate(PlotPalette.ORIGINAL_NAME, "A");   // 1
        mgr.setActivePalette("A");                      // 2
        mgr.updatePalette(mgr.getPalette("A")
            .withEntry(0, new LineStyle(java.awt.Color.RED, StrokeStyle.BOLD)));  // 3
        mgr.rename("A", "B");                           // 4
        mgr.delete("B");                                // 5

        assertEquals(5, count[0]);
    }

    @Test
    void removedListenerIsNoLongerNotified() {
        PlotPaletteManager mgr = new PlotPaletteManager(new InMemoryPaletteStore());
        int[] count = {0};
        Runnable listener = () -> count[0]++;
        mgr.addChangeListener(listener);
        mgr.removeChangeListener(listener);

        mgr.duplicate(PlotPalette.ORIGINAL_NAME, "A");
        assertEquals(0, count[0]);
    }

    // ==== Persistence ====

    @Test
    void palettesAndActiveSelectionSurviveAReload() {
        InMemoryPaletteStore store = new InMemoryPaletteStore();

        PlotPaletteManager first = new PlotPaletteManager(store);
        first.duplicate(PlotPalette.ORIGINAL_NAME, "Persisted");
        first.setActivePalette("Persisted");

        // A brand-new manager over the same store simulates the next IDE launch.
        PlotPaletteManager reloaded = new PlotPaletteManager(store);

        assertTrue(reloaded.hasPalette("Persisted"));
        assertEquals("Persisted", reloaded.getActivePaletteName());
    }

    @Test
    void persistedPaletteCollidingWithABuiltInIsDroppedOnLoad() {
        InMemoryPaletteStore store = new InMemoryPaletteStore();
        // Seed the store with a user palette illegally named like the built-in.
        store.saveUserPalettes(List.of(
            PlotPalette.builtInOriginal().asUserCopy("placeholder")));
        store.palettes.set(0, store.palettes.get(0).withName(PlotPalette.ORIGINAL_NAME));

        PlotPaletteManager mgr = new PlotPaletteManager(store);

        assertEquals(1, mgr.getPalettes().size(), "the colliding palette must be dropped");
        assertTrue(mgr.getPalette(PlotPalette.ORIGINAL_NAME).builtIn());
    }
}
