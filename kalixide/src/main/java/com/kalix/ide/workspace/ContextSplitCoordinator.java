package com.kalix.ide.workspace;

/**
 * The shared model of the contextual-view region's divider: one remembered expanded
 * width and one collapsed flag for the whole application, exactly as when the region
 * was a single shared panel — dragging the divider in one tab moves it for every tab.
 *
 * <p>Each {@link DocumentSplitView} reads this state when it is laid out or shown and
 * writes the width back when its divider is dragged; {@link DocumentTabPane} flips the
 * collapsed flag (View → Toggle Map). Changes are pushed to the {@link Persister} so
 * the host can store them; the persisted values are passed back in through the
 * constructor at startup.
 *
 * <p>Plain state + callback, no Swing: the split views own all component behaviour.
 */
public class ContextSplitCoordinator {

    /** Receives every state change, for persistence. */
    @FunctionalInterface
    public interface Persister {
        void persist(int width, boolean collapsed);
    }

    private final Persister persister;
    private int width; // remembered expanded width of the contextual-view region
    private boolean collapsed;

    public ContextSplitCoordinator(int width, boolean collapsed, Persister persister) {
        this.width = width;
        this.collapsed = collapsed;
        this.persister = persister;
    }

    /** The remembered expanded width of the region (meaningful even while collapsed). */
    public int width() {
        return width;
    }

    public boolean isCollapsed() {
        return collapsed;
    }

    /** Records a divider drag. Non-positive widths (transient collapse artefacts) are ignored. */
    void setWidth(int width) {
        if (width <= 0 || width == this.width) {
            return;
        }
        this.width = width;
        persister.persist(this.width, collapsed);
    }

    void setCollapsed(boolean collapsed) {
        if (collapsed == this.collapsed) {
            return;
        }
        this.collapsed = collapsed;
        persister.persist(width, this.collapsed);
    }
}
