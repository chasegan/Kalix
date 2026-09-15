package com.kalix.ide.tableview.definitions;

/**
 * Table property definition for AWBM model parameters.
 * AWBM has 8 named parameters displayed vertically.
 */
public class AwbmParamsDefinition extends AbstractVerticalParamsDefinition {

    private static final String[] PARAMETER_NAMES = {
        "a1",      // Partial area of surface store 1 (-)
        "a2",      // Partial area of surface store 2 (-); a3 = 1 - a1 - a2
        "c1",      // Capacity of surface store 1 (mm)
        "c2",      // Capacity of surface store 2 (mm)
        "c3",      // Capacity of surface store 3 (mm)
        "bfi",     // Baseflow index (-)
        "k_base",  // Baseflow recession constant (-)
        "k_surf"   // Surface-runoff recession constant (-)
    };

    // The engine writes all eight values on one line; match it so a table edit
    // and a canonical save produce the same layout.
    private static final int VALUES_PER_LINE = 8;

    @Override
    public String getNodeType() {
        return "awbm";
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
        return "AWBM Parameters";
    }
}
