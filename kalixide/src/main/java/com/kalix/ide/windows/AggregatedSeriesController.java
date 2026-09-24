package com.kalix.ide.windows;

import com.kalix.ide.cli.SessionManager;
import com.kalix.ide.components.JCheckboxTree;
import com.kalix.ide.flowviz.data.AggregateLabel;
import com.kalix.ide.flowviz.data.AggregateSeries;
import com.kalix.ide.flowviz.data.DatasetSeries;
import com.kalix.ide.flowviz.data.DefaultLabelResolver;
import com.kalix.ide.flowviz.data.LastSource;
import com.kalix.ide.flowviz.data.SeriesRef;
import com.kalix.ide.flowviz.data.SourceRef;
import com.kalix.ide.flowviz.data.TimeSeriesData;
import com.kalix.ide.flowviz.transform.SeriesSum;
import com.kalix.ide.managers.DatasetLoaderManager;
import com.kalix.ide.managers.DatasetSeriesSource;
import com.kalix.ide.managers.OutputsTreeBuilder;
import com.kalix.ide.managers.TimeSeriesRequestManager;

import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreeNode;
import javax.swing.tree.TreePath;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Consumer;

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

    private static final String TITLE = "New Aggregate";

    private final RunManager window;
    private final JCheckboxTree sourceTree;
    private final JCheckboxTree outputsTree;
    private final DefaultTreeModel treeModel;
    private final DefaultMutableTreeNode aggregateSeriesNode;
    private final TimeSeriesRequestManager timeSeriesRequestManager;
    private final DefaultLabelResolver labelResolver;
    private final Map<DatasetSeries, DatasetSeriesSource> datasetSeriesSources;
    private final LastRunTracker lastRunTracker;
    private final Consumer<String> statusUpdater;

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
        TimeSeriesRequestManager timeSeriesRequestManager,
        DefaultLabelResolver labelResolver,
        Map<DatasetSeries, DatasetSeriesSource> datasetSeriesSources,
        LastRunTracker lastRunTracker,
        Consumer<String> statusUpdater
    ) {
        this.window = window;
        this.sourceTree = sourceTree;
        this.outputsTree = outputsTree;
        this.treeModel = treeModel;
        this.aggregateSeriesNode = aggregateSeriesNode;
        this.timeSeriesRequestManager = timeSeriesRequestManager;
        this.labelResolver = labelResolver;
        this.datasetSeriesSources = datasetSeriesSources;
        this.lastRunTracker = lastRunTracker;
        this.statusUpdater = statusUpdater;
    }

    /**
     * Creates one aggregate per origin covered by the outputs-tree selection, each the sum
     * of the selected series in that origin. Wired to the outputs tree's
     * "New aggregate…" item.
     *
     * <p>A selected node contributes every series leaf under it, as the tree currently
     * shows it (so the filter narrows what an in-between node sums). An aggregate counts as
     * a series of its own origin. Every origin must contribute the same series.
     * All-or-nothing: if any input fails, nothing is created.</p>
     */
    void createAggregatedSeries() {
        Map<SourceRef, OriginInputs> selection = selectedInputsByOrigin();
        if (selection == null) {
            return;
        }

        List<SourceRef> origins = List.copyOf(selection.keySet());
        String name = promptForName(origins);
        if (name == null) {
            return;
        }

        Map<SourceRef, List<CompletableFuture<TimeSeriesData>>> futures = new LinkedHashMap<>();
        for (Map.Entry<SourceRef, OriginInputs> entry : selection.entrySet()) {
            List<CompletableFuture<TimeSeriesData>> originFutures =
                requestInputs(entry.getKey(), entry.getValue());
            if (originFutures == null) {
                return;
            }
            futures.put(entry.getKey(), originFutures);
        }

        long lastGeneration = lastRunTracker.getGeneration();
        status("Creating aggregate." + name + "…");
        CompletableFuture.allOf(futures.values().stream().flatMap(List::stream)
                .toArray(CompletableFuture[]::new))
            .whenComplete((ignored, failure) -> SwingUtilities.invokeLater(
                () -> finishCreation(name, selection, futures, lastGeneration)));
    }

    /** One origin's selected inputs, keyed by display name, and where its series are read. */
    private static final class OriginInputs {
        /** The run or dataset tree object; {@code null} if every input is an aggregate. */
        Object source;
        final Map<String, AggregateInfo.Input> inputs = new LinkedHashMap<>();
    }

    /**
     * The selected inputs grouped by origin, or {@code null} (after telling the user why)
     * if the selection can't make an aggregate.
     */
    private Map<SourceRef, OriginInputs> selectedInputsByOrigin() {
        TreePath[] selected = outputsTree.getSelectionPaths();
        Map<SourceRef, OriginInputs> selection = new LinkedHashMap<>();
        if (selected != null) {
            for (TreePath path : selected) {
                Enumeration<TreeNode> nodes =
                    ((DefaultMutableTreeNode) path.getLastPathComponent()).preorderEnumeration();
                while (nodes.hasMoreElements()) {
                    DefaultMutableTreeNode node = (DefaultMutableTreeNode) nodes.nextElement();
                    if (node.getUserObject() instanceof OutputsTreeBuilder.SeriesLeafNode leaf) {
                        addLeaf(selection, leaf);
                    }
                }
            }
        }
        if (selection.isEmpty()) {
            error("Select the series to sum in the Timeseries tree.");
            return null;
        }

        // Require that all selected series are present in all selected origins.
        // Prevents silent dropped series.
        Set<String> allNames = new LinkedHashSet<>();
        selection.values().forEach(o -> allNames.addAll(o.inputs.keySet()));
        List<String> missing = new ArrayList<>();
        for (Map.Entry<SourceRef, OriginInputs> entry : selection.entrySet()) {
            for (String name : allNames) {
                if (!entry.getValue().inputs.containsKey(name)) {
                    missing.add(originLabel(entry.getKey()) + ": " + name);
                }
            }
        }
        if (!missing.isEmpty()) {
            error("Each source must include every selected series. Not selected:\n  "
                + String.join("\n  ", missing));
            return null;
        }
        return selection;
    }

    private void addLeaf(Map<SourceRef, OriginInputs> selection, OutputsTreeBuilder.SeriesLeafNode leaf) {
        if (leaf.source instanceof AggregateInfo aggregate) {
            selection.computeIfAbsent(aggregate.origin, o -> new OriginInputs()).inputs
                .putIfAbsent(labelResolver.nameFor(aggregate.ref()),
                    new AggregateInfo.AggregateInput(aggregate.id));
        } else {
            OriginInputs origin = selection.computeIfAbsent(
                window.sourceRefForNode(leaf.source), o -> new OriginInputs());
            origin.source = leaf.source;
            origin.inputs.putIfAbsent(leaf.seriesName, new AggregateInfo.SeriesInput(leaf.seriesName));
        }
    }

    /** Prompts until the name is valid for every origin; {@code null} if cancelled. */
    private String promptForName(List<SourceRef> origins) {
        String name = suggestName(origins);
        while (true) {
            name = (String) JOptionPane.showInputDialog(window, "Aggregate name:", TITLE,
                JOptionPane.PLAIN_MESSAGE, null, null, name);
            if (name == null) {
                return null;
            }
            name = name.trim();
            String problem = nameProblem(name, origins);
            if (problem == null) {
                return name;
            }
            JOptionPane.showMessageDialog(window, problem, "Invalid Name", JOptionPane.WARNING_MESSAGE);
        }
    }

    private String suggestName(List<SourceRef> origins) {
        int n = 1;
        while (nameProblem("sum_" + n, origins) != null) {
            n++;
        }
        return "sum_" + n;
    }

    /** Why {@code name} can't be used for an aggregate of each origin, or {@code null}. */
    private String nameProblem(String name, List<SourceRef> origins) {
        if (name.isEmpty()) {
            return "Enter a name.";
        }
        if (name.contains(".") || name.chars().anyMatch(Character::isWhitespace)) {
            return "An aggregate name can't contain dots or spaces.";
        }
        for (AggregateInfo existing : aggregates.values()) {
            if (existing.name().equals(name) && origins.contains(existing.origin)) {
                return originLabel(existing.origin) + " already has an aggregate named \"" + name + "\".";
            }
        }
        String full = AggregateSeries.NAME_PREFIX + name;
        for (DatasetSeries column : datasetSeriesSources.keySet()) {
            if (column.baseName().equals(full)) {
                return "A loaded dataset (" + labelResolver.sourceLabel(column)
                    + ") has a column named \"" + full + "\".";
            }
        }
        return null;
    }

    /**
     * Starts fetching one origin's inputs, or returns {@code null} (after telling the user
     * why) if they can't be.
     */
    private List<CompletableFuture<TimeSeriesData>> requestInputs(SourceRef origin, OriginInputs selected) {
        List<CompletableFuture<TimeSeriesData>> futures = new ArrayList<>();
        for (AggregateInfo.Input input : selected.inputs.values()) {
            CompletableFuture<TimeSeriesData> future = switch (input) {
                case AggregateInfo.SeriesInput series -> requestSeries(origin, selected.source, series.name());
                case AggregateInfo.AggregateInput aggregate -> aggregateValues(origin, aggregate.aggregateId());
            };
            if (future == null) {
                return null;
            }
            futures.add(future);
        }
        return futures;
    }

    private CompletableFuture<TimeSeriesData> requestSeries(SourceRef origin, Object source, String name) {
        if (source instanceof RunInfoImpl run) {
            RunInfoImpl resolved = run.isLastAlias() ? lastRunTracker.getLastRunInfo() : run;
            SessionManager.KalixSession session = resolved != null ? resolved.getSession() : null;
            if (session == null) {
                error(originLabel(origin) + " has no session to read from.");
                return null;
            }
            return timeSeriesRequestManager.requestTimeSeries(session.getSessionKey(), name);
        }
        if (source instanceof DatasetLoaderManager.LoadedDatasetInfo dataset) {
            String datasetId = dataset.file.getAbsolutePath();
            switch (datasetSeriesSources.get(new DatasetSeries(datasetId, name))) {
                case DatasetSeriesSource.Loaded loaded -> {
                    return CompletableFuture.completedFuture(loaded.data());
                }
                case DatasetSeriesSource.Pixie ignored -> {
                    error("Aggregates of Pixie datasets are not supported yet.");
                    return null;
                }
                case null -> {
                    error(originLabel(origin) + " has no series " + name + ".");
                    return null;
                }
            }
        }
        error("Unsupported source: " + source);
        return null;
    }

    private CompletableFuture<TimeSeriesData> aggregateValues(SourceRef origin, long aggregateId) {
        AggregateInfo aggregate = aggregates.get(aggregateId);
        TimeSeriesData values = aggregate != null ? aggregate.values() : null;
        if (values == null) {
            String reason = aggregate != null ? aggregate.unavailableReason() : "it was deleted";
            error(originLabel(origin) + ": an input aggregate is unavailable (" + reason + ").");
            return null;
        }
        return CompletableFuture.completedFuture(values);
    }

    /** Sums and registers every origin's aggregate, or none of them. */
    private void finishCreation(String name, Map<SourceRef, OriginInputs> selection,
                                Map<SourceRef, List<CompletableFuture<TimeSeriesData>>> futures,
                                long lastGeneration) {
        List<SourceRef> origins = List.copyOf(selection.keySet());
        if (origins.contains(new LastSource()) && lastRunTracker.getGeneration() != lastGeneration) {
            fail("The last run changed while aggregate." + name + " was being created. Try again.");
            return;
        }
        // Re-checked: another creation may have taken the name while this one fetched.
        String problem = nameProblem(name, origins);
        if (problem != null) {
            fail(problem);
            return;
        }

        Map<SourceRef, TimeSeriesData> sums = new LinkedHashMap<>();
        List<String> problems = new ArrayList<>();
        for (SourceRef origin : origins) {
            List<String> names = List.copyOf(selection.get(origin).inputs.keySet());
            List<CompletableFuture<TimeSeriesData>> originFutures = futures.get(origin);
            SeriesSum sum = new SeriesSum();
            for (int i = 0; i < names.size(); i++) {
                String where = originLabel(origin) + ": " + names.get(i);
                try {
                    sum.add(originFutures.get(i).join());
                } catch (CompletionException e) {
                    Throwable cause = e.getCause() != null ? e.getCause() : e;
                    problems.add(where + " could not be read (" + cause.getMessage() + ")");
                } catch (IllegalArgumentException e) {
                    problems.add(where + " " + e.getMessage());
                }
            }
            if (problems.isEmpty()) {
                sums.put(origin, sum.result());
            }
        }
        if (!problems.isEmpty()) {
            fail("aggregate." + name + " was not created:\n  " + String.join("\n  ", problems));
            return;
        }

        List<TreePath> newPaths = new ArrayList<>();
        Set<SeriesRef> newRefs = new LinkedHashSet<>();
        for (SourceRef origin : origins) {
            DefaultMutableTreeNode node = register(origin, name,
                List.copyOf(selection.get(origin).inputs.values()), sums.get(origin));
            newPaths.add(new TreePath(node.getPath()));
            newRefs.add(((AggregateInfo) node.getUserObject()).ref());
        }

        // Show and plot them: check the new sources, then their series.
        for (TreePath path : newPaths) {
            sourceTree.expandPath(path.getParentPath());
        }
        sourceTree.addCheckedPaths(newPaths);
        window.checkOutputsSeries(newRefs);
        status("Created aggregate." + name + " for " + origins.size()
            + (origins.size() == 1 ? " source" : " sources"));
    }

    /** The label lookup for {@link DefaultLabelResolver}; {@code null} for an unknown id. */
    AggregateLabel labelFor(long id) {
        AggregateInfo info = aggregates.get(id);
        return info == null ? null
            : new AggregateLabel(info.name(), info.origin, removedOriginLabels.get(info.origin));
    }

    /**
     * Registers an aggregate and adds it to the source tree under its origin's group,
     * creating the group if this is the origin's first aggregate. Returns its tree node.
     */
    DefaultMutableTreeNode register(SourceRef origin, String name, List<AggregateInfo.Input> inputs,
                                    TimeSeriesData values) {
        AggregateInfo info = new AggregateInfo(nextId++, origin, name, inputs, values);
        aggregates.put(info.id, info);

        DefaultMutableTreeNode group = groupNodeFor(origin);
        DefaultMutableTreeNode node = new DefaultMutableTreeNode(info);
        group.add(node);
        treeModel.nodesWereInserted(group, new int[]{group.getChildCount() - 1});
        return node;
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

    private String originLabel(SourceRef origin) {
        return labelResolver.originLabel(origin, removedOriginLabels.get(origin));
    }

    private void error(String message) {
        JOptionPane.showMessageDialog(window, message, TITLE, JOptionPane.WARNING_MESSAGE);
    }

    private void fail(String message) {
        status("Aggregate not created");
        error(message);
    }

    private void status(String message) {
        if (statusUpdater != null) {
            statusUpdater.accept(message);
        }
    }

    /** Source-tree user object for an origin's group. Not a source itself. */
    private final class OriginGroup {
        final SourceRef origin;

        OriginGroup(SourceRef origin) {
            this.origin = origin;
        }

        @Override
        public String toString() {
            return originLabel(origin);
        }
    }
}
