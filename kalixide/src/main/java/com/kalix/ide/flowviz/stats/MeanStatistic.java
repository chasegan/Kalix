package com.kalix.ide.flowviz.stats;

import com.kalix.ide.utils.ValueFormatUtil;

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
    public String calculate(StatSample series, StatSample reference) {
        double mean = series.mean();
        return Double.isNaN(mean) ? "-" : ValueFormatUtil.formatDataValue(mean);
    }

    @Override
    public String getTooltip() {
        return "Mean (average) value of the series";
    }
}
