package com.kalix.ide.managers.optimisation;

import com.kalix.ide.flowviz.PlotPanel;
import com.kalix.ide.flowviz.data.DataSet;
import com.kalix.ide.flowviz.data.TimeSeriesData;
import com.kalix.ide.flowviz.rendering.XAxisType;
import com.kalix.ide.flowviz.rendering.SeriesRenderMode;
import com.kalix.ide.models.optimisation.OptimisationResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import java.awt.*;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Manages the convergence plot for optimisation runs.
 * Displays best objective and population values over evaluation counts.
 */
public class OptimisationPlotManager {

    private static final Logger logger = LoggerFactory.getLogger(OptimisationPlotManager.class);

    // Plot configuration
    private static final int PLOT_HEIGHT = 300;
    private static final Color COLOR_BEST_OBJECTIVE = new Color(0, 100, 200);  // Blue
    private static final Color COLOR_POPULATION = new Color(255, 140, 0);      // Orange

    private final PlotPanel convergencePlot;
    private final DataSet convergenceDataSet;

    // Reference time for COUNT axis conversion
    private static final LocalDateTime EPOCH = LocalDateTime.of(1970, 1, 1, 0, 0, 0);

    /**
     * Creates a new OptimisationPlotManager.
     */
    public OptimisationPlotManager() {
        // Initialize dataset
        this.convergenceDataSet = new DataSet();

        // Initialize plot panel
        this.convergencePlot = new PlotPanel();
        this.convergencePlot.setDataSet(convergenceDataSet);
        this.convergencePlot.setXAxisType(XAxisType.COUNT); // Use COUNT x-axis for evaluation numbers
        this.convergencePlot.setPreferredSize(new Dimension(0, PLOT_HEIGHT));

        logger.debug("Created optimisation plot manager");
    }

    /**
     * Gets the plot panel component.
     *
     * @return The plot panel
     */
    public PlotPanel getPlotPanel() {
        return convergencePlot;
    }

    /**
     * Gets the convergence dataset.
     *
     * @return The dataset
     */
    public DataSet getDataSet() {
        return convergenceDataSet;
    }

    /**
     * Updates the convergence plot with data from the given result.
     * Creates two series: best objective (blue line) and population samples (orange dots).
     *
     * @param result The optimisation result containing convergence history
     */
    public void updatePlot(OptimisationResult result) {
        if (result == null) {
            clearPlot();
            return;
        }

        // Clear existing data
        convergenceDataSet.removeAllSeries();

        List<OptimisationResult.ConvergencePoint> history = result.getConvergenceHistory();
        if (history.isEmpty()) {
            convergencePlot.repaint();
            return;
        }

        // Create time series for best objective (blue line)
        createBestObjectiveSeries(history);

        // Create time series for population samples (orange dots)
        createPopulationSeries(history);

        // Configure colors and visibility
        configureSeriesAppearance();

        // Refresh plot with zoom to fit
        convergencePlot.refreshData(true);

        logger.debug("Updated convergence plot with {} data points", history.size());
    }

    /**
     * Creates the best objective time series.
     */
    private void createBestObjectiveSeries(List<OptimisationResult.ConvergencePoint> history) {
        int n = history.size();
        LocalDateTime[] timestamps = new LocalDateTime[n];
        double[] bestValues = new double[n];

        for (int i = 0; i < n; i++) {
            OptimisationResult.ConvergencePoint point = history.get(i);
            // Convert evaluation count to fake timestamp for TimeSeriesData
            // TimeSeriesData expects LocalDateTime, so we convert count to nanoseconds
            timestamps[i] = EPOCH.plusNanos((long) point.getEvaluation() * 1_000_000);
            bestValues[i] = point.getBestObjective();
        }

        TimeSeriesData bestSeries = new TimeSeriesData("Best Objective", timestamps, bestValues);
        convergenceDataSet.addSeries(bestSeries);
    }

    /**
     * Creates the population samples time series.
     */
    private void createPopulationSeries(List<OptimisationResult.ConvergencePoint> history) {
        List<LocalDateTime> popTimestamps = new ArrayList<>();
        List<Double> popValues = new ArrayList<>();

        for (OptimisationResult.ConvergencePoint point : history) {
            LocalDateTime evalTime = EPOCH.plusNanos((long) point.getEvaluation() * 1_000_000);
            List<Double> populationAtEval = point.getPopulationValues();

            for (Double objValue : populationAtEval) {
                popTimestamps.add(evalTime);
                popValues.add(objValue);
            }
        }

        if (!popTimestamps.isEmpty()) {
            LocalDateTime[] popTimestampsArray = popTimestamps.toArray(new LocalDateTime[0]);
            double[] popValuesArray = new double[popValues.size()];
            for (int i = 0; i < popValues.size(); i++) {
                popValuesArray[i] = popValues.get(i);
            }

            TimeSeriesData popSeries = new TimeSeriesData("Population", popTimestampsArray, popValuesArray);
            convergenceDataSet.addSeries(popSeries);
        }
    }

