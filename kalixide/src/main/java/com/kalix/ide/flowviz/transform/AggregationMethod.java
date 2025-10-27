package com.kalix.ide.flowviz.transform;

import java.util.List;

/**
 * Methods for aggregating multiple data points into a single value.
 */
public enum AggregationMethod {
    /** Sum all values in the period. */
    SUM("Sum"),

    /** Take minimum value in the period. */
    MIN("Min"),

    /** Take maximum value in the period. */
    MAX("Max"),

    /** Calculate mean (average) of values in the period. */
    MEAN("Mean");

    private final String displayName;

    AggregationMethod(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }

    /**
     * Applies the aggregation method to a list of values.
     *
     * @param values List of Y-values to aggregate
     * @return Aggregated value
     */
    public double aggregate(List<Double> values) {
        if (values == null || values.isEmpty()) {
            return Double.NaN;
        }

        return switch(this) {
            case SUM -> values.stream().mapToDouble(Double::doubleValue).sum();
            case MIN -> values.stream().mapToDouble(Double::doubleValue).min().orElse(Double.NaN);
            case MAX -> values.stream().mapToDouble(Double::doubleValue).max().orElse(Double.NaN);
            case MEAN -> values.stream().mapToDouble(Double::doubleValue).average().orElse(Double.NaN);
        };
    }

    /**
     * Parses display name to enum value.
     */
    public static AggregationMethod fromDisplayName(String displayName) {
        for (AggregationMethod method : values()) {
            if (method.displayName.equals(displayName)) {
                return method;
            }
        }
        return SUM;
    }
}
