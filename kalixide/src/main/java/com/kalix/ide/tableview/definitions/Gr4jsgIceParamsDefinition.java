package com.kalix.ide.tableview.definitions;

/**
 * Table property definition for the GR4JSG node's glacier parameters.
 * Five named parameters displayed vertically.
 */
public class Gr4jsgIceParamsDefinition extends AbstractVerticalParamsDefinition {

    private static final String[] PARAMETER_NAMES = {
        "initial_ice",  // Ice store at the start of the run (mm)
        "ddfi",         // Ice degree-day factor (mm/deg C/day)
        "tmelt",        // Ice melts above this temperature (deg C)
        "return_flow",  // Time base of the ice-melt unit hydrograph (days)
        "accumulation"  // Constant gain to the ice store (mm/day)
    };

    // The engine writes the values on one line.
    private static final int VALUES_PER_LINE = 5;

    @Override
    public String getNodeType() {
        return "gr4jsg";
    }

    @Override
    public String getPropertyName() {
        return "ice_params";
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
        return "GR4JSG Glacier Parameters";
    }
}
