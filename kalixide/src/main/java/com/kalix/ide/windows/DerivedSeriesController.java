package com.kalix.ide.windows;

import com.kalix.ide.cli.SessionManager;
import com.kalix.ide.utils.StatusReporter;
import com.kalix.ide.components.JCheckboxTree;
import com.kalix.ide.flowviz.VisualizationTabManager;
import com.kalix.ide.flowviz.data.DerivedSeriesLabel;
import com.kalix.ide.flowviz.data.DerivedSeries;
import com.kalix.ide.flowviz.data.DerivedSeriesSource;
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
import com.kalix.ide.linter.ui.HoverTipSupplier;
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
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
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
import java.util.stream.Collectors;

/**
 * The Run Manager's user-created derived series: the sum of chosen series from one source,
 * shown in the data source tree (Derived series &gt; origin &gt; name) and as
 * {@code derived.<name>} in the outputs tree.
 *
 * <p>Owns each derived series' values, independently of the source they were summed from,
 * so a derived series outlives the removal of that source. Derived series of the "Last" alias
 * are recomputed when Last changes; all others are fixed at creation. EDT-only.</p>
 */
class DerivedSeriesController {

    private static final String TITLE = "New derived series";
    // Inputs listed in the recipe tooltip before "… and N more".
    private static final int RECIPE_LINES = 20;

    private final RunManager window;
    private final JCheckboxTree sourceTree;
    private final JCheckboxTree outputsTree;
    private final DefaultTreeModel treeModel;
    private final DefaultMutableTreeNode derivedSeriesNode;
    private final VisualizationTabManager tabManager;
    private final DataSet plotDataSet;
    private final TimeSeriesRequestManager timeSeriesRequestManager;
    private final DefaultLabelResolver labelResolver;
    private final Map<DatasetSeries, DatasetSeriesSource> datasetSeriesSources;
    private final LastRunTracker lastRunTracker;
    private final SeriesFetchCoordinator fetchCoordinator;
    private final StatusReporter statusUpdater;

    private final DerivedSeriesStore derivedSeries = new DerivedSeriesStore();
    // The Last generation whose derived series are fully recomputed.
    private long recomputedGeneration;

    DerivedSeriesController(
        RunManager window,
        JCheckboxTree sourceTree,
        JCheckboxTree outputsTree,
        DefaultTreeModel treeModel,
        DefaultMutableTreeNode derivedSeriesNode,
        VisualizationTabManager tabManager,
        DataSet plotDataSet,
        TimeSeriesRequestManager timeSeriesRequestManager,
        DefaultLabelResolver labelResolver,
        Map<DatasetSeries, DatasetSeriesSource> datasetSeriesSources,
        LastRunTracker lastRunTracker,
        SeriesFetchCoordinator fetchCoordinator,
        StatusReporter statusUpdater
    ) {
        this.window = window;
        this.sourceTree = sourceTree;
        this.outputsTree = outputsTree;
        this.treeModel = treeModel;
        this.derivedSeriesNode = derivedSeriesNode;
        this.tabManager = tabManager;
        this.plotDataSet = plotDataSet;
        this.timeSeriesRequestManager = timeSeriesRequestManager;
        this.labelResolver = labelResolver;
        this.datasetSeriesSources = datasetSeriesSources;
        this.lastRunTracker = lastRunTracker;
        this.fetchCoordinator = fetchCoordinator;
        this.statusUpdater = statusUpdater;
        this.recomputedGeneration = lastRunTracker.getGeneration();
    }

    /** "Sum selected…": sums the outputs-tree selection. */
    void createFromSelected() {
        create(selectedLeaves(), "Select the series to sum in the Timeseries tree.");
    }

