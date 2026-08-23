package com.kalix.ide.language;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The single Java home for the Kalix expression language definition.
 *
 * <p>The expression language used to live in five hand-synced tables scattered
 * across the linter and autocomplete code ({@code KNOWN_FUNCTIONS},
 * {@code KNOWN_SIM_VARIABLES}, {@code RESERVED}, {@code BUILTIN_FUNCTIONS},
 * {@code SIM_VARIABLES}) held together by "keep in sync" comments. They are now
 * all derived from this class, so a new builtin is added in one Java place.</p>
 *
 * <p>This class is the Java mirror of the engine's reserved-name registry
 * (single source of truth: {@code src/functions/functions.rs}). It carries, per
 * builtin: name, arity (negative = variadic minimum argument count), a signature
 * string, a one-line description, and whether it is stateful (a temporal builtin
 * resolved at lowering). It also carries the simulation variables, the reserved
 * words, and the bare-name pattern.</p>
 *
 * <h2>Consumers</h2>
 * <ul>
 *   <li>{@code FunctionExpressionValidator} — {@code KNOWN_FUNCTIONS} (arities)
 *       and {@code KNOWN_SIM_VARIABLES} derive from here; the local-shadow guard
 *       names the reserved tier via {@link #reservedTier(String)}.</li>
 *   <li>{@code FnSectionValidator} — the {@code [fn]} name/param reservation
 *       check uses {@link #reservedTier(String)}; the bare-name rule uses
 *       {@link #BARE_NAME}.</li>
 *   <li>{@code KalixCompletionProvider} — builtin and sim-variable completions
 *       derive from {@link #BUILTINS} and {@link #SIM_VARIABLES}.</li>
 * </ul>
 *
 * <h2>Checklist for adding a builtin (keep this current — mirror of the one atop
 * {@code src/functions/functions.rs})</h2>
 * <ol>
 *   <li><b>Engine (Rust):</b> a pure function gets its enum variant +
 *       {@code from_name} + {@code name} + {@code call} arms in
 *       {@code functions.rs} plus a lowering arm in {@code dynamic_input.rs}; a
 *       stateful function gets its name added to {@code STATEFUL_FUNCTIONS} plus
 *       a {@code lower_stateful_call} arm and its state layout.</li>
 *   <li><b>IDE (here):</b> add a {@link Builtin} entry to {@link #BUILTINS} with
 *       its name, arity, signature, description, and stateful flag. Everything
 *       downstream (linter arity map, {@code [fn]} reservation, autocomplete)
 *       picks it up automatically.</li>
 *   <li><b>Tests:</b> add the name to the engine-drift pins in
 *       {@code ExpressionLanguageCrossSyncTest} (and the rejected-spelling
 *       suggestion in {@code FunctionExpressionValidator} if it has a common
 *       wrong spelling).</li>
 * </ol>
 */
public final class ExpressionLanguage {

    private ExpressionLanguage() {}

    /**
     * One builtin function: its name, arity, human signature, one-line
     * description, and whether it is stateful (a temporal builtin).
     *
     * @param name        the lowercase bare name (e.g. {@code "moving_mean"})
     * @param arity        fixed argument count, or a negative value whose
     *                     magnitude is the variadic minimum (e.g. {@code -2} for
     *                     "at least 2")
     * @param signature   a display signature (e.g. {@code "clamp(x, lo, hi)"})
     * @param description one-line description for tooltips
     * @param stateful    true for the temporal builtins (moving_*, *_since),
     *                     which the engine resolves at lowering rather than as
     *                     a {@code BuiltinFunction} enum variant
     */
    public record Builtin(String name, int arity, String signature, String description, boolean stateful) {
        /** The reserved tier for error messages, mirroring the engine's
         *  {@code reserved_name_kind}: "builtin function" or "stateful function". */
        public String reservedTier() {
            return stateful ? "stateful function" : "builtin function";
        }

        /** Display line for autocomplete: {@code "signature - description"}. */
        public String completionText() {
            return signature + " - " + description;
        }
    }

    /** One simulation variable: its dotted reference and a one-line description. */
    public record SimVariable(String name, String description) {}

    /**
     * Every builtin recognised by the parser — the 25 pure builtins, the 9
     * temporal (stateful) builtins, and the 2 calendar functions. Mirrors the
     * engine's {@code BuiltinFunction} enum, {@code STATEFUL_FUNCTIONS}, and
     * {@code CALENDAR_FUNCTIONS} ({@code src/functions/functions.rs}).
     */
    public static final List<Builtin> BUILTINS = List.of(
            // Conditional
            new Builtin("if", 3, "if(cond, a, b)", "a when cond is true, otherwise b", false),

            // Aggregation (variadic; negative arity = minimum argument count)
            new Builtin("min", -2, "min(a, b, ...)", "smallest of its arguments", false),
            new Builtin("max", -2, "max(a, b, ...)", "largest of its arguments", false),
            new Builtin("sum", -1, "sum(a, ...)", "sum of its arguments", false),
            new Builtin("mean", -1, "mean(a, ...)", "arithmetic mean of its arguments", false),

            // Single-argument math
            new Builtin("abs", 1, "abs(x)", "absolute value", false),
            new Builtin("sqrt", 1, "sqrt(x)", "square root", false),
            new Builtin("sin", 1, "sin(x)", "sine (radians)", false),
            new Builtin("cos", 1, "cos(x)", "cosine (radians)", false),
            new Builtin("tan", 1, "tan(x)", "tangent (radians)", false),
            new Builtin("asin", 1, "asin(x)", "arcsine (radians)", false),
            new Builtin("acos", 1, "acos(x)", "arccosine (radians)", false),
            new Builtin("atan", 1, "atan(x)", "arctangent (radians)", false),
            new Builtin("exp", 1, "exp(x)", "e raised to the power x", false),
            new Builtin("ln", 1, "ln(x)", "natural logarithm", false),
            new Builtin("log10", 1, "log10(x)", "base-10 logarithm", false),
            new Builtin("log2", 1, "log2(x)", "base-2 logarithm", false),
            new Builtin("ceil", 1, "ceil(x)", "round up to an integer", false),
            new Builtin("floor", 1, "floor(x)", "round down to an integer", false),
            new Builtin("round", 1, "round(x)", "round to the nearest integer", false),
            new Builtin("sign", 1, "sign(x)", "-1, 0, or 1 by the sign of x", false),
            new Builtin("is_leap_year", 1, "is_leap_year(yyyy)", "1 in a Gregorian leap year, else 0", false),

            // Two-argument math
            new Builtin("pow", 2, "pow(x, y)", "x raised to the power y", false),
            new Builtin("atan2", 2, "atan2(y, x)", "angle of the vector (x, y)", false),

            // Three-argument
            new Builtin("clamp", 3, "clamp(x, lo, hi)", "constrain to a range", false),

            // Temporal (stateful): fixed-window moving_*(x, n, default)
            new Builtin("moving_sum", 3, "moving_sum(x, n, default)", "sum over the last n steps", true),
            new Builtin("moving_mean", 3, "moving_mean(x, n, default)", "mean over the last n steps", true),
            new Builtin("moving_min", 3, "moving_min(x, n, default)", "minimum over the last n steps", true),
            new Builtin("moving_max", 3, "moving_max(x, n, default)", "maximum over the last n steps", true),

            // Temporal (stateful): event-windowed *_since (last argument is the reset condition)
            new Builtin("sum_since", 2, "sum_since(x, reset)", "sum of x since reset last fired", true),
            new Builtin("min_since", 2, "min_since(x, reset)", "minimum of x since reset last fired", true),
            new Builtin("max_since", 2, "max_since(x, reset)", "maximum of x since reset last fired", true),
            new Builtin("count_since", 2, "count_since(cond, reset)", "steps on which cond held since reset last fired", true),
            new Builtin("steps_since", 1, "steps_since(reset)", "steps since reset last fired", true),

            // Temporal (stateful): latching
            new Builtin("latch", 3, "latch(x, condition, init)", "x when condition holds, else the value held from the previous step (init until it first holds)", true),

            // Calendar (context: read the simulation clock, resolved at lowering)
            new Builtin("month_at", 1, "month_at(n)", "month (1-12) at the current date + n days", false),
            new Builtin("days_in_month_at", 1, "days_in_month_at(n)", "days in the month at the current date + n days", false)
    );

    /**
     * The calendar functions — context functions that read the simulation
     * clock, resolved at lowering like the stateful family. Mirrors the
     * engine's {@code CALENDAR_FUNCTIONS}; drives their reserved-tier name.
     */
    public static final Set<String> CALENDAR_FUNCTIONS = Set.of("month_at", "days_in_month_at");

    /**
     * The simulation variables. Mirrors the engine's {@code sim.*} set; names
     * carry the {@code sim.} prefix so they compare directly against references.
     */
    public static final List<SimVariable> SIM_VARIABLES = List.of(
            new SimVariable("sim.year", "sim.year - calendar year of the current step"),
            new SimVariable("sim.month", "sim.month - month of the year (1-12)"),
            new SimVariable("sim.day", "sim.day - day of the month"),
            new SimVariable("sim.day_of_year", "sim.day_of_year - day of the year (1-366)"),
            new SimVariable("sim.step", "sim.step - zero-based step index"),
            new SimVariable("sim.new_day", "sim.new_day - 1 on the first step of a new day, else 0"),
            new SimVariable("sim.new_month", "sim.new_month - 1 on the first step of a new month, else 0"),
            new SimVariable("sim.new_year", "sim.new_year - 1 on the first step of a new year, else 0"),
            new SimVariable("sim.days_in_month", "sim.days_in_month - days in the current month (28-31, leap-aware)"),
            new SimVariable("sim.days_in_year", "sim.days_in_year - days in the current year (365 or 366)"),
            new SimVariable("sim.is_leap", "sim.is_leap - 1 in a leap year, else 0")
    );

    /**
     * Grammar keywords: names with statement-level meaning that are neither
     * builtins nor stateful functions. Mirrors the engine's {@code RESERVED_WORDS}.
     * {@code this} is the enclosing definition; {@code self} is the per-target
     * binding of [ras.*] action arguments (expression-naming §2.8).
     */
    public static final Set<String> RESERVED_WORDS = Set.of("assert", "this", "self");

    /**
     * The strict rule for bare definition names — {@code [fn]} function names
     * and parameters, {@code [var.*]} block and key names: a lowercase letter,
     * then lowercase letters, digits, or underscores. Mirrors the engine's
     * {@code is_valid_bare_name} ({@code src/misc/misc_functions.rs}).
     */
    public static final Pattern BARE_NAME = Pattern.compile("^[a-z][a-z0-9_]*$");

    // --- Derived lookups (built once) ---

    private static final Map<String, Builtin> BY_NAME;
    private static final Map<String, Integer> ARITIES;
    private static final Set<String> SIM_VARIABLE_NAMES;

    static {
        Map<String, Builtin> byName = new LinkedHashMap<>();
        Map<String, Integer> arities = new LinkedHashMap<>();
        for (Builtin b : BUILTINS) {
            byName.put(b.name(), b);
            arities.put(b.name(), b.arity());
        }
        BY_NAME = Map.copyOf(byName);
        ARITIES = Map.copyOf(arities);

        List<String> simNames = new ArrayList<>();
        for (SimVariable v : SIM_VARIABLES) {
            simNames.add(v.name());
        }
        SIM_VARIABLE_NAMES = Set.copyOf(simNames);
    }

    /** Function name to arity (negative = variadic minimum). Immutable. */
    public static Map<String, Integer> functionArities() {
        return ARITIES;
    }

    /** The set of builtin function names (pure + stateful). Immutable. */
    public static Set<String> functionNames() {
        return BY_NAME.keySet();
    }

    /** The set of {@code sim.*} variable references. Immutable. */
    public static Set<String> simVariableNames() {
        return SIM_VARIABLE_NAMES;
    }

    /** The builtin with this lowercase name, or null. */
    public static Builtin builtin(String lowerName) {
        return BY_NAME.get(lowerName);
    }

    /** True if this lowercase name is a moving_* fixed-window builtin. */
    public static boolean isMovingWindowFunction(String lowerName) {
        Builtin b = BY_NAME.get(lowerName);
        return b != null && b.stateful() && b.name().startsWith("moving_");
    }

    /**
     * The reserved tier of a lowercase bare name, or null when the name is free
     * for the modeller. Mirrors the engine's {@code reserved_name_kind}:
     * "builtin function", "stateful function", "reserved word", or
     * "calendar function".
     */
    public static String reservedTier(String lowerName) {
        if (CALENDAR_FUNCTIONS.contains(lowerName)) {
            return "calendar function";
        }
        Builtin b = BY_NAME.get(lowerName);
        if (b != null) {
            return b.reservedTier();
        }
        if (RESERVED_WORDS.contains(lowerName)) {
            return "reserved word";
        }
        return null;
    }

    /** True if the string satisfies the strict bare-name rule. */
    public static boolean isBareName(String s) {
        return BARE_NAME.matcher(s).matches();
    }
}
