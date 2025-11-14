package com.kalix.ide.flowviz.stats;

import com.kalix.ide.flowviz.data.TimeSeriesData;

/**
 * Computes the mean (average) value of a time series.
 */
public class MeanStatistic implements Statistic {

    @Override
    public String getName() {
        return "Mean";
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

        Double meanValue = series.getMeanValue();
        if (meanValue == null) {
            return "-";
        }

        return String.format("%.3f", meanValue);
    }

    @Override
    public String getTooltip() {
        return "Mean (average) value of the series";
    }
}
