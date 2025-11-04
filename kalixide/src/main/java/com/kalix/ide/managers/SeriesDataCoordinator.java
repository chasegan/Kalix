package com.kalix.ide.managers;

import com.kalix.ide.cli.SessionManager;
import com.kalix.ide.flowviz.data.TimeSeriesData;
import com.kalix.ide.flowviz.data.DataSet;
import com.kalix.ide.flowviz.PlotPanel;
import com.kalix.ide.windows.VisualizationTabManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import javax.swing.event.TreeSelectionEvent;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.TreePath;
import java.awt.*;
import java.time.LocalDateTime;
import java.util.*;
import java.util.List;
import java.util.function.Function;

/**
 * Coordinates series data management, selection, and plotting for RunManager.
 *
 * Responsibilities:
 * - Managing series selection state
 * - Fetching timeseries data from sessions or dataset cache
 * - Color assignment and management
 * - Adding/removing series to/from plots
 * - Coordinating with TimeSeriesRequestManager for async data fetching
 * - Updating visualization tabs with new data
 *
 * Usage:
 * 1. Create coordinator with required dependencies
 * 2. Set selection change listener on outputs tree
 * 3. Coordinator handles all series data operations
 */
public class SeriesDataCoordinator {

    private static final Logger logger = LoggerFactory.getLogger(SeriesDataCoordinator.class);

    // Dependencies
    private final JTree timeseriesTree;
    private final TimeSeriesRequestManager timeSeriesRequestManager;
    private final VisualizationTabManager tabManager;
    private final DataSet plotDataSet;
    private final Map<String, TimeSeriesData> datasetSeriesCache;
    private final OutputsTreeBuilder outputsTreeBuilder;

    // State
    private final Map<String, Color> seriesColorMap = new HashMap<>();
    private Set<String> selectedSeries = new HashSet<>();
    private boolean isUpdatingSelection = false;

    // Color palette - Categorical 10 colors optimized for visibility
    private static final Color[] SERIES_COLORS = {
        new Color(0x1f77b4),  // Blue
        new Color(0xff7f0e),  // Orange
        new Color(0x2ca02c),  // Green
        new Color(0xd62728),  // Red
        new Color(0x9467bd),  // Purple
        new Color(0x8c564b),  // Brown
        new Color(0xe377c2),  // Pink
        new Color(0x7f7f7f),  // Gray
        new Color(0xbcbd22),  // Yellow-green
        new Color(0x17becf)   // Cyan
    };

    // Callbacks to RunManager
    private final Function<Object, SessionManager.KalixSession> resolveSessionCallback;
    private final Runnable clearPlotAndStatsCallback;
    private final Runnable updatePlotVisibilityCallback;

    /**
     * Interface for accessing run information.
     */
    public interface RunInfo {
        String getRunName();
        SessionManager.KalixSession getSession();
    }

    /**
     * Creates a new SeriesDataCoordinator.
     *
     * @param timeseriesTree The timeseries tree (for selection events)
     * @param timeSeriesRequestManager Manager for async data fetching
     * @param tabManager Visualization tab manager
     * @param plotDataSet The plot dataset
     * @param datasetSeriesCache Cache of loaded dataset series
     * @param outputsTreeBuilder Tree builder for leaf collection
     * @param resolveSessionCallback Callback to resolve RunInfo session (handles "Last")
     * @param clearPlotAndStatsCallback Callback to clear plots and stats
     * @param updatePlotVisibilityCallback Callback to update plot visibility
     */
    public SeriesDataCoordinator(
            JTree timeseriesTree,
            TimeSeriesRequestManager timeSeriesRequestManager,
            VisualizationTabManager tabManager,
            DataSet plotDataSet,
            Map<String, TimeSeriesData> datasetSeriesCache,
            OutputsTreeBuilder outputsTreeBuilder,
            Function<Object, SessionManager.KalixSession> resolveSessionCallback,
            Runnable clearPlotAndStatsCallback,
            Runnable updatePlotVisibilityCallback) {
        this.timeseriesTree = timeseriesTree;
        this.timeSeriesRequestManager = timeSeriesRequestManager;
        this.tabManager = tabManager;
        this.plotDataSet = plotDataSet;
        this.datasetSeriesCache = datasetSeriesCache;
        this.outputsTreeBuilder = outputsTreeBuilder;
        this.resolveSessionCallback = resolveSessionCallback;
        this.clearPlotAndStatsCallback = clearPlotAndStatsCallback;
        this.updatePlotVisibilityCallback = updatePlotVisibilityCallback;
    }

