package com.kalix.ide.flowviz.transform;

/**
 * Defines time periods for aggregating time series data.
 */
public enum AggregationPeriod {
    /** Original resolution - no aggregation. */
    ORIGINAL("Original"),

    /** Aggregate to monthly values. */
    MONTHLY("Monthly"),

    /** Annual aggregation starting in January. */
    ANNUAL_JAN_DEC("Annual (Jan-Dec)"),

    /** Annual aggregation starting in February. */
    ANNUAL_FEB_JAN("Annual (Feb-Jan)"),

    /** Annual aggregation starting in March. */
    ANNUAL_MAR_FEB("Annual (Mar-Feb)"),

    /** Annual aggregation starting in April. */
    ANNUAL_APR_MAR("Annual (Apr-Mar)"),

    /** Annual aggregation starting in May. */
    ANNUAL_MAY_APR("Annual (May-Apr)"),

    /** Annual aggregation starting in June. */
    ANNUAL_JUN_MAY("Annual (Jun-May)"),

    /** Annual aggregation starting in July. */
    ANNUAL_JUL_JUN("Annual (Jul-Jun)"),

    /** Annual aggregation starting in August. */
    ANNUAL_AUG_JUL("Annual (Aug-Jul)"),

    /** Annual aggregation starting in September. */
    ANNUAL_SEP_AUG("Annual (Sep-Aug)"),

    /** Annual aggregation starting in October. */
    ANNUAL_OCT_SEP("Annual (Oct-Sep)"),

    /** Annual aggregation starting in November. */
    ANNUAL_NOV_OCT("Annual (Nov-Oct)"),

    /** Annual aggregation starting in December. */
    ANNUAL_DEC_NOV("Annual (Dec-Nov)");

    private final String displayName;

    AggregationPeriod(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }

    /**
     * Gets the starting month (1-12) for annual periods.
     * Returns 1 for MONTHLY and ORIGINAL.
     */
    public int getStartMonth() {
        return switch(this) {
            case ORIGINAL, MONTHLY -> 1;
            case ANNUAL_JAN_DEC -> 1;
            case ANNUAL_FEB_JAN -> 2;
            case ANNUAL_MAR_FEB -> 3;
            case ANNUAL_APR_MAR -> 4;
            case ANNUAL_MAY_APR -> 5;
            case ANNUAL_JUN_MAY -> 6;
            case ANNUAL_JUL_JUN -> 7;
            case ANNUAL_AUG_JUL -> 8;
            case ANNUAL_SEP_AUG -> 9;
            case ANNUAL_OCT_SEP -> 10;
            case ANNUAL_NOV_OCT -> 11;
            case ANNUAL_DEC_NOV -> 12;
        };
    }

    /**
     * Checks if this is an annual aggregation period.
     */
    public boolean isAnnual() {
        return this != ORIGINAL && this != MONTHLY;
    }

    /**
     * Parses display name to enum value.
     */
    public static AggregationPeriod fromDisplayName(String displayName) {
        for (AggregationPeriod period : values()) {
            if (period.displayName.equals(displayName)) {
                return period;
            }
        }
        return ORIGINAL;
    }
}
