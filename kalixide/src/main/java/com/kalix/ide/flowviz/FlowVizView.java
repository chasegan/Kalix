package com.kalix.ide.flowviz;

/**
 * The two pages a unified visualization tab can show: the plot rendering or
 * the statistics table. A tab's view is pure presentation — both pages project
 * the same {@link FlowVizPanel}-owned state, and switching is never undoable.
 */
enum FlowVizView {
    PLOT,
    STATS
}
