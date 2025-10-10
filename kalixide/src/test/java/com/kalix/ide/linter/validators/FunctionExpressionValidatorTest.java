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
        FunctionExpressionValidator.clearCache(); // Clear cache before each test
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
        assertValid("data.a > 0 & data.b > 0");
        assertValid("data.a > 0 | data.b > 0");
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
        assertValid("log(data.x)");
        assertValid("log10(data.x)");

        // Aggregation
        assertValid("max(1, 2, 3)");
        assertValid("min(data.a, data.b)");
        assertValid("mean(data.x, data.y, data.z)");
        assertValid("sum(1, 2, 3, 4, 5)");

        // Two argument
        assertValid("pow(2, 8)");
        assertValid("atan2(data.y, data.x)");
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
        assertInvalid("data.a && data.b", "Invalid operator '&&'");
        assertInvalid("data.a || data.b", "Invalid operator '||'");
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
    @DisplayName("Trailing operators should be invalid")
    void testTrailingOperators() {
        assertInvalid("data.evap *", "Expected number");
        assertInvalid("5 +", "Expected number");
    }

    @Test
    @DisplayName("Division by zero constant should warn")
    void testDivisionByZeroConstant() {
        assertInvalid("5 / 0", "Division by zero");
        assertInvalid("data.x / 0", "Division by zero");
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

    // ==================== Performance Tests ====================

    @Test
    @DisplayName("Caching should improve performance")
    void testCaching() {
        String expression = "if(data.temp > 20, data.evap_high, data.evap_low) * 1.2";

        // First call
        long start1 = System.nanoTime();
        validator.validate(expression);
        long time1 = System.nanoTime() - start1;

        // Second call (should use cache)
        long start2 = System.nanoTime();
        validator.validate(expression);
        long time2 = System.nanoTime() - start2;

        // Cached call should be significantly faster
        assertTrue(time2 < time1 / 2,
            "Cached validation should be at least 2x faster. First: " + time1 + "ns, Second: " + time2 + "ns");
    }

    @Test
    @DisplayName("Validation should be fast (<10ms)")
    void testPerformance() {
        String complexExpression = "if(data.temperature > 20, " +
            "max(data.evap_high * 1.2, data.evap_medium), " +
            "min(data.evap_low * 0.8, data.evap_minimum)) * " +
            "if(data.season == 1, 1.1, 0.9)";

        long start = System.nanoTime();
        for (int i = 0; i < 100; i++) {
            FunctionExpressionValidator.clearCache(); // Force re-validation
            validator.validate(complexExpression);
        }
        long elapsed = (System.nanoTime() - start) / 1_000_000; // Convert to ms

        double avgTime = elapsed / 100.0;
        assertTrue(avgTime < 10.0,
            "Average validation time should be < 10ms, was: " + avgTime + "ms");
    }

    @Test
    @DisplayName("Simple expressions should be very fast (<1ms)")
    void testFastPathPerformance() {
        long start = System.nanoTime();
        for (int i = 0; i < 10000; i++) {
            FunctionExpressionValidator.clearCache();
            validator.validate("data.evap");
            validator.validate("5.0");
            validator.validate("2 + 3");
        }
        long elapsed = (System.nanoTime() - start) / 1_000_000; // Convert to ms

        double avgTime = elapsed / 30000.0; // 10000 iterations * 3 expressions
        assertTrue(avgTime < 1.0,
            "Average fast path time should be < 1ms, was: " + avgTime + "ms");
    }

    // ==================== Helper Methods ====================

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
