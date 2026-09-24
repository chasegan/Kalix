package com.kalix.ide.windows;

import com.kalix.ide.cli.SessionManager;
import com.kalix.ide.components.JCheckboxTree;
import com.kalix.ide.flowviz.VisualizationTabManager;
import com.kalix.ide.flowviz.data.AggregateLabel;
import com.kalix.ide.flowviz.data.AggregateSeries;
import com.kalix.ide.flowviz.data.AggregateSource;
import com.kalix.ide.flowviz.data.DataSet;
import com.kalix.ide.flowviz.data.DatasetSeries;
import com.kalix.ide.flowviz.data.DefaultLabelResolver;
import com.kalix.ide.flowviz.data.LastSource;
import com.kalix.ide.flowviz.data.SeriesRef;
import com.kalix.ide.flowviz.data.SourceRef;
import com.kalix.ide.flowviz.data.TimeSeriesData;
import com.kalix.ide.flowviz.transform.SeriesSum;
import com.kalix.ide.filedialog.FileDialogFilter;
import com.kalix.ide.filedialog.KalixFileDialog;
import com.kalix.ide.icons.MenuIcons;
import com.kalix.ide.io.PixieStore;
import com.kalix.ide.io.SeriesFileWriter;
import com.kalix.ide.managers.DatasetLoaderManager;
import com.kalix.ide.managers.DatasetSeriesSource;
import com.kalix.ide.managers.OutputsTreeBuilder;
import com.kalix.ide.managers.TimeSeriesRequestManager;
import com.kalix.ide.preferences.PreferenceKeys;
import com.kalix.ide.utils.DialogUtils;

