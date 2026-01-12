package com.kalix.ide.tableview.definitions;

/**
 * Table property definition for Sacramento model parameters.
 * Sacramento has 17 named parameters displayed vertically.
 */
public class SacramentoParamsDefinition extends AbstractVerticalParamsDefinition {

    private static final String[] PARAMETER_NAMES = {
        "adimp",
        "lzfpm",
        "lzfsm",
        "lzpk",
        "lzsk",
        "lztwm",
        "pctim",
        "pfree",
        "rexp",
        "sarva",
        "side",
        "ssout",
        "uzfwm",
        "uzk",
        "uztwm",
        "zperc",
        "laguh"
    };

    private static final int VALUES_PER_LINE = 4;

    @Override
    public String getNodeType() {
        return "sacramento";
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
        return "Sacramento Parameters";
    }
}
