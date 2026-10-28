package com.kalix.ide.flowviz;

import com.kalix.ide.flowviz.data.DataSet;
import com.kalix.ide.flowviz.data.LabelResolver;
import com.kalix.ide.flowviz.data.SeriesRef;
import com.kalix.ide.flowviz.data.TimeSeriesData;
import com.kalix.ide.flowviz.rendering.LineShape;
import com.kalix.ide.flowviz.rendering.PlotColors;
import com.kalix.ide.flowviz.rendering.SeriesRenderMode;
import com.kalix.ide.flowviz.rendering.TimeSeriesRenderer;
import com.kalix.ide.flowviz.rendering.ViewPort;
import com.kalix.ide.flowviz.rendering.XAxisType;
import com.kalix.ide.flowviz.stats.SeasonalMaskMode;
import com.kalix.ide.flowviz.style.PaletteSeriesStyleResolver;
import com.kalix.ide.flowviz.style.PlotPaletteManager;
import com.kalix.ide.flowviz.style.SeriesSlotManager;
import com.kalix.ide.flowviz.style.SeriesStyleResolver;
import com.kalix.ide.flowviz.transform.AggregationMethod;
import com.kalix.ide.flowviz.transform.AggregationPeriod;
import com.kalix.ide.flowviz.transform.PlotType;
import com.kalix.ide.flowviz.transform.YAxisScale;
import com.kalix.ide.flowviz.stats.MaskMode;

