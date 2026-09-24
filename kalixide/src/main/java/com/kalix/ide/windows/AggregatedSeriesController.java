package com.kalix.ide.windows;

import com.kalix.ide.components.JCheckboxTree;
import com.kalix.ide.flowviz.VisualizationTabManager;
import com.kalix.ide.flowviz.data.AggregateLabel;
import com.kalix.ide.flowviz.data.DataSet;
import com.kalix.ide.flowviz.data.DefaultLabelResolver;
import com.kalix.ide.flowviz.data.SourceRef;
import com.kalix.ide.flowviz.data.TimeSeriesData;
import com.kalix.ide.flowviz.style.SeriesSlotManager;
import com.kalix.ide.managers.TimeSeriesRequestManager;

import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The Run Manager's user-created aggregates: the sum of chosen series from one source,
 * shown in the data source tree (Aggregate series &gt; origin &gt; name) and as
 * {@code aggregate.<name>} in the outputs tree.
 *
 * <p>Owns each aggregate's values, independently of the source they were summed from,
 * so an aggregate outlives the removal of that source. Aggregates of the "Last" alias
 * are recomputed when Last changes; all others are fixed at creation. EDT-only.</p>
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
    private final DefaultLabelResolver labelResolver;

    private final Map<Long, AggregateInfo> aggregates = new LinkedHashMap<>();
    // An origin's last display name, recorded when it is removed.
    private final Map<SourceRef, String> removedOriginLabels = new HashMap<>();
    private long nextId = 1;

    AggregatedSeriesController(
        RunManager window,
        JCheckboxTree sourceTree,
        JCheckboxTree outputsTree,
        DefaultTreeModel treeModel,
        DefaultMutableTreeNode aggregateSeriesNode,
        VisualizationTabManager tabManager,
        DataSet plotDataSet,
        SeriesSlotManager seriesSlotManager,
        TimeSeriesRequestManager timeSeriesRequestManager,
        DefaultLabelResolver labelResolver
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
        this.labelResolver = labelResolver;
    }

    /**
     * Creates one aggregate per source covered by the outputs-tree selection, each the
     * sum of the selected series in that source. Wired to the outputs tree's
     * "New aggregate…" item.
     */
    void createAggregatedSeries() {
        // Not yet implemented: selection → sources, name prompt, fetch and sum.
    }

    /** The label lookup for {@link DefaultLabelResolver}; {@code null} for an unknown id. */
    AggregateLabel labelFor(long id) {
        AggregateInfo info = aggregates.get(id);
        return info == null ? null
            : new AggregateLabel(info.name(), info.origin, removedOriginLabels.get(info.origin));
    }

    /**
     * Registers an aggregate and adds it to the source tree under its origin's group,
     * creating the group if this is the origin's first aggregate.
     */
    AggregateInfo register(SourceRef origin, String name, List<String> inputNames,
                           TimeSeriesData values) {
        AggregateInfo info = new AggregateInfo(nextId++, origin, name, inputNames, values);
        aggregates.put(info.id, info);

        DefaultMutableTreeNode group = groupNodeFor(origin);
        group.add(new DefaultMutableTreeNode(info));
        treeModel.nodesWereInserted(group, new int[]{group.getChildCount() - 1});
        return info;
    }

    /** The group node for {@code origin}, created and inserted if absent. */
    private DefaultMutableTreeNode groupNodeFor(SourceRef origin) {
        for (int i = 0; i < aggregateSeriesNode.getChildCount(); i++) {
            DefaultMutableTreeNode child = (DefaultMutableTreeNode) aggregateSeriesNode.getChildAt(i);
            if (child.getUserObject() instanceof OriginGroup g && g.origin.equals(origin)) {
                return child;
            }
        }
        DefaultMutableTreeNode group = new DefaultMutableTreeNode(new OriginGroup(origin));
        aggregateSeriesNode.add(group);
        treeModel.nodesWereInserted(aggregateSeriesNode,
            new int[]{aggregateSeriesNode.getChildCount() - 1});
        return group;
    }

    /** Source-tree user object for an origin's group. Not a source itself. */
    private final class OriginGroup {
        final SourceRef origin;

        OriginGroup(SourceRef origin) {
            this.origin = origin;
        }

        @Override
        public String toString() {
            return labelResolver.originLabel(origin, removedOriginLabels.get(origin));
        }
    }
}