    /**
     * Sets up the selection listener on the outputs tree.
     */
    public void setupSelectionListener() {
        timeseriesTree.addTreeSelectionListener(this::onOutputsTreeSelectionChanged);
    }

    /**
     * Sets the updating selection flag to prevent recursive updates.
     */
    public void setUpdatingSelection(boolean updating) {
        this.isUpdatingSelection = updating;
    }

    /**
     * Gets the currently selected series keys.
     */
    public Set<String> getSelectedSeries() {
        return new HashSet<>(selectedSeries);
    }

    /**
     * Gets the series color map.
     */
    public Map<String, Color> getSeriesColorMap() {
        return new HashMap<>(seriesColorMap);
    }

    /**
     * Handles outputs tree selection changes.
     * Supports recursive selection: selecting a parent node plots all its leaf children.
     * Fetches timeseries data for leaf nodes and updates plot and stats.
     */
    private void onOutputsTreeSelectionChanged(TreeSelectionEvent e) {
        // Ignore selection changes during programmatic updates
        if (isUpdatingSelection) {
            return;
        }

        TreePath[] selectedPaths = timeseriesTree.getSelectionPaths();

        if (selectedPaths == null || selectedPaths.length == 0) {
            // Clear plot and stats when nothing is selected
            clearPlotAndStatsCallback.run();
            selectedSeries.clear();
            return;
        }

        // Collect all leaf nodes recursively (parent selection = all children)
        List<OutputsTreeBuilder.SeriesLeafNode> allLeaves = new ArrayList<>();
        for (TreePath path : selectedPaths) {
            DefaultMutableTreeNode node = (DefaultMutableTreeNode) path.getLastPathComponent();
            outputsTreeBuilder.collectLeafNodes(node, allLeaves);
        }

        // If no valid leaves found, clear everything
        if (allLeaves.isEmpty()) {
            clearPlotAndStatsCallback.run();
            selectedSeries.clear();
            return;
        }

        // Build new set of selected series (with unique keys)
        Set<String> newSelectedSeries = new HashSet<>();
        Map<String, OutputsTreeBuilder.SeriesLeafNode> seriesKeyToLeaf = new HashMap<>();

        for (OutputsTreeBuilder.SeriesLeafNode leaf : allLeaves) {
            String seriesKey = createSeriesKey(leaf);
            newSelectedSeries.add(seriesKey);
            seriesKeyToLeaf.put(seriesKey, leaf);
        }

        // Check if there's overlap between old and new selections
        boolean hasOverlap = selectedSeries.stream().anyMatch(newSelectedSeries::contains);
        final boolean shouldResetZoom = selectedSeries.isEmpty() || !hasOverlap;

        // Determine which series to add and which to remove
        Set<String> seriesToAdd = new HashSet<>(newSelectedSeries);
        seriesToAdd.removeAll(selectedSeries);

        Set<String> seriesToRemove = new HashSet<>(selectedSeries);
        seriesToRemove.removeAll(newSelectedSeries);

        // Remove series that are no longer selected
        removeSeries(seriesToRemove);

        // Add new series
        addSeries(seriesToAdd, seriesKeyToLeaf, shouldResetZoom);

        // Update the selected series set
        selectedSeries = newSelectedSeries;

        // Update plot visibility
        updatePlotVisibilityCallback.run();

        // Zoom to fit if selection completely changed
        if (!plotDataSet.isEmpty() && shouldResetZoom) {
            for (PlotPanel panel : tabManager.getAllPlotPanels()) {
                panel.zoomToFit();
            }
        }
    }

    /**
     * Creates a unique series key from a SeriesLeafNode.
     * Format: "seriesName [sourceName]"
     */
    private String createSeriesKey(OutputsTreeBuilder.SeriesLeafNode leaf) {
        String sourceName = getSourceName(leaf.source);
        return leaf.seriesName + " [" + sourceName + "]";
    }

