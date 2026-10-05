package com.kalix.ide.tableview.definitions;

/**
 * Table property definition for the GR4JSG node's snow parameters.
 * Five named parameters displayed vertically.
 */
public class Gr4jsgSnowParamsDefinition extends AbstractVerticalParamsDefinition {

    private static final String[] PARAMETER_NAMES = {
        "tfrac",         // Weight on tmax in the representative temperature (-)
        "taccum",        // Precipitation is snow below this temperature (deg C)
        "m_rainfall",    // Rain-on-snow melt rate (mm/deg C/day)
        "base_rainfall", // Rain-on-snow base melt (mm/day)
        "m_nonrainfall"  // Melt rate on a day without rain (mm/deg C/day)
    };

    // The engine writes the values on one line.
    private static final int VALUES_PER_LINE = 5;

    @Override
    public String getNodeType() {
        return "gr4jsg";
    }

    @Override
    public String getPropertyName() {
        return "snow_params";
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
        return "GR4JSG Snow Parameters";
    }
}
