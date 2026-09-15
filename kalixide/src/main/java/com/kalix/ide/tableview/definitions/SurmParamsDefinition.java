package com.kalix.ide.tableview.definitions;

/**
 * Table property definition for SURM model parameters.
 * SURM has 9 named parameters displayed vertically.
 */
public class SurmParamsDefinition extends AbstractVerticalParamsDefinition {

    private static final String[] PARAMETER_NAMES = {
        "imp_fraction",  // Impervious fraction of catchment (-)
        "impsc",         // Impervious initial loss (mm)
        "smsc",          // Soil moisture store capacity (mm)
        "coeff",         // Maximum infiltration rate (mm/day)
        "sq",            // Infiltration exponent (-)
        "fc",            // Field capacity (mm)
        "rfac",          // Groundwater recharge factor (-)
        "bfac",          // Baseflow factor (-)
        "sfac"           // Deep-seepage factor (-)
    };

    // The engine writes all nine values on one line; match it so a table edit
    // and a canonical save produce the same layout.
    private static final int VALUES_PER_LINE = 9;

    @Override
    public String getNodeType() {
        return "surm";
    }

    @Override
    public String getPropertyName() {
        return "params";
    }

    @Override
    protected String[] getParameterNames() {
        return PARAMETER_NAMES;
    }

    @Override
    protected int getMultiLineValuesPerLine() {
        return VALUES_PER_LINE;
    }

    @Override
    public String getWindowTitle() {
        return "SURM Parameters";
    }
}