    /**
     * Gets the source name from a source object (RunInfo or LoadedDatasetInfo).
     */
    private String getSourceName(Object source) {
        if (source instanceof RunInfo) {
            return ((RunInfo) source).getRunName();
        } else if (source instanceof DatasetLoaderManager.LoadedDatasetInfo) {
            return ((DatasetLoaderManager.LoadedDatasetInfo) source).fileName;
        }
        return source.toString();
    }

    /**
     * Removes series from plots and stats.
     */
    private void removeSeries(Set<String> seriesToRemove) {
        for (String seriesKey : seriesToRemove) {
            plotDataSet.removeSeries(seriesKey);
            seriesColorMap.remove(seriesKey);

            // Remove from all stats tables
            tabManager.removeSeriesFromStatsTabs(seriesKey);

            // Remove from legend in all plots
            for (PlotPanel panel : tabManager.getAllPlotPanels()) {
                panel.removeLegendSeries(seriesKey);
            }
        }
    }

    /**
     * Adds new series to plots and stats.
     */
    private void addSeries(
            Set<String> seriesToAdd,
            Map<String, OutputsTreeBuilder.SeriesLeafNode> seriesKeyToLeaf,
            boolean shouldResetZoom) {

        // Group series by their underlying data source
        Map<String, List<String>> dataSourceToSeriesKeys = new LinkedHashMap<>();
        Set<String> datasetSeriesKeys = new HashSet<>();

        for (String seriesKey : seriesToAdd) {
            OutputsTreeBuilder.SeriesLeafNode leaf = seriesKeyToLeaf.get(seriesKey);
            if (leaf == null) continue;

            // Assign color
            Color seriesColor = getColorForSeries(seriesKey);
            seriesColorMap.put(seriesKey, seriesColor);

            if (leaf.source instanceof DatasetLoaderManager.LoadedDatasetInfo) {
                // Dataset series - already loaded in cache
                datasetSeriesKeys.add(seriesKey);
            } else {
                // Run series - need to fetch from session
                RunInfo runInfo = (RunInfo) leaf.source;

                // Resolve actual session (handles "Last" run)
                SessionManager.KalixSession resolvedSession = resolveSessionCallback.apply(runInfo);
                if (resolvedSession == null) {
                    continue;
                }

                String sessionKey = resolvedSession.getSessionKey();
                String seriesName = leaf.seriesName;
                String dataSourceKey = sessionKey + "|" + seriesName;

                dataSourceToSeriesKeys.computeIfAbsent(dataSourceKey, k -> new ArrayList<>()).add(seriesKey);
            }
        }

        // Process run series
        processRunSeries(dataSourceToSeriesKeys, shouldResetZoom);

        // Process dataset series
        processDatasetSeries(datasetSeriesKeys, seriesKeyToLeaf);
    }

    /**
     * Processes run series (fetches from sessions).
     */
    private void processRunSeries(
            Map<String, List<String>> dataSourceToSeriesKeys,
            boolean shouldResetZoom) {

        for (Map.Entry<String, List<String>> entry : dataSourceToSeriesKeys.entrySet()) {
            String dataSourceKey = entry.getKey();
            List<String> seriesKeys = entry.getValue();

            String[] parts = dataSourceKey.split("\\|", 2);
            String sessionKey = parts[0];
            String seriesName = parts[1];

            // Check cache
            TimeSeriesData cachedData = timeSeriesRequestManager.getTimeSeriesFromCache(sessionKey, seriesName);
            if (cachedData != null) {
                // Add immediately with cached data
                for (String seriesKey : seriesKeys) {
                    TimeSeriesData renamedData = renameTimeSeriesData(cachedData, seriesKey);
                    addSeriesToPlot(renamedData, seriesColorMap.get(seriesKey));
                    tabManager.updateSeriesInStatsTabsWithAggregation(seriesKey, renamedData);
                }
            } else if (!timeSeriesRequestManager.isRequestInProgress(sessionKey, seriesName)) {
                // Show loading state
                for (String seriesKey : seriesKeys) {
                    tabManager.addLoadingSeriesInStatsTabs(seriesKey);
                }

                // Request data
                final List<String> capturedSeriesKeys = new ArrayList<>(seriesKeys);
                timeSeriesRequestManager.requestTimeSeries(sessionKey, seriesName)
                    .thenAccept(timeSeriesData -> {
                        SwingUtilities.invokeLater(() -> {
                            for (String capturedSeriesKey : capturedSeriesKeys) {
                                if (selectedSeries.contains(capturedSeriesKey)) {
                                    TimeSeriesData renamedData = renameTimeSeriesData(timeSeriesData, capturedSeriesKey);
                                    addSeriesToPlot(renamedData, seriesColorMap.get(capturedSeriesKey));
                                    tabManager.updateSeriesInStatsTabsWithAggregation(capturedSeriesKey, renamedData);
                                }
                            }

                            if (shouldResetZoom) {
                                for (PlotPanel panel : tabManager.getAllPlotPanels()) {
                                    panel.zoomToFit();
                                }
                            }
                        });
                    })
                    .exceptionally(throwable -> {
                        SwingUtilities.invokeLater(() -> {
                            for (String capturedSeriesKey : capturedSeriesKeys) {
                                if (selectedSeries.contains(capturedSeriesKey)) {
                                    tabManager.addErrorSeriesInStatsTabs(capturedSeriesKey, throwable.getMessage());
                                }
                            }
                        });
                        return null;
                    });
            } else {
                // Request already in progress
                for (String seriesKey : seriesKeys) {
                    tabManager.addLoadingSeriesInStatsTabs(seriesKey);
                }
            }
        }
    }

