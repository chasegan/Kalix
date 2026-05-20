package com.kalix.ide.flowviz.stats;

/**
 * Computes the maximum value in a time series.
 */
public class MaxStatistic implements Statistic {

    @Override
    public String getName() {
        return "Max";
    }

    @Override
    public boolean isBivariate() {
        return false;
    }

    @Override
    public String calculate(StatSample series, StatSample reference) {
        double max = series.max();
        return Double.isNaN(max) ? "-" : String.format("%.3f", max);
    }

    @Override
    public String getTooltip() {
        return "Maximum value in the series";
    }
}
