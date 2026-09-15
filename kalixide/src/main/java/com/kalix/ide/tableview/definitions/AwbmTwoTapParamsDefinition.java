package com.kalix.ide.tableview.definitions;

/**
 * Table property definition for the AWBM two-tap variant's parameters
 * ({@code variant = two_tap}). Eleven named parameters displayed vertically.
 */
public class AwbmTwoTapParamsDefinition extends AbstractVerticalParamsDefinition {

    static final int PARAMETER_COUNT = 11;

    private static final String[] PARAMETER_NAMES = {
        "a1",        // Partial area of surface store 1 (-)
        "a2",        // Partial area of surface store 2 (-); a3 = 1 - a1 - a2
        "c1",        // Capacity of surface store 1 (mm, times cap_ave)
        "c2",        // Capacity of surface store 2 (mm, times cap_ave)
        "c3",        // Capacity of surface store 3 (mm, times cap_ave)
        "inf_base",  // Recharge fraction below gw_sat (-)
        "gw_sat",    // Groundwater depth where the recharge fraction starts to fall (mm)
        "gw_max",    // Groundwater depth where the recharge fraction reaches zero (mm)
        "k_base",    // Lower-tap retention per day (-)
        "k2",        // Upper-tap retention per day (-)
        "h_gw"       // Depth of the upper tap (mm)
    };

    // The engine writes all eleven values on one line; match it so a table edit
    // and a canonical save produce the same layout.
    private static final int VALUES_PER_LINE = PARAMETER_COUNT;

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
        return "AWBM Two-Tap Parameters";
    }

    /** Claims only an eleven-value line; the standard definition takes the rest. */
    @Override
    public boolean canHandleValue(String value) {
        return parseNumericValues(value).length == PARAMETER_COUNT;
    }
}
