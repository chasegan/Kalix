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
 * Validates [crop.*] sections: a crop declared once and referenced by name
 * from a field's {@code fallow} and {@code crop_N} properties.
 *
 * <p>Mirrors the engine's load-time rules (src/hydrology/crop.rs and the
 * crop pre-pass in src/io/ini_model_io_versions/ini_doc_model_io_0_0_1.rs)
 * so a modeller sees the problem in the editor rather than at model load:</p>
 * <ul>
 *   <li>Crop names: a lowercase letter, then lowercase letters, digits and underscores</li>
 *   <li>Allowed properties: {@code root_depth} (required, positive mm), {@code p}
 *       (0 &le; p &lt; 1, default 0.5), {@code kc} (required: a non-negative number,
 *       or a two-column table of days since planting and kc, days non-negative and
 *       increasing, an optional two-label text header), {@code season_len}
 *       (optional whole number of days, at least 1)</li>
 * </ul>
 */
public class CropSectionValidator implements ValidationStrategy {

    private static final Pattern VALID_CROP_NAME = Pattern.compile("^[a-z][a-z0-9_]*$");

    private static final Pattern CROP_SLOT_KEY = Pattern.compile("^crop_([1-9][0-9]*)(|_plant|_order|_viable_area)$");

    @Override
    public void validate(INIModelParser.ParsedModel model, LinterSchema schema, ValidationResult result, java.io.File baseDirectory) {
        for (Map.Entry<String, INIModelParser.Section> entry : model.getSections().entrySet()) {
            String sectionName = entry.getKey();
            if (sectionName.startsWith("crop.")) {
                validateCropSection(sectionName, entry.getValue(), result);
            }
        }
        for (INIModelParser.NodeSection node : model.getAllNodeSections()) {
            if ("field".equalsIgnoreCase(node.getNodeType())) {
                validateFieldCropReferences(node, model, result);
            }
        }
    }

    /**
     * A field's {@code fallow} and {@code crop_N} name a declared [crop.*] section, every
     * {@code crop_N} comes with its {@code crop_N_plant}, and
     * the slots are numbered from 1 without gaps: the engine's load-time rules for a field.
     */
    private void validateFieldCropReferences(INIModelParser.NodeSection node, INIModelParser.ParsedModel model, ValidationResult result) {
        java.util.Map<Integer, java.util.Set<String>> slots = new java.util.TreeMap<>();
        java.util.Map<Integer, Integer> slotLines = new java.util.HashMap<>();
        for (INIModelParser.Property prop : node.getProperties().values()) {
            String key = prop.getKey().toLowerCase();
            if (key.equals("fallow")) {
                checkCropDeclared(prop, "the fallow", model, result);
                continue;
            }
            java.util.regex.Matcher m = CROP_SLOT_KEY.matcher(key);
            if (!m.matches()) continue;
            int n = Integer.parseInt(m.group(1));
            String suffix = m.group(2);
            slots.computeIfAbsent(n, k -> new java.util.HashSet<>()).add(suffix);
            slotLines.putIfAbsent(n, prop.getLineNumber());
            if (suffix.isEmpty()) {
                checkCropDeclared(prop, "crop_" + n, model, result);
            }
        }
        int expected = 1;
        for (java.util.Map.Entry<Integer, java.util.Set<String>> slot : slots.entrySet()) {
            int n = slot.getKey();
            int line = slotLines.get(n);
            if (n != expected) {
                result.addIssue(line, "Node '" + node.getNodeName() + "' has crop_" + n + " but no crop_" + expected
                        + "; slots are numbered from 1 without gaps", ValidationRule.Severity.ERROR, "crop_slot_gap");
                return;
            }
            expected++;
            if (!slot.getValue().contains("")) {
                result.addIssue(line, "Node '" + node.getNodeName() + "' has crop_" + n + " properties but no crop_" + n + " = <crop>",
                        ValidationRule.Severity.ERROR, "crop_slot_missing_crop");
                continue;
            }
            if (!slot.getValue().contains("_plant")) {
                result.addIssue(line, "crop_" + n + " needs crop_" + n + "_plant (the area to plant each day, in km2, 0 for none)",
                        ValidationRule.Severity.ERROR, "crop_slot_incomplete");
            }
        }
    }

    private void checkCropDeclared(INIModelParser.Property prop, String what, INIModelParser.ParsedModel model, ValidationResult result) {
        String name = prop.getValue().trim();
        if (!model.getSections().containsKey("crop." + name.toLowerCase())) {
            result.addIssue(prop.getLineNumber(), "No crop '" + name + "' is declared for " + what + " (a [crop." + name + "] section)",
                    ValidationRule.Severity.ERROR, "undeclared_crop");
        }
    }

    @Override
    public String getDescription() {
        return "Crop section validation";
    }

