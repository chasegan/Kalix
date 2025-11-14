package com.kalix.ide.flowviz.stats;

import com.kalix.ide.flowviz.data.TimeSeriesData;

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
    public String calculate(TimeSeriesData series, TimeSeriesData reference) {
        if (series == null || series.getPointCount() == 0) {
            return "-";
        }

        Double maxValue = series.getMaxValue();
        if (maxValue == null) {
            return "-";
        }

        return String.format("%.3f", maxValue);
    }

    @Override
    public String getTooltip() {
        return "Maximum value in the series";
    }
}
