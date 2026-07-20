package com.kalix.ide.linter;

import com.kalix.ide.linter.model.ValidationIssue;
import com.kalix.ide.linter.parsing.INIModelParser;
import com.kalix.ide.linter.validators.FunctionExpressionValidator;
import com.kalix.ide.linter.validators.NodeValidator;
import com.kalix.ide.linter.validators.ReferenceValidator;
import com.kalix.ide.linter.validators.SectionValidator;
import com.kalix.ide.linter.validators.ValidationStrategy;
import com.kalix.ide.linter.model.ValidationResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end lint of the accounting grammar ([acc.*] groups, [ras.*] systems,
 * node `accounts =` references, and acc./ras. series in expressions and
 * [outputs]) — the shape a migrated real model takes. Guards against the IDE
 * red-underlining models the engine runs happily.
 */
class AccountingGrammarLintTest {

    private static final String MODEL = """
            [kalix]
            start = 2020-01-01
            end = 2020-12-31

            [const]
            const.wy = 7

            [acc.avl]
            accounts = name, size, initial,
                       n0031, 42, 42,
                       n0035, 336, 336,

            [ras.avl_reset]
            targets = acc.avl
            trigger = start_water_year(const.wy)
            action  = set_full

            [node.src]
            type = inflow
            loc = 0, 0
            inflow = 50
            ds_1 = u1

            [node.u1]
            type = unregulated_user
            loc = 0, 10
            demand = 0.1 * acc.n0031.opening_balance
            accounts = n0031, n0035
            ds_1 = sink

            [node.sink]
            type = blackhole
            loc = 0, 20

            [outputs]
            node.u1.diversion
            acc.n0031.opening_balance
            acc.n0031.closing_balance
            acc.n0031.debits
            acc.avl.closing_balance
            ras.avl_reset.fired
            """;

    /**
     * Run the validators that matter for this grammar directly against the
     * default schema. ModelLinter is skipped deliberately: it gates on
     * preference-backed state, so a test driving it would pass vacuously
     * whenever linting happened to be disabled.
     */
    private static List<ValidationIssue> lint(String content) {
        LinterSchema schema = LinterSchema.loadDefault();
        INIModelParser.ParsedModel model = INIModelParser.parse(content);
        ValidationResult result = new ValidationResult();
        List<ValidationStrategy> validators = List.of(
                new SectionValidator(), new NodeValidator(), new ReferenceValidator());
        for (ValidationStrategy validator : validators) {
            validator.validate(model, schema, result, null);
        }
        return result.getIssues();
    }

    private static String describe(List<ValidationIssue> issues) {
        return issues.stream()
                .map(i -> "line " + i.getLineNumber() + ": " + i.getMessage())
                .collect(Collectors.joining("\n"));
    }

    @Test
    void migratedAccountingModelLintsClean() {
        List<ValidationIssue> issues = lint(MODEL);
        assertTrue(issues.isEmpty(),
                "accounting grammar should lint clean, got:\n" + describe(issues));
    }

    @Test
    void unknownAccountFieldInOutputsIsReported() {
        String bad = MODEL.replace("acc.n0031.debits", "acc.n0031.balance");
        List<ValidationIssue> issues = lint(bad);
        assertTrue(issues.stream().anyMatch(i -> i.getMessage().contains("Unknown field for account")),
                "retired 'balance' field should be reported, got:\n" + describe(issues));
    }

    @Test
    void groupAggregateRejectsPerAccountOnlyField() {
        String bad = MODEL.replace("acc.avl.closing_balance", "acc.avl.size");
        List<ValidationIssue> issues = lint(bad);
        assertTrue(issues.stream().anyMatch(i -> i.getMessage().contains("Unknown field for account group")),
                "size is not a group aggregate, got:\n" + describe(issues));
    }

    @Test
    void retiredInlineAccountDeclarationIsReported() {
        String bad = MODEL.replace("accounts = n0031, n0035", "account = n0031, avl, 42, 7");
        List<ValidationIssue> issues = lint(bad);
        assertFalse(issues.isEmpty(),
                "the removed inline account property should not lint clean");
    }
}
