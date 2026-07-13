package com.kalix.ide.linter.validators;

import com.kalix.ide.linter.LinterSchema;
import com.kalix.ide.linter.model.ValidationContext;
import com.kalix.ide.linter.model.ValidationResult;
import com.kalix.ide.linter.model.ValidationRule;
import com.kalix.ide.linter.parsing.INIModelParser;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Validates the [fn] section: user-defined function definitions.
 *
 * <p>Mirrors the engine's load-time rules (src/functions/fn_registry.rs and
 * docs/functions/structured_expressions_design.md §8) so a modeller sees the
 * problem in the editor rather than at model load:</p>
 * <ul>
 *   <li>Keys are signatures: {@code name(a, b)} or {@code name()}. Name and
 *       parameters are bare identifiers (lowercase letter first; lowercase
 *       letters, digits, underscores after; no dots), and parameters are
 *       distinct.</li>
 *   <li>Neither the name nor a parameter may shadow a builtin function or the
 *       reserved words {@code assert} / {@code this}.</li>
 *   <li>Duplicate function names are rejected regardless of arity — fixed
 *       signatures, no overloads (§8.1).</li>
 *   <li>Bodies are validated as expressions or {@code { ... }} blocks whose
 *       bare names resolve against the signature's parameters.</li>
 *   <li>The {@code fn.} call graph must be a DAG: recursion (direct or mutual)
 *       is rejected, with the cycle named (three-colour DFS mirroring the
 *       engine's {@code check_dag}).</li>
 *   <li>{@code [fn.something]} is reserved for future namespaced groups; only
 *       {@code [fn]} is supported for now.</li>
 * </ul>
 */
public class FnSectionValidator implements ValidationStrategy {

    /** A bare name per the engine's is_valid_variable_name: lowercase first. */
    private static final Pattern VALID_NAME = Pattern.compile("^[a-z][a-z0-9_]*$");

    /** {@code fn.<name>} call scan, for building the recursion call graph. */
    private static final Pattern FN_CALL = Pattern.compile("\\bfn\\.([a-z][a-z0-9_]*)");

    /**
     * Names that cannot be used as a function name or parameter: the builtin
     * functions (mirrors FunctionExpressionValidator's KNOWN_FUNCTIONS, which
     * mirrors the engine's BuiltinFunction enum) plus 'assert' and 'this'.
     */
    private static final Set<String> RESERVED = Set.of(
            "if", "min", "max", "sum", "mean", "abs", "sqrt", "sin", "cos", "tan",
            "asin", "acos", "atan", "ln", "log10", "log2", "exp", "ceil", "floor",
            "round", "sign", "pow", "atan2", "clamp",
            "moving_sum", "moving_mean", "moving_min", "moving_max",
            "sum_since", "min_since", "max_since", "count_since", "steps_since",
            "assert", "this"
    );

    private final FunctionExpressionValidator expressionValidator = new FunctionExpressionValidator();

    @Override
    public void validate(INIModelParser.ParsedModel model, LinterSchema schema, ValidationResult result, File baseDirectory) {
        // [fn.something] is reserved for future namespaced function groups.
        for (Map.Entry<String, INIModelParser.Section> entry : model.getSections().entrySet()) {
            String sectionName = entry.getKey();
            if (sectionName.startsWith("fn.")) {
                result.addIssue(entry.getValue().getStartLine(),
                        "Section [" + sectionName + "] is reserved for future namespaced function "
                                + "groups; only [fn] is supported for now",
                        ValidationRule.Severity.ERROR, "reserved_fn_section");
            }
        }

        INIModelParser.Section fnSection = model.getSections().get("fn");
        if (fnSection == null) {
            return;
        }

        ValidationContext context = ValidationContext.builder()
                .model(model)
                .schema(schema)
                .build();

        // Lowercased name -> line of first definition (for duplicate detection).
        Map<String, Integer> firstDefinitionLine = new LinkedHashMap<>();
        // Lowercased name -> body text (for the recursion scan).
        Map<String, String> bodies = new LinkedHashMap<>();

        for (INIModelParser.Property prop : fnSection.getAllProperties()) {
            String key = prop.getKey();
            int line = prop.getLineNumber();

            Signature sig = parseSignature(key);
            if (sig.error != null) {
                result.addIssue(line, sig.error, ValidationRule.Severity.ERROR, "invalid_fn_signature");
                continue;
            }

            String lowerName = sig.name.toLowerCase();
            if (firstDefinitionLine.containsKey(lowerName)) {
                result.addIssue(line,
                        "Duplicate function '" + sig.name + "' in [fn] (one definition per name; "
                                + "there are no overloads)",
                        ValidationRule.Severity.ERROR, "duplicate_fn_name");
                continue;
            }
            firstDefinitionLine.put(lowerName, line);
            bodies.put(lowerName, prop.getValue());

            for (String err : expressionValidator.validateFnBody(prop.getValue(), sig.params, context)) {
                result.addIssue(line, "In function '" + sig.name + "': " + err,
                        ValidationRule.Severity.ERROR, "invalid_fn_body");
            }
        }

        checkNoRecursion(bodies, firstDefinitionLine, result);
    }

    @Override
    public String getDescription() {
        return "Function section ([fn]) validation";
    }

    /** A parsed signature: name and ordered parameters, or an error message. */
    private static final class Signature {
        String name;
        final List<String> params = new ArrayList<>();
        String error;
    }

    /**
     * Parse a signature key {@code name(a, b)} / {@code name()}. Returns a
     * Signature whose {@code error} is non-null when the key is malformed.
     */
    private static Signature parseSignature(String key) {
        Signature sig = new Signature();
        String k = key.trim();

        int open = k.indexOf('(');
        if (open < 0 || !k.endsWith(")")) {
            sig.error = "Invalid [fn] signature '" + key + "': expected a signature like name(a, b) or name()";
            return sig;
        }

        String name = k.substring(0, open).trim();
        if (name.contains(".")) {
            sig.error = "Invalid function name '" + name + "' (use a bare name; no dots)";
            return sig;
        }
        if (!VALID_NAME.matcher(name).matches()) {
            sig.error = "Invalid function name '" + name
                    + "' (start with a lowercase letter; use lowercase letters, digits and underscores)";
            return sig;
        }
        if (RESERVED.contains(name)) {
            sig.error = "Function name '" + name + "' collides with a builtin function or reserved word";
            return sig;
        }
        sig.name = name;

        String inner = k.substring(open + 1, k.length() - 1).trim();
        if (!inner.isEmpty()) {
            for (String rawParam : inner.split(",")) {
                String p = rawParam.trim();
                if (p.contains(".")) {
                    sig.error = "Invalid parameter '" + p + "' in signature '" + key + "' (use a bare name; no dots)";
                    return sig;
                }
                if (!VALID_NAME.matcher(p).matches()) {
                    sig.error = "Invalid parameter '" + p + "' in signature '" + key
                            + "' (start with a lowercase letter; use lowercase letters, digits and underscores)";
                    return sig;
                }
                if (RESERVED.contains(p)) {
                    sig.error = "Parameter '" + p + "' in signature '" + key
                            + "' collides with a builtin function or reserved word";
                    return sig;
                }
                if (sig.params.contains(p)) {
                    sig.error = "Duplicate parameter '" + p + "' in signature '" + key + "'";
                    return sig;
                }
                sig.params.add(p);
            }
        }
        return sig;
    }

    /**
     * Verify the fn call graph is a DAG. Cycles (direct or mutual) are rejected
     * even when the cyclic definition is unused, mirroring the engine's
     * load-time check_dag. Three-colour iterative DFS; the cycle is named.
     */
    private void checkNoRecursion(Map<String, String> bodies, Map<String, Integer> lines,
                                  ValidationResult result) {
        // Build fn->fn adjacency, following only calls to known functions.
        Map<String, List<String>> graph = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : bodies.entrySet()) {
            List<String> callees = new ArrayList<>();
            Matcher m = FN_CALL.matcher(entry.getValue());
            while (m.find()) {
                String callee = m.group(1).toLowerCase();
                if (bodies.containsKey(callee)) {
                    callees.add(callee);
                }
            }
            graph.put(entry.getKey(), callees);
        }

        Map<String, Mark> marks = new HashMap<>();
        for (String node : graph.keySet()) {
            marks.put(node, Mark.WHITE);
        }
        Set<String> reportedCycles = new HashSet<>();

        for (String start : graph.keySet()) {
            if (marks.get(start) != Mark.WHITE) {
                continue;
            }
            dfs(start, graph, marks, new ArrayList<>(), lines, result, reportedCycles);
        }
    }

    private enum Mark { WHITE, GREY, BLACK }

    private void dfs(String node, Map<String, List<String>> graph, Map<String, Mark> marks,
                     List<String> path, Map<String, Integer> lines, ValidationResult result,
                     Set<String> reportedCycles) {
        marks.put(node, Mark.GREY);
        path.add(node);

        for (String callee : graph.get(node)) {
            switch (marks.get(callee)) {
                case BLACK:
                    break;
                case GREY: {
                    // Cycle: the grey callee is an ancestor on the current path.
                    int idx = path.indexOf(callee);
                    List<String> chain = new ArrayList<>(path.subList(idx, path.size()));
                    chain.add(callee);
                    reportCycle(chain, lines, result, reportedCycles);
                    break;
                }
                case WHITE:
                    dfs(callee, graph, marks, path, lines, result, reportedCycles);
                    break;
            }
        }

        marks.put(node, Mark.BLACK);
        path.remove(path.size() - 1);
    }

    private void reportCycle(List<String> chain, Map<String, Integer> lines, ValidationResult result,
                             Set<String> reportedCycles) {
        // Deduplicate by the set of members so the same cycle is reported once.
        String canonical = new TreeSet<>(chain).toString();
        if (!reportedCycles.add(canonical)) {
            return;
        }
        StringBuilder joined = new StringBuilder();
        for (int i = 0; i < chain.size(); i++) {
            if (i > 0) {
                joined.append(" -> ");
            }
            joined.append("fn.").append(chain.get(i));
        }
        Integer line = lines.get(chain.get(0));
        result.addIssue(line != null ? line : 1,
                "Function definitions are recursive, which is not allowed: " + joined,
                ValidationRule.Severity.ERROR, "recursive_fn");
    }
}
