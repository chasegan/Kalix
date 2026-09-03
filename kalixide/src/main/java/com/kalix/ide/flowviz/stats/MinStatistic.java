package com.kalix.ide.flowviz.stats;

import com.kalix.ide.utils.ValueFormatUtil;

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
    public String calculate(StatSample series, StatSample reference) {
        double min = series.min();
        return Double.isNaN(min) ? "-" : ValueFormatUtil.formatDataValue(min);
    }

    @Override
    public String getTooltip() {
        return "Minimum value in the series";
    }
}