    /**
     * Processes dataset series (from cache).
     */
    private void processDatasetSeries(
            Set<String> datasetSeriesKeys,
            Map<String, OutputsTreeBuilder.SeriesLeafNode> seriesKeyToLeaf) {

        for (String seriesKey : datasetSeriesKeys) {
            OutputsTreeBuilder.SeriesLeafNode leaf = seriesKeyToLeaf.get(seriesKey);
            if (leaf == null) continue;

            // Get cached data
            TimeSeriesData cachedData = datasetSeriesCache.get(leaf.seriesName);
            if (cachedData != null) {
                TimeSeriesData renamedData = renameTimeSeriesData(cachedData, seriesKey);
                addSeriesToPlot(renamedData, seriesColorMap.get(seriesKey));
                tabManager.updateSeriesInStatsTabsWithAggregation(seriesKey, renamedData);
            } else {
                logger.warn("Dataset series not found in cache: " + leaf.seriesName);
                tabManager.addErrorSeriesInStatsTabs(seriesKey, "Series not found");
            }
        }
    }

    /**
     * Assigns the first unused color from the palette.
     */
    private Color getColorForSeries(String seriesName) {
        // Find which color indices are currently in use
        Set<Integer> usedIndices = new HashSet<>();
        for (Color color : seriesColorMap.values()) {
            for (int i = 0; i < SERIES_COLORS.length; i++) {
                if (SERIES_COLORS[i].equals(color)) {
                    usedIndices.add(i);
                    break;
                }
            }
        }

        // Find first available color index
        for (int i = 0; i < SERIES_COLORS.length; i++) {
            if (!usedIndices.contains(i)) {
                return SERIES_COLORS[i];
            }
        }

        // All colors used, wrap around
        return SERIES_COLORS[seriesColorMap.size() % SERIES_COLORS.length];
    }

    /**
     * Adds a series to plotDataSet and all plot panels with the specified color.
     */
    private void addSeriesToPlot(TimeSeriesData timeSeriesData, Color seriesColor) {
        plotDataSet.addSeries(timeSeriesData);
        seriesColorMap.put(timeSeriesData.getName(), seriesColor);

        // Add to legend in all plot panels
        for (PlotPanel panel : tabManager.getAllPlotPanels()) {
            panel.addLegendSeries(timeSeriesData.getName(), seriesColor);
        }
    }

    /**
     * Creates a new TimeSeriesData with a different name but same data.
     */
    private TimeSeriesData renameTimeSeriesData(TimeSeriesData original, String newName) {
        // Convert timestamps back to LocalDateTime array
        long[] timestamps = original.getTimestamps();
        LocalDateTime[] dateTimes = new LocalDateTime[timestamps.length];
        for (int i = 0; i < timestamps.length; i++) {
            dateTimes[i] = LocalDateTime.ofInstant(
                java.time.Instant.ofEpochMilli(timestamps[i]),
                java.time.ZoneOffset.UTC);
        }

        return new TimeSeriesData(newName, dateTimes, original.getValues());
    }
}
