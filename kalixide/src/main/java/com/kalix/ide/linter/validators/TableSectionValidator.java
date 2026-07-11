package com.kalix.ide.linter.validators;

import com.kalix.ide.linter.LinterSchema;
import com.kalix.ide.linter.parsing.INIModelParser;
import com.kalix.ide.linter.model.ValidationResult;
import com.kalix.ide.linter.model.ValidationRule;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Validates [table.*] lookup table sections.
 *
 * <p>Mirrors the engine's load-time rules (src/numerical/lookup_table.rs) so a
 * modeller sees the problem in the editor rather than at model load:</p>
 * <ul>
 *   <li>Table names: lowercase letters, digits, underscores; no dots</li>
 *   <li>Allowed properties: {@code values} (required) and {@code n_cols} (integer &ge; 2, default 2)</li>
 *   <li>1D (n_cols = 2): rows of (x, y) with an optional two-label text header;
 *       x values strictly ascending</li>
 *   <li>2D (n_cols &gt; 2): non-numeric corner marker, then column keys; each row is
 *       a row key plus values; both key sets strictly ascending; total value count
 *       a multiple of n_cols</li>
 *   <li>All cells finite numbers (nan/inf rejected)</li>
 * </ul>
 */
public class TableSectionValidator implements ValidationStrategy {

    private static final Pattern VALID_TABLE_NAME = Pattern.compile("^[a-z][a-z0-9_]*$");

    @Override
    public void validate(INIModelParser.ParsedModel model, LinterSchema schema, ValidationResult result, java.io.File baseDirectory) {
        for (Map.Entry<String, INIModelParser.Section> entry : model.getSections().entrySet()) {
            String sectionName = entry.getKey();
            if (sectionName.startsWith("table.")) {
                validateTableSection(sectionName, entry.getValue(), result);
            }
        }
    }

    @Override
    public String getDescription() {
        return "Lookup table section validation";
    }

    private void validateTableSection(String sectionName, INIModelParser.Section section, ValidationResult result) {
        String tableName = sectionName.substring("table.".length());

        if (!VALID_TABLE_NAME.matcher(tableName).matches()) {
            result.addIssue(section.getStartLine(),
                    "Invalid table name: '" + tableName + "' (use lowercase letters, digits and underscores; no dots)",
                    ValidationRule.Severity.ERROR, "invalid_table_name");
        }

        // Properties: only n_cols and values are recognised
        int nCols = 2;
        INIModelParser.Property valuesProp = null;
        for (INIModelParser.Property prop : section.getProperties().values()) {
            switch (prop.getKey()) {
                case "n_cols":
                    try {
                        nCols = Integer.parseInt(prop.getValue().trim());
                        if (nCols < 2) {
                            result.addIssue(prop.getLineNumber(),
                                    "n_cols must be at least 2, got " + nCols,
                                    ValidationRule.Severity.ERROR, "invalid_table_ncols");
                            return;
                        }
                    } catch (NumberFormatException e) {
                        result.addIssue(prop.getLineNumber(),
                                "n_cols must be an integer, got '" + prop.getValue() + "'",
                                ValidationRule.Severity.ERROR, "invalid_table_ncols");
                        return;
                    }
                    break;
                case "values":
                    valuesProp = prop;
                    break;
                default:
                    result.addIssue(prop.getLineNumber(),
                            "Unexpected property '" + prop.getKey() + "' in [" + sectionName
                                    + "] (allowed: values, n_cols)",
                            ValidationRule.Severity.ERROR, "unexpected_table_property");
                    break;
            }
        }

        if (valuesProp == null) {
            result.addIssue(section.getStartLine(),
                    "Table '" + tableName + "' has no 'values' property",
                    ValidationRule.Severity.ERROR, "missing_table_values");
            return;
        }

        for (String error : checkTableValues(tableName, valuesProp.getValue(), nCols)) {
            result.addIssue(valuesProp.getLineNumber(), error,
                    ValidationRule.Severity.ERROR, "invalid_table_values");
        }
    }

