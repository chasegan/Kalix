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
        return String.valueOf(series.rawCount());
    }

    @Override
    public String getTooltip() {
        return "Number of data points in the series";
    }
}
