/// Built-in mathematical functions for the functions module — and the
/// language's reserved-name registry.
///
/// This module exposes the closed set of built-in functions accepted by the Kalix
/// expression parser as a [`BuiltinFunction`] enum. Names are resolved against this
/// set at **parse time** (see [`BuiltinFunction::from_name`]) so the AST stores a
/// resolved enum tag rather than a string. Evaluation is then a direct match — no
/// per-call string compare or hashing, which matters because parameter and inflow
/// expressions are evaluated millions of times per model run.
///
/// # The reserved-name registry (single source of truth)
///
/// Everything the language claims for itself lives in THIS file, in three tiers:
/// - [`BuiltinFunction`] — the pure, context-free builtins (`abs`, `min`, ...);
/// - [`STATEFUL_FUNCTIONS`] — the temporal builtins (`moving_*`, `*_since`),
///   which are not enum variants because they resolve at lowering, where their
///   arena state is allocated (`lower_stateful_call` in dynamic_input.rs);
/// - [`RESERVED_WORDS`] — grammar keywords (`assert`, `this`, `self`).
///
/// [`reserved_name_kind`] answers "is this name the language's?" for every
/// consumer: the program parser's local-assignment guard, `[fn]` name/param
/// validation, and anything added later. Per expression-naming §1.3, the
/// language owns the bare names — a user-definable name must never collide
/// with any tier, including tiers added in the future.
///
/// # Checklist for adding a builtin (keep this current)
///
/// 1. Pure function: add the enum variant + `from_name` + `name` + `call`
///    arms here, and the lowering arm in dynamic_input.rs (`Func1`/`Func2`/
///    `Fold`/dedicated node).
///    Stateful function: add the name to [`STATEFUL_FUNCTIONS`] here, the
///    lowering arm in `lower_stateful_call`, and its state layout/advance.
/// 2. Mirror the name, arity, and a one-line description in the IDE's single
///    Java definition: `kalixide/.../language/ExpressionLanguage.java`
///    (the linter, section validators, and autocomplete all consume it).
/// 3. Add the name to the engine-drift pins in the IDE's
///    `FunctionExpressionValidatorTest`, and the rejected-spelling suggestions
///    if the new name has a common wrong spelling (expression-naming §2.4).
/// 4. Document it in docs/functions/FUNCTIONS_DOCUMENTATION.md (function
///    table + a section if it carries semantics worth explaining).

use crate::functions::errors::EvaluationError;

/// The temporal (stateful) builtins, resolved at lowering rather than through
/// [`BuiltinFunction`]. Membership here reserves the name exactly as builtin
/// status does. `stateful_lowering_covers_registry` in the tests ties this
/// list to `lower_stateful_call`'s match arms so they cannot drift.
pub const STATEFUL_FUNCTIONS: [&str; 10] = [
    "moving_sum", "moving_mean", "moving_min", "moving_max",
    "sum_since", "min_since", "max_since", "count_since", "steps_since",
    "latch",
];

/// Grammar keywords: names with statement-level meaning that are neither
/// builtins nor stateful functions. `this` is the enclosing definition;
/// `self` is the per-target binding of [ras.*] action arguments
/// (expression-naming §2.8) — reserved everywhere so a local or [fn] name
/// can never shadow either.
pub const RESERVED_WORDS: [&str; 3] = ["assert", "this", "self"];

/// Calendar functions that read the current simulation date: like the
/// stateful builtins they are not [`BuiltinFunction`] variants because they
/// need context — they resolve at lowering (`lower_calendar_call` in
/// dynamic_input.rs) into nodes that read the DataCache clock. `month_at(n)`
/// and `days_in_month_at(n)` answer for the date n days from today, which is
/// what order-ahead pattern lookups need.
pub const CALENDAR_FUNCTIONS: [&str; 2] = ["month_at", "days_in_month_at"];

/// Is `lower` (a lowercased bare name) reserved by the language? Returns the
/// tier for error messages ("builtin function", "stateful function",
/// "reserved word"), or None when the name is free for the modeller.
pub fn reserved_name_kind(lower: &str) -> Option<&'static str> {
    if BuiltinFunction::from_name(lower).is_some() {
        return Some("builtin function");
    }
    if STATEFUL_FUNCTIONS.contains(&lower) {
        return Some("stateful function");
    }
    if RESERVED_WORDS.contains(&lower) {
        return Some("reserved word");
    }
    if CALENDAR_FUNCTIONS.contains(&lower) {
        return Some("calendar function");
    }
    None
}

