package com.kalix.ide.tableview.definitions;

/**
 * Table property definition for the GR4JSG node's GR4J parameters.
 * Four named parameters displayed vertically.
 */
public class Gr4jsgParamsDefinition extends AbstractVerticalParamsDefinition {

    private static final String[] PARAMETER_NAMES = {
        "x1", // Production store capacity (mm)
        "x2", // Groundwater exchange coefficient (mm)
        "x3", // Routing store capacity (mm)
        "x4"  // Unit hydrograph time base (days)
    };

    // The engine writes the values on one line.
    private static final int VALUES_PER_LINE = 4;

    @Override
    public String getNodeType() {
        return "gr4jsg";
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
        return "GR4JSG Parameters";
    }
}
