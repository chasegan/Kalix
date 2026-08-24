package com.kalix.ide.language;

import com.kalix.ide.linter.validators.FunctionExpressionValidator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Cross-sync guard for the single Java language definition
 * ({@link ExpressionLanguage}). It pins the engine-mirrored function and
 * sim-variable sets so a drift shows up as a failing test, and it checks that
 * the real consumer ({@code FunctionExpressionValidator}) agrees with the
 * definition — so the five formerly hand-synced tables cannot silently diverge.
 */
class ExpressionLanguageCrossSyncTest {

    /** Engine-drift pin: 26 pure builtins + 18 stateful builtins + 2 calendar functions. */
    private static final Set<String> EXPECTED_FUNCTION_NAMES = Set.of(
            "if", "min", "max", "sum", "mean",
            "abs", "sqrt", "sin", "cos", "tan", "asin", "acos", "atan",
            "exp", "ln", "log10", "log2", "ceil", "floor", "round", "sign",
            "is_leap_year", "infill",
            "pow", "atan2", "clamp",
            "moving_sum", "moving_mean", "moving_min", "moving_max",
            "moving_sum_years", "moving_min_years", "moving_max_years",
            "moving_sum_months", "moving_min_months", "moving_max_months",
            "moving_sum_days", "moving_min_days", "moving_max_days",
            "sum_since", "min_since", "max_since", "count_since", "steps_since",
            "month_at", "days_in_month_at");

    private static final Set<String> EXPECTED_STATEFUL_NAMES = Set.of(
            "moving_sum", "moving_mean", "moving_min", "moving_max",
            "moving_sum_years", "moving_min_years", "moving_max_years",
            "moving_sum_months", "moving_min_months", "moving_max_months",
            "moving_sum_days", "moving_min_days", "moving_max_days",
            "sum_since", "min_since", "max_since", "count_since", "steps_since");

    private static final Set<String> EXPECTED_SIM_VARIABLES = Set.of(
            "sim.year", "sim.month", "sim.day", "sim.day_of_year", "sim.step",
            "sim.new_day", "sim.new_month", "sim.new_year",
            "sim.days_in_month", "sim.days_in_year", "sim.is_leap");

    @Test
    @DisplayName("The function and sim-variable sets match the engine pin exactly")
    void testEngineDriftPins() {
        assertEquals(EXPECTED_FUNCTION_NAMES, ExpressionLanguage.functionNames());
        assertEquals(EXPECTED_FUNCTION_NAMES, ExpressionLanguage.functionArities().keySet());
        assertEquals(EXPECTED_SIM_VARIABLES, ExpressionLanguage.simVariableNames());
        assertEquals(46, ExpressionLanguage.BUILTINS.size());
        assertEquals(11, ExpressionLanguage.SIM_VARIABLES.size());
    }

    @Test
    @DisplayName("Every builtin carries an arity, a signature, and a description")
    void testEveryEntryComplete() {
        for (ExpressionLanguage.Builtin b : ExpressionLanguage.BUILTINS) {
            assertNotEquals(0, b.arity(), "arity must be non-zero for " + b.name());
            assertNotNull(b.signature(), "signature for " + b.name());
            assertFalse(b.signature().isBlank(), "signature blank for " + b.name());
            assertNotNull(b.description(), "description for " + b.name());
            assertFalse(b.description().isBlank(), "description blank for " + b.name());
            assertEquals(b.stateful(), EXPECTED_STATEFUL_NAMES.contains(b.name()),
                    "stateful flag wrong for " + b.name());
        }
        for (ExpressionLanguage.SimVariable v : ExpressionLanguage.SIM_VARIABLES) {
            assertFalse(v.description().isBlank(), "description blank for " + v.name());
        }
    }

    @Test
    @DisplayName("reservedTier names the tier for each reserved name, and null for free names")
    void testReservedTiers() {
        assertEquals("builtin function", ExpressionLanguage.reservedTier("min"));
        assertEquals("builtin function", ExpressionLanguage.reservedTier("clamp"));
        assertEquals("stateful function", ExpressionLanguage.reservedTier("moving_mean"));
        assertEquals("stateful function", ExpressionLanguage.reservedTier("moving_sum_years"));
        assertEquals("stateful function", ExpressionLanguage.reservedTier("moving_max_years"));
        assertEquals("stateful function", ExpressionLanguage.reservedTier("moving_sum_months"));
        assertEquals("stateful function", ExpressionLanguage.reservedTier("moving_max_days"));
        assertEquals("stateful function", ExpressionLanguage.reservedTier("steps_since"));
        assertEquals("reserved word", ExpressionLanguage.reservedTier("assert"));
        assertEquals("reserved word", ExpressionLanguage.reservedTier("this"));
        assertEquals("reserved word", ExpressionLanguage.reservedTier("self"));
        assertEquals("builtin function", ExpressionLanguage.reservedTier("is_leap_year"));
        assertEquals("builtin function", ExpressionLanguage.reservedTier("infill"));
        assertEquals("calendar function", ExpressionLanguage.reservedTier("month_at"));
        assertEquals("calendar function", ExpressionLanguage.reservedTier("days_in_month_at"));
        assertNull(ExpressionLanguage.reservedTier("headroom"));
    }

    @Test
    @DisplayName("The FunctionExpressionValidator recognises every defined builtin")
    void testValidatorConsumesEveryBuiltin() {
        FunctionExpressionValidator validator = new FunctionExpressionValidator();
        for (ExpressionLanguage.Builtin b : ExpressionLanguage.BUILTINS) {
            // Call each function with its minimum arity; every argument a data
            // ref, except the load-time literals every moving_* family needs
            // (window length, and the element default or water-year anchor).
            // moving_*_years needs its own values because its 3rd argument is
            // a month in [1,12], where the fixed-window family's 3rd is any
            // constant: (x, 6, 3) is a 6-year window anchored at March.
            // moving_*_months / moving_*_days are 2-arity, so their single
            // n falls out of the shared "i==1 -> 3" branch below.
            int minArity = b.arity() < 0 ? -b.arity() : b.arity();
            List<String> args = new java.util.ArrayList<>();
            for (int i = 0; i < minArity; i++) {
                if (b.name().endsWith("_years") && i >= 1) {
                    args.add(i == 1 ? "6" : "3");
                } else if (b.name().startsWith("moving_") && i >= 1) {
                    args.add(i == 1 ? "3" : "0");
                } else {
                    args.add("data.x");
                }
            }
            String expr = b.name() + "(" + String.join(", ", args) + ")";
            List<String> errors = validator.validate(expr);
            assertTrue(errors.stream().noneMatch(e -> e.contains("Unknown function")),
                    "validator did not recognise builtin '" + b.name() + "': " + errors);
        }
    }
}
