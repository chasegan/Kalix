package com.kalix.ide.linter.validators;

import com.kalix.ide.linter.LinterSchema;
import com.kalix.ide.linter.model.FnRegistry;
import com.kalix.ide.linter.model.ValidationContext;
import com.kalix.ide.linter.model.ValidationResult;
import com.kalix.ide.linter.model.ValidationRule;
import com.kalix.ide.linter.parsing.INIModelParser;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

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
 *       distinct. Trailing/empty parameters ({@code foo(a,)}) are rejected.</li>
 *   <li>Neither the name nor a parameter may shadow a builtin function, a
 *       stateful function, or the reserved words {@code assert} / {@code this}.</li>
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
 *
 * <p>Signature parsing, arity, and the call graph all read from the shared
 * {@link FnRegistry} (one parse per lint pass), and the recursion scan walks
 * tokenized bodies (see {@link FunctionExpressionValidator#collectFnCallees})
 * rather than regex-scanning raw text.</p>
 */
public class FnSectionValidator implements ValidationStrategy {

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

        // One registry per lint pass: shared signature parser, arity, call graph.
        FnRegistry registry = FnRegistry.forModel(model);
        ValidationContext context = ValidationContext.builder()
                .model(model)
                .schema(schema)
                .fnRegistry(registry)
                .build();

        for (INIModelParser.Property prop : fnSection.getAllProperties()) {
            int line = prop.getLineNumber();

            FnRegistry.Signature sig = FnRegistry.parseSignature(prop.getKey());
            if (sig.error() != null) {
                result.addIssue(line, sig.error(), ValidationRule.Severity.ERROR, "invalid_fn_signature");
                continue;
            }

            // Duplicate detection consumes the registry: it keeps the FIRST
            // definition per name, so any property on a different line is a dup.
            FnRegistry.Entry first = registry.get(sig.name());
            if (first != null && first.line() != line) {
                result.addIssue(line,
                        "Duplicate function '" + sig.name() + "' in [fn] (one definition per name; "
                                + "there are no overloads)",
                        ValidationRule.Severity.ERROR, "duplicate_fn_name");
                continue;
            }

            for (String err : expressionValidator.validateFnBody(prop.getValue(), sig.params(), context)) {
                result.addIssue(line, "In function '" + sig.name() + "': " + err,
                        ValidationRule.Severity.ERROR, "invalid_fn_body");
            }
        }

        checkNoRecursion(registry, result);
    }

    @Override
    public String getDescription() {
        return "Function section ([fn]) validation";
    }

    /**
     * Verify the fn call graph is a DAG. Cycles (direct or mutual) are rejected
     * even when the cyclic definition is unused, mirroring the engine's
     * load-time check_dag. Three-colour iterative DFS; the cycle is named.
     *
     * <p>Edges come from walking each body with the expression Tokenizer and
     * collecting lowercased {@code fn.} calls — case-correct (so a mutual cycle
     * through {@code fn.B} is caught) and free of the phantom edges a raw-text
     * regex produced (e.g. an input alias named {@code fn} in {@code data.fn.a}).</p>
     */
    private void checkNoRecursion(FnRegistry registry, ValidationResult result) {
        // Build fn->fn adjacency, following only calls to known functions.
        Map<String, List<String>> graph = new HashMap<>();
        Map<String, Integer> lines = new HashMap<>();
        for (FnRegistry.Entry entry : registry.entries()) {
            lines.put(entry.name(), entry.line());
            List<String> callees = new ArrayList<>();
            for (String callee : FunctionExpressionValidator.collectFnCallees(entry.body())) {
                if (registry.contains(callee)) {
                    callees.add(callee);
                }
            }
            graph.put(entry.name(), callees);
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
