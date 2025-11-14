package com.kalix.ide.flowviz.stats;

import com.kalix.ide.flowviz.data.TimeSeriesData;

/**
 * Computes the percentage bias between a series and a reference series.
 * Bias% = (mean(series) - mean(reference)) / mean(reference) * 100
 * This is a bivariate statistic that compares the mean values of two series.
 */
public class BiasStatistic implements Statistic {

    @Override
    public String getName() {
        return "Bias%";
    }

    @Override
    public boolean isBivariate() {
        return true;
    }

    @Override
    public String calculate(TimeSeriesData series, TimeSeriesData reference) {
        if (series == null || reference == null) {
            return "N/A";
        }

        // Use the pre-computed mean values from TimeSeriesData
        Double seriesMean = series.getMeanValue();
        Double referenceMean = reference.getMeanValue();

        if (seriesMean == null || referenceMean == null) {
            return "N/A";
        }

        if (referenceMean == 0.0) {
            return "N/A";  // Cannot calculate percentage bias with zero reference mean
        }

        // Calculate: (mean(series) - mean(reference)) / mean(reference) * 100
        double biasPercent = ((seriesMean - referenceMean) / referenceMean) * 100.0;

        return String.format("%.3f", biasPercent);
    }

    @Override
    public String getTooltip() {
        return "Percentage bias: (mean(series) - mean(reference)) / mean(reference) * 100";
    }
}