/// Every built-in function recognised by the parser.
///
/// Added here are only the *pure*, *context-free* operations — `abs`, `sqrt`, etc.
/// Context-specific functions like `lin_range`, `log_range`, and `g` (optimisation)
/// are NOT built-ins; they are registered per-evaluation via
/// [`crate::functions::FunctionRegistry`].
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum BuiltinFunction {
    // Single argument
    Abs, Sqrt, Sin, Cos, Tan, Asin, Acos, Atan,
    Exp, Ln, Log10, Log2,
    Ceil, Floor, Round, Sign, IsLeapYear,

    // Two argument
    Pow, Atan2,

    // Variadic (>= 2)
    Min, Max,

    // Variadic (>= 1)
    Sum, Mean,

    // Three argument (special)
    If, Clamp,
}

impl BuiltinFunction {
    /// Look up a built-in function by name (lowercased). Returns `None` for unknown names,
    /// in which case the caller treats it as a context-function name to be resolved at
    /// evaluation time against the [`crate::functions::FunctionRegistry`].
    pub fn from_name(name: &str) -> Option<BuiltinFunction> {
        Some(match name {
            "abs"    => BuiltinFunction::Abs,
            "sqrt"   => BuiltinFunction::Sqrt,
            "sin"    => BuiltinFunction::Sin,
            "cos"    => BuiltinFunction::Cos,
            "tan"    => BuiltinFunction::Tan,
            "asin"   => BuiltinFunction::Asin,
            "acos"   => BuiltinFunction::Acos,
            "atan"   => BuiltinFunction::Atan,
            "exp"    => BuiltinFunction::Exp,
            "ln"     => BuiltinFunction::Ln,
            "log10"  => BuiltinFunction::Log10,
            "log2"   => BuiltinFunction::Log2,
            "ceil"   => BuiltinFunction::Ceil,
            "floor"  => BuiltinFunction::Floor,
            "round"  => BuiltinFunction::Round,
            "sign"   => BuiltinFunction::Sign,
            "is_leap_year" => BuiltinFunction::IsLeapYear,
            "pow"    => BuiltinFunction::Pow,
            "atan2"  => BuiltinFunction::Atan2,
            "min"    => BuiltinFunction::Min,
            "max"    => BuiltinFunction::Max,
            "sum"    => BuiltinFunction::Sum,
            "mean"   => BuiltinFunction::Mean,
            "if"     => BuiltinFunction::If,
            "clamp"  => BuiltinFunction::Clamp,
            _ => return None,
        })
    }

