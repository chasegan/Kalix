package com.kalix.ide.flowviz.style;

import com.kalix.ide.flowviz.data.SeriesRef;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Assigns each {@link SeriesRef} a stable palette <em>slot index</em> in the range
 * {@code [0, PlotPalette.SLOT_COUNT)}.
 *
 * <p>Crucially this assigns an index, not a colour. The colour and stroke a series
 * draws with are the slot looked up in the active {@link PlotPalette} — so when the
 * palette is edited or switched, a series keeps its slot but draws with the new
 * style. (This replaces the former {@code SeriesColorManager}, which bound refs
 * directly to {@code Color} and so could not react to palette changes.)</p>
 *
 * <p>Assignment fills the lowest free slot first, so a slot freed by
 * {@link #removeSlot} is reused before the palette wraps. Once every slot is in
 * use, further series wrap around modulo {@link PlotPalette#SLOT_COUNT} — matching
 * the colour wrap-around the IDE has always applied beyond ten series.</p>
 *
 * <p>Not thread-safe; used on the Swing event-dispatch thread.</p>
 */
public class SeriesSlotManager {

    private final Map<SeriesRef, Integer> slots = new HashMap<>();
    private final List<Runnable> changeListeners = new ArrayList<>();

    /**
     * Returns {@code ref}'s slot, assigning the lowest free one on first request.
     *
     * <p>Idempotent — a ref keeps the same slot for its lifetime, so calling this
     * during rendering is safe and cheap.</p>
     */
    public int assignSlot(SeriesRef ref) {
        Integer existing = slots.get(ref);
        if (existing != null) {
            return existing;
        }

        Set<Integer> used = new HashSet<>(slots.values());
        int slot = -1;
        for (int i = 0; i < PlotPalette.SLOT_COUNT; i++) {
            if (!used.contains(i)) {
                slot = i;
                break;
            }
        }
        // Every slot in use — wrap around.
        if (slot < 0) {
            slot = slots.size() % PlotPalette.SLOT_COUNT;
        }

        slots.put(ref, slot);
        return slot;
    }

    /** The slot assigned to {@code ref}, or {@code null} if it has none. */
    public Integer getSlot(SeriesRef ref) {
        return slots.get(ref);
    }

    /**
     * Explicitly re-assigns {@code ref} to {@code slot} (wrapped modulo
     * {@link PlotPalette#SLOT_COUNT}), notifying change listeners if it differs
     * from the current assignment. This is the user-driven counterpart to
     * {@link #assignSlot} — it backs the legend's style picker.
     */
    public void setSlot(SeriesRef ref, int slot) {
        int normalized = Math.floorMod(slot, PlotPalette.SLOT_COUNT);
        Integer previous = slots.put(ref, normalized);
        if (previous == null || previous != normalized) {
            fireChange();
        }
    }

    /** Releases {@code ref}'s slot so it can be reused by a later series. */
    public void removeSlot(SeriesRef ref) {
        slots.remove(ref);
    }

    /** Clears every slot assignment. */
    public void clearAll() {
        slots.clear();
    }

    /** Registers a listener invoked after a slot is explicitly reassigned via {@link #setSlot}. */
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
}
