package com.kalix.ide.linter.validators;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for FunctionExpressionValidator
 */
class FunctionExpressionValidatorTest {

    private FunctionExpressionValidator validator;

    @BeforeEach
    void setUp() {
        validator = new FunctionExpressionValidator();
    }

    // ==================== Valid Expressions ====================

    @Test
    @DisplayName("Simple number should be valid")
    void testSimpleNumber() {
        assertValid("5.0");
        assertValid("42");
        assertValid("-3.14");
        assertValid("1.23e-4");
    }

    @Test
    @DisplayName("Simple data reference should be valid")
    void testSimpleDataReference() {
        assertValid("data.evap");
        assertValid("data.rex_mpot_csv");
        assertValid("data.rex_mpot_csv.by_name.value");
        assertValid("data.temp_data.field_1.value");
    }

    @Test
    @DisplayName("Simple constant reference should be valid")
    void testSimpleConstantReference() {
        assertValid("c.pi");
        assertValid("c.node_1_demand_levels.high");
        assertValid("c.some_value");
        assertValid("c.nested.constant.path");
    }

    @Test
    @DisplayName("Simple arithmetic expressions should be valid")
    void testSimpleArithmetic() {
        assertValid("2 + 3");
        assertValid("10 - 5");
        assertValid("4 * 7");
        assertValid("20 / 4");
        assertValid("10 % 3");
    }

    @Test
    @DisplayName("Complex arithmetic expressions should be valid")
    void testComplexArithmetic() {
        assertValid("2 + 3 * 4");
        assertValid("(2 + 3) * 4");
        assertValid("data.evap * 1.2");
        assertValid("data.temp * 2.5 + 10");
        assertValid("(data.a + data.b) / 2");
    }

    @Test
    @DisplayName("Power expressions should be valid")
    void testPowerExpressions() {
        assertValid("2 ^ 3");
        assertValid("2 ** 3");
        assertValid("data.temp ^ 2");
        assertValid("pow(2, 3)");
    }

    @Test
    @DisplayName("Comparison expressions should be valid")
    void testComparisonExpressions() {
        assertValid("data.temp > 20");
        assertValid("data.temp >= 20");
        assertValid("data.temp < 20");
        assertValid("data.temp <= 20");
        assertValid("data.temp == 20");
        assertValid("data.temp != 20");
    }

    @Test
    @DisplayName("Logical expressions should be valid")
    void testLogicalExpressions() {
        // The engine's expression grammar uses C-style logical operators
        // (src/functions/operators.rs: "&&" -> And, "||" -> Or).
        assertValid("data.a > 0 && data.b > 0");
        assertValid("data.a > 0 || data.b > 0");
        assertValid("!(data.a > 0)");
    }

    @Test
    @DisplayName("Unary operators should be valid")
    void testUnaryOperators() {
        assertValid("-data.temp");
        assertValid("+5");
        assertValid("!data.flag");
    }

    @Test
    @DisplayName("Known functions should be valid")
    void testKnownFunctions() {
        // Conditional
        assertValid("if(data.temp > 20, 10.0, 5.0)");

        // Math functions
        assertValid("abs(-5)");
        assertValid("sqrt(16)");
        assertValid("sin(data.angle)");
        assertValid("cos(data.angle)");
        assertValid("exp(data.x)");
        assertValid("ln(data.x)");
        assertValid("log10(data.x)");
        assertValid("log2(data.x)");
        assertValid("sign(data.x)");

        // Aggregation
        assertValid("max(1, 2, 3)");
        assertValid("min(data.a, data.b)");
        assertValid("mean(data.x, data.y, data.z)");
        assertValid("sum(1, 2, 3, 4, 5)");
        assertValid("sum(data.x)"); // sum and mean accept a single argument
        assertValid("mean(data.x)");

        // Two argument
        assertValid("pow(2, 8)");
        assertValid("atan2(data.y, data.x)");
    }