    /// Human-readable name (lowercase) for error messages.
    pub fn name(&self) -> &'static str {
        match self {
            BuiltinFunction::Abs => "abs",
            BuiltinFunction::Sqrt => "sqrt",
            BuiltinFunction::Sin => "sin",
            BuiltinFunction::Cos => "cos",
            BuiltinFunction::Tan => "tan",
            BuiltinFunction::Asin => "asin",
            BuiltinFunction::Acos => "acos",
            BuiltinFunction::Atan => "atan",
            BuiltinFunction::Exp => "exp",
            BuiltinFunction::Ln => "ln",
            BuiltinFunction::Log10 => "log10",
            BuiltinFunction::Log2 => "log2",
            BuiltinFunction::Ceil => "ceil",
            BuiltinFunction::Floor => "floor",
            BuiltinFunction::Round => "round",
            BuiltinFunction::Sign => "sign",
            BuiltinFunction::IsLeapYear => "is_leap_year",
            BuiltinFunction::Pow => "pow",
            BuiltinFunction::Atan2 => "atan2",
            BuiltinFunction::Min => "min",
            BuiltinFunction::Max => "max",
            BuiltinFunction::Sum => "sum",
            BuiltinFunction::Mean => "mean",
            BuiltinFunction::If => "if",
            BuiltinFunction::Clamp => "clamp",
        }
    }

    /// Evaluate the built-in with the given arguments.
    ///
    /// Mathematical domain errors (sqrt of negative, log of zero, etc.) return NaN
    /// or ∞ per IEEE 754, rather than returning errors. Argument count errors do
    /// return [`EvaluationError::InvalidFunctionArguments`].
    pub fn call(&self, args: &[f64]) -> Result<f64, EvaluationError> {
        match self {
            // Single argument
            BuiltinFunction::Abs    => Self::single(self.name(), args, |x| x.abs()),
            BuiltinFunction::Sqrt   => Self::single(self.name(), args, |x| x.sqrt()),
            BuiltinFunction::Sin    => Self::single(self.name(), args, |x| x.sin()),
            BuiltinFunction::Cos    => Self::single(self.name(), args, |x| x.cos()),
            BuiltinFunction::Tan    => Self::single(self.name(), args, |x| x.tan()),
            BuiltinFunction::Asin   => Self::single(self.name(), args, |x| x.asin()),
            BuiltinFunction::Acos   => Self::single(self.name(), args, |x| x.acos()),
            BuiltinFunction::Atan   => Self::single(self.name(), args, |x| x.atan()),
            BuiltinFunction::Exp    => Self::single(self.name(), args, |x| x.exp()),
            BuiltinFunction::Ln     => Self::single(self.name(), args, |x| x.ln()),
            BuiltinFunction::Log10  => Self::single(self.name(), args, |x| x.log10()),
            BuiltinFunction::Log2   => Self::single(self.name(), args, |x| x.log2()),
            BuiltinFunction::Ceil   => Self::single(self.name(), args, |x| x.ceil()),
            BuiltinFunction::Floor  => Self::single(self.name(), args, |x| x.floor()),
            BuiltinFunction::Round  => Self::single(self.name(), args, |x| x.round()),
            BuiltinFunction::Sign   => Self::single(self.name(), args, sign),
            BuiltinFunction::IsLeapYear => Self::single(self.name(), args, is_leap_year_f),

            // Two argument
            BuiltinFunction::Pow => {
                if args.len() != 2 { return Self::arity_err(self.name(), 2, args.len()); }
                Ok(args[0].powf(args[1]))
            }
            BuiltinFunction::Atan2 => {
                if args.len() != 2 { return Self::arity_err(self.name(), 2, args.len()); }
                Ok(args[0].atan2(args[1]))
            }

            // Variadic (>= 2)
            BuiltinFunction::Min => {
                if args.len() < 2 { return Self::arity_err(self.name(), 2, args.len()); }
                Ok(args.iter().fold(args[0], |acc, &x| acc.min(x)))
            }
            BuiltinFunction::Max => {
                if args.len() < 2 { return Self::arity_err(self.name(), 2, args.len()); }
                Ok(args.iter().fold(args[0], |acc, &x| acc.max(x)))
            }

            // Variadic (>= 1)
            BuiltinFunction::Sum => {
                if args.is_empty() { return Self::arity_err(self.name(), 1, 0); }
                Ok(args.iter().sum())
            }
            BuiltinFunction::Mean => {
                if args.is_empty() { return Self::arity_err(self.name(), 1, 0); }
                Ok(args.iter().sum::<f64>() / args.len() as f64)
            }

            // Three argument
            BuiltinFunction::If => {
                if args.len() != 3 { return Self::arity_err(self.name(), 3, args.len()); }
                Ok(if args[0] != 0.0 { args[1] } else { args[2] })
            }
            BuiltinFunction::Clamp => {
                if args.len() != 3 { return Self::arity_err(self.name(), 3, args.len()); }
                // Deliberately NOT f64::clamp, which panics when lo > hi or on NaN
                // bounds. This max/min composition is total (never panics): lo > hi
                // yields hi (the min applied last always wins), and — consistent with
                // the min/max builtins — f64::max/min suppress NaN (the non-NaN
                // operand wins), so a NaN input or bound does not propagate.
                Ok(args[0].max(args[1]).min(args[2]))
            }
        }
    }

    fn single<F>(name: &str, args: &[f64], f: F) -> Result<f64, EvaluationError>
    where
        F: Fn(f64) -> f64,
    {
        if args.len() != 1 {
            Self::arity_err(name, 1, args.len())
        } else {
            Ok(f(args[0]))
        }
    }

    fn arity_err(name: &str, expected: usize, found: usize) -> Result<f64, EvaluationError> {
        Err(EvaluationError::InvalidFunctionArguments {
            function: name.to_string(),
            expected,
            found,
        })
    }
}

/// Sign of a value: -1, 0, or +1 (NaN propagates). Deliberately NOT
/// `f64::signum`, which maps ±0.0 to ±1.0 — a modeller reading `sign(0)`
/// expects 0.
pub fn sign(x: f64) -> f64 {
    if x > 0.0 {
        1.0
    } else if x < 0.0 {
        -1.0
    } else {
        x // 0.0, -0.0, or NaN: all map to themselves
    }
}

/// `is_leap_year(yyyy)` as an f64 builtin: 1 for Gregorian leap years, else 0.
/// The year is rounded to the nearest integer (it usually arrives as
/// `sim.year`, already exact).
pub fn is_leap_year_f(year: f64) -> f64 {
    if crate::tid::utils::is_leap_year(year.round() as i64) { 1.0 } else { 0.0 }
}

/// Back-compat shim for callers that still dispatch by name (e.g. context-function
/// fallback path). Internally goes through [`BuiltinFunction::from_name`] +
/// [`BuiltinFunction::call`], so it incurs one match-on-string but only for callers
/// that haven't been migrated to parse-time resolution.
pub fn evaluate_builtin_function(name: &str, args: &[f64]) -> Result<f64, EvaluationError> {
    match BuiltinFunction::from_name(name) {
        Some(f) => f.call(args),
        None => Err(EvaluationError::InvalidOperation {
            message: format!("Unknown function: {}", name),
        }),
    }
}
