package com.kalix.ide.windows.optimisation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link ParameterExpressionLibrary}, the table that turns a
 * parameter name reported by kalixcli into a default calibration expression.
 *
 * <p>The most valuable test here is the <b>engine-drift pin</b>: the parameter
 * names below mirror the engine's {@code list_params()} implementations. If a
 * node type gains a parameter — or a new node type becomes optimisable — the
 * pin must be updated, and the coverage test then proves the library actually
 * has an entry for it. Without this, a missing entry degrades silently into the
 * "Unrecognized Parameter Types" dialog at runtime.</p>
 */
class ParameterExpressionLibraryTest {

    // ==================== Engine-drift pins ====================
    // Each list mirrors an OptimisableComponent::list_params() in the Rust engine.

    /** {@code src/nodes/gr4j_node.rs} — GR4J's fixed parameters. */
    private static final List<String> GR4J_PARAMS = List.of("x1", "x2", "x3", "x4");

    /** {@code src/nodes/sacramento_node.rs} — Sacramento's fixed parameters. */
    private static final List<String> SACRAMENTO_PARAMS = List.of(
            "adimp", "lzfpm", "lzfsm", "lzpk", "lzsk", "lztwm",
            "pctim", "pfree", "rexp", "sarva", "side",
            "ssout", "uzfwm", "uzk", "uztwm", "zperc", "laguh");

    /**
     * {@code src/nodes/rainfall_weights.rs} — appended to GR4J and Sacramento when
     * the rain input is a LinearCombination: a bias plus n-1 distribution params.
     */
    private static final List<String> RAINFALL_PARAMS = List.of(
            "rf_bias", "rf_d0", "rf_d1", "rf_d2");

    /**
     * {@code src/nodes/routing_node.rs} — a routing node reports the non-linear
     * Muskingum pair, or the piecewise-linear travel times, but never both.
     */
    private static final List<String> ROUTING_NLM_PARAMS = List.of("nlm_k", "nlm_m");
    private static final List<String> ROUTING_PWL_PARAMS = List.of(
            "pwl_tt_0", "pwl_tt_1", "pwl_tt_2", "pwl_tt_10");

    /** Every node parameter the engine can emit, as a fully-qualified name. */
    private static List<String> allEngineNodeParams() {
        List<String> names = new ArrayList<>();
        for (String p : GR4J_PARAMS) names.add("node.mygr4j." + p);
        for (String p : SACRAMENTO_PARAMS) names.add("node.mysac." + p);
        for (String p : RAINFALL_PARAMS) names.add("node.mygr4j." + p);
        for (String p : RAINFALL_PARAMS) names.add("node.mysac." + p);
        for (String p : ROUTING_NLM_PARAMS) names.add("node.myrouting." + p);
        for (String p : ROUTING_PWL_PARAMS) names.add("node.myrouting." + p);
        return names;
    }

    // ==================== Coverage against the engine ====================

    @Test
    @DisplayName("Every parameter the engine can report has a library entry")
    void testEveryEngineParameterIsRecognised() {
        for (String name : allEngineNodeParams()) {
            assertTrue(ParameterExpressionLibrary.isParameterTypeRecognized(name),
                    "No library entry for engine parameter: " + name);
        }
        // Constants are reported already carrying their "const." prefix.
        assertTrue(ParameterExpressionLibrary.isParameterTypeRecognized("const.rain_scale"));
    }

    @Test
    @DisplayName("Every engine parameter generates a well-formed, ordered range")
    void testEveryEngineParameterGeneratesValidExpression() throws Exception {
        List<String> names = new ArrayList<>(allEngineNodeParams());
        names.add("const.rain_scale");

        for (String name : names) {
            Range r = parse(ParameterExpressionLibrary.generateExpression(name, 7), name);
            assertEquals(7, r.geneIndex, "gene index not substituted for " + name);
            assertTrue(r.min < r.max,
                    "min must be below max for " + name + " (got " + r.min + ".." + r.max + ")");
            if (r.function.equals("log_range")) {
                // log_range takes log10 of both bounds; a non-positive bound yields NaN
                // in the engine (see Transform::Log in src/numerical/opt/parameter_mapping.rs).
                assertTrue(r.min > 0,
                        "log_range lower bound must be positive for " + name + " (got " + r.min + ")");
            }
        }
    }

    // ==================== Routing parameters ====================

    @Test
    @DisplayName("Non-linear Muskingum parameters use their documented ranges")
    void testRoutingNlmExpressions() throws Exception {
        assertEquals("log_range(g(1),100,500000)",
                ParameterExpressionLibrary.generateExpression("node.r1.nlm_k", 1));
        assertEquals("lin_range(g(2),0.5,1)",
                ParameterExpressionLibrary.generateExpression("node.r1.nlm_m", 2));
    }

    @Test
    @DisplayName("Every piecewise-linear travel time shares the one template")
    void testRoutingPwlExpressions() throws Exception {
        assertEquals("lin_range(g(1),0,5)",
                ParameterExpressionLibrary.generateExpression("node.r1.pwl_tt_0", 1));
        assertEquals("lin_range(g(2),0,5)",
                ParameterExpressionLibrary.generateExpression("node.r1.pwl_tt_1", 2));
        assertEquals("lin_range(g(3),0,5)",
                ParameterExpressionLibrary.generateExpression("node.r1.pwl_tt_47", 3));
    }

    // ==================== Type detection ====================

