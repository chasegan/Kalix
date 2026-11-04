package com.kalix.ide.flowviz;

/**
 * The three pages a unified visualization tab can show: the plot rendering, the
 * statistics table, and the data table. A tab's view is pure presentation — all
 * pages project the same {@link FlowVizModel}-owned state (ADR-0016), and switching
 * is never undoable.
 */
enum FlowVizView {
    PLOT,
    STATS,
    DATA
}