    private void validateCropSection(String sectionName, INIModelParser.Section section, ValidationResult result) {
        String cropName = sectionName.substring("crop.".length());

        if (!VALID_CROP_NAME.matcher(cropName).matches()) {
            result.addIssue(section.getStartLine(),
                    "Invalid crop name: '" + cropName + "' (lowercase letter first, then lowercase letters, digits and underscores)",
                    ValidationRule.Severity.ERROR, "invalid_crop_name");
        }

        boolean hasRootDepth = false;
        boolean hasKc = false;
        for (INIModelParser.Property prop : section.getProperties().values()) {
            String value = prop.getValue().trim();
            switch (prop.getKey().toLowerCase()) {
                case "root_depth":
                    hasRootDepth = true;
                    if (!isFiniteNumber(value) || Double.parseDouble(value) <= 0) {
                        result.addIssue(prop.getLineNumber(),
                                "Crop '" + cropName + "': root_depth must be a positive number of mm, got '" + value + "'",
                                ValidationRule.Severity.ERROR, "invalid_crop_root_depth");
                    }
                    break;
                case "p":
                    if (!isFiniteNumber(value) || Double.parseDouble(value) < 0 || Double.parseDouble(value) >= 1) {
                        result.addIssue(prop.getLineNumber(),
                                "Crop '" + cropName + "': p must be at least 0 and less than 1, got '" + value + "'",
                                ValidationRule.Severity.ERROR, "invalid_crop_p");
                    }
                    break;
                case "kc":
                    hasKc = true;
                    for (String error : checkKc(cropName, value)) {
                        result.addIssue(prop.getLineNumber(), error, ValidationRule.Severity.ERROR, "invalid_crop_kc");
                    }
                    break;
                case "season_len":
                    if (!value.matches("\\d+") || Long.parseLong(value) < 1) {
                        result.addIssue(prop.getLineNumber(),
                                "Crop '" + cropName + "': season_len must be a whole number of days, at least 1, got '" + value + "'",
                                ValidationRule.Severity.ERROR, "invalid_crop_season_len");
                    }
                    break;
                default:
                    result.addIssue(prop.getLineNumber(),
                            "Unexpected property '" + prop.getKey() + "' in [" + sectionName
                                    + "] (allowed: root_depth, p, kc, season_len)",
                            ValidationRule.Severity.ERROR, "unexpected_crop_property");
                    break;
            }
        }

        if (!hasRootDepth) {
            result.addIssue(section.getStartLine(),
                    "Crop '" + cropName + "' has no 'root_depth'",
                    ValidationRule.Severity.ERROR, "missing_crop_root_depth");
        }
        if (!hasKc) {
            result.addIssue(section.getStartLine(),
                    "Crop '" + cropName + "' has no 'kc'",
                    ValidationRule.Severity.ERROR, "missing_crop_kc");
        }
    }

    /**
     * Validate a crop's kc: a non-negative number, or a two-column table of
     * (days since planting, kc). Returns error messages, empty if well-formed.
     * Package-private for tests.
     */
    static List<String> checkKc(String cropName, String kc) {
        List<String> errors = new ArrayList<>();
        String trimmed = stripTrailingCommaAndWhitespace(kc);
        if (trimmed.trim().isEmpty()) {
            errors.add("Crop '" + cropName + "': kc is empty");
            return errors;
        }
        if (isFiniteNumber(trimmed)) {
            if (Double.parseDouble(trimmed) < 0) {
                errors.add("Crop '" + cropName + "': kc must be a non-negative number, got " + trimmed);
            }
            return errors;
        }
        String[] tokens = trimmed.split(",");
        for (int i = 0; i < tokens.length; i++) {
            tokens[i] = tokens[i].trim();
        }
        int bodyStart = 0;
        if (!isFiniteNumber(tokens[0])) {
            if (tokens.length < 2 || isFiniteNumber(tokens[1])) {
                errors.add("Crop '" + cropName + "': kc must be a number, or a table of two columns (days since planting, kc) with an optional two-label header");
                return errors;
            }
            bodyStart = 2;
        }
        int bodyLength = tokens.length - bodyStart;
        if (bodyLength == 0) {
            errors.add("Crop '" + cropName + "': kc table has no data rows");
            return errors;
        }
        if (bodyLength % 2 != 0) {
            errors.add("Crop '" + cropName + "': kc table must have an even number of values (days, kc pairs)");
            return errors;
        }
        double previousDay = Double.NEGATIVE_INFINITY;
        for (int i = bodyStart; i < tokens.length; i += 2) {
            int row = (i - bodyStart) / 2 + 1;
            if (!isFiniteNumber(tokens[i]) || !isFiniteNumber(tokens[i + 1])) {
                errors.add("Crop '" + cropName + "': kc table row " + row + " is not a pair of finite numbers");
                return errors;
            }
            double day = Double.parseDouble(tokens[i]);
            double value = Double.parseDouble(tokens[i + 1]);
            if (day < 0) {
                errors.add("Crop '" + cropName + "': kc table days must be non-negative, got " + tokens[i] + " in row " + row);
                return errors;
            }
            if (value < 0) {
                errors.add("Crop '" + cropName + "': kc table values must be non-negative, got " + tokens[i + 1] + " in row " + row);
                return errors;
            }
            if (day <= previousDay) {
                errors.add("Crop '" + cropName + "': kc table days must increase down the table, row " + row + " does not");
                return errors;
            }
            previousDay = day;
        }
        return errors;
    }

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
