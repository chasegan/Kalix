package com.kalix.ide.linter.validators;

import com.kalix.ide.linter.model.ValidationResult;
import com.kalix.ide.linter.parsing.INIModelParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for FnSectionValidator ([fn] user-defined function definitions).
 * The rules mirror the engine's load-time validation in
 * src/functions/fn_registry.rs.
 */
class FnSectionValidatorTest {

    private FnSectionValidator validator;

    @BeforeEach
    void setUp() {
        validator = new FnSectionValidator();
    }

    // ==================== Happy paths ====================

    @Test
    @DisplayName("A one-line function definition should produce no issues")
    void testOneLineFunction() {
        assertNoIssues("""
            [fn]
            storage_frac(v, cap) = v / cap
            """);
    }

    @Test
    @DisplayName("A block-bodied function with locals should produce no issues")
    void testBlockBodiedFunction() {
        assertNoIssues("""
            [fn]
            net_demand(pop, doy) = {
                base = pop * 2.0;
                peak = 1 + 0.3 * doy;
                base * peak
                }
            """);
    }

    @Test
    @DisplayName("A function may call another defined later in the file, and zero-arg forms are legal")
    void testForwardReferenceAndZeroArg() {
        assertNoIssues("""
            [fn]
            outer(x) = fn.inner(x) + 1
            flag() = 1
            inner(y) = y * 2
            """);
    }

    // ==================== Signature errors ====================

    @Test
    @DisplayName("A key that is not a signature should be flagged")
    void testBadSignatureKey() {
        assertHasIssue("[fn]\nbadkey = 1\n", "signature");
    }

    @Test
    @DisplayName("A dotted function name should be flagged")
    void testDottedName() {
        assertHasIssue("[fn]\nbad.name(x) = x\n", "no dots");
    }

    @Test
    @DisplayName("A dotted parameter should be flagged")
    void testDottedParam() {
        assertHasIssue("[fn]\nfoo(a.b) = 1\n", "no dots");
    }

    @Test
    @DisplayName("A reserved builtin used as the function name should be flagged")
    void testReservedName() {
        assertHasIssue("[fn]\nmax(x) = x\n", "reserved");
    }

    @Test
    @DisplayName("A reserved word used as a parameter should be flagged")
    void testReservedParam() {
        assertHasIssue("[fn]\nfoo(this) = 1\n", "reserved");
    }

    @Test
    @DisplayName("Duplicate parameters in a signature should be flagged")
    void testDuplicateParam() {
        assertHasIssue("[fn]\nfoo(x, x) = x\n", "Duplicate parameter");
    }

    // ==================== Cross-key errors ====================

    @Test
    @DisplayName("Duplicate function names (any arity) should be flagged")
    void testDuplicateName() {
        assertHasIssue("""
            [fn]
            foo(x) = x
            foo(a, b) = a + b
            """, "Duplicate function");
        assertHasIssue("""
            [fn]
            foo(x) = x
            foo(a, b) = a + b
            """, "no overloads");
    }

    @Test
    @DisplayName("Mutually recursive functions should be flagged, naming the cycle")
    void testMutualRecursion() {
        assertHasIssue("""
            [fn]
            a(x) = fn.b(x)
            b(x) = fn.a(x)
            """, "recursive");
        assertHasIssue("""
            [fn]
            a(x) = fn.b(x)
            b(x) = fn.a(x)
            """, "fn.a");
    }

    @Test
    @DisplayName("[fn.something] is reserved and should be flagged")
    void testReservedNamespacedSection() {
        assertHasIssue("[fn.scheme]\nfoo(x) = x\n", "reserved for future");
    }

    @Test
    @DisplayName("A bad expression in a function body should be flagged")
    void testBadBody() {
        assertHasIssue("[fn]\nfoo(x) = notafunc(x)\n", "Unknown function");
    }

    // ==================== Helper methods ====================

    private ValidationResult run(String ini) {
        INIModelParser.ParsedModel model = INIModelParser.parse(ini);
        ValidationResult result = new ValidationResult();
        validator.validate(model, null, result, null);
        return result;
    }

    private void assertNoIssues(String ini) {
        ValidationResult result = run(ini);
        assertTrue(result.getIssues().isEmpty(),
                "Expected no issues, got: " + result.getIssues());
    }

    private void assertHasIssue(String ini, String expectedMessage) {
        ValidationResult result = run(ini);
        boolean found = result.getIssues().stream()
                .anyMatch(issue -> issue.getMessage().contains(expectedMessage));
        assertTrue(found, "Expected an issue containing '" + expectedMessage
                + "', got: " + result.getIssues());
    }
}