    /** "Sum checked…": sums the series ticked in the outputs tree. */
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
     * Creates one derived series per origin covered by {@code leaves}, each the sum of that
     * origin's series among them.
     *
     * <p>A node contributes every series leaf under it, as the tree currently shows it (so
     * the filter narrows what an in-between node sums). A derived series counts as a series of
     * its own origin. Every origin must contribute the same series. All-or-nothing: if any
     * input fails, nothing is created.</p>
     */
    private void create(List<OutputsTreeBuilder.SeriesLeafNode> leaves, String noneMessage) {
        Map<SourceRef, OriginInputs> selection = inputsByOrigin(leaves, noneMessage);
        if (selection == null) {
            return;
        }

        List<SourceRef> origins = List.copyOf(selection.keySet());
        // Taken before the prompt: the name dialog is modal but the EDT keeps running,
        // so a run can complete while it is open. The generation lets finish() see that.
        long lastGeneration = lastRunTracker.getGeneration();
        String name = promptForName(origins);
        if (name == null) {
            return;
        }

        List<Plan> plans = new ArrayList<>();
        try {
            for (Map.Entry<SourceRef, OriginInputs> entry : selection.entrySet()) {
                OriginInputs selected = entry.getValue();
                // Summed in name order, not click order: floating-point addition is not
                // associative, so the same inputs then give the same bits in every source.
                List<DerivedSeriesInfo.Input> inputs = selected.inputs.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey()).map(Map.Entry::getValue).toList();
                plans.add(plan(entry.getKey(), selected.source, inputs));
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
        // Last's derived series hold the previous run's values until their recompute ends.
        if (recomputedGeneration != lastRunTracker.getGeneration() && plans.stream().anyMatch(p ->
                p.origin() instanceof LastSource
                    && p.inputs().stream().anyMatch(i -> i instanceof DerivedSeriesInfo.DerivedSeriesInput))) {
            error("The derived series of the last run are still being recomputed. Try again when they are done.");
            return;
        }

        status("Creating derived." + name + "…");
        new Creation(name, plans, lastGeneration).next();
    }

    /** One origin's selected inputs, keyed by display name, and where its series are read. */
    private static final class OriginInputs {
        /** The run or dataset tree object; {@code null} if every input is a derived series. */
        Object source;
        final Map<String, DerivedSeriesInfo.Input> inputs = new LinkedHashMap<>();
    }

    /**
     * {@code leaves} grouped by origin, or {@code null} (after telling the user why) if they
     * can't make a derived series.
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
            error("Each source must include every selected series. Missing from:\n  "
                + String.join("\n  ", missing));
            return null;
        }
        return selection;
    }

    private void addLeaf(Map<SourceRef, OriginInputs> selection, Map<Object, SourceRef> originOfSource,
                         OutputsTreeBuilder.SeriesLeafNode leaf) {
        if (leaf.source instanceof DerivedSeriesInfo derived) {
            selection.computeIfAbsent(derived.origin, o -> new OriginInputs()).inputs
                .putIfAbsent(leaf.seriesName, new DerivedSeriesInfo.DerivedSeriesInput(derived.id));
        } else {
            SourceRef originRef = originOfSource.computeIfAbsent(leaf.source, window::sourceRefForNode);
            OriginInputs origin = selection.computeIfAbsent(originRef, o -> new OriginInputs());
            origin.source = leaf.source;
            origin.inputs.putIfAbsent(leaf.seriesName, new DerivedSeriesInfo.SeriesInput(leaf.seriesName));
        }
    }

    /** Prompts until the name is valid for every origin; {@code null} if cancelled. */
    private String promptForName(List<SourceRef> origins) {
        String name = suggestName(origins);
        while (true) {
            name = (String) JOptionPane.showInputDialog(window, "Derived series name:", TITLE,
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
        return derivedSeries.suggestName(origins);
    }

    /** Why {@code name} can't be used for a derived series of each origin, or {@code null}. */
    private String nameProblem(String name, List<SourceRef> origins) {
        DerivedSeriesStore.NameProblem problem = derivedSeries.nameProblem(name, origins);
        if (problem == null) {
            return null;
        }
        return problem.taken() == null ? problem.message() : originLabel(problem.taken().origin) + problem.message();
    }

    /** One origin's inputs, ready to be read one at a time. */
    private record Plan(SourceRef origin, List<DerivedSeriesInfo.Input> inputs, List<Read> reads) {
        /** Made from Pixie data, directly or through an input derived series. */
        boolean pixieBacked() {
            return pixieLength() > 0;
        }

        /** The derived series' point count if Pixie-backed, else 0. */
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
                        int decodePoints, long pixieBackedLength, Runnable afterUse) {
        Read(String name, Supplier<CompletableFuture<TimeSeriesData>> read,
             int decodePoints, long pixieBackedLength) {
            this(name, read, decodePoints, pixieBackedLength, () -> { });
        }
    }

    /** Why an input can't be read; the message is ready to show. */
    private static final class CannotRead extends Exception {
        CannotRead(String message) {
            super(message);
        }
    }

    /**
     * Plans the reads for one origin's inputs, from {@code source} (a run or dataset tree
     * object, or {@code null} if every input is a derived series). Run series are requested,
     * Pixie series decoded, and input derived series looked up, only when their turn comes.
     */
    private Plan plan(SourceRef origin, Object source, List<DerivedSeriesInfo.Input> inputs) throws CannotRead {
        List<Read> reads = new ArrayList<>();
        for (DerivedSeriesInfo.Input input : inputs) {
            reads.add(switch (input) {
                case DerivedSeriesInfo.SeriesInput series -> seriesRead(origin, source, series.name());
                case DerivedSeriesInfo.DerivedSeriesInput derived -> derivedRead(origin, derived.derivedId());
            });
        }
        return new Plan(origin, inputs, reads);
    }

    private Read seriesRead(SourceRef origin, Object source, String name) throws CannotRead {
        if (source instanceof RunInfoImpl run) {
            // A Last alias is replaced when a run completes, and the leaf may hold the old
            // one, so Last is resolved through the tracker, as the fetch coordinator does.
            RunInfoImpl current = run.isLastAlias() ? lastRunTracker.getLastRunInfo() : run;
            SessionManager.KalixSession session = current == null ? null : current.getSession();
            if (session == null) {
                throw new CannotRead(originLabel(origin) + " has no session to read from.");
            }
            String sessionKey = session.getSessionKey();
            // A run series fetched for the sum alone is dropped from the request cache once
            // added, or a total of hundreds of nodes would hold hundreds of series the user
            // never ticked for the life of the run. One already cached stays cached.
            boolean cached = timeSeriesRequestManager.getTimeSeriesFromCache(sessionKey, name) != null;
            return new Read(name, () -> timeSeriesRequestManager.requestTimeSeries(sessionKey, name), 0, 0,
                cached ? () -> { } : () -> timeSeriesRequestManager.forgetCompleted(sessionKey, name));
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

    private Read derivedRead(SourceRef origin, long derivedId) throws CannotRead {
        DerivedSeriesInfo input = derivedSeries.get(derivedId);
        if (input == null) {
            throw new CannotRead(originLabel(origin) + ": an input derived series was deleted.");
        }
        String name = labelResolver.nameFor(input.ref());
        TimeSeriesData values = input.values();
        if (values == null) {
            throw new CannotRead(originLabel(origin) + ": " + name + " is unavailable ("
                + input.unavailableReason() + ").");
        }
        return new Read(name, () -> currentValues(derivedId), 0,
            input.pixieBacked ? values.getPointCount() : 0);
    }

    /** A derived series' values as of now, so a plan never pins an older array. */
    private CompletableFuture<TimeSeriesData> currentValues(long derivedId) {
        DerivedSeriesInfo input = derivedSeries.get(derivedId);
        TimeSeriesData values = input != null ? input.values() : null;
        return values != null ? CompletableFuture.completedFuture(values)
            : CompletableFuture.failedFuture(new IllegalStateException(
                input == null ? "it was deleted" : input.unavailableReason()));
    }

    /** The Pixie memory check for {@code plans}, or {@code null} if none reads Pixie data. */
    private String pixieRefusal(List<Plan> plans) {
        long newPoints = pixiePoints(plans);
        int longestInput = 0;
        for (Plan plan : plans) {
            longestInput = Math.max(longestInput, plan.longestDecode());
        }
        return newPoints == 0 ? null : fetchCoordinator.pixieDerivedRefusal(newPoints, longestInput);
    }

    private static long pixiePoints(List<Plan> plans) {
        return plans.stream().mapToLong(Plan::pixieLength).sum();
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
            fetchCoordinator.reserveDerivedPixiePoints(pixiePoints(plans));
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
                problem -> end("derived." + name + " was not created: " + problem + ".")
            ).next();
        }

        /** Registers every origin's derived series, or none of them. */
        private void finish() {
            List<SourceRef> origins = plans.stream().map(Plan::origin).toList();
            if (origins.contains(new LastSource()) && lastRunTracker.getGeneration() != lastGeneration) {
                end("The last run changed while derived." + name + " was being created. Try again.");
                return;
            }
            // An origin removed while this one read would never be labelled removed.
            for (SourceRef origin : origins) {
                if (!(origin instanceof LastSource) && !window.hasSourceNode(origin)) {
                    end("A source was removed while derived." + name + " was being created.");
                    return;
                }
            }
            // Re-checked: another creation may have taken the name while this one read.
            String problem = nameProblem(name, origins);
            if (problem != null) {
                end(problem);
                return;
            }

            List<TreePath> newPaths = new ArrayList<>();
            Set<SeriesRef> newRefs = new LinkedHashSet<>();
            for (int i = 0; i < plans.size(); i++) {
                Plan plan = plans.get(i);
                DerivedSeriesInfo info = new DerivedSeriesInfo(derivedSeries.nextId(), plan.origin(), name, plan.inputs(),
                    plan.pixieBacked(), sums.get(i));
                newPaths.add(register(info));
                newRefs.add(info.ref());
            }

            // Show and plot them: check the new sources, then their series.
            for (TreePath path : newPaths) {
                sourceTree.expandPath(path.getParentPath());
            }
            sourceTree.addCheckedPaths(newPaths);
            fetchCoordinator.releaseDerivedPixiePoints(pixiePoints(plans));
            boolean filterCleared = window.checkOutputsSeries(newRefs);
            status("Created derived." + name + " for " + origins.size()
                + (origins.size() == 1 ? " source" : " sources")
                + (filterCleared ? "; the filter was cleared to show it" : ""));
        }

        /** Ends the creation without a derived series: the reserved budget goes back. */
        private void end(String message) {
            fetchCoordinator.releaseDerivedPixiePoints(pixiePoints(plans));
            fail(message);
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
                String reason = cause.getMessage() != null ? cause.getMessage() : cause.getClass().getSimpleName();
                failed.accept(where + " could not be read (" + reason + ")");
                return;
            }
            try {
                sum.add(data);
            } catch (IllegalArgumentException e) {
                failed.accept(where + " " + e.getMessage());
                return;
            } finally {
                reads.get(index).afterUse().run();
            }
            index++;
            next();
        }
    }

    /**
     * Recomputes every derived series of the Last alias from the new Last run. Registered as a
     * {@link LastRunTracker} listener: runs whenever Last changes, including to no run.
     */
    void onLastChanged() {
        LastSource last = new LastSource();
        List<DerivedSeriesInfo> targets = derivedSeries.of(last);
        if (targets.isEmpty()) {
            recomputedGeneration = lastRunTracker.getGeneration();
            return;
        }
        RunInfoImpl lastRun = lastRunTracker.getLastRunInfo();
        if (lastRun == null) {
            for (DerivedSeriesInfo target : targets) {
                apply(target, null, "There is no last run.");
            }
            recomputedGeneration = lastRunTracker.getGeneration();
            tabManager.updateAllTabs(false);
            return;
        }
        new Recompute(targets, lastRun, lastRunTracker.getGeneration()).next();
    }

    /**
     * One recompute of Last's derived series, in creation order so that an input derived series is
     * always recomputed before those made from it. Superseded, and stopped, by a newer Last.
     */
    private final class Recompute {
        private final List<DerivedSeriesInfo> targets;
        private final RunInfoImpl lastRun;
        private final long generation;
        private int index;

        Recompute(List<DerivedSeriesInfo> targets, RunInfoImpl lastRun, long generation) {
            this.targets = targets;
            this.lastRun = lastRun;
            this.generation = generation;
        }

        void next() {
            if (index == targets.size()) {
                recomputedGeneration = generation;
                tabManager.updateAllTabs(false);
                return;
            }
            DerivedSeriesInfo target = targets.get(index);
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

        private void done(DerivedSeriesInfo target, TimeSeriesData sum, String problem) {
            if (lastRunTracker.getGeneration() != generation) {
                return; // a newer Last has started its own recompute
            }
            // Deleted meanwhile: publishing would put an orphan back in the pool
            if (derivedSeries.get(target.id) == target) {
                apply(target, sum, problem);
                if (problem != null) {
                    status(labelResolver.labelFor(target.ref()) + " could not be recomputed: " + problem);
                }
            }
            index++;
            next();
        }
    }

    /**
     * Sets a derived series' values in the pool and stats tabs, or clears them there with
     * {@code reason}. The caller redraws the plot tabs once when done.
     */
    private void apply(DerivedSeriesInfo target, TimeSeriesData values, String reason) {
        SeriesRef ref = target.ref();
        if (values != null) {
            target.setValues(values);
            window.publishSeries(ref, values);
        } else {
            target.setUnavailable(reason);
            plotDataSet.removeSeries(ref);
            tabManager.addErrorSeriesInStatsTabs(ref, reason);
        }
    }

    /**
     * The source-tree right-click menu for a derived series, an origin's group or the top-level
     * node, or {@code null}
     * for any other node. Context-specific, modify, then destructive, each in its own block
     * (ADR-0002 §1).
     */
    JPopupMenu contextMenuFor(Object userObject) {
        if (userObject == derivedSeriesNode.getUserObject()) {
            JPopupMenu menu = new JPopupMenu();
            JMenuItem deleteAll = new JMenuItem("Delete all", MenuIcons.delete());
            deleteAll.setEnabled(!derivedSeries.isEmpty());
            deleteAll.addActionListener(e -> delete(List.copyOf(derivedSeries.all()),
                "all " + derivedSeries.size() + " derived series"));
            menu.add(deleteAll);
            return menu;
        }
        if (userObject instanceof OriginGroup group) {
            JPopupMenu menu = new JPopupMenu();
            JMenuItem save = new JMenuItem("Save…");
            save.addActionListener(e -> save(derivedSeriesOf(group.origin), "derived series " + group));
            menu.add(save);
            menu.addSeparator();
            JMenuItem deleteAll = new JMenuItem("Delete all", MenuIcons.delete());
            deleteAll.addActionListener(e -> {
                List<DerivedSeriesInfo> ofGroup = derivedSeriesOf(group.origin);
                delete(ofGroup, "all " + ofGroup.size() + " derived series of " + group);
            });
            menu.add(deleteAll);
            return menu;
        }
        if (!(userObject instanceof DerivedSeriesInfo info)) {
            return null;
        }
        JPopupMenu menu = new JPopupMenu();
        JMenuItem show = new JMenuItem("Copy inputs");
        show.addActionListener(e -> copyInputs(info));
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

    private List<DerivedSeriesInfo> derivedSeriesOf(SourceRef origin) {
        return derivedSeries.of(origin);
    }

    /**
     * Saves {@code toSave} to one file, CSV, zipped CSV or Pixie, one column per derived series
     * named by its label, as a run's "Save results" offers. The dialog suggests a name
     * made from {@code suggestion}.
     */
    private void save(List<DerivedSeriesInfo> toSave, String suggestion) {
        List<String> unavailable = toSave.stream().filter(a -> a.values() == null)
            .map(a -> labelResolver.labelFor(a.ref())).toList();
        if (!unavailable.isEmpty()) {
            DialogUtils.showWarning(window, "Nothing was saved. These derived series are unavailable:\n  "
                + String.join("\n  ", unavailable), "Save derived series");
            return;
        }
        Optional<File> chosen = KalixFileDialog.saveFile(window)
            .title("Save derived series")
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
        for (DerivedSeriesInfo info : toSave) {
            data.addSeries(info.ref(), info.values());
        }
        try {
            File written = SeriesFileWriter.write(data, chosen.get(), null, labelResolver,
                PreferenceKeys.FLOWVIZ_PRECISION64.get());
            status("Saved " + toSave.size() + (toSave.size() == 1 ? " derived series" : " derived series")
                + " to " + written.getName());
        } catch (IOException | IllegalArgumentException e) {
            DialogUtils.showError(window, "Could not save the derived series: " + e.getMessage(), "Save derived series");
        }
    }

    /** Prompts for a new name, valid within the derived series' origin, and applies it. */
    private void rename(DerivedSeriesInfo info) {
        String name = info.name();
        while (true) {
            name = (String) JOptionPane.showInputDialog(window, "Derived series name:", "Rename Derived series",
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
     * any other derived series made from them: they keep their values, but those of Last fail on
     * their next recompute. Deleting a group's last derived series removes the group.
     */
    private void delete(List<DerivedSeriesInfo> toDelete, String what) {
        if (toDelete.isEmpty()) {
            return;
        }
        List<String> dependents = derivedSeries.dependentsOf(toDelete).stream()
            .map(a -> labelResolver.labelFor(a.ref())).toList();
        String message = "Delete " + what + "?";
        if (!dependents.isEmpty()) {
            message += "\n\nThese were made from " + (toDelete.size() == 1 ? "it" : "them")
                + ". They keep their values, but any of Last can no longer be recomputed:\n  "
                + String.join("\n  ", dependents);
        }
        if (!DialogUtils.showConfirmation(window, message, "Delete Derived Series")) {
            return;
        }
        deleteNow(toDelete);
        status("Deleted " + what);
    }

    /** Deletes {@code toDelete} without asking. */
    private void deleteNow(List<DerivedSeriesInfo> toDelete) {
        // One source-tree change: one outputs rebuild and one undo step for the lot
        window.changeSourceTree(() -> {
            for (DerivedSeriesInfo info : toDelete) {
                DefaultMutableTreeNode group = (DefaultMutableTreeNode) nodeFor(info).getParent();
                window.removeSourceNode(group, info);
                window.purgeSeries(List.of(info.ref()), new DerivedSeriesSource(info.id));
                derivedSeries.remove(info.id);
                if (group.getChildCount() == 0) {
                    window.removeSourceNode(derivedSeriesNode, group.getUserObject());
                }
            }
        });
    }

    /** The source-tree tooltip for a derived series: what it sums, and why it is unavailable. */
    String recipe(DerivedSeriesInfo info) {
        List<String> names = inputNames(info);
        int shown = Math.min(names.size(), RECIPE_LINES);
        StringBuilder html = new StringBuilder("<html>Sum of:");
        for (String name : names.subList(0, shown)) {
            html.append("<br>&nbsp;&nbsp;").append(HoverTipSupplier.escapeHtml(name));
        }
        if (names.size() > shown) {
            html.append("<br>&nbsp;&nbsp;… and ").append(names.size() - shown).append(" more");
        }
        if (info.values() == null) {
            html.append("<br><br>Unavailable: ").append(HoverTipSupplier.escapeHtml(info.unavailableReason()));
        }
        return html.append("</html>").toString();
    }

    /** Opens the derived series' inputs, one per line, in a text window they can be copied from. */
    /** Puts the input names on the clipboard, one per line; the hover tooltip is the view. */
    private void copyInputs(DerivedSeriesInfo info) {
        List<String> names = inputNames(info);
        Toolkit.getDefaultToolkit().getSystemClipboard()
            .setContents(new StringSelection(String.join("\n", names) + "\n"), null);
        status("Copied " + names.size() + (names.size() == 1 ? " input" : " inputs") + " of "
            + labelResolver.nameFor(info.ref()));
    }

    /** A derived series' inputs by their current names. */
    /** The names of {@code info}'s inputs; an input that is itself a derived series by its name. */
    private List<String> inputNames(DerivedSeriesInfo info) {
        return info.inputs.stream().map(input -> switch (input) {
            case DerivedSeriesInfo.SeriesInput series -> series.name();
            case DerivedSeriesInfo.DerivedSeriesInput derived -> {
                DerivedSeriesInfo source = derivedSeries.get(derived.derivedId());
                yield source != null ? labelResolver.nameFor(source.ref()) : "(deleted derived series)";
            }
        }).toList();
    }

    /** The source-tree node holding {@code info}. */
    private DefaultMutableTreeNode nodeFor(DerivedSeriesInfo info) {
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
     * Deletes the derived series of {@code origin}, which has just been removed: a derived
     * series does not outlive its source, as the source's other series do not. Called once
     * the source tree has finished changing. {@code label} is the source's last display
     * name, for the status line.
     */
    void onOriginRemoved(SourceRef origin, String label) {
        List<DerivedSeriesInfo> gone = derivedSeriesOf(origin);
        if (gone.isEmpty()) {
            return;
        }
        deleteNow(gone);
        status("Deleted the derived series of " + label + ": "
            + gone.stream().map(DerivedSeriesInfo::name).collect(Collectors.joining(", ")));
    }

    /**
     * Relabels {@code origin}'s group after a run rename; the rename itself already
     * rebuilt the outputs tree and redrew the tabs.
     */
    void onOriginRenamed(SourceRef origin) {
        if (hasDerivedSeriesOf(origin)) {
            refreshGroupNodes();
        }
    }

    private void refreshGroupNodes() {
        for (int i = 0; i < derivedSeriesNode.getChildCount(); i++) {
            treeModel.nodeChanged(derivedSeriesNode.getChildAt(i));
        }
    }

    private boolean hasDerivedSeriesOf(SourceRef origin) {
        return derivedSeries.hasAnyOf(origin);
    }

    /** Point counts of the derived series made from Pixie data, for the Pixie memory budget. */
    Map<SeriesRef, Integer> pixieBackedPoints() {
        return derivedSeries.pixieBackedPoints();
    }

    /** The label lookup for {@link DefaultLabelResolver}; {@code null} for an unknown id. */
    DerivedSeriesLabel labelFor(long id) {
        DerivedSeriesInfo info = derivedSeries.get(id);
        return info == null ? null : new DerivedSeriesLabel(info.name(), info.origin);
    }

    /**
     * Registers a derived series and adds it to the source tree under its origin's group,
     * creating the group if this is the origin's first derived series. Returns its tree path.
     */
    private TreePath register(DerivedSeriesInfo info) {
        derivedSeries.add(info);

        DefaultMutableTreeNode group = groupNodeFor(info.origin);
        DefaultMutableTreeNode node = new DefaultMutableTreeNode(info);
        group.add(node);
        treeModel.nodesWereInserted(group, new int[]{group.getChildCount() - 1});
        return new TreePath(node.getPath());
    }

    /** The group node for {@code origin}, created and inserted if absent. */
    private DefaultMutableTreeNode groupNodeFor(SourceRef origin) {
        for (int i = 0; i < derivedSeriesNode.getChildCount(); i++) {
            DefaultMutableTreeNode child = (DefaultMutableTreeNode) derivedSeriesNode.getChildAt(i);
            if (child.getUserObject() instanceof OriginGroup g && g.origin.equals(origin)) {
                return child;
            }
        }
        DefaultMutableTreeNode group = new DefaultMutableTreeNode(new OriginGroup(origin));
        derivedSeriesNode.add(group);
        treeModel.nodesWereInserted(derivedSeriesNode,
            new int[]{derivedSeriesNode.getChildCount() - 1});
        return group;
    }

    private String originLabel(SourceRef origin) {
        return labelResolver.originLabel(origin);
    }

    private void error(String message) {
        DialogUtils.showWarning(window, message, TITLE);
    }

    private void fail(String message) {
        status("Derived series not created");
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