    @Test
    @DisplayName("Functions that don't exist in the engine should be invalid")
    void testEngineDriftFunctions() {
        // These were historically accepted by the linter but never existed in
        // the engine's BuiltinFunction set - keep the two in sync.
        assertInvalid("log(data.x)", "Unknown function"); // deliberate: use ln or log10
        assertInvalid("avg(data.x, data.y)", "Unknown function"); // deliberate: the statistic is mean
        assertInvalid("sinh(data.x)", "Unknown function");
        assertInvalid("cosh(data.x)", "Unknown function");
        assertInvalid("tanh(data.x)", "Unknown function");
    }

    @Test
    @DisplayName("Nested function calls should be valid")
    void testNestedFunctions() {
        assertValid("max(abs(-5), sqrt(16))");
        assertValid("if(data.temp > 20, max(data.a, data.b), min(data.c, data.d))");
        assertValid("sin(cos(data.angle))");
    }

    @Test
    @DisplayName("Complex realistic expressions should be valid")
    void testComplexRealisticExpressions() {
        assertValid("if(data.temperature > 20, data.evap_high, data.evap_low) * 1.2");
        assertValid("max(data.rainfall * data.adjustment, 0)");
        assertValid("(data.flow - data.demand) * if(data.season == 1, 1.1, 0.9)");
    }

    @Test
    @DisplayName("Mixed data and constant references should be valid")
    void testMixedDataAndConstantReferences() {
        assertValid("data.temp * c.pi");
        assertValid("if(data.x > c.threshold, c.high, c.low)");
        assertValid("max(data.value, c.minimum) * c.adjustment_factor");
        assertValid("data.flow + c.base_offset - c.calibration.factor");
    }

    // ==================== Invalid Expressions ====================

    @Test
    @DisplayName("Empty expression should be invalid")
    void testEmptyExpression() {
        assertInvalid("", "Expression is empty");
    }

    @Test
    @DisplayName("Unbalanced parentheses should be invalid")
    void testUnbalancedParentheses() {
        assertInvalid("(2 + 3", "Expected RPAREN");
        assertInvalid("2 + 3)", "Unexpected tokens");
        assertInvalid("if(data.a > data.b, 1, 2", "Expected RPAREN");
    }

    @Test
    @DisplayName("Unknown function should be invalid")
    void testUnknownFunction() {
        assertInvalid("foo(1)", "Unknown function");
        assertInvalid("maximum(data.a, data.b)", "Unknown function"); // Should suggest 'max'
    }

    @Test
    @DisplayName("Wrong argument count should be invalid")
    void testWrongArgumentCount() {
        assertInvalid("if(data.a > data.b, 1)", "expects 3 argument");
        assertInvalid("abs(1, 2)", "expects 1 argument");
        assertInvalid("pow(2)", "expects 2 argument");
        assertInvalid("max(1)", "at least 2 argument");
    }

    @Test
    @DisplayName("Invalid operators should be invalid")
    void testInvalidOperators() {
        assertInvalid("data.a & data.b", "Invalid operator '&'");
        assertInvalid("data.a | data.b", "Invalid operator '|'");
        assertInvalid("data.a = 5", "Invalid operator '='");
    }

    @Test
    @DisplayName("Malformed data references should be invalid")
    void testMalformedDataReferences() {
        assertInvalid("data..evap", "Malformed data reference");
        assertInvalid("data.", "Incomplete data reference");
        assertInvalid("data.evap.", "Malformed data reference");
    }

    @Test
    @DisplayName("Malformed constant references should be invalid")
    void testMalformedConstantReferences() {
        assertInvalid("c..pi", "Malformed constant reference");
        assertInvalid("c.", "Incomplete constant reference");
        assertInvalid("c.value.", "Malformed constant reference");
    }

    @Test
    @DisplayName("Trailing operators should be invalid")
    void testTrailingOperators() {
        assertInvalid("data.evap *", "Expected number");
        assertInvalid("5 +", "Expected number");
    }

    @Test
    @DisplayName("Division by a zero constant is allowed (a valid NaN-manufacturing idiom)")
    void testDivisionByZeroConstant() {
        assertValid("5 / 0");
        assertValid("data.x / 0");
    }

    @Test
    @DisplayName("Missing commas in function calls should be invalid")
    void testMissingCommas() {
        assertInvalid("if(data.a > data.b 10 5)", "Expected RPAREN");
        assertInvalid("max(1 2 3)", "Expected RPAREN");
    }

