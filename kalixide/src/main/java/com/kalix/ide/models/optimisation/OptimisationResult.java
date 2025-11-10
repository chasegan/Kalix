package com.kalix.ide.models.optimisation;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Represents the result of an optimisation run.
 * Contains both final results and convergence history for visualization.
 */
public class OptimisationResult {

    // Core result data
    private Double bestObjective;
    private Integer evaluations;
    private Integer generations;
    private String message;
    private boolean success;

    // Parameter values (both physical and normalized)
    private Map<String, Double> parametersPhysical = new HashMap<>();
    private Map<String, Double> parametersNormalized = new HashMap<>();

    // The optimised model with updated parameters
    private String optimisedModelIni;

    // Progress tracking
    private Integer currentProgress;
    private String progressDescription;

    // Convergence data for plotting
    private final List<ConvergencePoint> convergenceHistory = new ArrayList<>();

    // Metadata
    private String configurationUsed;
    private LocalDateTime startTime;
    private LocalDateTime endTime;

    /**
     * Represents a single point in the convergence history.
     */
    public static class ConvergencePoint {
        private final int evaluation;
        private final double bestObjective;
        private final List<Double> populationValues;

        public ConvergencePoint(int evaluation, double bestObjective, List<Double> populationValues) {
            this.evaluation = evaluation;
            this.bestObjective = bestObjective;
            this.populationValues = new ArrayList<>(populationValues);
        }

        public int getEvaluation() { return evaluation; }
        public double getBestObjective() { return bestObjective; }
        public List<Double> getPopulationValues() { return new ArrayList<>(populationValues); }
    }

    // Getters and setters
    public Double getBestObjective() {
        return bestObjective;
    }

    public void setBestObjective(Double bestObjective) {
        this.bestObjective = bestObjective;
    }

    public Integer getEvaluations() {
        return evaluations;
    }

    public void setEvaluations(Integer evaluations) {
        this.evaluations = evaluations;
    }

    public Integer getGenerations() {
        return generations;
    }

    public void setGenerations(Integer generations) {
        this.generations = generations;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public boolean isSuccess() {
        return success;
    }

    public void setSuccess(boolean success) {
        this.success = success;
    }

    public Map<String, Double> getParametersPhysical() {
        return new HashMap<>(parametersPhysical);
    }

    public void setParametersPhysical(Map<String, Double> parameters) {
        this.parametersPhysical = new HashMap<>(parameters);
    }

    public Map<String, Double> getParametersNormalized() {
        return new HashMap<>(parametersNormalized);
    }

    public void setParametersNormalized(Map<String, Double> parameters) {
        this.parametersNormalized = new HashMap<>(parameters);
    }

    public String getOptimisedModelIni() {
        return optimisedModelIni;
    }

    public void setOptimisedModelIni(String optimisedModelIni) {
        this.optimisedModelIni = optimisedModelIni;
    }

    public Integer getCurrentProgress() {
        return currentProgress;
    }

    public void setCurrentProgress(Integer currentProgress) {
        this.currentProgress = currentProgress;
    }

    public String getProgressDescription() {
        return progressDescription;
    }

    public void setProgressDescription(String progressDescription) {
        this.progressDescription = progressDescription;
    }

    public List<ConvergencePoint> getConvergenceHistory() {
        return new ArrayList<>(convergenceHistory);
    }

    public void addConvergencePoint(int evaluation, double bestObjective, List<Double> populationValues) {
        convergenceHistory.add(new ConvergencePoint(evaluation, bestObjective, populationValues));
    }

    public void clearConvergenceHistory() {
        convergenceHistory.clear();
    }

    public String getConfigurationUsed() {
        return configurationUsed;
    }

    public void setConfigurationUsed(String configurationUsed) {
        this.configurationUsed = configurationUsed;
    }

    public LocalDateTime getStartTime() {
        return startTime;
    }

    public void setStartTime(LocalDateTime startTime) {
        this.startTime = startTime;
    }

    public LocalDateTime getEndTime() {
        return endTime;
    }

    public void setEndTime(LocalDateTime endTime) {
        this.endTime = endTime;
    }

    /**
     * Calculates the elapsed time for the optimisation.
     *
     * @return Duration between start and end time, or null if times not set
     */
    public Duration getElapsedTime() {
        if (startTime != null && endTime != null) {
            return Duration.between(startTime, endTime);
        }
        return null;
    }

    /**
     * Gets the list of evaluation counts from convergence history.
     * This is a convenience method for backwards compatibility.
     *
     * @return List of evaluation counts
     */
    public List<Integer> getConvergenceEvaluations() {
        List<Integer> evaluations = new ArrayList<>();
        for (ConvergencePoint point : convergenceHistory) {
            evaluations.add(point.getEvaluation());
        }
        return evaluations;
    }

    /**
     * Gets the list of best objective values from convergence history.
     * This is a convenience method for backwards compatibility.
     *
     * @return List of best objective values
     */
    public List<Double> getConvergenceBestObjective() {
        List<Double> objectives = new ArrayList<>();
        for (ConvergencePoint point : convergenceHistory) {
            objectives.add(point.getBestObjective());
        }
        return objectives;
    }

    /**
     * Gets the list of population values from convergence history.
     * This is a convenience method for backwards compatibility.
     *
     * @return List of lists of population values
     */
    public List<List<Double>> getConvergencePopulation() {
        List<List<Double>> population = new ArrayList<>();
        for (ConvergencePoint point : convergenceHistory) {
            population.add(point.getPopulationValues());
        }
        return population;
    }

    /**
     * Formats the result as a human-readable summary.
     *
     * @return Formatted string summary of the optimisation result
     */
    public String formatSummary() {
        StringBuilder sb = new StringBuilder();
        sb.append("=== OPTIMISATION RESULT ===\n");
        sb.append(String.format("Status: %s\n", success ? "SUCCESS" : "FAILED"));

        if (bestObjective != null) {
            sb.append(String.format("Best Objective: %.8f\n", bestObjective));
        }

        if (evaluations != null) {
            sb.append(String.format("Evaluations: %d\n", evaluations));
        }

        if (generations != null) {
            sb.append(String.format("Generations: %d\n", generations));
        }

        if (message != null) {
            sb.append(String.format("Message: %s\n", message));
        }

        Duration elapsed = getElapsedTime();
        if (elapsed != null) {
            long hours = elapsed.toHours();
            long minutes = elapsed.toMinutesPart();
            long seconds = elapsed.toSecondsPart();
            sb.append(String.format("Elapsed Time: %02d:%02d:%02d\n", hours, minutes, seconds));
        }

        sb.append("\nOptimized Parameters:\n");
        if (!parametersPhysical.isEmpty()) {
            parametersPhysical.forEach((param, value) -> {
                sb.append(String.format("  %s = %.8f\n", param, value));
            });
        }

        sb.append("=========================\n");
        return sb.toString();
    }

    @Override
    public String toString() {
        return String.format("OptimisationResult[success=%s, bestObjective=%s, evaluations=%d]",
                success, bestObjective, evaluations);
    }
}