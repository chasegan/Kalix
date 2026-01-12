package com.kalix.ide.tableview.definitions;

import com.kalix.ide.tableview.DisplayOrientation;
import com.kalix.ide.tableview.TablePropertyDefinition;

/**
 * Table property definition for GR4J model parameters.
 * GR4J has 4 named parameters displayed vertically.
 */
public class Gr4jParamsDefinition implements TablePropertyDefinition {

    private static final String[] PARAMETER_NAMES = {
        "X1",  // Production store capacity (mm)
        "X2",  // Groundwater exchange coefficient (mm)
        "X3",  // Routing store capacity (mm)
        "X4"   // Unit hydrograph time base (days)
    };

    @Override
    public String getNodeType() {
        return "gr4j";
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
        return "GR4J Parameters";
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
