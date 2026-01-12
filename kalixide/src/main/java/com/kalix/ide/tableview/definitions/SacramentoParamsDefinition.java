package com.kalix.ide.tableview.definitions;

import com.kalix.ide.tableview.DisplayOrientation;
import com.kalix.ide.tableview.TablePropertyDefinition;

/**
 * Table property definition for Sacramento model parameters.
 * Sacramento has 17 named parameters displayed vertically.
 */
public class SacramentoParamsDefinition implements TablePropertyDefinition {

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

    @Override
    public String getNodeType() {
        return "sacramento";
    }

    @Override
    public String getPropertyName() {
        return "params";
    }

    @Override
    public DisplayOrientation getOrientation() {
        return DisplayOrientation.VERTICAL;
    }

    @Override
    public String[] getColumnNames() {
        return new String[]{"Parameter", "Value"};
    }

    @Override
    public String[] getRowNames() {
        return PARAMETER_NAMES;
    }

    @Override
    public boolean isFixedRowCount() {
        return true;
    }

    private static final int VALUES_PER_LINE = 4;

    @Override
    public String[][] parseValues(String iniValue) {
        String[] values = parseNumericValues(iniValue);
        String[][] result = new String[PARAMETER_NAMES.length][1];

        for (int i = 0; i < PARAMETER_NAMES.length; i++) {
            result[i][0] = i < values.length ? values[i] : "";
        }

        return result;
    }

    @Override
    public int getValuesPerLine() {
        return VALUES_PER_LINE;
    }

    @Override
    public String validateCell(int row, int col, String value) {
        if (value == null || value.trim().isEmpty()) {
            return "Value cannot be empty";
        }
        try {
            Double.parseDouble(value.trim());
            return null;
        } catch (NumberFormatException e) {
            return "Value must be a number";
        }
    }

    @Override
    public String getWindowTitle() {
        return "Sacramento Parameters";
    }

    private String[] parseNumericValues(String iniValue) {
        if (iniValue == null || iniValue.trim().isEmpty()) {
            return new String[0];
        }
        String[] parts = iniValue.split(",");
        String[] result = new String[parts.length];
        for (int i = 0; i < parts.length; i++) {
            result[i] = parts[i].trim();
        }
        return result;
    }
}