import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import java.awt.Cursor;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.event.MouseAdapter;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import com.kalix.ide.preferences.PreferenceKeys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Panel for rendering time series plots with support for aggregation, transforms, and LOD rendering.
 *
 * <h2>Data Flow and Caching</h2>
 * There are two caching layers:
 * <ol>
 *   <li><b>Display set</b> ({@link FlowVizModel#display()}): aggregated, masked and transformed
 *       data, cached by the model against its settings and the identity of the data it
 *       was built from, so new data in the pool is noticed without invalidation calls.</li>
 *   <li><b>LOD rendering cache</b> (in {@link TimeSeriesRenderer} → {@link com.kalix.ide.flowviz.rendering.LODManager}):
 *       Caches pixel-level min/max bands for efficient rendering. Cleared whenever the
 *       display set is replaced.</li>
 * </ol>
 *
 * <h2>IMPORTANT: Refreshing Data</h2>
 * When underlying data changes, call {@link #refreshData(boolean)}, which takes the model's
 * current display set and repaints. Simply calling {@code repaint()} is NOT sufficient - it
 * will render the display set taken at the last refresh!
 *
 * <h2>Rendering Pipeline</h2>
 * <pre>
 * originalDataSet (shared)
 *   → TimeSeriesAggregator.aggregate()   [seasonal mask THEN aggregation: daily, monthly, etc.]
 *   → TimeSeriesMasker (if ALL mode)     [filter to common valid timestamps]
 *   → PlotTypeTransformer.transform()    [plot type: values, cumulative, difference]
 * </pre>
 *
 * The seasonal (per-month) mask is applied <em>inside</em> the aggregation step, not as a
 * further filter after it. Aggregation is the last point at which individual months are
 * still distinguishable: afterwards every point carries its period's start timestamp, so
 * a Jan-Dec year reads as January and a post-aggregation mask would degenerate to "is
 * January selected?" - inert if yes, empty plot if no (#235). Any further filter
 * dimension that is calendar-aware belongs there too, not here.
 * <pre>
 *   → displayDataSet (cached per-panel)
 *   → TimeSeriesRenderer.render()        [with LOD optimization for large datasets]
 * </pre>
 *
 * @see com.kalix.ide.flowviz.VisualizationTabManager#updateAllTabs
 * @see com.kalix.ide.flowviz.rendering.LODManager
 */
public class FlowVizPanel extends JPanel {

    private static final Logger logger = LoggerFactory.getLogger(FlowVizPanel.class);

    // Plot margins
    private static final int MARGIN_LEFT = 80;
    private static final int MARGIN_RIGHT = 20;
    private static final int MARGIN_TOP = 20;
    private static final int MARGIN_BOTTOM = 60;


    // === DATA FLOW ===
    // originalDataSet → [model: transforms] → displayDataSet → [LOD] → screen

    private DataSet originalDataSet;  // Reference to shared dataset (same for all plot tabs)
    private DataSet displayDataSet;   // The model's display set as of the last refresh
    private final TimeSeriesRenderer renderer;  // Contains LOD cache - clear via renderer.clearCache()
    private ViewPort currentViewport;
    private SeriesStyleResolver styleResolver;  // Resolves each series to its colour + stroke
    // The renderer's and overlays' view of the selection, kept in step with the state.
    private final List<SeriesRef> visibleSeries;
    private XAxisType xAxisTypeOverride = null;  // If set, overrides automatic axis type selection

    // Reference series tracking for DIFFERENCE plot types
    private SeriesRef lastReferenceSeries = null;

    // LabelResolver for projecting refs to display labels (legend, hover, etc.).
    // Set by the owner (typically RunManager via VisualizationTabManager) after construction.
    private LabelResolver labelResolver;


    /**
     * True once the user has zoomed/panned this plot. Live-updating owners (the
     * optimisation convergence plot) use this to stop re-fitting the viewport on
     * every data update the moment the user chooses their own view.
     */
    private boolean userViewportTouched = false;

    // Managers
    private final CoordinateDisplayManager coordinateDisplayManager;
    private final PlotInteractionManager plotInteractionManager;
    private final PlotLegendManager legendManager;

    // === STATE ===
    // The tab's state and undo history live in the model; the panel draws them.
    private final FlowVizModel model;
    private boolean restoringState = false;  // Suppresses pushState() during restores
    private boolean shown = true;            // A hidden page defers rebuilds and fits...
    private boolean stale = false;           // ...and catches up when shown
    private Fit pendingFit = Fit.NONE;       // The fit a hidden page owes, the widest asked for

    /** A fit a hidden page owes, ordered by reach: a whole fit covers a Y fit. */
    private enum Fit { NONE, Y, ALL }

    // Repaints this panel whenever the active palette is edited or switched, or a
    // series is moved to a different palette slot, so a palette-backed resolver's
    // new styles take effect live. Registered/unregistered with the component's
    // display lifecycle in addNotify()/removeNotify().
    private final Runnable paletteChangeListener = this::repaint;

    // The slot manager this panel is registered with for repaint-on-slot-change;
    // tracked so removeNotify() detaches from the exact same instance.
    private SeriesSlotManager registeredSlotManager;
    private final javax.swing.Timer viewportCoalesceTimer;  // Coalesces rapid zoom/pan changes

    /** A standalone plot with a private model. */
    public FlowVizPanel() {
        this(new FlowVizModel());
    }

    /** A plot of {@code model}'s state; the panel follows every change of it. */
    public FlowVizPanel(FlowVizModel model) {
        this.model = model;
        // Background is theme-driven; set here and re-resolved in updateUI() on theme switch.
        setBackground(PlotColors.fromUIManager().background);

        // Initialize data structures
        visibleSeries = new java.util.ArrayList<>();
        renderer = new TimeSeriesRenderer(visibleSeries);

        // Initialize managers
        coordinateDisplayManager = new CoordinateDisplayManager(this, visibleSeries);
        plotInteractionManager = new PlotInteractionManager(this, coordinateDisplayManager);
        legendManager = new PlotLegendManager();

        // Default to the global palette with a private slot assignment. An owner
        // (e.g. the Run Manager) typically injects a shared resolver afterwards via
        // setStyleResolver() so colours stay consistent across its tabs.
        setStyleResolver(new PaletteSeriesStyleResolver(
            new SeriesSlotManager(), PlotPaletteManager.getInstance()));

        // Setup viewport coalescing timer for undo/redo (500ms delay)
        viewportCoalesceTimer = new javax.swing.Timer(500, e -> pushState());
        viewportCoalesceTimer.setRepeats(false);

        // Setup manager data access
        plotInteractionManager.setupDataAccess(
            () -> displayDataSet,
            () -> currentViewport,
            viewport -> {
                currentViewport = viewport;
                // Coalesce rapid viewport changes (zoom/pan) into one history entry
                if (!restoringState) {
                    userViewportTouched = true;
                    viewportCoalesceTimer.restart();
                }
            },
            () -> visibleSeries,
            this::getPlotArea
        );

        // Setup plot type supplier for format-aware export
        plotInteractionManager.setPlotTypeSupplier(() -> state().getPlotType());
        plotInteractionManager.setAutoYModeSupplier(() -> state().isAutoYMode());

        // Resolver for projecting ref-keyed series to column headers on CSV export.
        plotInteractionManager.setLabelResolverSupplier(() -> labelResolver);

        setupMouseListeners();

        visibleSeries.addAll(model.current().getVisibleSeries());
        legendManager.setSeries(model.current().getVisibleSeries());
        renderer.setLineShape(model.current().getLineShape());
        model.addListener(this::onStateChanged);
    }

    /** The model this panel draws. */
    public FlowVizModel model() {
        return model;
    }

    /**
     * Shows or hides the page. A hidden plot does not rebuild or fit: it notes what it
     * missed and catches up when shown, folding the fit it owed into the current history
     * entry, so undo returns to the view the change produced.
     */
    public void setShown(boolean shown) {
        this.shown = shown;
        if (!shown) {
            return;
        }
        if (stale) {
            stale = false;
            refreshData(false);
        }
        Fit fit = pendingFit;
        pendingFit = Fit.NONE;
        switch (fit) {
            case ALL -> zoomToFit();
            case Y -> fitYAxis();
            case NONE -> {
                return;
            }
        }
        model.amendRecorded(s -> s.withViewport(currentViewport));
    }

    /** Rebuilds now when shown; a hidden page rebuilds when shown. */
    private void rebuildOrDefer() {
        if (shown) {
            rebuildDisplayDataSet();
        } else {
            stale = true;
        }
    }

    /** Notes a fit a hidden page owes; returns whether it was deferred. */
    private boolean deferFit(Fit fit) {
        if (shown) {
            return false;
        }
        if (fit.compareTo(pendingFit) > 0) {
            pendingFit = fit;
        }
        return true;
    }

    private FlowVizState state() {
        return model.current();
    }

    @Override
    public void updateUI() {
        super.updateUI();
        // Re-resolve the theme's plot background after a LaF/theme switch (ThemeManager
        // runs SwingUtilities.updateComponentTreeUI over open FlowViz windows).
        setBackground(PlotColors.fromUIManager().background);
    }

    @Override
    public void addNotify() {
        super.addNotify();
        PlotPaletteManager.getInstance().addChangeListener(paletteChangeListener);
        // Also repaint when a series is moved to a different palette slot.
        if (styleResolver instanceof PaletteSeriesStyleResolver psr) {
            registeredSlotManager = psr.slotManager();
            registeredSlotManager.addChangeListener(paletteChangeListener);
        }
    }

    /**
     * Releases display-scoped resources only. This fires on every hierarchy detach,
     * including the remove/insert cycle a tab reorder or reset performs, and everything
     * torn down here is re-established on {@link #addNotify()} or on next use. Callbacks
     * installed by the owning toolbar ({@code onHistoryChanged}, {@code onAutoYModeChanged})
     * are deliberately <em>not</em> cleared: they belong to the owner, which detaches
     * them when it closes the tab, and clearing them here would sever a re-parented
     * panel from its own toolbar. (Stopping the coalesce timer drops one pending history
     * push if a tab is dragged within 500 ms of a zoom — known and harmless.)
     */
    @Override
    public void removeNotify() {
        PlotPaletteManager.getInstance().removeChangeListener(paletteChangeListener);
        if (registeredSlotManager != null) {
            registeredSlotManager.removeChangeListener(paletteChangeListener);
            registeredSlotManager = null;
        }
        viewportCoalesceTimer.stop();
        coordinateDisplayManager.dispose();
        super.removeNotify();
    }

    public void setDataSet(DataSet dataSet) {
        this.originalDataSet = dataSet;
        model.setPool(dataSet);

        // Rebuild display dataset with current transforms
        rebuildDisplayDataSet();

        // Create initial viewport to show all data
        if (displayDataSet != null && !displayDataSet.isEmpty()) {
            zoomToFitData();
        } else {
            createDefaultViewport();
        }

        repaint();
    }
    
    /**
     * Sets the resolver that maps each series to its colour and stroke, propagating
     * it to the renderer, legend, and coordinate overlay. The resolver is consulted
     * at paint time, so a shared palette-backed resolver keeps every plot in sync
     * with the active palette automatically.
     */
    public void setStyleResolver(SeriesStyleResolver styleResolver) {
        this.styleResolver = styleResolver;
        renderer.setStyleResolver(styleResolver);
        coordinateDisplayManager.setStyleResolver(styleResolver);
        legendManager.setStyleResolver(styleResolver);
        // The legend's style picker only applies to palette-backed plots; a null
        // callback disables line-sample clicking for fixed-colour resolvers.
        legendManager.setOnStyleClicked(
            styleResolver instanceof PaletteSeriesStyleResolver ? this::showStylePicker : null);
        repaint();
    }

    /**
     * Opens the legend's line-style picker for {@code ref}. Only reachable when the
     * style resolver is palette-backed — the legend callback is null otherwise.
     */
    private void showStylePicker(SeriesRef ref, Point at) {
        if (styleResolver instanceof PaletteSeriesStyleResolver psr) {
            LineStylePicker.show(this, at.x, at.y, ref, psr.slotManager(), psr.paletteManager());
        }
    }

    /**
     * Sets the {@link LabelResolver} used to project {@link SeriesRef}s to display
     * labels. Required for any UI surface that renders series identity (legend,
     * coordinate hover).
     */
    public void setLabelResolver(LabelResolver labelResolver) {
        this.labelResolver = labelResolver;
        if (legendManager != null) {
            legendManager.setLabelResolver(labelResolver);
        }
    }
    
    private void zoomToFitData() {
        if (displayDataSet == null || displayDataSet.isEmpty()) {
            createDefaultViewport();
            return;
        }

        long startTime = displayDataSet.getGlobalMinTime();
        long endTime = displayDataSet.getGlobalMaxTime();
        Double minValue = displayDataSet.getGlobalMinValue();
        Double maxValue = displayDataSet.getGlobalMaxValue();
        
        if (minValue == null || maxValue == null) {
            createDefaultViewport();
            return;
        }

        // The cached global minimum ignores the scale's domain: on LOG it may be zero or
        // negative, and padding from that gives NaN bounds and a blank view. Fit to the
        // smallest showable value instead, as auto-Y does, or to the default view when
        // nothing is showable.
        YAxisScale yAxisScale = state().getYAxisScale();
        double domainFloor = yAxisScale.domainFloor();
        if (minValue <= domainFloor) {
            minValue = smallestAbove(displayDataSet, domainFloor);
            if (minValue == null) {
                createDefaultViewport();
                return;
            }
        }

        // Clamp minimum value for log scale to prevent zooming too far out
        // Hydrological models often produce tiny values (e.g., 1e-12) that are meaningless
        // This only affects auto-zoom; manual zoom/pan can still access the full range
        double logScaleMin = PreferenceKeys.PLOT_LOG_SCALE_MIN_THRESHOLD.get();
        if (yAxisScale == YAxisScale.LOG && minValue < logScaleMin && logScaleMin < maxValue) {
            minValue = logScaleMin;
        }

        // Add 5% padding
        long timePadding = (long) ((endTime - startTime) * 0.05);

        startTime -= timePadding;
        endTime += timePadding;

        // Ensure minimum time range
        if (endTime - startTime < 1000) { // Less than 1 second
            long center = (startTime + endTime) / 2;
            startTime = center - 500;
            endTime = center + 500;
        }

        // Apply padding to Y values in appropriate space (linear or log)
        double[] paddedYRange = applyYAxisPadding(minValue, maxValue, yAxisScale, 0.05);
        minValue = paddedYRange[0];
        maxValue = paddedYRange[1];

        Rectangle plotArea = getPlotArea();
        XAxisType xAxisType = determineXAxisType();
        currentViewport = new ViewPort(startTime, endTime, minValue, maxValue,
                                     plotArea.x, plotArea.y, plotArea.width, plotArea.height, yAxisScale, xAxisType);
    }

    /** The smallest valid value above {@code floor} across every series, or null if none. */
    private static Double smallestAbove(DataSet dataSet, double floor) {
        double smallest = Double.POSITIVE_INFINITY;
        for (TimeSeriesData series : dataSet.getAllSeries()) {
            double[] values = series.getValues();
            boolean[] validPoints = series.getValidPoints();
            for (int i = 0; i < series.getPointCount(); i++) {
                if (validPoints[i] && values[i] > floor && values[i] < smallest) smallest = values[i];
            }
        }
        return smallest == Double.POSITIVE_INFINITY ? null : smallest;
    }

    private void createDefaultViewport() {
        // Default viewport showing current time ± 1 hour
        long now = System.currentTimeMillis();
        long startTime = now - 3600000; // 1 hour ago
        long endTime = now + 3600000;   // 1 hour from now

        Rectangle plotArea = getPlotArea();
        XAxisType xAxisType = determineXAxisType();
        currentViewport = new ViewPort(startTime, endTime, -10.0, 10.0,
                                     plotArea.x, plotArea.y, plotArea.width, plotArea.height,
                                     state().getYAxisScale(), xAxisType);
    }

    /**
     * Applies padding to Y-axis range in the appropriate transformed space.
     * Works for every scale in {@link YAxisScale} by applying padding in transformed
     * space and then inverse transforming back to data space. This ensures consistent
     * visual spacing regardless of scale type.
     *
     * @param minValue The minimum Y value before padding
     * @param maxValue The maximum Y value before padding
     * @param yAxisScale The Y-axis scale to pad in
     * @param paddingFraction The fraction of range to use as padding (e.g., 0.05 for 5%)
     * @return Array of [paddedMin, paddedMax]
     */
    private static double[] applyYAxisPadding(double minValue, double maxValue, YAxisScale yAxisScale, double paddingFraction) {
        double valueRange = maxValue - minValue;

        // Handle constant data (all values identical) - use relative padding based on magnitude
        if (valueRange < 1e-15) {
            double center = (minValue + maxValue) / 2;
            double halfRange = Math.max(Math.abs(center) * 0.1, 1e-6);
            return new double[]{center - halfRange, center + halfRange};
        }

        // Apply padding in transformed space for correct visual spacing
        double transformedMin = yAxisScale.transform(minValue);
        double transformedMax = yAxisScale.transform(maxValue);
        double transformedRange = transformedMax - transformedMin;

        double padding = transformedRange * paddingFraction;
        transformedMin -= padding;
        transformedMax += padding;

        // Inverse transform back to data space
        double paddedMin = yAxisScale.inverseTransform(transformedMin);
        double paddedMax = yAxisScale.inverseTransform(transformedMax);

        return new double[]{paddedMin, paddedMax};
    }

    /**
     * Determines the X-axis type based on override or plot type.
     */
    private XAxisType determineXAxisType() {
        if (xAxisTypeOverride != null) {
            return xAxisTypeOverride;
        }
        return xAxisTypeFor(state().getPlotType());
    }

    private void setupMouseListeners() {
        MouseAdapter plotMouseHandler = plotInteractionManager.createMouseHandler();

        // Create composite mouse handler that routes events to legend first, then to plot.
        // Only a plain left press belongs to the legend (drag, collapse). A popup trigger
        // must reach the plot handler even over the legend, or on macOS (where the popup
        // fires on press) a right-click over the key could never open the menu that holds
        // "Reset key".
        MouseAdapter compositeHandler = new MouseAdapter() {
            @Override
            public void mousePressed(java.awt.event.MouseEvent e) {
                boolean plainLeftPress = SwingUtilities.isLeftMouseButton(e) && !e.isPopupTrigger();
                if (plainLeftPress && legendManager != null
                        && legendManager.handleMousePress(e.getX(), e.getY())) {
                    repaint();
                    return; // Event consumed by legend
                }
                plotMouseHandler.mousePressed(e);
            }

            @Override
            public void mouseReleased(java.awt.event.MouseEvent e) {
                if (legendManager != null) {
                    legendManager.handleMouseRelease();
                    repaint();
                }
                plotMouseHandler.mouseReleased(e);
            }

            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
                if (legendManager != null && legendManager.handleMouseClick(e.getX(), e.getY())) {
                    repaint();
                    return; // Event consumed by legend
                }
                plotMouseHandler.mouseClicked(e);
            }

            @Override
            public void mouseDragged(java.awt.event.MouseEvent e) {
                if (legendManager != null && legendManager.handleMouseDrag(e.getX(), e.getY())) {
                    repaint();
                    return; // Event consumed by legend
                }
                plotMouseHandler.mouseDragged(e);
            }

            @Override
            public void mouseMoved(java.awt.event.MouseEvent e) {
                // Update legend hover state and cursor
                if (legendManager != null) {
                    if (legendManager.handleMouseMove(e.getX(), e.getY())) {
                        repaint();
                    }
                    Cursor legendCursor = legendManager.getCursor(e.getX(), e.getY());
                    if (legendCursor != null) {
                        setCursor(legendCursor);
                    } else {
                        setCursor(Cursor.getDefaultCursor());
                    }
                }
                plotMouseHandler.mouseMoved(e);
            }

            @Override
            public void mouseWheelMoved(java.awt.event.MouseWheelEvent e) {
                // Don't zoom if mouse is over legend
                if (legendManager != null && legendManager.contains(e.getX(), e.getY())) {
                    return; // Event consumed by legend
                }
                plotMouseHandler.mouseWheelMoved(e);
            }
        };

        addMouseListener(compositeHandler);
        addMouseMotionListener(compositeHandler);
        addMouseWheelListener(compositeHandler);
    }
    
    private Rectangle getPlotArea() {
        int width = getWidth();
        int height = getHeight();
        
        return new Rectangle(
            MARGIN_LEFT,
            MARGIN_TOP,
            width - MARGIN_LEFT - MARGIN_RIGHT,
            height - MARGIN_TOP - MARGIN_BOTTOM
        );
    }
    
    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2d = (Graphics2D) g.create();
        
        // Update viewport with current plot area
        Rectangle plotArea = getPlotArea();
        if (currentViewport != null) {
            currentViewport = currentViewport.withPlotArea(
                plotArea.x, plotArea.y, plotArea.width, plotArea.height);
        } else {
            createDefaultViewport();
        }
        
        // Render using the new rendering engine
        if (displayDataSet != null && renderer != null) {
            renderer.render(g2d, displayDataSet, currentViewport);
        } else {
            // Fallback to empty state
            g2d.setColor(PlotColors.fromUIManager().emptyForeground);
            g2d.setFont(new Font("Arial", Font.PLAIN, 16));

            String message = "No data loaded";
            FontMetrics fm = g2d.getFontMetrics();
            int messageX = plotArea.x + (plotArea.width - fm.stringWidth(message)) / 2;
            int messageY = plotArea.y + plotArea.height / 2;

            g2d.drawString(message, messageX, messageY);
        }

        // Update coordinate display manager and render overlays
        if (coordinateDisplayManager != null) {
            coordinateDisplayManager.updateCoordinateDisplay(displayDataSet, currentViewport);
            coordinateDisplayManager.renderCoordinateOverlays(g2d, currentViewport);
        }

        // Render legend (after plot and coordinates, so it appears on top)
        if (legendManager != null) {
            legendManager.render(g2d, currentViewport);
        }

        // Render zoom selection rectangle (on top of everything)
        if (plotInteractionManager != null) {
            plotInteractionManager.renderZoomRectangle(g2d);
        }

        g2d.dispose();
    }
    
    public void zoomIn() {
        if (plotInteractionManager != null) {
            plotInteractionManager.zoomIn();
        }
    }

    public void zoomOut() {
        if (plotInteractionManager != null) {
            plotInteractionManager.zoomOut();
        }
    }
    
    /** Fits both axes to the data; a hidden page fits when shown. */
    public void zoomToFit() {
        if (deferFit(Fit.ALL)) {
            return;
        }
        zoomToFitData();
        repaint();
    }

    /** Fits Y to the data in the current X range; a hidden page fits when shown. */
    public void fitYAxis() {
        if (deferFit(Fit.Y)) {
            return;
        }
        if (plotInteractionManager != null) {
            plotInteractionManager.fitYAxisToCurrentXRange();
        }
    }

    public void resetView() {
        zoomToFitData();
        repaint();
    }
    
    public void saveData() {
        rebuildDisplayDataSet(); // current even when saved from another page
        if (plotInteractionManager != null) {
            plotInteractionManager.saveData();
        }
    }

    public boolean isAutoYMode() {
        return state().isAutoYMode();
    }

    public void setShowCoordinates(boolean showCoordinates) {
        if (coordinateDisplayManager != null) {
            coordinateDisplayManager.setShowCoordinates(showCoordinates);
        }
    }

    public boolean isShowCoordinates() {
        return coordinateDisplayManager != null && coordinateDisplayManager.isShowCoordinates();
    }

    public void setLegendEnabled(boolean enabled) {
        if (legendManager != null) {
            legendManager.setEnabled(enabled);
            repaint();
        }
    }

    public void setLegendCollapsed(boolean collapsed) {
        if (legendManager != null) {
            legendManager.setCollapsed(collapsed);
            repaint();
        }
    }

    public boolean isLegendCollapsed() {
        return legendManager != null && legendManager.isCollapsed();
    }

    public boolean isLegendEnabled() {
        return legendManager != null && legendManager.isEnabled();
    }

    /**
     * Resets the legend to its default state: shown, expanded, and auto-positioned.
     */
    public void resetLegend() {
        if (legendManager != null) {
            legendManager.reset();
            repaint();
        }
    }

    /**
     * Sets the precision preference supplier for export operations.
     * This only affects data export format, not plotting functionality.
     */
    public void setPrecision64Supplier(Supplier<Boolean> precision64Supplier) {
        if (plotInteractionManager != null) {
            plotInteractionManager.setPrecision64Supplier(precision64Supplier);
        }
    }

    /**
     * Sets the base directory supplier used to seed the "Save Data" file dialog's
     * starting folder. Returns the model's directory (or {@code null} if no file is
     * loaded). This only affects where the save dialog opens, not plotting.
     */
    public void setBaseDirectorySupplier(Supplier<java.io.File> baseDirectorySupplier) {
        if (plotInteractionManager != null) {
            plotInteractionManager.setBaseDirectorySupplier(baseDirectorySupplier);
        }
    }

    /**
     * Sets the render mode for a specific series (LINE, POINTS, or LINE_AND_POINTS).
     */
    public void setSeriesRenderMode(SeriesRef ref, SeriesRenderMode renderMode) {
        if (renderer != null) {
            renderer.setSeriesRenderMode(ref, renderMode);
            repaint();
        }
    }

    /**
     * Gets the render mode for a specific series.
     */
    public SeriesRenderMode getSeriesRenderMode(SeriesRef ref) {
        if (renderer != null) {
            return renderer.getSeriesRenderMode(ref);
        }
        return SeriesRenderMode.LINE;
    }

    /**
     * Whether the line is drawn continuously across missing data instead of breaking at gaps.
     * Off by default, so gaps in regular series read as holes. A pure render toggle (not part of
     * undo/redo plot state), so it does not invalidate any cache — a repaint suffices.
     */
    public void setConnectAcrossGaps(boolean connect) {
        if (renderer != null) {
            renderer.setConnectAcrossGaps(connect);
            // Mutually exclusive with orphan markers: a continuous line leaves no gaps to mark.
            if (connect) {
                renderer.setShowOrphanMarkers(false);
            }
            repaint();
        }
    }

    public boolean isConnectAcrossGaps() {
        return renderer != null && renderer.isConnectAcrossGaps();
    }

    /**
     * Whether isolated valid points (surrounded by missing data) are marked with a dot so they
     * are not invisible in line mode. Off by default. Mutually exclusive with connect-across-gaps.
     */
    public void setShowOrphanMarkers(boolean show) {
        if (renderer != null) {
            renderer.setShowOrphanMarkers(show);
            // Mutually exclusive with connect-across-gaps (which would suppress the markers).
            if (show) {
                renderer.setConnectAcrossGaps(false);
            }
            repaint();
        }
    }

    public boolean isShowOrphanMarkers() {
        return renderer != null && renderer.isShowOrphanMarkers();
    }

    public LineShape getLineShape() {
        return state().getLineShape();
    }

    /**
     * Sets the X-axis type override (COUNT, TIME, or PERCENTILE).
     * Set to null to use automatic detection based on plot type.
     */
    public void setXAxisType(XAxisType xAxisType) {
        this.xAxisTypeOverride = xAxisType;

        // Update viewport with new axis type
        if (currentViewport != null) {
            XAxisType newAxisType = determineXAxisType();
            Rectangle plotArea = getPlotArea();
            currentViewport = new ViewPort(
                currentViewport.getStartTimeMs(),
                currentViewport.getEndTimeMs(),
                currentViewport.getMinValue(),
                currentViewport.getMaxValue(),
                plotArea.x, plotArea.y, plotArea.width, plotArea.height,
                state().getYAxisScale(),
                newAxisType
            );
        }

        repaint();
    }

    /**
     * Gets the current X-axis type (considering override).
     */
    public XAxisType getXAxisType() {
        return determineXAxisType();
    }

    // Legend management methods

    /**
     * Gets the legend manager for direct access.
     */
    public PlotLegendManager getLegendManager() {
        return legendManager;
    }

    public AggregationPeriod getAggregationPeriod() {
        return state().getAggregationPeriod();
    }

    public AggregationMethod getAggregationMethod() {
        return state().getAggregationMethod();
    }

    /** The transformed dataset the renderer draws — package-private, for tests. */
    DataSet displayDataSetForTests() {
        return displayDataSet;
    }

    /**
     * Refreshes the display dataset from the original data.
     *
     * @param resetZoom If true, resets zoom to fit all data. If false, preserves current zoom.
     */
    /** Whether the user has zoomed/panned since the last {@link #resetUserViewportTouched()}. */
    public boolean isUserViewportTouched() {
        return userViewportTouched;
    }

    /** Re-arms auto-fitting for live-updating owners (call when a new run's data begins). */
    public void resetUserViewportTouched() {
        userViewportTouched = false;
    }

    /**
     * Centres the viewport on a datapoint — the plot-side "Show in plot",
     * mirroring how "Show in file" reveals a row. The time axis re-centres on
     * {@code timeMs} keeping its span; the value axis re-centres on
     * {@code value} only when it is currently off-screen and showable on the
     * current scale (finite; positive on log), keeping its span in transformed
     * space so the zoom level survives whatever the scale. Counts as a user
     * pan: touched flag set, coalesced into undo like a drag.
     */
    public void centerViewportOn(long timeMs, double value) {
        if (currentViewport == null) {
            return;
        }
        if (determineXAxisType() != XAxisType.TIME) {
            // Exceedance (percentile) and Double-Mass (numeric) domains are not
            // epoch time: centring a date there would scroll orders of magnitude
            // past the data and blank the plot. Refuse audibly instead.
            java.awt.Toolkit.getDefaultToolkit().beep();
            return;
        }
        long span = currentViewport.getTimeRangeMs();
        long newStart = timeMs - span / 2;
        currentViewport = currentViewport.centreValueAxisOn(value) // no-op when on screen
            .withTimeRange(newStart, newStart + span);
        userViewportTouched = true;
        viewportCoalesceTimer.restart(); // one history entry, like a pan
        repaint();
    }

    /** The current viewport — package-private, for tests. */
    ViewPort viewportForTests() {
        return currentViewport;
    }

    public void refreshData(boolean resetZoom) {
        rebuildDisplayDataSet();
        if (resetZoom) {
            fitToData();
        }
        repaint();
    }

    /** Fits the view to the data already shown, if there is any; no rebuild. A hidden page fits when shown. */
    public void fitToData() {
        if (deferFit(Fit.ALL)) {
            return;
        }
        if (displayDataSet != null && !displayDataSet.isEmpty()) {
            zoomToFit();
        }
    }

    public YAxisScale getYAxisScale() {
        return state().getYAxisScale();
    }

    /**
     * Maps a plot type to the X-axis domain it plots against, so callers can tell whether
     * switching between two plot types changes that domain (and therefore can't preserve the
     * existing X zoom). Shared by {@link #setPlotType} and {@link #setPlotTypeAndMaskMode}.
     */
    private static XAxisType xAxisTypeFor(PlotType type) {
        return switch (type) {
            case EXCEEDANCE -> XAxisType.PERCENTILE;
            case DOUBLE_MASS -> XAxisType.NUMERIC;
            case VALUES, CUMULATIVE, DIFFERENCE, CUMULATIVE_DIFFERENCE, RESIDUAL_MASS -> XAxisType.TIME;
        };
    }

    /** The tab's current state: what the toolbar and the window trees resync from. */
    public FlowVizState currentState() {
        return state();
    }

    public PlotType getPlotType() {
        return state().getPlotType();
    }

    /**
     * The data as displayed (aggregated, masked, transformed) — what Save Data exports —
     * current whether or not this page is showing.
     */
    public DataSet getDisplayDataSet() {
        return model.display();
    }

    /**
     * Gets the seasonal (per-month) mask: which calendar months a view is restricted to.
     * Orthogonal to {@link #getMaskMode()}, which is the validity/overlap mask.
     */
    public SeasonalMaskMode getSeasonalMaskMode() {
        return state().getSeasonalMaskMode();
    }

    /**
     * Gets the current mask mode.
     */
    public MaskMode getMaskMode() {
        return state().getMaskMode();
    }

    // === UNDO/REDO ===

    /**
     * Sets a callback invoked whenever the history changes (push/undo/redo).
     * Used by toolbar to update button enabled state.
     */
    public void setOnHistoryChanged(Runnable callback) {
        model.setOnHistoryChanged(callback);
    }


    /**
     * Records the current state as an undo entry (if changed), with the viewport as it is
     * now: a zoom or pan, and the target tab's source changes. Skipped during restores,
     * and before data is loaded (to avoid junk history from initial setup).
     */
    public void pushState() {
        if (restoringState || originalDataSet == null) return;
        model.amend(s -> s.withViewport(currentViewport));
        model.record();
    }

    /**
     * Follows the model. A restore applies the snapshot's viewport and rebuilds once; a
     * user change rebuilds what it affects and fits as the change calls for (the rules each
     * setter used to apply for itself); a change of only the viewport or the checked
     * sources needs nothing.
     */
    private void onStateChanged(FlowVizState old, FlowVizState now, FlowVizModel.Cause cause) {
        visibleSeries.clear();
        visibleSeries.addAll(now.getVisibleSeries());
        legendManager.setSeries(now.getVisibleSeries());
        renderer.setLineShape(now.getLineShape());
        switch (cause) {
            case REPLACE -> applyReplaced(old);
            case RESTORE -> applyRestored(old, now);
            case DATA -> {
                if (shown) {
                    refreshData(false);
                } else {
                    stale = true;
                    // Let go of a display set the model has released; rebuilt when shown.
                    if (displayDataSet != null && !model.holdsDisplay(displayDataSet)) {
                        displayDataSet = null;
                        renderer.clearCache();
                    }
                }
            }
            case USER -> applyUserChange(old, now);
        }
    }

    /**
     * Shows a whole new state in full: rebuild, then fit once — domain-aware, as a
     * plot-type change fits — and fold the fitted viewport into the entry being recorded.
     */
    private void applyReplaced(FlowVizState old) {
        pendingFit = Fit.NONE; // a whole new state: only its own fit counts
        lastReferenceSeries = null;
        rebuildOrDefer();
        if (xAxisTypeFor(old.getPlotType()) != xAxisTypeFor(state().getPlotType())) {
            zoomToFit();
        } else if (state().isAutoYMode()) {
            fitYAxis();
        } else {
            zoomToFit();
        }
        model.amend(s -> s.withViewport(currentViewport));
    }

    private void applyUserChange(FlowVizState old, FlowVizState now) {
        if (!now.differsInPlotFrom(old)) {
            return;
        }
        boolean plotTypeChanged = now.getPlotType() != old.getPlotType();
        boolean shapeChanged = plotTypeChanged
            || now.getAggregationPeriod() != old.getAggregationPeriod()
            || now.getAggregationMethod() != old.getAggregationMethod()
            || now.getMaskMode() != old.getMaskMode()
            || !now.getSeasonalMaskMode().equals(old.getSeasonalMaskMode());
        boolean seriesChanged = !now.getVisibleSeries().equals(old.getVisibleSeries());
        boolean scaleChanged = now.getYAxisScale() != old.getYAxisScale();
        boolean autoYSwitchedOn = now.isAutoYMode() && !old.isAutoYMode();

        if (scaleChanged && currentViewport != null) {
            currentViewport = currentViewport.withYAxisScale(now.getYAxisScale());
        }
        if (plotTypeChanged) {
            lastReferenceSeries = null; // Reset reference tracking
        }
        if (seriesChanged) {
            checkReferenceSeriesChange(); // a new reference re-fits Y on difference plots
        }
        if (seriesChanged || shapeChanged) {
            rebuildOrDefer();
        }

        // If the X-axis domain changed, X zoom can't be preserved; otherwise auto-Y keeps
        // the X zoom and fits Y, and without it the user expects to see all the new data.
        if (shapeChanged) {
            if (xAxisTypeFor(old.getPlotType()) != xAxisTypeFor(now.getPlotType())) {
                zoomToFit();
            } else if (now.isAutoYMode()) {
                fitYAxis();
            } else {
                zoomToFit();
            }
        } else if ((scaleChanged || autoYSwitchedOn) && now.isAutoYMode()) {
            fitYAxis();
        }
        repaint();
        // The entry recorded for this change holds the viewport it produced.
        model.amend(s -> s.withViewport(currentViewport));
    }

    /**
     * Applies a restored snapshot. Runs the full transform pipeline exactly ONCE against
     * the final settings and applies the snapshot's viewport, with no fit and no history
     * push. Undo on a large dataset used to pay up to five full aggregations.
     */
    private void applyRestored(FlowVizState old, FlowVizState now) {
        boolean wasRestoring = restoringState;
        restoringState = true;
        viewportCoalesceTimer.stop();
        pendingFit = Fit.NONE; // the snapshot's viewport wins over a fit owed while hidden
        try {
            if (now.getPlotType() != old.getPlotType()) {
                lastReferenceSeries = null;
            }
            if (currentViewport != null) {
                currentViewport = new ViewPort(
                    now.getStartTimeMs(), now.getEndTimeMs(),
                    now.getMinValue(), now.getMaxValue(),
                    currentViewport.getPlotX(), currentViewport.getPlotY(),
                    currentViewport.getPlotWidth(), currentViewport.getPlotHeight(),
                    now.getYAxisScale(), determineXAxisType());
            }
            refreshData(false);
        } finally {
            restoringState = wasRestoring;
        }
    }

    /**
     * Undoes the last state change. Returns the restored state, or null if at beginning.
     * The model notifies every listener of the restore, so the other pages, the toolbar
     * and the window trees follow without any further call.
     */
    public FlowVizState undo() {
        return model.undo();
    }

    /**
     * Redoes the last undone state change. Returns the restored state, or null if at end.
     * Listeners follow, as for {@link #undo()}.
     */
    public FlowVizState redo() {
        return model.redo();
    }

    public boolean canUndo() { return model.canUndo(); }
    public boolean canRedo() { return model.canRedo(); }

    /**
     * Copies the full state history from another FlowVizPanel (Chrome-style tab duplication).
     * Restores the current state including viewport so the new tab looks identical.
     */
    public void copyHistoryFrom(FlowVizPanel source) {
        model.copyFrom(source.model);
    }

    /**
     * Checks if the reference series (first visible series) has changed
     * for DIFFERENCE plot types. If so, triggers recalculation.
     */
    private void checkReferenceSeriesChange() {
        if (restoringState) {
            return; // restoreState rebuilds once at the end and applies the snapshot's viewport
        }
        // Only check if current plot type requires reference series
        if (!state().getPlotType().requiresReferenceSeries()) {
            lastReferenceSeries = null;
            return;
        }

        // Determine new reference series
        SeriesRef newReference = visibleSeries.isEmpty() ? null : visibleSeries.get(0);

        // Check if reference changed
        if (!java.util.Objects.equals(lastReferenceSeries, newReference)) {
            // Reference series changed - need to recalculate
            rebuildOrDefer();

            if (state().isAutoYMode()) {
                fitYAxis();
            }
        }
    }

    /**
     * Takes the model's display set (aggregated, masked, transformed). The model caches
     * it and notices new data by itself, so this is cheap when nothing changed; when the
     * set is new, the renderer's LOD cache is dropped so it never draws stale lines.
     */
    private void rebuildDisplayDataSet() {
        DataSet display = model.display();
        if (display != displayDataSet) {
            renderer.clearCache();
            displayDataSet = display;
        }

        // Update reference tracking
        FlowVizState state = state();
        if (state.getPlotType().requiresReferenceSeries() && !state.getVisibleSeries().isEmpty()) {
            lastReferenceSeries = state.getVisibleSeries().get(0);
        } else {
            lastReferenceSeries = null;
        }
    }

}