    @Test
    @DisplayName("A const. prefix wins over the trailing segment")
    void testConstantDetection() {
        assertEquals("constant", ParameterExpressionLibrary.detectParameterType("const.rain_scale"));
        // A constant may legitimately be named after a model parameter; the prefix decides.
        assertEquals("constant", ParameterExpressionLibrary.detectParameterType("const.x1"));
        assertEquals("constant", ParameterExpressionLibrary.detectParameterType("const.nested.name"));
    }

    @Test
    @DisplayName("A node parameter's type is the segment after the last dot")
    void testNodeParameterDetection() {
        assertEquals("x1", ParameterExpressionLibrary.detectParameterType("node.mygr4jnode.x1"));
        assertEquals("lztwm", ParameterExpressionLibrary.detectParameterType("node.sac_a.lztwm"));
        assertEquals("nlm_k", ParameterExpressionLibrary.detectParameterType("node.r1.nlm_k"));
    }

    @Test
    @DisplayName("Indexed families collapse onto their prefix")
    void testIndexedFamilyNormalisation() {
        assertEquals("rf_d", ParameterExpressionLibrary.detectParameterType("node.n1.rf_d0"));
        assertEquals("rf_d", ParameterExpressionLibrary.detectParameterType("node.n1.rf_d12"));
        assertEquals("pwl_tt_", ParameterExpressionLibrary.detectParameterType("node.n1.pwl_tt_0"));
        assertEquals("pwl_tt_", ParameterExpressionLibrary.detectParameterType("node.n1.pwl_tt_12"));
    }

    @Test
    @DisplayName("Only a purely numeric suffix collapses a family")
    void testNonNumericSuffixIsNotCollapsed() {
        // Guards the prefix match from swallowing unrelated names that merely share a stem.
        assertEquals("rf_debug", ParameterExpressionLibrary.detectParameterType("node.n1.rf_debug"));
        assertEquals("rf_d1a", ParameterExpressionLibrary.detectParameterType("node.n1.rf_d1a"));
        assertEquals("pwl_tt_x", ParameterExpressionLibrary.detectParameterType("node.n1.pwl_tt_x"));
    }

    @Test
    @DisplayName("A name with no dot has no detectable type")
    void testUndetectableNames() {
        assertNull(ParameterExpressionLibrary.detectParameterType("x1"));
        assertNull(ParameterExpressionLibrary.detectParameterType(""));
        // A trailing dot leaves no segment to read.
        assertNull(ParameterExpressionLibrary.detectParameterType("node.n1."));
    }

    // ==================== Gene index substitution ====================

    @Test
    @DisplayName("The gene index is substituted into the template")
    void testGeneIndexSubstitution() throws Exception {
        assertEquals("lin_range(g(1),1,1500)",
                ParameterExpressionLibrary.generateExpression("node.n1.x1", 1));
        assertEquals("lin_range(g(42),1,1500)",
                ParameterExpressionLibrary.generateExpression("node.n1.x1", 42));
    }

    @Test
    @DisplayName("Two parameters may share a gene index, tying them together")
    void testSameGeneIndexProducesTiedExpressions() throws Exception {
        // The engine treats a shared g(i) as a tie rather than a new dimension
        // (see src/numerical/opt/parameter_mapping.rs).
        String a = ParameterExpressionLibrary.generateExpression("node.sac_a.adimp", 1);
        String b = ParameterExpressionLibrary.generateExpression("node.sac_b.adimp", 1);
        assertEquals(a, b);
    }

    // ==================== Unrecognised parameters ====================

    @Test
    @DisplayName("An unknown type is rejected rather than given an arbitrary range")
    void testUnknownTypeThrows() {
        assertThrows(ParameterExpressionLibrary.UnrecognizedParameterTypeException.class,
                () -> ParameterExpressionLibrary.generateExpression("node.n1.not_a_param", 1));
        assertFalse(ParameterExpressionLibrary.isParameterTypeRecognized("node.n1.not_a_param"));
    }

    @Test
    @DisplayName("An undetectable name is rejected")
    void testUndetectableNameThrows() {
        assertThrows(ParameterExpressionLibrary.UnrecognizedParameterTypeException.class,
                () -> ParameterExpressionLibrary.generateExpression("x1", 1));
        assertFalse(ParameterExpressionLibrary.isParameterTypeRecognized("x1"));
    }

    // ==================== Helpers ====================

    /** A parsed {@code lin_range(g(i),min,max)} / {@code log_range(...)} expression. */
    private record Range(String function, int geneIndex, double min, double max) {}

    /**
     * Parses a generated expression. Deliberately strict: the engine parses these
     * as Kalix expressions, so a malformed template should fail the test loudly
     * rather than be quietly tolerated.
     */
    private static Range parse(String expression, String paramName) {
        int open = expression.indexOf('(');
        assertTrue(open > 0, "no function call in expression for " + paramName + ": " + expression);
        String function = expression.substring(0, open);
        assertTrue(function.equals("lin_range") || function.equals("log_range"),
                "unexpected transform '" + function + "' for " + paramName);
        assertTrue(expression.endsWith(")"), "unterminated expression for " + paramName);

        // The gene term g(i) contains no comma, so a plain split yields the 3 arguments.
        String[] args = expression.substring(open + 1, expression.length() - 1).split(",");
        assertEquals(3, args.length, "expected 3 arguments for " + paramName + ": " + expression);
        assertTrue(args[0].startsWith("g(") && args[0].endsWith(")"),
                "first argument must be a gene lookup for " + paramName + ": " + args[0]);

        int geneIndex = Integer.parseInt(args[0].substring(2, args[0].length() - 1));
        return new Range(function, geneIndex, Double.parseDouble(args[1]), Double.parseDouble(args[2]));
    }
}
