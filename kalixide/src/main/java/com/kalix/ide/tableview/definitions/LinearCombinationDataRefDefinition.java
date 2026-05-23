package com.kalix.ide.tableview.definitions;

import com.kalix.ide.tableview.DisplayOrientation;
import com.kalix.ide.tableview.LinearCombinationParser;
import com.kalix.ide.tableview.LinearCombinationParser.Term;
import com.kalix.ide.tableview.TablePropertyDefinition;
import com.kalix.ide.tableview.TableValueFormatter;

import java.util.ArrayList;
import java.util.List;

/**
 * A {@link TablePropertyDefinition} for property values that are linear
 * combinations of data references, e.g.
 * {@code rain = 0.5 * data.foo + 0.3 * data.bar}.
 *
 * <p>The table has two columns: <b>Coefficient</b> (numeric, narrow) and
 * <b>Data reference</b> (string, wide). Rows can be added and removed. The
 * value is parsed and re-emitted via {@link LinearCombinationParser} so the
 * grammar - including {@code [a,b]} lookback suffixes on data refs - is
 * defined in exactly one place.</p>
 *
 * <p>One instance is registered per supporting node type. The grammar and
 * layout are identical across node types; constructor parameters let one
 * class cover them all without subclassing.</p>
 */
public final class LinearCombinationDataRefDefinition implements TablePropertyDefinition {

    private static final String[] COLUMN_NAMES = {"Coefficient", "Data reference"};

    private final String nodeType;
    private final String propertyName;

    public LinearCombinationDataRefDefinition(String nodeType, String propertyName) {
        this.nodeType = nodeType;
        this.propertyName = propertyName;
    }

    @Override public String getNodeType() { return nodeType; }
    @Override public String getPropertyName() { return propertyName; }
    @Override public DisplayOrientation getOrientation() { return DisplayOrientation.HORIZONTAL; }
    @Override public String[] getColumnNames() { return COLUMN_NAMES.clone(); }
    @Override public String[] getRowNames() { return null; }  // dynamic rows
    @Override public boolean isFixedRowCount() { return false; }
    @Override public int getValuesPerLine() { return COLUMN_NAMES.length; }

    @Override
    public boolean canHandleValue(String value) {
        return LinearCombinationParser.canParse(value);
    }

    @Override
    public String[][] parseValues(String iniValue) {
        List<Term> terms = LinearCombinationParser.parse(iniValue);
        if (terms == null) {
            // Should not happen in practice: canHandleValue gates the menu so
            // we only reach this method for a value that previously parsed.
            return new String[0][COLUMN_NAMES.length];
        }
        String[][] rows = new String[terms.size()][COLUMN_NAMES.length];
        for (int i = 0; i < terms.size(); i++) {
            Term t = terms.get(i);
            rows[i][0] = LinearCombinationParser.formatCoefficient(t.coefficient);
            rows[i][1] = t.dataRef;
        }
        return rows;
    }

    @Override
    public String formatValues(String[][] values, boolean multiLine,
                               TableValueFormatter formatter, int continuationIndent) {
        List<Term> terms = new ArrayList<>(values.length);
        for (String[] row : values) {
            if (row.length < COLUMN_NAMES.length) {
                continue;
            }
            String coefText = row[0] == null ? "" : row[0].trim();
            String refText = row[1] == null ? "" : row[1].trim();
            if (coefText.isEmpty() || refText.isEmpty()) {
                continue;
            }
            double coef;
            try {
                coef = Double.parseDouble(coefText);
            } catch (NumberFormatException e) {
                // Cell-level validation should normally catch this; skip the
                // row defensively rather than crashing the format step.
                continue;
            }
            terms.add(new Term(coef, refText));
        }
        return multiLine
                ? LinearCombinationParser.formatMultiLine(terms, continuationIndent)
                : LinearCombinationParser.formatInline(terms);
    }

    @Override
    public String validateCell(int row, int col, String value) {
        if (value == null || value.trim().isEmpty()) {
            return col == 0 ? "Coefficient is required" : "Data reference is required";
        }
        String trimmed = value.trim();
        if (col == 0) {
            try {
                Double.parseDouble(trimmed);
                return null;
            } catch (NumberFormatException e) {
                return "Coefficient must be a number";
            }
        }
        if (col == 1) {
            return trimmed.startsWith("data.")
                    ? null
                    : "Data reference must start with 'data.'";
        }
        return null;
    }
}
