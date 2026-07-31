package com.kalix.ide.document;

import java.util.List;

/**
 * The set of models an auxiliary window may target, and notification when that set
 * changes.
 *
 * <p>This exists so that a window like the Optimiser can <em>enumerate</em> the open
 * models rather than only sample whichever one happens to be in front. Handing such a
 * window a {@code Supplier<String> modelText} bound to the active document makes the
 * choice of model implicit and unstateable — the user cannot see it, and cannot change
 * it without going back to the main window.</p>
 *
 * @see ModelSource
 */
public interface ModelSourceRegistry {

    /** Every open model, in tab order. Never null; may be empty. */
    List<? extends ModelSource> available();

    /** The model in front in the main window, or {@code null} if none is open. */
    ModelSource active();

    /**
     * Registers a listener fired whenever {@link #available()} or {@link #active()}
     * would return something different — a model opened, closed, or activated.
     *
     * <p>Listeners are never removed: the registry outlives every window that
     * observes it (the Optimiser is a singleton for the life of the app), so there is
     * no unregister path to get wrong.</p>
     */
    void addChangeListener(Runnable listener);
}