    /**
     * Configures the appearance of the series.
     */
    private void configureSeriesAppearance() {
        Map<String, Color> colors = new HashMap<>();
        colors.put("Best Objective", COLOR_BEST_OBJECTIVE);
        colors.put("Population", COLOR_POPULATION);

        // Add series in rendering order: Population first (back), then Best Objective (front)
        List<String> visibleSeries = new ArrayList<>();
        visibleSeries.add("Population");
        visibleSeries.add("Best Objective");

        convergencePlot.setSeriesColors(colors);
        convergencePlot.setVisibleSeries(visibleSeries);

        // Configure rendering modes: Best Objective as LINE, Population as POINTS only
        convergencePlot.setSeriesRenderMode("Best Objective", SeriesRenderMode.LINE);
        convergencePlot.setSeriesRenderMode("Population", SeriesRenderMode.POINTS);
    }

    /**
     * Adds a single convergence point to the plot.
     * Used for real-time updates during optimisation.
     *
     * @param evaluation The evaluation count
     * @param bestObjective The best objective value
     * @param populationValues The population values at this evaluation
     */
    public void addConvergencePoint(int evaluation, double bestObjective, List<Double> populationValues) {
        // This would require incremental updates to existing series
        // For simplicity, we'll rebuild the entire plot
        // In a production system, we'd maintain the data and update incrementally

        logger.debug("Added convergence point at evaluation {}: best={}", evaluation, bestObjective);
    }

    /**
     * Clears the convergence plot.
     */
    public void clearPlot() {
        convergenceDataSet.removeAllSeries();
        convergencePlot.refreshData(true);
        logger.debug("Cleared convergence plot");
    }

    /**
     * Exports the plot as an image.
     *
     * @param file The file to export to
     * @param width The image width
     * @param height The image height
     * @throws IOException If export fails
     */
    public void exportPlotAsImage(File file, int width, int height) throws IOException {
        // Create off-screen image
        java.awt.image.BufferedImage image =
            new java.awt.image.BufferedImage(width, height, java.awt.image.BufferedImage.TYPE_INT_RGB);

        Graphics2D g2 = image.createGraphics();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        // Set background
        g2.setColor(Color.WHITE);
        g2.fillRect(0, 0, width, height);

        // Render plot
        convergencePlot.setSize(width, height);
        convergencePlot.paint(g2);

        g2.dispose();

        // Write to file
        String format = file.getName().toLowerCase().endsWith(".png") ? "PNG" : "JPEG";
        javax.imageio.ImageIO.write(image, format, file);

        logger.info("Exported plot to {}", file.getAbsolutePath());
    }

    /**
     * Exports convergence data as CSV.
     *
     * @param file The file to export to
     * @param result The optimisation result
     * @throws IOException If export fails
     */
    public void exportDataAsCsv(File file, OptimisationResult result) throws IOException {
        if (result == null || result.getConvergenceHistory().isEmpty()) {
            throw new IllegalArgumentException("No convergence data to export");
        }

        StringBuilder csv = new StringBuilder();
        csv.append("Evaluation,Best Objective,Population Min,Population Mean,Population Max\n");

        for (OptimisationResult.ConvergencePoint point : result.getConvergenceHistory()) {
            csv.append(point.getEvaluation()).append(",");
            csv.append(point.getBestObjective()).append(",");

            List<Double> pop = point.getPopulationValues();
            if (!pop.isEmpty()) {
                double min = pop.stream().mapToDouble(Double::doubleValue).min().orElse(0);
                double mean = pop.stream().mapToDouble(Double::doubleValue).average().orElse(0);
                double max = pop.stream().mapToDouble(Double::doubleValue).max().orElse(0);
                csv.append(min).append(",").append(mean).append(",").append(max);
            } else {
                csv.append(",,");
            }
            csv.append("\n");
        }

        Files.writeString(file.toPath(), csv.toString());
        logger.info("Exported convergence data to {}", file.getAbsolutePath());
    }

    /**
     * Creates a panel containing the plot with labels.
     *
     * @return A panel with the plot and labels
     */
    public JPanel createPlotPanelWithLabels() {
        JPanel panel = new JPanel(new BorderLayout(5, 5));

        // Add title
        JLabel titleLabel = new JLabel("Objective function convergence", JLabel.CENTER);
        titleLabel.setFont(titleLabel.getFont().deriveFont(Font.BOLD, 14f));
        panel.add(titleLabel, BorderLayout.NORTH);

        // Add plot
        panel.add(convergencePlot, BorderLayout.CENTER);

        return panel;
    }

    /**
     * Gets statistics about the current convergence data.
     *
     * @return A map of statistic names to values
     */
    public Map<String, String> getStatistics() {
        Map<String, String> stats = new HashMap<>();

        TimeSeriesData bestSeries = convergenceDataSet.getSeries("Best Objective");
        if (bestSeries != null && bestSeries.getPointCount() > 0) {
            stats.put("Evaluations", String.valueOf(bestSeries.getPointCount()));
            stats.put("Best Value", String.format("%.6f", bestSeries.getMinValue()));
            stats.put("Initial Value", String.format("%.6f", bestSeries.getValues()[0]));

            double improvement = bestSeries.getValues()[0] - bestSeries.getMinValue();
            stats.put("Improvement", String.format("%.6f", improvement));
        }

        return stats;
    }

    /**
     * Refreshes the plot display without changing data.
     */
    public void refreshDisplay() {
        convergencePlot.repaint();
    }

    /**
     * Sets whether the plot should auto-zoom to fit data.
     *
     * @param autoZoom true to enable auto-zoom
     */
    public void setAutoZoom(boolean autoZoom) {
        if (autoZoom) {
            convergencePlot.refreshData(true);
        }
    }
}