import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPopupMenu;
import javax.swing.SwingUtilities;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreeNode;
import javax.swing.tree.TreePath;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Consumer;
import java.util.function.Supplier;

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
    // Inputs listed in the recipe tooltip before "… and N more".
    private static final int RECIPE_LINES = 20;

    private final RunManager window;
    private final JCheckboxTree sourceTree;
    private final JCheckboxTree outputsTree;
    private final DefaultTreeModel treeModel;
    private final DefaultMutableTreeNode aggregateSeriesNode;
    private final VisualizationTabManager tabManager;
    private final DataSet plotDataSet;
    private final TimeSeriesRequestManager timeSeriesRequestManager;
    private final DefaultLabelResolver labelResolver;
    private final Map<DatasetSeries, DatasetSeriesSource> datasetSeriesSources;
    private final LastRunTracker lastRunTracker;
    private final SeriesFetchCoordinator fetchCoordinator;
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
        VisualizationTabManager tabManager,
        DataSet plotDataSet,
        TimeSeriesRequestManager timeSeriesRequestManager,
        DefaultLabelResolver labelResolver,
        Map<DatasetSeries, DatasetSeriesSource> datasetSeriesSources,
        LastRunTracker lastRunTracker,
        SeriesFetchCoordinator fetchCoordinator,
        Consumer<String> statusUpdater
    ) {
        this.window = window;
        this.sourceTree = sourceTree;
        this.outputsTree = outputsTree;
        this.treeModel = treeModel;
        this.aggregateSeriesNode = aggregateSeriesNode;
        this.tabManager = tabManager;
        this.plotDataSet = plotDataSet;
        this.timeSeriesRequestManager = timeSeriesRequestManager;
        this.labelResolver = labelResolver;
        this.datasetSeriesSources = datasetSeriesSources;
        this.lastRunTracker = lastRunTracker;
        this.fetchCoordinator = fetchCoordinator;
        this.statusUpdater = statusUpdater;
    }

    /** "New aggregate from selected…": sums the series under the outputs-tree selection. */
    void createFromSelected() {
        create(selectedLeaves(), "Select the series to sum in the Timeseries tree.");
    }

    /** "New aggregate from checked…": sums the series ticked in the outputs tree. */
    void createFromChecked() {
        create(checkedLeaves(), "Tick the series to sum in the Timeseries tree.");
    }

    /** Whether the outputs-tree selection covers more than one series, so there is a sum. */
    boolean selectionHasSeriesToSum() {
        return hasSeriesToSum(selectedLeaves());
    }

    /** Whether more than one series is ticked in the outputs tree, so there is a sum. */
    boolean checkedHasSeriesToSum() {
        return hasSeriesToSum(checkedLeaves());
    }

    // Counted by name: one series ticked in two sources is still one input per source.
    private static boolean hasSeriesToSum(List<OutputsTreeBuilder.SeriesLeafNode> leaves) {
        return leaves.stream().map(leaf -> leaf.seriesName).distinct().limit(2).count() > 1;
    }

    /**
     * Creates one aggregate per origin covered by {@code leaves}, each the sum of that
     * origin's series among them.
     *
     * <p>A node contributes every series leaf under it, as the tree currently shows it (so
     * the filter narrows what an in-between node sums). An aggregate counts as a series of
     * its own origin. Every origin must contribute the same series. All-or-nothing: if any
     * input fails, nothing is created.</p>
     */
    private void create(List<OutputsTreeBuilder.SeriesLeafNode> leaves, String noneMessage) {
        Map<SourceRef, OriginInputs> selection = inputsByOrigin(leaves, noneMessage);
        if (selection == null) {
            return;
        }

        List<SourceRef> origins = List.copyOf(selection.keySet());
        String name = promptForName(origins);
        if (name == null) {
            return;
        }

        List<Plan> plans = new ArrayList<>();
        try {
            for (Map.Entry<SourceRef, OriginInputs> entry : selection.entrySet()) {
                OriginInputs selected = entry.getValue();
                plans.add(plan(entry.getKey(), selected.source, List.copyOf(selected.inputs.values())));
            }
        } catch (CannotRead e) {
            error(e.getMessage());
            return;
        }
        String refusal = pixieRefusal(plans);
        if (refusal != null) {
            error(refusal);
            return;
        }

        status("Creating aggregate." + name + "…");
        new Creation(name, plans, lastRunTracker.getGeneration()).next();
    }

    /** One origin's selected inputs, keyed by display name, and where its series are read. */
    private static final class OriginInputs {
        /** The run or dataset tree object; {@code null} if every input is an aggregate. */
        Object source;
        final Map<String, AggregateInfo.Input> inputs = new LinkedHashMap<>();
    }

    /**
     * {@code leaves} grouped by origin, or {@code null} (after telling the user why) if they
     * can't make an aggregate.
     */
    private Map<SourceRef, OriginInputs> inputsByOrigin(List<OutputsTreeBuilder.SeriesLeafNode> leaves,
                                                       String noneMessage) {
        Map<SourceRef, OriginInputs> selection = new LinkedHashMap<>();
        Map<Object, SourceRef> originOfSource = new IdentityHashMap<>();
        for (OutputsTreeBuilder.SeriesLeafNode leaf : leaves) {
            addLeaf(selection, originOfSource, leaf);
        }
        if (selection.isEmpty()) {
            error(noneMessage);
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

    private void addLeaf(Map<SourceRef, OriginInputs> selection, Map<Object, SourceRef> originOfSource,
                         OutputsTreeBuilder.SeriesLeafNode leaf) {
        if (leaf.source instanceof AggregateInfo aggregate) {
            selection.computeIfAbsent(aggregate.origin, o -> new OriginInputs()).inputs
                .putIfAbsent(leaf.seriesName, new AggregateInfo.AggregateInput(aggregate.id));
        } else {
            SourceRef originRef = originOfSource.computeIfAbsent(leaf.source, window::sourceRefForNode);
            OriginInputs origin = selection.computeIfAbsent(originRef, o -> new OriginInputs());
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
            DialogUtils.showWarning(window, problem, "Invalid Name");
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

    /** One origin's inputs, ready to be read one at a time. */
    private record Plan(SourceRef origin, List<AggregateInfo.Input> inputs, List<Read> reads) {
        /** Made from Pixie data, directly or through an input aggregate. */
        boolean pixieBacked() {
            return pixieLength() > 0;
        }

        /** The aggregate's point count if Pixie-backed, else 0. */
        long pixieLength() {
            return reads.stream().mapToLong(Read::pixieBackedLength).max().orElse(0);
        }

        int longestDecode() {
            return reads.stream().mapToInt(Read::decodePoints).max().orElse(0);
        }
    }

    /**
     * How to read one input. {@code decodePoints} is its size if it decodes from Pixie;
     * {@code pixieBackedLength} is its size if it is Pixie data at all. Both are 0 otherwise.
     */
    private record Read(String name, Supplier<CompletableFuture<TimeSeriesData>> read,
                        int decodePoints, long pixieBackedLength) {
    }

    /** Why an input can't be read; the message is ready to show. */
    private static final class CannotRead extends Exception {
        CannotRead(String message) {
            super(message);
        }
    }

    /**
     * Plans the reads for one origin's inputs, from {@code source} (a run or dataset tree
     * object, or {@code null} if every input is an aggregate). Run series are requested now,
     * as the request manager caches them anyway; Pixie series are decoded, and input
     * aggregates looked up, only when their turn comes.
     */
    private Plan plan(SourceRef origin, Object source, List<AggregateInfo.Input> inputs) throws CannotRead {
        List<Read> reads = new ArrayList<>();
        for (AggregateInfo.Input input : inputs) {
            reads.add(switch (input) {
                case AggregateInfo.SeriesInput series -> seriesRead(origin, source, series.name());
                case AggregateInfo.AggregateInput aggregate -> aggregateRead(origin, aggregate.aggregateId());
            });
        }
        return new Plan(origin, inputs, reads);
    }

    private Read seriesRead(SourceRef origin, Object source, String name) throws CannotRead {
        if (source instanceof RunInfoImpl run) {
            // A Last alias holds the Last run's session, so no alias resolution is needed.
            SessionManager.KalixSession session = run.getSession();
            if (session == null) {
                throw new CannotRead(originLabel(origin) + " has no session to read from.");
            }
            CompletableFuture<TimeSeriesData> request =
                timeSeriesRequestManager.requestTimeSeries(session.getSessionKey(), name);
            return new Read(name, () -> request, 0, 0);
        }
        if (source instanceof DatasetLoaderManager.LoadedDatasetInfo dataset) {
            String datasetId = dataset.file.getAbsolutePath();
            switch (datasetSeriesSources.get(new DatasetSeries(datasetId, name))) {
                case DatasetSeriesSource.Loaded loaded -> {
                    return new Read(name, () -> CompletableFuture.completedFuture(loaded.data()), 0, 0);
                }
                case DatasetSeriesSource.Pixie pixie -> {
                    PixieStore store = PixieStore.shared();
                    try {
                        int points = store.info(pixie.key()).pointCount;
                        return new Read(name, () -> store.get(pixie.key()), points, points);
                    } catch (IllegalArgumentException stale) {
                        throw new CannotRead(originLabel(origin)
                            + " has changed on disk since it was loaded. Reload it.");
                    }
                }
                case null -> throw new CannotRead(originLabel(origin) + " has no series " + name + ".");
            }
        }
        throw new CannotRead("Unsupported source: " + source);
    }

    private Read aggregateRead(SourceRef origin, long aggregateId) throws CannotRead {
        AggregateInfo input = aggregates.get(aggregateId);
        if (input == null) {
            throw new CannotRead(originLabel(origin) + ": an input aggregate was deleted.");
        }
        String name = labelResolver.nameFor(input.ref());
        TimeSeriesData values = input.values();
        if (values == null) {
            throw new CannotRead(originLabel(origin) + ": " + name + " is unavailable ("
                + input.unavailableReason() + ").");
        }
        return new Read(name, () -> currentValues(aggregateId), 0,
            input.pixieBacked ? values.getPointCount() : 0);
    }

    /** An aggregate's values as of now, so a plan never pins an older array. */
    private CompletableFuture<TimeSeriesData> currentValues(long aggregateId) {
        AggregateInfo input = aggregates.get(aggregateId);
        TimeSeriesData values = input != null ? input.values() : null;
        return values != null ? CompletableFuture.completedFuture(values)
            : CompletableFuture.failedFuture(new IllegalStateException(
                input == null ? "it was deleted" : input.unavailableReason()));
    }

    /** The Pixie memory check for {@code plans}, or {@code null} if none reads Pixie data. */
    private String pixieRefusal(List<Plan> plans) {
        long newPoints = 0;
        int longestInput = 0;
        for (Plan plan : plans) {
            newPoints += plan.pixieLength();
            longestInput = Math.max(longestInput, plan.longestDecode());
        }
        return newPoints == 0 ? null : fetchCoordinator.pixieAggregateRefusal(newPoints, longestInput);
    }

    /**
     * One creation in progress: reads each origin's inputs one after another, adding each
     * to the running sum on the EDT as it arrives and then dropping it, so at most one
     * decoded Pixie input is held at a time. The first failure abandons the creation.
     */
    private final class Creation {
        private final String name;
        private final List<Plan> plans;
        private final long lastGeneration;
        private final List<TimeSeriesData> sums = new ArrayList<>();
        private int planIndex;

        Creation(String name, List<Plan> plans, long lastGeneration) {
            this.name = name;
            this.plans = plans;
            this.lastGeneration = lastGeneration;
        }

        void next() {
            if (planIndex == plans.size()) {
                finish();
                return;
            }
            Plan plan = plans.get(planIndex);
            new SequentialSum(plan.reads(), originLabel(plan.origin()),
                result -> {
                    sums.add(result);
                    planIndex++;
                    next();
                },
                problem -> fail("aggregate." + name + " was not created: " + problem + ".")
            ).next();
        }

        /** Registers every origin's aggregate, or none of them. */
        private void finish() {
            List<SourceRef> origins = plans.stream().map(Plan::origin).toList();
            if (origins.contains(new LastSource()) && lastRunTracker.getGeneration() != lastGeneration) {
                fail("The last run changed while aggregate." + name + " was being created. Try again.");
                return;
            }
            // Re-checked: another creation may have taken the name while this one read.
            String problem = nameProblem(name, origins);
            if (problem != null) {
                fail(problem);
                return;
            }

            List<TreePath> newPaths = new ArrayList<>();
            Set<SeriesRef> newRefs = new LinkedHashSet<>();
            for (int i = 0; i < plans.size(); i++) {
                Plan plan = plans.get(i);
                AggregateInfo info = new AggregateInfo(nextId++, plan.origin(), name, plan.inputs(),
                    plan.pixieBacked(), sums.get(i));
                newPaths.add(register(info));
                newRefs.add(info.ref());
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
    }

    /**
     * Sums {@code reads} one after another: each input is added on the EDT as it arrives and
     * then dropped. Reports the sum, or the first problem, naming the input.
     */
    private static final class SequentialSum {
        private final List<Read> reads;
        private final String originLabel;
        private final Consumer<TimeSeriesData> done;
        private final Consumer<String> failed;
        private final SeriesSum sum = new SeriesSum();
        private int index;

        SequentialSum(List<Read> reads, String originLabel,
                      Consumer<TimeSeriesData> done, Consumer<String> failed) {
            this.reads = reads;
            this.originLabel = originLabel;
            this.done = done;
            this.failed = failed;
        }

        void next() {
            if (index == reads.size()) {
                done.accept(sum.result());
                return;
            }
            reads.get(index).read().get().whenComplete((data, failure) ->
                SwingUtilities.invokeLater(() -> accept(data, failure)));
        }

        private void accept(TimeSeriesData data, Throwable failure) {
            String where = originLabel + ": " + reads.get(index).name();
            if (failure != null) {
                Throwable cause = failure instanceof CompletionException && failure.getCause() != null
                    ? failure.getCause() : failure;
                failed.accept(where + " could not be read (" + cause.getMessage() + ")");
                return;
            }
            try {
                sum.add(data);
            } catch (IllegalArgumentException e) {
                failed.accept(where + " " + e.getMessage());
                return;
            }
            index++;
            next();
        }
    }

    /**
     * Recomputes every aggregate of the Last alias from the new Last run. Registered as a
     * {@link LastRunTracker} listener: runs whenever Last changes, including to no run.
     */
    void onLastChanged() {
        LastSource last = new LastSource();
        List<AggregateInfo> targets = aggregates.values().stream()
            .filter(a -> a.origin.equals(last)).toList();
        if (targets.isEmpty()) {
            return;
        }
        RunInfoImpl lastRun = lastRunTracker.getLastRunInfo();
        if (lastRun == null) {
            for (AggregateInfo target : targets) {
                apply(target, null, "There is no last run.");
            }
            tabManager.updateAllTabs(false);
            return;
        }
        new Recompute(targets, lastRun, lastRunTracker.getGeneration()).next();
    }

    /**
     * One recompute of Last's aggregates, in creation order so that an input aggregate is
     * always recomputed before those made from it. Superseded, and stopped, by a newer Last.
     */
    private final class Recompute {
        private final List<AggregateInfo> targets;
        private final RunInfoImpl lastRun;
        private final long generation;
        private int index;

        Recompute(List<AggregateInfo> targets, RunInfoImpl lastRun, long generation) {
            this.targets = targets;
            this.lastRun = lastRun;
            this.generation = generation;
        }

        void next() {
            if (index == targets.size()) {
                tabManager.updateAllTabs(false);
                return;
            }
            AggregateInfo target = targets.get(index);
            Plan plan;
            try {
                plan = plan(target.origin, lastRun, target.inputs);
            } catch (CannotRead e) {
                done(target, null, e.getMessage());
                return;
            }
            new SequentialSum(plan.reads(), originLabel(target.origin),
                sum -> done(target, sum, null),
                problem -> done(target, null, problem + ".")
            ).next();
        }

        private void done(AggregateInfo target, TimeSeriesData sum, String problem) {
            if (lastRunTracker.getGeneration() != generation) {
                return; // a newer Last has started its own recompute
            }
            apply(target, sum, problem);
            if (problem != null) {
                status(labelResolver.labelFor(target.ref()) + " could not be recomputed: " + problem);
            }
            index++;
            next();
        }
    }

    /**
     * Sets an aggregate's values in the pool and stats tabs, or clears them there with
     * {@code reason}. The caller redraws the plot tabs once when done.
     */
    private void apply(AggregateInfo target, TimeSeriesData values, String reason) {
        SeriesRef ref = target.ref();
        if (values != null) {
            target.setValues(values);
            plotDataSet.addSeries(ref, values);
            tabManager.updateSeriesInStatsTabsWithAggregation(ref, values);
        } else {
            target.setUnavailable(reason);
            plotDataSet.removeSeries(ref);
            tabManager.addErrorSeriesInStatsTabs(ref, reason);
        }
    }

    /**
     * The source-tree right-click menu for an aggregate, an origin's group or the top-level
     * node, or {@code null}
     * for any other node. Context-specific, modify, then destructive, each in its own block
     * (ADR-0002 §1).
     */
    JPopupMenu contextMenuFor(Object userObject) {
        if (userObject == aggregateSeriesNode.getUserObject()) {
            JPopupMenu menu = new JPopupMenu();
            JMenuItem deleteAll = new JMenuItem("Delete all", MenuIcons.delete());
            deleteAll.setEnabled(!aggregates.isEmpty());
            deleteAll.addActionListener(e -> delete(List.copyOf(aggregates.values()),
                "all " + aggregates.size() + " aggregates"));
            menu.add(deleteAll);
            return menu;
        }
        if (userObject instanceof OriginGroup group) {
            JPopupMenu menu = new JPopupMenu();
            JMenuItem save = new JMenuItem("Save…");
            save.addActionListener(e -> save(aggregatesOf(group.origin), "aggregates " + group));
            menu.add(save);
            menu.addSeparator();
            JMenuItem deleteAll = new JMenuItem("Delete all", MenuIcons.delete());
            deleteAll.addActionListener(e -> {
                List<AggregateInfo> ofGroup = aggregatesOf(group.origin);
                delete(ofGroup, "all " + ofGroup.size() + " aggregates of " + group);
            });
            menu.add(deleteAll);
            return menu;
        }
        if (!(userObject instanceof AggregateInfo info)) {
            return null;
        }
        JPopupMenu menu = new JPopupMenu();
        JMenuItem show = new JMenuItem("Show component series");
        show.addActionListener(e -> showComponents(info));
        menu.add(show);
        JMenuItem save = new JMenuItem("Save…");
        save.addActionListener(e -> save(List.of(info), labelResolver.labelFor(info.ref())));
        menu.add(save);
        menu.addSeparator();
        JMenuItem rename = new JMenuItem("Rename…");
        rename.addActionListener(e -> rename(info));
        menu.add(rename);
        menu.addSeparator();
        JMenuItem delete = new JMenuItem("Delete", MenuIcons.delete());
        delete.addActionListener(e -> delete(List.of(info), labelResolver.labelFor(info.ref())));
        menu.add(delete);
        return menu;
    }

    /** Whether the outputs-tree selection includes an aggregate, so it can be saved. */
    boolean selectionHasAggregates() {
        return selectedLeaves().stream().anyMatch(leaf -> leaf.source instanceof AggregateInfo);
    }

    /**
     * Saves the aggregates selected in the outputs tree to one file. Refuses a selection that
     * also holds run or dataset series, rather than silently leaving them out.
     */
    void saveSelectedAggregates() {
        Set<AggregateInfo> selected = new LinkedHashSet<>();
        for (OutputsTreeBuilder.SeriesLeafNode leaf : selectedLeaves()) {
            if (!(leaf.source instanceof AggregateInfo aggregate)) {
                error("Only aggregates can be saved here: " + labelResolver.labelFor(leaf.ref)
                    + " is not one. Save a run's results from its own menu.");
                return;
            }
            selected.add(aggregate);
        }
        save(List.copyOf(selected), "aggregates");
    }

    /** Every series leaf under the outputs-tree selection, as the tree shows it. */
    private List<OutputsTreeBuilder.SeriesLeafNode> selectedLeaves() {
        return leavesUnder(outputsTree.getSelectionPaths());
    }

    /** Every series leaf ticked in the outputs tree, as the tree shows it. */
    private List<OutputsTreeBuilder.SeriesLeafNode> checkedLeaves() {
        return leavesUnder(outputsTree.getCheckedPaths());
    }

    private static List<OutputsTreeBuilder.SeriesLeafNode> leavesUnder(TreePath[] paths) {
        List<OutputsTreeBuilder.SeriesLeafNode> leaves = new ArrayList<>();
        if (paths != null) {
            for (TreePath path : paths) {
                Enumeration<TreeNode> nodes =
                    ((DefaultMutableTreeNode) path.getLastPathComponent()).preorderEnumeration();
                while (nodes.hasMoreElements()) {
                    if (((DefaultMutableTreeNode) nodes.nextElement()).getUserObject()
                            instanceof OutputsTreeBuilder.SeriesLeafNode leaf) {
                        leaves.add(leaf);
                    }
                }
            }
        }
        return leaves;
    }

    private List<AggregateInfo> aggregatesOf(SourceRef origin) {
        return aggregates.values().stream().filter(a -> a.origin.equals(origin)).toList();
    }

    /**
     * Saves {@code toSave} to one file, CSV, zipped CSV or Pixie, one column per aggregate
     * named by its label, as a run's "Save results" offers. The dialog suggests a name
     * made from {@code suggestion}.
     */
    private void save(List<AggregateInfo> toSave, String suggestion) {
        List<String> unavailable = toSave.stream().filter(a -> a.values() == null)
            .map(a -> labelResolver.labelFor(a.ref())).toList();
        if (!unavailable.isEmpty()) {
            error("Nothing was saved. These aggregates are unavailable:\n  " + String.join("\n  ", unavailable));
            return;
        }
        Optional<File> chosen = KalixFileDialog.saveFile(window)
            .title("Save Aggregates")
            .startIn(window.baseDirectory())
            .suggestedName(suggestion.replaceAll("[^A-Za-z0-9._-]+", "_").replaceAll("^_+|_+$", "") + ".csv")
            .filters(
                FileDialogFilter.of("CSV Files (*.csv)", "csv"),
                FileDialogFilter.of("Zipped CSV (*.csv.zip)", "csv.zip"),
                FileDialogFilter.of("Pixie Files (*.pxt)", "pxt"))
            .show();
        if (chosen.isEmpty()) {
            return;
        }
        DataSet data = new DataSet();
        for (AggregateInfo info : toSave) {
            data.addSeries(info.ref(), info.values());
        }
        try {
            File written = SeriesFileWriter.write(data, chosen.get(), null, labelResolver,
                PreferenceKeys.FLOWVIZ_PRECISION64.get());
            status("Saved " + toSave.size() + (toSave.size() == 1 ? " aggregate" : " aggregates")
                + " to " + written.getName());
        } catch (IOException | IllegalArgumentException e) {
            DialogUtils.showError(window, "Could not save the aggregates: " + e.getMessage(), "Save Aggregates");
        }
    }

    /** Prompts for a new name, valid within the aggregate's origin, and applies it. */
    private void rename(AggregateInfo info) {
        String name = info.name();
        while (true) {
            name = (String) JOptionPane.showInputDialog(window, "Aggregate name:", "Rename Aggregate",
                JOptionPane.PLAIN_MESSAGE, null, null, name);
            if (name == null) {
                return;
            }
            name = name.trim();
            if (name.equals(info.name())) {
                return;
            }
            String problem = nameProblem(name, List.of(info.origin));
            if (problem == null) {
                break;
            }
            DialogUtils.showWarning(window, problem, "Invalid Name");
        }
        String oldName = labelResolver.nameFor(info.ref());
        info.rename(name);
        treeModel.nodeChanged(nodeFor(info));
        window.rebuildOutputsTree();
        tabManager.updateAllTabs(false);
        status("Renamed " + oldName + " to " + labelResolver.nameFor(info.ref()));
    }

    /**
     * Deletes {@code toDelete} ({@code what} names it in the dialog) after confirming, naming
     * any other aggregates made from them: they keep their values, but those of Last fail on
     * their next recompute. Deleting a group's last aggregate removes the group.
     */
    private void delete(List<AggregateInfo> toDelete, String what) {
        if (toDelete.isEmpty()) {
            return;
        }
        Set<AggregateInfo.Input> deleted = new HashSet<>();
        for (AggregateInfo info : toDelete) {
            deleted.add(new AggregateInfo.AggregateInput(info.id));
        }
        List<String> dependents = aggregates.values().stream()
            .filter(a -> !deleted.contains(new AggregateInfo.AggregateInput(a.id))
                && a.inputs.stream().anyMatch(deleted::contains))
            .map(a -> labelResolver.labelFor(a.ref())).toList();
        String message = "Delete " + what + "?";
        if (!dependents.isEmpty()) {
            message += "\n\nThese were made from " + (toDelete.size() == 1 ? "it" : "them")
                + ". They keep their values, but any of Last can no longer be recomputed:\n  "
                + String.join("\n  ", dependents);
        }
        if (!DialogUtils.showConfirmation(window, message,
                toDelete.size() == 1 ? "Delete Aggregate" : "Delete Aggregates")) {
            return;
        }

        for (AggregateInfo info : toDelete) {
            DefaultMutableTreeNode group = (DefaultMutableTreeNode) nodeFor(info).getParent();
            window.removeSourceNode(group, info);
            window.purgeSeries(List.of(info.ref()), new AggregateSource(info.id));
            aggregates.remove(info.id);
            if (group.getChildCount() == 0) {
                window.removeSourceNode(aggregateSeriesNode, group.getUserObject());
                removedOriginLabels.remove(info.origin);
            }
        }
        status("Deleted " + what);
    }

    /** The source-tree tooltip for an aggregate: what it sums, and why it is unavailable. */
    String recipe(AggregateInfo info) {
        List<String> names = componentNames(info);
        int shown = Math.min(names.size(), RECIPE_LINES);
        StringBuilder html = new StringBuilder("<html>Sum of:");
        for (String name : names.subList(0, shown)) {
            html.append("<br>&nbsp;&nbsp;").append(escapeHtml(name));
        }
        if (names.size() > shown) {
            html.append("<br>&nbsp;&nbsp;… and ").append(names.size() - shown).append(" more");
        }
        if (info.values() == null) {
            html.append("<br><br>Unavailable: ").append(escapeHtml(info.unavailableReason()));
        }
        return html.append("</html>").toString();
    }

    /** Opens the aggregate's inputs, one per line, in a text window they can be copied from. */
    private void showComponents(AggregateInfo info) {
        MinimalEditorWindow editor = new MinimalEditorWindow(String.join("\n", componentNames(info)) + "\n");
        editor.setTitle(labelResolver.labelFor(info.ref()));
        editor.setVisible(true);
    }

    /** An aggregate's inputs by their current names. */
    private List<String> componentNames(AggregateInfo info) {
        return info.inputs.stream().map(input -> switch (input) {
            case AggregateInfo.SeriesInput series -> series.name();
            case AggregateInfo.AggregateInput aggregate -> {
                AggregateInfo source = aggregates.get(aggregate.aggregateId());
                yield source != null ? labelResolver.nameFor(source.ref()) : "(deleted aggregate)";
            }
        }).toList();
    }

    private static String escapeHtml(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /** The source-tree node holding {@code info}. */
    private DefaultMutableTreeNode nodeFor(AggregateInfo info) {
        DefaultMutableTreeNode group = groupNodeFor(info.origin);
        for (int i = 0; i < group.getChildCount(); i++) {
            DefaultMutableTreeNode child = (DefaultMutableTreeNode) group.getChildAt(i);
            if (child.getUserObject() == info) {
                return child;
            }
        }
        throw new IllegalStateException("No tree node for " + info);
    }

    /**
     * Records {@code origin}'s last display name as it is removed. Its aggregates stay,
     * labelled {@code "<lastLabel> (removed)"}.
     */
    void onOriginRemoved(SourceRef origin, String lastLabel) {
        if (hasAggregatesOf(origin)) {
            removedOriginLabels.put(origin, lastLabel);
            refreshOriginLabels();
        }
    }

    /** Forgets a removal when the same dataset is loaded again: its aggregates are live again. */
    void onOriginLoaded(SourceRef origin) {
        if (removedOriginLabels.remove(origin) != null && hasAggregatesOf(origin)) {
            refreshOriginLabels();
        }
    }

    /** Redraws every label that names an origin, after one is renamed or removed. */
    void refreshOriginLabels() {
        for (int i = 0; i < aggregateSeriesNode.getChildCount(); i++) {
            treeModel.nodeChanged(aggregateSeriesNode.getChildAt(i));
        }
        outputsTree.repaint();
        tabManager.updateAllTabs(false);
    }

    private boolean hasAggregatesOf(SourceRef origin) {
        return aggregates.values().stream().anyMatch(a -> a.origin.equals(origin));
    }

    /**
     * Why a dataset with these series names must not load, or {@code null}: no column may
     * share an aggregate's full name, or the outputs tree would merge the two.
     */
    String datasetNameClash(List<String> seriesNames) {
        Map<String, AggregateInfo> byFullName = new HashMap<>();
        for (AggregateInfo info : aggregates.values()) {
            byFullName.putIfAbsent(AggregateSeries.NAME_PREFIX + info.name(), info);
        }
        for (String name : seriesNames) {
            AggregateInfo info = name.startsWith(AggregateSeries.NAME_PREFIX) ? byFullName.get(name) : null;
            if (info != null) {
                return "This dataset has a column named \"" + name + "\", the same as an aggregate of "
                    + originLabel(info.origin) + ".\n\nRename or delete the aggregate, or rename the"
                    + " column, then load the dataset again.";
            }
        }
        return null;
    }

    /** Point counts of the aggregates made from Pixie data, for the Pixie memory budget. */
    Map<SeriesRef, Integer> pixieBackedPoints() {
        Map<SeriesRef, Integer> points = new HashMap<>();
        for (AggregateInfo info : aggregates.values()) {
            if (info.pixieBacked && info.values() != null) {
                points.put(info.ref(), info.values().getPointCount());
            }
        }
        return points;
    }

    /** The label lookup for {@link DefaultLabelResolver}; {@code null} for an unknown id. */
    AggregateLabel labelFor(long id) {
        AggregateInfo info = aggregates.get(id);
        return info == null ? null
            : new AggregateLabel(info.name(), info.origin, removedOriginLabels.get(info.origin));
    }

    /**
     * Registers an aggregate and adds it to the source tree under its origin's group,
     * creating the group if this is the origin's first aggregate. Returns its tree path.
     */
    private TreePath register(AggregateInfo info) {
        aggregates.put(info.id, info);

        DefaultMutableTreeNode group = groupNodeFor(info.origin);
        DefaultMutableTreeNode node = new DefaultMutableTreeNode(info);
        group.add(node);
        treeModel.nodesWereInserted(group, new int[]{group.getChildCount() - 1});
        return new TreePath(node.getPath());
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
        DialogUtils.showWarning(window, message, TITLE);
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
