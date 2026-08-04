package com.kalix.ide.linter.validators;

import com.kalix.ide.language.ExpressionLanguage;
import com.kalix.ide.linter.LinterSchema;
import com.kalix.ide.linter.model.ValidationContext;
import com.kalix.ide.linter.model.ValidationResult;
import com.kalix.ide.linter.model.ValidationRule;
import com.kalix.ide.linter.parsing.INIModelParser;

import java.io.File;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Validates [var.*] blocks: model variables (published, scheduled calculations).
 *
 * <p>Mirrors the engine's load-time rules (the {@code var.} arm of
 * src/io/ini_model_io_versions/ini_doc_model_io_0_0_1.rs and
 * docs/functions/structured_expressions_design.md §9) so a modeller sees the
 * problem in the editor rather than at model load:</p>
 * <ul>
 *   <li>The block name after {@code var.} is a bare identifier (lowercase
 *       letter first; lowercase letters, digits, underscores after; no dots).</li>
 *   <li>{@code phase = flow} is accepted; {@code phase = order} is designed but
 *       not yet implemented; any other phase value is invalid.</li>
 *   <li>Every other key is a bare identifier, and its value is validated as a
 *       function expression (with model context but no current-node context —
 *       a var block belongs to no node, so {@code this.} cannot be resolved).</li>
 * </ul>
 */
public class VarSectionValidator implements ValidationStrategy {

    /** A bare name per the engine's is_valid_variable_name: lowercase first. */
    private static final Pattern VALID_NAME = ExpressionLanguage.BARE_NAME;

    private final FunctionExpressionValidator expressionValidator = new FunctionExpressionValidator();

    @Override
    public void validate(INIModelParser.ParsedModel model, LinterSchema schema, ValidationResult result, File baseDirectory) {
        // A var block belongs to no node: 'this.' cannot be resolved here, so the
        // context carries the model (for node/var/fn/table refs) but no current
        // node. It is identical for every [var.*] block, so build it once.
        ValidationContext context = ValidationContext.builder()
                .model(model)
                .schema(schema)
                .build();

        for (Map.Entry<String, INIModelParser.Section> entry : model.getSections().entrySet()) {
            String sectionName = entry.getKey();
            if (sectionName.equals("var") || sectionName.startsWith("var.")) {
                validateVarSection(sectionName, entry.getValue(), context, result);
            }
        }
    }

    @Override
    public String getDescription() {
        return "Variable block ([var.*]) validation";
    }

    private void validateVarSection(String sectionName, INIModelParser.Section section,
                                    ValidationContext context, ValidationResult result) {
        // The namespace is everything after "var."; a bare [var] has none.
        String blockName = sectionName.equals("var") ? "" : sectionName.substring("var.".length());
        if (!VALID_NAME.matcher(blockName).matches()) {
            result.addIssue(section.getStartLine(),
                    "Invalid var block name: '" + blockName
                            + "' (start with a lowercase letter; use lowercase letters, digits and "
                            + "underscores; no dots)",
                    ValidationRule.Severity.ERROR, "invalid_var_block_name");
        }

        for (INIModelParser.Property prop : section.getAllProperties()) {
            String key = prop.getKey();
            int line = prop.getLineNumber();

            if (key.equals("phase")) {
                validatePhase(prop.getValue(), sectionName, line, result);
                continue;
            }

            if (!VALID_NAME.matcher(key).matches()) {
                result.addIssue(line,
                        "Invalid var name: '" + key + "' in [" + sectionName
                                + "] (start with a lowercase letter; use lowercase letters, digits and "
                                + "underscores; no dots)",
                        ValidationRule.Severity.ERROR, "invalid_var_name");
                continue;
            }

            for (String err : expressionValidator.validateVarDefinition(prop.getValue(), context)) {
                result.addIssue(line, "In var '" + key + "': " + err,
                        ValidationRule.Severity.ERROR, "invalid_var_expression");
            }
        }
    }

    private void validatePhase(String value, String sectionName, int line, ValidationResult result) {
        String phase = value == null ? "" : value.trim().toLowerCase();
        switch (phase) {
            case "flow":
            case "ras":
                // 'ras' runs in the assessment slot at the top of the step;
                // the engine additionally requires such blocks to precede the
                // first node section (placement is validated at load).
                return;
            case "order":
                result.addIssue(line,
                        "phase = order is not yet implemented for [var.*] blocks "
                                + "(only phase = ras or flow is supported)",
                        ValidationRule.Severity.ERROR, "unsupported_var_phase");
                return;
            default:
                result.addIssue(line,
                        "invalid phase '" + value + "' for [" + sectionName + "] (expected 'ras', 'flow' or 'order')",
                        ValidationRule.Severity.ERROR, "invalid_var_phase");
        }
    }
}
