/// Built-in mathematical functions for the functions module.
///
/// This module exposes the closed set of built-in functions accepted by the Kalix
/// expression parser as a [`BuiltinFunction`] enum. Names are resolved against this
/// set at **parse time** (see [`BuiltinFunction::from_name`]) so the AST stores a
/// resolved enum tag rather than a string. Evaluation is then a direct match — no
/// per-call string compare or hashing, which matters because parameter and inflow
/// expressions are evaluated millions of times per model run.

use crate::functions::errors::EvaluationError;

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
    Ceil, Floor, Round,

    // Two argument
    Pow, Atan2,

    // Variadic (>= 2)
    Min, Max,

    // Variadic (>= 1)
    Sum, Avg,

    // Three argument (special)
    If,
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
            "pow"    => BuiltinFunction::Pow,
            "atan2"  => BuiltinFunction::Atan2,
            "min"    => BuiltinFunction::Min,
            "max"    => BuiltinFunction::Max,
            "sum"    => BuiltinFunction::Sum,
            "avg"    => BuiltinFunction::Avg,
            "if"     => BuiltinFunction::If,
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
            BuiltinFunction::Pow => "pow",
            BuiltinFunction::Atan2 => "atan2",
            BuiltinFunction::Min => "min",
            BuiltinFunction::Max => "max",
            BuiltinFunction::Sum => "sum",
            BuiltinFunction::Avg => "avg",
            BuiltinFunction::If => "if",
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
            BuiltinFunction::Avg => {
                if args.is_empty() { return Self::arity_err(self.name(), 1, 0); }
                Ok(args.iter().sum::<f64>() / args.len() as f64)
            }

            // Three argument
            BuiltinFunction::If => {
                if args.len() != 3 { return Self::arity_err(self.name(), 3, args.len()); }
                Ok(if args[0] != 0.0 { args[1] } else { args[2] })
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
