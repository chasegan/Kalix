package com.kalix.ide.flowviz;

import com.kalix.ide.flowviz.data.LabelResolver;

import java.io.File;

/**
 * The context an owning window supplies to a {@link VisualizationTabManager}.
 *
 * <p>The tab manager is host-agnostic: it owns tabs, panels, and their shared
 * state, and asks its host only for the things that genuinely belong to the
 * window around it — how series refs project to labels, where save dialogs
 * should start, and who wants to know when the active tab's context changes
 * (the Run Manager reprojects its trees; a plainer host ignores it).</p>
 *
 * <p>Every method has a working default, so the no-frills host is
 * {@code new VizHost() { }}: plain tabs, default labels, no source context.
 * The source-context APIs on the manager ({@code setTargetTabCheckedSources},
 * {@code removeSourceFromAllTabs}, …) are host-driven — a host that never
 * calls them simply has tabs with empty source records, which is a fully
 * supported state, not a degraded one.</p>
 */
public interface VizHost {

    /**
     * Projects {@link com.kalix.ide.flowviz.data.SeriesRef}s to user-visible
     * labels for legends, stats rows, and hover overlays. {@code null} (the
     * default) leaves the panels' built-in fallback labelling in place.
     */
    default LabelResolver labelResolver() {
        return null;
    }

    /**
     * The folder save dialogs should open in (typically the model's directory),
     * re-read at each dialog open. {@code null} (the default) lets the dialog
     * fall back to its own default location.
     */
    default File baseDirectory() {
        return null;
    }

    /**
     * Called when the active tab changes, or when the active tab's canonical
     * record (selected series / checked sources) mutates in place — the moment
     * for the host to reproject any views that mirror the active tab.
     */
    default void onActiveTabChanged() {
    }
}
