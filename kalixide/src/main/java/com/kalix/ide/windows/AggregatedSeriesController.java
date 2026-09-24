package com.kalix.ide.windows;

import com.kalix.ide.components.JCheckboxTree;
import com.kalix.ide.flowviz.VisualizationTabManager;
import com.kalix.ide.flowviz.data.DataSet;
import com.kalix.ide.flowviz.style.SeriesSlotManager;
import com.kalix.ide.managers.TimeSeriesRequestManager;

import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;

/**
 * The Run Manager's user-created aggregates: the sum of chosen series from one source,
 * shown in the data source tree (Aggregate series &gt; origin &gt; name) and as
 * {@code aggregate.<name>} in the outputs tree.
 *
 * <p>Owns each aggregate's values, independently of the source they were summed from,
 * so an aggregate outlives the removal of that source. Aggregates of the "Last" alias
 * are recomputed when Last changes; all others are fixed at creation.</p>
 */
class AggregatedSeriesController {

    private final RunManager window;
    private final JCheckboxTree sourceTree;
    private final JCheckboxTree outputsTree;
    private final DefaultTreeModel treeModel;
    private final DefaultMutableTreeNode aggregateSeriesNode;
    private final VisualizationTabManager tabManager;
    private final DataSet plotDataSet;
    private final SeriesSlotManager seriesSlotManager;
    private final TimeSeriesRequestManager timeSeriesRequestManager;

    AggregatedSeriesController(
        RunManager window,
        JCheckboxTree sourceTree,
        JCheckboxTree outputsTree,
        DefaultTreeModel treeModel,
        DefaultMutableTreeNode aggregateSeriesNode,
        VisualizationTabManager tabManager,
        DataSet plotDataSet,
        SeriesSlotManager seriesSlotManager,
        TimeSeriesRequestManager timeSeriesRequestManager
    ) {
        this.window = window;
        this.sourceTree = sourceTree;
        this.outputsTree = outputsTree;
        this.treeModel = treeModel;
        this.aggregateSeriesNode = aggregateSeriesNode;
        this.tabManager = tabManager;
        this.plotDataSet = plotDataSet;
        this.seriesSlotManager = seriesSlotManager;
        this.timeSeriesRequestManager = timeSeriesRequestManager;
    }

    /**
     * Creates one aggregate per source covered by the outputs-tree selection, each the
     * sum of the selected series in that source. Wired to the outputs tree's
     * "New aggregate…" item.
     */
    void createAggregatedSeries() {
        // Not yet implemented: selection → sources, name prompt, fetch and sum.
    }
}
