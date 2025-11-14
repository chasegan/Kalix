package com.kalix.ide.flowviz.stats;

import com.kalix.ide.flowviz.data.TimeSeriesData;

/**
 * Computes the minimum value in a time series.
 */
public class MinStatistic implements Statistic {

    @Override
    public String getName() {
        return "Min";
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

        Double minValue = series.getMinValue();
        if (minValue == null) {
            return "-";
        }

        return String.format("%.3f", minValue);
    }

    @Override
    public String getTooltip() {
        return "Minimum value in the series";
    }
}
