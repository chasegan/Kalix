package com.kalix.ide.document;

import java.io.File;
import java.util.List;

/**
 * A read-only view of the workspace for windows outside the main frame: which models are
 * open, which one is in front, where the project lives, and notification when any of that
 * changes.
 *
 * <p>This exists so a window like the Optimiser can <em>enumerate</em> the open models
 * rather than only sample whichever one happens to be active. Handing such a window a
 * {@code Supplier<String> modelText} bound to the active document makes its choice of
 * model implicit and unstateable — the user cannot see it, and cannot change it without
 * going back to the main window.</p>
 *
 * <p>Deliberately read-only: a window that only needs to look at models cannot mutate the
 * document graph through this. Writing back is a separate, explicitly granted capability
 * ({@link ModelWriteBack}).</p>
 *
 * @see OpenModel
 */
public interface WorkspaceView {

    /** Every open model, in tab order. Never null; may be empty. */
    List<? extends OpenModel> openModels();

    /** The model in front in the main window, or {@code null} if none is open. */
    OpenModel activeModel();

    /**
     * The open project folder, or {@code null} if none.
     *
     * <p>A property of the workspace rather than of any one model: it is the bound
     * {@link DocumentLabels} stops walking paths at, so a name shown by a window is
     * disambiguated exactly the way the editor tab strip disambiguates it.</p>
     */
    File projectRoot();

    /**
     * Registers a listener fired whenever this view would report something different — a
     * model opened, closed, or activated.
     *
     * <p>Listeners are never removed: the workspace outlives every window that observes
     * it (the Optimiser is a singleton for the life of the app), so there is no
     * unregister path to get wrong.</p>
     */
    void addChangeListener(Runnable listener);
}