    @Test
    @DisplayName("Invalid number format should be invalid")
    void testInvalidNumberFormat() {
        assertInvalid("1.2.3", "Unexpected tokens");
        assertInvalid("..5", "Unexpected character");
    }

    @Test
    @DisplayName("Unexpected characters should be invalid")
    void testUnexpectedCharacters() {
        assertInvalid("data.evap @ 5", "Unexpected character");
        assertInvalid("$var", "Unexpected character");
        assertInvalid("#comment", "Unexpected character");
    }

    // ==================== Edge Cases ====================

    @Test
    @DisplayName("Whitespace should be handled correctly")
    void testWhitespace() {
        assertValid("  2 + 3  ");
        assertValid("if ( data.temp > 20 , 10 , 5 )");
    }

    @Test
    @DisplayName("Case sensitivity for function names")
    void testCaseInsensitivity() {
        assertValid("IF(data.temp > 20, 10, 5)");
        assertValid("Max(1, 2, 3)");
        assertValid("SIN(data.angle)");
    }

    @Test
    @DisplayName("Scientific notation should be valid")
    void testScientificNotation() {
        assertValid("1.5e10");
        assertValid("2.3E-5");
        assertValid("1e6");
    }

    @Test
    @DisplayName("Very long data reference paths should be valid")
    void testLongDataPaths() {
        assertValid("data.very.long.path.to.some.deeply.nested.value");
    }

    // ==================== Edge-case Tests ====================

    @Test
    @DisplayName("Unterminated bracket in a reference should be invalid")
    void testUnterminatedBracket() {
        // Historically the tokenizer consumed the rest of the input scanning for ']'
        // and the expression validated clean.
        assertInvalid("data.rain[unclosed", "Unterminated '['");
        assertInvalid("node.storage1.dsflow[-1, 0", "Unterminated '['");
        assertValid("data.rain[-1, 0]");
    }

    @Test
    @DisplayName("Division by a zero constant is allowed for every zero spelling")
    void testDivisionByZeroSpellings() {
        assertValid("data.a / 0");
        assertValid("data.a / 0.0");
        assertValid("data.a / 0e5");
        assertValid("data.a / 0.5");
    }

    @Test
    @DisplayName("Simple expressions should be very fast (<1ms)")
    void testFastPathPerformance() {
        long start = System.nanoTime();
        for (int i = 0; i < 10000; i++) {
            validator.validate("data.evap");
            validator.validate("5.0");
            validator.validate("2 + 3");
        }
        long elapsed = (System.nanoTime() - start) / 1_000_000; // Convert to ms

        double avgTime = elapsed / 30000.0; // 10000 iterations * 3 expressions
        assertTrue(avgTime < 1.0,
            "Average fast path time should be < 1ms, was: " + avgTime + "ms");
    }

    @Test
    @DisplayName("Functions should be case-insensitive")
    void testCaseInsensitiveFunctions() {
        // Test various capitalizations of common functions
        assertValid("MAX(data.a, data.b)");
        assertValid("Max(data.a, data.b)");
        assertValid("max(data.a, data.b)");

        assertValid("MIN(data.a, data.b)");
        assertValid("Min(data.a, data.b)");

        assertValid("IF(data.a > data.b, 1, 2)");
        assertValid("If(data.a > data.b, 1, 2)");

        assertValid("ABS(data.a)");
        assertValid("Abs(data.a)");

        assertValid("SQRT(data.a)");
        assertValid("Sqrt(data.a)");

        assertValid("POW(data.a, 2)");
        assertValid("Pow(data.a, 2)");
    }

    // ==================== Table Lookups ====================

    @Test
    @DisplayName("Table lookup calls should be valid without model context")
    void testTableCallsWithoutContext() {
        assertValid("table.rating(data.stage)");
        assertValid("table.pump(sim.month, node.dam.volume)");
        assertValid("2 * table.rating(data.stage) + 1");
        assertValid("max(table.rating(data.stage), c.floor_value)");
        assertValid("table.rating(table.other(data.x))");
    }

