package com.kalix.ide.tableview.definitions;

/**
 * Table property definition for GR4J model parameters.
 * GR4J has 4 named parameters displayed vertically.
 */
public class Gr4jParamsDefinition extends AbstractVerticalParamsDefinition {

    private static final String[] PARAMETER_NAMES = {
        "X1",  // Production store capacity (mm)
        "X2",  // Groundwater exchange coefficient (mm)
        "X3",  // Routing store capacity (mm)
        "X4"   // Unit hydrograph time base (days)
    };

    private static final int VALUES_PER_LINE = 4;

    @Override
    public String getNodeType() {
        return "gr4j";
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
        return "GR4J Parameters";
    }
}
