package com.kalix.ide.flowviz;

/**
 * The three pages a unified visualization tab can show: the plot rendering, the
 * statistics table, and the data table. A tab's view is pure presentation — all
 * pages project the same {@link FlowVizPanel}-owned state, and switching is
 * never undoable.
 */
enum FlowVizView {
    PLOT,
    STATS,
    DATA
}
