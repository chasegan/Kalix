package com.kalix.ide.flowviz.stats;

import com.kalix.ide.flowviz.data.TimeSeriesData;

/**
 * Computes the number of valid data points in a time series.
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
    public String calculate(TimeSeriesData series, TimeSeriesData reference) {
        if (series == null) {
            return "0";
        }

        return String.valueOf(series.getPointCount());
    }

    @Override
    public String getTooltip() {
        return "Number of data points in the series";
    }
}