    /**
     * Validate a table's joined values string against the engine's structural
     * rules. Returns error messages, empty if the values are well-formed.
     * Package-private for tests.
     */
    static List<String> checkTableValues(String tableName, String values, int nCols) {
        List<String> errors = new ArrayList<>();

        String trimmed = stripTrailingCommaAndWhitespace(values);
        if (trimmed.trim().isEmpty()) {
            errors.add("Table '" + tableName + "': values is empty");
            return errors;
        }
        String[] tokens = trimmed.split(",");
        for (int i = 0; i < tokens.length; i++) {
            tokens[i] = tokens[i].trim();
        }

        if (nCols == 2) {
            check1d(tableName, tokens, errors);
        } else {
            check2d(tableName, tokens, nCols, errors);
        }
        return errors;
    }

    private static void check1d(String tableName, String[] tokens, List<String> errors) {
        // Optional text header: first two cells both non-numeric
        int bodyStart = 0;
        if (!isFiniteNumber(tokens[0])) {
            if (tokens.length < 2 || isFiniteNumber(tokens[1])) {
                errors.add("Table '" + tableName + "': a 1D table header must have exactly 2 non-numeric labels");
                return;
            }
            bodyStart = 2;
        }

        int bodyLength = tokens.length - bodyStart;
        if (bodyLength == 0) {
            errors.add("Table '" + tableName + "': no data rows");
            return;
        }
        if (bodyLength % 2 != 0) {
            errors.add("Table '" + tableName + "': a 1D table needs an even number of values (rows of x, y), got " + bodyLength);
            return;
        }

        double prevX = Double.NEGATIVE_INFINITY;
        for (int row = 0; row < bodyLength / 2; row++) {
            Double x = parseCell(tableName, tokens[bodyStart + row * 2], errors);
            Double y = parseCell(tableName, tokens[bodyStart + row * 2 + 1], errors);
            if (x == null || y == null) {
                return;
            }
            if (x <= prevX) {
                errors.add("Table '" + tableName + "': x values must be strictly ascending, but " + x + " follows " + prevX);
                return;
            }
            prevX = x;
        }
    }

    private static void check2d(String tableName, String[] tokens, int nCols, List<String> errors) {
        if (isFiniteNumber(tokens[0])) {
            errors.add("Table '" + tableName + "': a 2D table must start with a non-numeric corner label (e.g. 'y\\x') followed by its column keys, got '" + tokens[0] + "'");
            return;
        }
        if (tokens.length % nCols != 0) {
            errors.add("Table '" + tableName + "': number of values (" + tokens.length + ") must be a multiple of n_cols (" + nCols + ")");
            return;
        }
        int nRows = tokens.length / nCols - 1;
        if (nRows < 1) {
            errors.add("Table '" + tableName + "': no data rows after the column-key row");
            return;
        }

        // Column keys, strictly ascending
        double prevKey = Double.NEGATIVE_INFINITY;
        for (int c = 1; c < nCols; c++) {
            Double key = parseCell(tableName, tokens[c], errors);
            if (key == null) {
                return;
            }
            if (key <= prevKey) {
                errors.add("Table '" + tableName + "': column keys must be strictly ascending, but " + key + " follows " + prevKey);
                return;
            }
            prevKey = key;
        }

        // Rows: row key (strictly ascending) plus values
        double prevRowKey = Double.NEGATIVE_INFINITY;
        for (int r = 0; r < nRows; r++) {
            int rowStart = (r + 1) * nCols;
            Double rowKey = parseCell(tableName, tokens[rowStart], errors);
            if (rowKey == null) {
                return;
            }
            if (rowKey <= prevRowKey) {
                errors.add("Table '" + tableName + "': row keys must be strictly ascending, but " + rowKey + " follows " + prevRowKey);
                return;
            }
            prevRowKey = rowKey;
            for (int c = 1; c < nCols; c++) {
                if (parseCell(tableName, tokens[rowStart + c], errors) == null) {
                    return;
                }
            }
        }
    }

    private static Double parseCell(String tableName, String token, List<String> errors) {
        if (!isFiniteNumber(token)) {
            errors.add("Table '" + tableName + "': could not parse '" + token + "' as a finite number");
            return null;
        }
        return Double.parseDouble(token);
    }

    /** Finite numbers only: nan/inf spellings read as text, matching the engine. */
    private static boolean isFiniteNumber(String token) {
        try {
            return Double.isFinite(Double.parseDouble(token));
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static String stripTrailingCommaAndWhitespace(String s) {
        int end = s.length();
        while (end > 0) {
            char ch = s.charAt(end - 1);
            if (ch == ',' || Character.isWhitespace(ch)) {
                end--;
            } else {
                break;
            }
        }
        return s.substring(0, end);
    }
}