    @Test
    @DisplayName("Bare table references should be invalid")
    void testBareTableReference() {
        assertInvalid("table.rating", "must be called with arguments");
        assertInvalid("2 * table.rating", "must be called with arguments");
    }

    @Test
    @DisplayName("Malformed table references should be invalid")
    void testMalformedTableReferences() {
        assertInvalid("table.", "Incomplete table reference");
        // The tokenizer stops a dotted read at a double dot, so this surfaces
        // as an incomplete reference ("table.") rather than a dot-count error.
        assertInvalid("table..rating(1)", "Incomplete table reference");
        assertInvalid("table.a.b(1)", "table names cannot contain dots");
    }

    @Test
    @DisplayName("Table existence and arity should be validated with model context")
    void testTableValidationWithModelContext() {
        String ini = """
            [table.rating]
            values = 0, 0, 1, 100

            [table.pump]
            n_cols = 3
            values = x, 1, 2, 0, 5, 6
            """;
        com.kalix.ide.linter.parsing.INIModelParser.ParsedModel model =
                com.kalix.ide.linter.parsing.INIModelParser.parse(ini);
        com.kalix.ide.linter.model.ValidationContext context =
                com.kalix.ide.linter.model.ValidationContext.builder().model(model).build();

        assertTrue(validator.validate("table.rating(data.stage)", context).isEmpty());
        assertTrue(validator.validate("table.pump(sim.month, data.volume)", context).isEmpty());

        // Unknown table
        assertFalse(validator.validate("table.nope(1)", context).isEmpty());
        // 1D table called with 2 arguments
        List<String> errors = validator.validate("table.rating(1, 2)", context);
        assertTrue(errors.stream().anyMatch(e -> e.contains("expects 1 argument")),
                "Expected 1D arity error, got: " + errors);
        // 2D table called with 1 argument
        errors = validator.validate("table.pump(1)", context);
        assertTrue(errors.stream().anyMatch(e -> e.contains("expects 2 arguments")),
                "Expected 2D arity error, got: " + errors);
    }

    // ==================== Temporal & Clamp Functions ====================

    @Test
    @DisplayName("Temporal and clamp functions validate their arity")
    void testTemporalAndClampFunctions() {
        assertValid("moving_mean(data.x, 30, 0)");
        assertInvalid("moving_mean(data.x, 30)", "expects 3 argument");
        assertValid("steps_since(sim.new_month)");
        assertValid("clamp(data.x, 0, 10)");
        assertInvalid("clamp(1, 2)", "expects 3 argument");
    }

    // ==================== Calendar-boundary Sim Flags ====================

    @Test
    @DisplayName("Calendar-boundary sim flags are valid; unknown ones are rejected")
    void testCalendarBoundarySimFlags() {
        assertValid("sim.new_day");
        assertValid("sim.new_month");
        assertValid("sim.new_year");
        assertInvalid("sim.new_week", "Unknown sim variable");
    }

    // ==================== Engine-drift Function Names ====================

    @Test
    @DisplayName("Drift-prone function names are rejected with a did-you-mean suggestion")
    void testDriftFunctionSuggestions() {
        assertInvalid("running_mean(data.x, 5, 0)", "Unknown function");
        assertInvalid("running_mean(data.x, 5, 0)", "did you mean 'moving_mean'");
        assertInvalid("running_sum(data.x, 5, 0)", "did you mean 'moving_sum'");
        assertInvalid("rolling_mean(data.x, 5, 0)", "did you mean 'moving_mean'");
        assertInvalid("days_since(sim.new_day)", "did you mean 'steps_since'");
        assertInvalid("avg(data.x, data.y)", "did you mean 'mean'");
        assertInvalid("log(data.x)", "did you mean 'ln'");
    }

    // ==================== Program Blocks ====================

    @Test
    @DisplayName("A well-formed program block is valid")
    void testValidProgramBlock() {
        assertValid("{ x = data.a * 2; assert(x >= 0); x + 1 }");
    }

    @Test
    @DisplayName("Program blocks that never yield a bare result are rejected")
    void testProgramBlockNoResult() {
        assertInvalid("{ x = 1; x; }", "no result value");
        assertInvalid("{ x = 1; }", "no result value");
        assertInvalid("{}", "no result value");
    }

