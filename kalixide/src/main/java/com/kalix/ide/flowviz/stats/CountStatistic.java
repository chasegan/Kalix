package com.kalix.ide.flowviz.stats;

/**
 * Computes the number of data points in a time series sample.
 */
public class CountStatistic implements Statistic {

    @Override
    public String getName() {
        return "Points";
    }

    @Override
    public boolean isBivariate() {
        return false;
    }

    @Override
    public String calculate(StatSample series, StatSample reference) {
        // Valid (non-missing) points, not the raw array length: under the materialised-grid
        // representation the array includes NaN slots for missing data, which are not observations.
        return String.valueOf(series.validCount());
    }

    @Override
    public String getTooltip() {
        return "Number of valid (non-missing) data points in the series";
    }
}