    @Test
    @DisplayName("A non-final bare expression in a block has no effect")
    void testProgramBlockStatementHasNoEffect() {
        assertInvalid("{ 1 + 2; 3 }", "Statement has no effect");
    }

    @Test
    @DisplayName("Assigning to a dotted model reference in a block is rejected")
    void testProgramBlockCannotAssignDotted() {
        assertInvalid("{ data.x = 1; 1 }", "Cannot assign to");
    }

    @Test
    @DisplayName("A builtin function name cannot be a local variable")
    void testProgramBlockBuiltinLocalName() {
        assertInvalid("{ min = 1; min }", "builtin function name");
    }

    @Test
    @DisplayName("A local used before assignment is rejected")
    void testProgramBlockUsedBeforeAssigned() {
        assertInvalid("{ y = x + 1; y }", "used before it is assigned");
    }

    @Test
    @DisplayName("A block missing its closing brace reports as unclosed")
    void testProgramBlockUnclosed() {
        assertInvalid("{ x = 1; x", "Unclosed program block");
    }

    @Test
    @DisplayName("A plain assert (outside a block) is rejected as a statement")
    void testAssertAsPlainExpression() {
        assertInvalid("assert(data.x > 0)", "statement");
    }

    // ==================== User-defined Function References ====================

    @Test
    @DisplayName("User-defined fn calls need no unknown-function error without a model")
    void testFnCallWithoutModel() {
        assertValid("fn.double(5)");
    }

    @Test
    @DisplayName("A bare fn reference must be called with parentheses")
    void testBareFnReference() {
        assertInvalid("fn.double", "must be called");
    }

    @Test
    @DisplayName("Dotted fn names are rejected")
    void testDottedFnReference() {
        assertInvalid("fn.a.b(1)", "cannot contain dots");
    }

    // ==================== Var References ====================

    @Test
    @DisplayName("Var references validate their shape and reject forward lookups")
    void testVarReferences() {
        assertValid("var.acct.headroom");
        assertInvalid("var.acct", "Invalid var reference");
        assertInvalid("var.acct.headroom[1, 0]", "Forward lookup");
        assertValid("var.acct.headroom[-1, 0]");
    }

    // ==================== Fn Body Validation ====================

    @Test
    @DisplayName("Fn bodies resolve bare names against their parameters")
    void testValidateFnBody() {
        assertFnBodyValid("x * 2", List.of("x"));
        assertFnBodyInvalid("y * 2", List.of("x"), "used before it is assigned");
        assertFnBodyValid("{ m = x + 1; m * 2 }", List.of("x"));
        // 'this.' is late-bound to the calling node, so it passes here.
        assertFnBodyValid("this.inflow[-1, 0] + x", List.of("x"));
    }

    // ==================== Helper Methods ====================

    private void assertFnBodyValid(String body, List<String> params) {
        List<String> errors = validator.validateFnBody(body, params,
                com.kalix.ide.linter.model.ValidationContext.empty());
        assertTrue(errors.isEmpty(),
            "Fn body '" + body + "' should be valid, but got errors: " + errors);
    }

    private void assertFnBodyInvalid(String body, List<String> params, String expectedError) {
        List<String> errors = validator.validateFnBody(body, params,
                com.kalix.ide.linter.model.ValidationContext.empty());
        assertFalse(errors.isEmpty(),
            "Fn body '" + body + "' should be invalid");
        assertTrue(errors.stream().anyMatch(e -> e.contains(expectedError)),
            "Expected error containing '" + expectedError + "', but got: " + errors);
    }

    private void assertValid(String expression) {
        List<String> errors = validator.validate(expression);
        assertTrue(errors.isEmpty(),
            "Expression '" + expression + "' should be valid, but got errors: " + errors);
    }

    private void assertInvalid(String expression, String expectedError) {
        List<String> errors = validator.validate(expression);
        assertFalse(errors.isEmpty(),
            "Expression '" + expression + "' should be invalid");

        boolean foundMatch = errors.stream()
            .anyMatch(error -> error.contains(expectedError));

        assertTrue(foundMatch,
            "Expected error containing '" + expectedError + "', but got: " + errors);
    }
}
