/// Dynamic Input - Optimized expression evaluation for model inputs
///
/// This module provides a high-performance input mechanism that allows model nodes
/// to accept constants, direct data references, or complex function expressions with
/// zero or minimal overhead.
///
/// # Performance Characteristics
///
/// - `None`: Zero overhead (returns 0.0)
/// - `DirectReference`: Zero overhead (single array lookup)
/// - `DirectConstantReference`: Zero overhead (single array lookup)
/// - `Constant`: Zero overhead (returns stored value)
/// - `Function`: Minimal overhead — a tree walk of pure arithmetic. Evaluation is
///   infallible and allocation-free: unknown functions and wrong argument counts are
///   rejected when the expression is parsed (at model load), `if`/`&&`/`||`
///   short-circuit, and variadic min/max/sum/mean fold an accumulator instead of
///   building an argument buffer.
///
/// # Error Handling - IEEE 754 Standard
///
/// Mathematical operations follow IEEE 754 floating-point standard:
/// - Division by zero: `x / 0.0` → `+∞` (x > 0), `-∞` (x < 0), `NaN` (x = 0)
/// - Domain errors: `sqrt(-1)` → `NaN`, `ln(0)` → `-∞`, `asin(2)` → `NaN`
/// - Overflow: `exp(1000)` → `+∞`
///
/// This allows simulations to continue running even with problematic data, while making
/// issues clearly visible in the output. Check for NaN/∞ in results to detect problems.

use std::collections::HashMap;
use std::sync::Arc;
use crate::data_management::data_cache::DataCache;
use crate::functions::{parse_function, EvaluationConfig, VariableContext};
use crate::functions::ast::{ExpressionNode, FunctionRef, evaluate_binary_op, evaluate_unary_op};
use crate::functions::functions::BuiltinFunction;
use crate::functions::operators::{BinaryOperator, UnaryOperator};
use crate::model_inputs::linear_combination::detect_linear_combination;
use crate::misc::misc_functions::format_f64;
use crate::numerical::lookup_table::{LookupTable, LookupTable1D, LookupTable2D, TableRegistry};

/// Expand `this.` references in an expression to the full node reference.
///
/// Only replaces `this.` when it appears at a word boundary (i.e., not preceded by
/// an alphanumeric character or underscore). This avoids false matches inside node
/// names like `node.that_and_this.inflow`.
///
/// # Arguments
/// * `expression` - The expression string potentially containing `this.`
/// * `self_context` - The expanded prefix, e.g. `"node.my_node_name"`
///
/// # Returns
/// A new string with `this.` replaced by `"{self_context}."` at word boundaries
fn expand_this(expression: &str, self_context: &str) -> String {
    let pattern = b"this.";
    let bytes = expression.as_bytes();
    let mut result = String::with_capacity(expression.len());
    let mut i = 0;
    while i < bytes.len() {
        if i + pattern.len() <= bytes.len() && &bytes[i..i + pattern.len()] == pattern {
            let at_word_boundary = i == 0 || !(bytes[i - 1].is_ascii_alphanumeric() || bytes[i - 1] == b'_');
            if at_word_boundary {
                result.push_str(self_context);
                result.push('.');
                i += pattern.len();
                continue;
            }
        }
        result.push(bytes[i] as char);
        i += 1;
    }
    result
}

/// Simulation context field types for the `sim.*` namespace
///
/// These fields provide access to simulation date/time information within expressions.
/// Example usage: `if(sim.month >= 6 && sim.month <= 8, summer_rate, winter_rate)`
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum SimField {
    /// Calendar year (e.g., 2020)
    Year,
    /// Month of year (1-12)
    Month,
    /// Day of month (1-31)
    Day,
    /// Day of year (1-366)
    DayOfYear,
    /// Current simulation step index (0-based)
    Step,
}

/// Parse a `sim.*` variable name into a SimField
fn parse_sim_field(name: &str) -> Option<SimField> {
    match name {
        "sim.year" => Some(SimField::Year),
        "sim.month" => Some(SimField::Month),
        "sim.day" => Some(SimField::Day),
        "sim.day_of_year" => Some(SimField::DayOfYear),
        "sim.step" => Some(SimField::Step),
        _ => None,
    }
}

/// Optimized AST that uses direct data cache indices instead of variable names
#[derive(Debug, Clone)]
pub enum OptimizedExpressionNode {
    /// A constant value
    Constant {
        value: f64
    },

    /// Direct reference to a data cache series by index (current timestep)
    DataCacheReference {
        cache_index: usize
    },

    /// Direct reference to a data cache series with temporal offset
    /// Offset convention: -ve = past, 0 = current, +ve = future
    /// default_value is used when offset goes outside available data range
    DataCacheReferenceWithOffset {
        cache_index: usize,
        offset: isize,
        default_value: f64
    },

    /// Direct reference to a constant cache value by index
    ConstantReference {
        cache_index: usize
    },

    /// Binary operation
    BinaryOp {
        left: Box<OptimizedExpressionNode>,
        op: BinaryOperator,
        right: Box<OptimizedExpressionNode>,
    },

    /// Unary operation
    UnaryOp {
        op: UnaryOperator,
        operand: Box<OptimizedExpressionNode>,
    },

    /// Single-argument built-in (abs, sqrt, sin, ..., round), lowered at
    /// construction to a plain function pointer — no dispatch, no argument
    /// buffer, no arity check on the hot path.
    Func1 {
        f: fn(f64) -> f64,
        arg: Box<OptimizedExpressionNode>,
    },

    /// Two-argument built-in (pow, atan2), lowered to a function pointer.
    Func2 {
        f: fn(f64, f64) -> f64,
        a: Box<OptimizedExpressionNode>,
        b: Box<OptimizedExpressionNode>,
    },

    /// `if(cond, then, else)` — short-circuits: only the taken branch is
    /// evaluated. Expressions are pure, so this cannot change results; it only
    /// skips wasted work. The condition follows the same truthiness rule as
    /// before (non-zero is true, including NaN).
    If {
        cond: Box<OptimizedExpressionNode>,
        then_branch: Box<OptimizedExpressionNode>,
        else_branch: Box<OptimizedExpressionNode>,
    },

    /// Variadic built-in (min, max, sum, mean) evaluated by folding an
    /// accumulator over the children — no argument buffer is ever built.
    Fold {
        op: FoldOp,
        args: Vec<OptimizedExpressionNode>,
    },

    /// Simulation context reference (sim.* namespace)
    /// Provides access to date/time information during evaluation
    SimContext {
        field: SimField,
    },

    /// Named 1D lookup table (`table.<name>(x)`): clamped linear interpolation.
    /// The concrete table is resolved and embedded at lowering, so evaluation
    /// is a deref + binary search with no name or dimensionality dispatch.
    Lookup1D {
        table: Arc<LookupTable1D>,
        arg: Box<OptimizedExpressionNode>,
    },

    /// Named 2D lookup table (`table.<name>(col_key, row_key)`): exact-match
    /// column selection, then clamped linear interpolation down the column.
    /// A missed column match panics with the table name and offending key —
    /// see LookupTable2D::lookup.
    Lookup2D {
        table: Arc<LookupTable2D>,
        col_key: Box<OptimizedExpressionNode>,
        row_key: Box<OptimizedExpressionNode>,
    },
}

/// Accumulator operation for the variadic built-ins.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum FoldOp {
    Min,
    Max,
    Sum,
    Mean,
}

impl OptimizedExpressionNode {
    /// Evaluate the expression using direct data cache access.
    ///
    /// Infallible by construction: every fallible condition (unknown function
    /// names, wrong argument counts, unresolved variables) is rejected when the
    /// optimised AST is built, so the hot path is pure arithmetic — no Result
    /// plumbing, no argument buffers, no allocation.
    pub fn evaluate(&self, data_cache: &DataCache) -> f64 {
        match self {
            OptimizedExpressionNode::Constant { value } => *value,

            OptimizedExpressionNode::DataCacheReference { cache_index } => {
                data_cache.get_current_value(*cache_index)
            }

            OptimizedExpressionNode::DataCacheReferenceWithOffset { cache_index, offset, default_value } => {
                data_cache.get_value_with_offset_or_default(*cache_index, *offset, *default_value)
            }

            OptimizedExpressionNode::ConstantReference { cache_index } => {
                data_cache.constants.get_value(*cache_index)
            }

            OptimizedExpressionNode::BinaryOp { left, op, right } => match op {
                // && and || short-circuit: the right operand is only evaluated
                // when it can affect the outcome. Truthiness (non-zero = true,
                // including NaN) matches the non-short-circuit forms exactly.
                BinaryOperator::And => {
                    if left.evaluate(data_cache) == 0.0 {
                        0.0
                    } else if right.evaluate(data_cache) != 0.0 { 1.0 } else { 0.0 }
                }
                BinaryOperator::Or => {
                    if left.evaluate(data_cache) != 0.0 {
                        1.0
                    } else if right.evaluate(data_cache) != 0.0 { 1.0 } else { 0.0 }
                }
                _ => evaluate_binary_op(*op, left.evaluate(data_cache), right.evaluate(data_cache)),
            },

            OptimizedExpressionNode::UnaryOp { op, operand } => {
                evaluate_unary_op(*op, operand.evaluate(data_cache))
            }

            OptimizedExpressionNode::Func1 { f, arg } => f(arg.evaluate(data_cache)),

            OptimizedExpressionNode::Func2 { f, a, b } => {
                f(a.evaluate(data_cache), b.evaluate(data_cache))
            }

            OptimizedExpressionNode::If { cond, then_branch, else_branch } => {
                if cond.evaluate(data_cache) != 0.0 {
                    then_branch.evaluate(data_cache)
                } else {
                    else_branch.evaluate(data_cache)
                }
            }

            OptimizedExpressionNode::Fold { op, args } => {
                let mut iter = args.iter();
                // Construction guarantees at least one argument.
                let mut acc = iter.next().expect("fold has >= 1 arg").evaluate(data_cache);
                match op {
                    FoldOp::Min => for a in iter { acc = acc.min(a.evaluate(data_cache)); },
                    FoldOp::Max => for a in iter { acc = acc.max(a.evaluate(data_cache)); },
                    FoldOp::Sum | FoldOp::Mean => for a in iter { acc += a.evaluate(data_cache); },
                }
                if matches!(op, FoldOp::Mean) { acc / args.len() as f64 } else { acc }
            }

            OptimizedExpressionNode::SimContext { field } => match field {
                SimField::Year => data_cache.get_timestamp_year() as f64,
                SimField::Month => data_cache.get_timestamp_month() as f64,
                SimField::Day => data_cache.get_timestamp_day() as f64,
                SimField::DayOfYear => data_cache.get_day_of_year() as f64,
                SimField::Step => data_cache.current_step as f64,
            },

            OptimizedExpressionNode::Lookup1D { table, arg } => {
                table.lookup(arg.evaluate(data_cache))
            }

            OptimizedExpressionNode::Lookup2D { table, col_key, row_key } => {
                table.lookup(col_key.evaluate(data_cache), row_key.evaluate(data_cache))
            }
        }
    }

    /// Visit every zero-offset DataCache reference in the expression and perform
    /// the checked read, regardless of which branches evaluation would take.
    ///
    /// Called on the first timestep only (see `DynamicInput::get_value`).
    /// Evaluation short-circuits `if`/`&&`/`||`, so an illegal reference — one
    /// whose value is computed later in the timestep — could otherwise hide in
    /// an untaken branch and only fail (or silently misbehave) when data first
    /// selects that branch, possibly years into a run. Walking the whole tree
    /// on step 0 makes the failure deterministic: first step, every run.
    ///
    /// Offset references are skipped: they fall back to their explicit default
    /// when out of range, which is legal by design.
    #[cold]
    #[inline(never)]
    pub fn validate_reads(&self, data_cache: &DataCache) {
        match self {
            OptimizedExpressionNode::Constant { .. }
            | OptimizedExpressionNode::ConstantReference { .. }
            | OptimizedExpressionNode::SimContext { .. }
            | OptimizedExpressionNode::DataCacheReferenceWithOffset { .. } => {}

            OptimizedExpressionNode::DataCacheReference { cache_index } => {
                // The checked read panics with the series name if no value
                // exists yet; the value itself is discarded.
                data_cache.get_current_value(*cache_index);
            }

            OptimizedExpressionNode::BinaryOp { left, right, .. } => {
                left.validate_reads(data_cache);
                right.validate_reads(data_cache);
            }
            OptimizedExpressionNode::UnaryOp { operand, .. } => {
                operand.validate_reads(data_cache);
            }
            OptimizedExpressionNode::Func1 { arg, .. } => arg.validate_reads(data_cache),
            OptimizedExpressionNode::Func2 { a, b, .. } => {
                a.validate_reads(data_cache);
                b.validate_reads(data_cache);
            }
            OptimizedExpressionNode::If { cond, then_branch, else_branch } => {
                cond.validate_reads(data_cache);
                then_branch.validate_reads(data_cache);
                else_branch.validate_reads(data_cache);
            }
            OptimizedExpressionNode::Fold { args, .. } => {
                for arg in args {
                    arg.validate_reads(data_cache);
                }
            }
            OptimizedExpressionNode::Lookup1D { arg, .. } => arg.validate_reads(data_cache),
            OptimizedExpressionNode::Lookup2D { col_key, row_key, .. } => {
                col_key.validate_reads(data_cache);
                row_key.validate_reads(data_cache);
            }
        }
    }

    /// Transform an ExpressionNode to an OptimizedExpressionNode by resolving variables to indices
    fn from_expression_node(
        node: &ExpressionNode,
        data_variable_map: &HashMap<String, usize>,
        constant_variable_map: &HashMap<String, usize>,
        tables: &TableRegistry
    ) -> Result<Self, String> {
        match node {
            ExpressionNode::Constant { value } => {
                Ok(OptimizedExpressionNode::Constant { value: *value })
            }
            ExpressionNode::Variable { name } => {
                // Convert to lowercase for case-insensitive lookup (maps use lowercase keys)
                let lower_name = name.to_lowercase();

                // Check for sim.* namespace first (no map lookup needed)
                if let Some(field) = parse_sim_field(&lower_name) {
                    return Ok(OptimizedExpressionNode::SimContext { field });
                }

                // Try constant (c.* variables)
                if let Some(&idx) = constant_variable_map.get(&lower_name) {
                    return Ok(OptimizedExpressionNode::ConstantReference { cache_index: idx });
                }
                // Try data cache (data.* and node.* variables)
                if let Some(&idx) = data_variable_map.get(&lower_name) {
                    return Ok(OptimizedExpressionNode::DataCacheReference { cache_index: idx });
                }
                Err(format!("Variable '{}' not found in variable maps", name))
            }
            ExpressionNode::VariableWithOffset { name, offset, default_value } => {
                // Convert to lowercase for case-insensitive lookup
                let lower_name = name.to_lowercase();

                // Constants don't support offset (they don't vary over time)
                if lower_name.starts_with("c.") {
                    return Err(format!("Offset syntax not supported for constants: {}", name));
                }

                // Simulation context variables don't support offset
                if lower_name.starts_with("sim.") {
                    return Err(format!("Offset syntax not supported for simulation context: {}", name));
                }

                // Node outputs cannot look forward - future values haven't been computed
                if lower_name.starts_with("node.") && *offset > 0 {
                    return Err(format!("Forward lookup not supported for node outputs: {}", name));
                }

                // Data cache variables support offset
                if let Some(&idx) = data_variable_map.get(&lower_name) {
                    if *offset == 0 {
                        // No offset - use the faster variant (default_value is never needed)
                        return Ok(OptimizedExpressionNode::DataCacheReference { cache_index: idx });
                    } else {
                        return Ok(OptimizedExpressionNode::DataCacheReferenceWithOffset {
                            cache_index: idx,
                            offset: *offset,
                            default_value: *default_value
                        });
                    }
                }
                Err(format!("Variable '{}' not found in variable maps", name))
            }
            ExpressionNode::BinaryOp { left, op, right } => {
                let left_opt = Self::from_expression_node(left, data_variable_map, constant_variable_map, tables)?;
                let right_opt = Self::from_expression_node(right, data_variable_map, constant_variable_map, tables)?;

                Ok(OptimizedExpressionNode::BinaryOp {
                    left: Box::new(left_opt),
                    op: *op,
                    right: Box::new(right_opt),
                })
            }
            ExpressionNode::UnaryOp { op, operand } => {
                let operand_opt = Self::from_expression_node(operand, data_variable_map, constant_variable_map, tables)?;

                Ok(OptimizedExpressionNode::UnaryOp {
                    op: *op,
                    operand: Box::new(operand_opt),
                })
            }
            ExpressionNode::FunctionCall { func, args } => {
                let args_opt: Result<Vec<_>, String> = args
                    .iter()
                    .map(|arg| Self::from_expression_node(arg, data_variable_map, constant_variable_map, tables))
                    .collect();

                lower_function_call(func, args_opt?, tables)
            }
        }
    }
}


/// DynamicInput supports constants, data references, and function expressions
///
/// This enum is optimised for performance with seven variants:
/// - `None`: No input (returns 0.0)
/// - `DirectReference`: Pure data reference (zero overhead)
/// - `DirectReferenceWithOffset`: Data reference with temporal offset (minimal overhead)
/// - `DirectConstantReference`: Pure constant reference (zero overhead)
/// - `Constant`: Constant value (zero overhead)
/// - `LinearCombination`: Linear combination of data references with optimizable weights
/// - `Function`: Complex expression (minimal overhead)
///
/// All variants store the original expression string for round-trip serialization
#[derive(Clone, Debug)]
pub enum DynamicInput {
    /// No input specified
    None {
        original: String
    },

    /// Direct reference to a data cache series (current timestep)
    DirectReference {
        idx: usize,
        original: String
    },

    /// Direct reference to a data cache series with temporal offset
    /// Offset convention: -ve = past, 0 = current, +ve = future
    /// default_value is used when offset goes outside available data range
    DirectReferenceWithOffset {
        idx: usize,
        offset: isize,
        default_value: f64,
        original: String
    },

    /// Direct reference to a constant cache value
    DirectConstantReference {
        idx: usize,
        original: String
    },

    /// Constant value (evaluated once at initialization)
    Constant {
        value: f64,
        original: String
    },

    /// Linear combination of data references with optimizable weights
    /// Form: a1 * data1 + a2 * data2 + ... where ai = bias * softmax(logit(ui))
    /// Following the symmetric parameterization from the wiki
    LinearCombination {
        /// Indices into data cache for each data source
        data_indices: Vec<usize>,
        /// Variable names for each data source (preserved for serialization)
        variable_names: Vec<String>,
        /// Current weights (updated when optimization parameters change)
        coefficients: Vec<f64>,
        /// Normalized parameters u_i in [0,1] (optimizable)
        /// u_i = 0.5 means equal weight to reference (first) station
        /// Length is n-1 for n stations (first station is reference)
        u_params: Vec<f64>,
        /// Bias parameter - sum of all weights (optimizable)
        bias: f64,
        /// Original expression for serialization
        original: String,
    },

    /// Function expression (optimised for performance)
    Function {
        expression: String,  // Original expression for error messages and serialization
        optimised_ast: OptimizedExpressionNode
    },
}

impl Default for DynamicInput {
    fn default() -> Self {
        DynamicInput::None {
            original: String::new()
        }
    }
}

impl DynamicInput {
    /// Create a DynamicInput from a string expression
    ///
    /// This method parses the expression but does NOT resolve variables.
    /// You must call `initialize()` afterwards to resolve data cache references.
    ///
    /// # Arguments
    ///
    /// * `expression` - The expression string (e.g., "data.evap", "1.2 * data.evap", "100.0")
    ///
    /// # Returns
    ///
    /// A DynamicInput that needs initialization, or an error if parsing fails
    pub fn from_string(expression: &str, data_cache: &mut DataCache, flag_as_critical: bool, self_context: Option<&str>) -> Result<Self, String> {
        let trimmed = expression.trim();

        if trimmed.is_empty() {
            return Ok(DynamicInput::None {
                original: String::new()
            });
        }

        // Expand "this." references if a self_context is provided
        let working_copy = match self_context {
            Some(ctx) => expand_this(trimmed, ctx),
            None => trimmed.to_string(),
        };

        // Parse the expression (using the expanded form)
        let parsed = parse_function(&working_copy)
            .map_err(|e| format!("Failed to parse expression '{}': {}", trimmed, e))?;

        // A bare `table.foo` (no call parentheses) parses as a variable, and would
        // otherwise silently register a phantom data series named "table.foo".
        // Reject it here, before linear-combination detection can capture it.
        for var_name in parsed.get_variables() {
            let lower_name = var_name.to_lowercase();
            if lower_name.starts_with("table.") {
                return Err(format!(
                    "'{}' is a lookup table reference and must be called with arguments, e.g. {}(x)",
                    var_name, lower_name
                ));
            }
        }

        // Check if it's a linear combination pattern first
        if let Some(linear_info) = detect_linear_combination(parsed.get_ast()) {
            // It's a linear combination! Create the LinearCombination variant
            let mut data_indices = Vec::new();

            // Resolve variable names to data cache indices
            for var_name in &linear_info.variables {
                let lower_name = var_name.to_lowercase();
                // node.* references are not critical inputs (they're outputs from other nodes)
                let is_critical = flag_as_critical && !lower_name.starts_with("node.");
                let idx = data_cache.get_or_add_new_series(&lower_name, is_critical);
                data_indices.push(idx);
            }

            // Initialize with default parameter values
            let n = data_indices.len();
            let u_params = if n > 1 {
                vec![0.5; n - 1]  // n-1 parameters for distribution
            } else {
                vec![]
            };
            let bias = linear_info.coefficients.iter().sum::<f64>();

            // Use parsed coefficients directly - don't recompute with defaults
            // (they may already be optimized values from a saved model)
            let coefficients = linear_info.coefficients.clone();

            return Ok(DynamicInput::LinearCombination {
                data_indices,
                variable_names: linear_info.variables,
                coefficients,
                u_params,
                bias,
                original: trimmed.to_string(),
            });
        }

        // Not a linear combination, proceed with existing logic
        // Get all variables referenced
        let variables = parsed.get_variables();

        // Separate variables into data cache and constants based on prefix
        // Note: We use lowercase for all map keys to ensure case-insensitive lookups
        // and avoid duplicate entries for the same variable with different cases
        let mut data_variable_map = HashMap::new();
        let mut constant_variable_map = HashMap::new();

        for var_name in variables.iter() {
            let lower_name = var_name.to_lowercase();

            if lower_name.starts_with("sim.") {
                // Simulation context variables - no cache lookup needed
                // They are resolved directly in from_expression_node via parse_sim_field
                continue;
            } else if lower_name.starts_with("c.") {
                // Resolve to constants cache
                let idx = data_cache.constants.add_if_needed_and_get_idx(&lower_name);
                constant_variable_map.insert(lower_name.clone(), idx);
            } else if lower_name.starts_with("node.") {
                // Resolve to data cache but NOT as critical input (node outputs don't determine simulation period)
                let idx = data_cache.get_or_add_new_series(lower_name.as_str(), false);
                data_variable_map.insert(lower_name.clone(), idx);
            } else {
                // Resolve to data cache (data.* references - use flag_as_critical from caller)
                let idx = data_cache.get_or_add_new_series(lower_name.as_str(), flag_as_critical);
                data_variable_map.insert(lower_name.clone(), idx);
            }
        }

        // Optimize based on expression type
        if variables.is_empty() {
            // No variables -> try to fold to a constant by evaluating once now.
            // This fails for expressions the generic evaluator cannot run —
            // notably table.* lookups with constant arguments — which fall
            // through to the optimised lowering below (where a genuinely bad
            // expression also gets its more specific error message).
            let config = EvaluationConfig::default();
            let empty_vars = HashMap::new();
            let context = VariableContext::new(&empty_vars, &config);
            if let Ok(value) = parsed.evaluate(&context) {
                return Ok(DynamicInput::Constant {
                    value,
                    original: trimmed.to_string()
                });
            }
        }

        if let Some((var_name, offset, default_value)) = parsed.is_single_variable_with_offset() {
            // It's a direct reference to a single variable with offset syntax (e.g., node.x.ds_1[1, 0.0])
            let lower_var = var_name.to_lowercase();

            // Constants don't support offset
            if lower_var.starts_with("c.") {
                return Err(format!("Offset syntax not supported for constants: {}", var_name));
            }

            // Simulation context variables don't support offset
            if lower_var.starts_with("sim.") {
                return Err(format!("Offset syntax not supported for simulation context: {}", var_name));
            }

            // Node outputs cannot look forward
            if lower_var.starts_with("node.") && offset > 0 {
                return Err(format!("Forward lookup not supported for node outputs: {}", var_name));
            }

            if let Some(&idx) = data_variable_map.get(&lower_var) {
                if offset == 0 {
                    // offset=0 means current value - use the faster DirectReference (default never needed)
                    Ok(DynamicInput::DirectReference {
                        idx,
                        original: trimmed.to_string()
                    })
                } else {
                    Ok(DynamicInput::DirectReferenceWithOffset {
                        idx,
                        offset,
                        default_value,
                        original: trimmed.to_string()
                    })
                }
            } else {
                Err(format!("Variable '{}' not found in variable maps", var_name))
            }
        } else if let Some(var_name) = parsed.is_single_variable() {
            // It's a direct reference to a single variable (no operations, no offset)
            // Check if it's a constant or data reference
            let lower_var = var_name.to_lowercase();

            // sim.* variables need to go through the Function path
            if lower_var.starts_with("sim.") {
                let optimised_ast = transform_to_optimised_ast(&parsed, &data_variable_map, &constant_variable_map, &data_cache.tables)?;
                Ok(DynamicInput::Function {
                    expression: trimmed.to_string(),
                    optimised_ast
                })
            } else if let Some(&idx) = constant_variable_map.get(&lower_var) {
                Ok(DynamicInput::DirectConstantReference {
                    idx,
                    original: trimmed.to_string()
                })
            } else if let Some(&idx) = data_variable_map.get(&lower_var) {
                Ok(DynamicInput::DirectReference {
                    idx,
                    original: trimmed.to_string()
                })
            } else {
                Err(format!("Variable '{}' not found in variable maps", var_name))
            }
        } else {
            // Multiple variables or complex expression -> function expression
            let optimised_ast = transform_to_optimised_ast(&parsed, &data_variable_map, &constant_variable_map, &data_cache.tables)?;
            Ok(DynamicInput::Function {
                expression: trimmed.to_string(),
                optimised_ast
            })
        }
    }

    /// Get the current value
    ///
    /// # Arguments
    ///
    /// * `data_cache` - The data cache to read values from
    ///
    /// # Returns
    ///
    /// The evaluated value as f64. Returns 0.0 for `None` variant.
    ///
    /// # Error Handling
    ///
    /// Mathematical domain errors (division by zero, sqrt of negative, etc.) return
    /// NaN or ∞ following IEEE 754 standard - they do NOT cause this function to fail.
    ///
    /// Programming errors (unknown function names, wrong argument counts) are extremely
    /// rare and indicate bugs in the parser. If they occur, this function prints an
    /// error to stderr and returns 0.0 to allow the simulation to continue.
    pub fn get_value(&self, data_cache: &DataCache) -> f64 {
        match self {
            DynamicInput::None { .. } => 0.0,
            DynamicInput::DirectReference { idx, .. } => {
                data_cache.get_current_value(*idx)
            }
            DynamicInput::DirectReferenceWithOffset { idx, offset, default_value, .. } => {
                data_cache.get_value_with_offset_or_default(*idx, *offset, *default_value)
            }
            DynamicInput::DirectConstantReference { idx, .. } => {
                data_cache.constants.get_value(*idx)
            }
            DynamicInput::Constant { value, .. } => *value,
            DynamicInput::LinearCombination { data_indices, coefficients, .. } => {
                // High-performance dot product of weights and data values
                data_indices.iter()
                    .zip(coefficients.iter())
                    .map(|(&idx, &weight)| data_cache.get_current_value(idx) * weight)
                    .sum()
            }
            DynamicInput::Function { optimised_ast, .. } => {
                // First-timestep validation. No flag needed: `current_step == 0`
                // is the whole condition, which also re-arms validation on every
                // fresh run (a new run always starts by setting step 0). After
                // step 0 this is a single always-false predicted branch.
                // Other variants need no walk: they read all of their references
                // unconditionally on every step, so their first real read IS the
                // validation.
                if data_cache.current_step == 0 {
                    optimised_ast.validate_reads(data_cache);
                }
                optimised_ast.evaluate(data_cache)
            }
        }
    }

    /// Get the expression string for serialization
    /// For LinearCombination, this returns the optimized expression with current weights
    pub fn to_string(&self) -> String {
        match self {
            DynamicInput::None { original } => original.clone(),
            DynamicInput::DirectReference { original, .. } => original.clone(),
            DynamicInput::DirectReferenceWithOffset { original, .. } => original.clone(),
            DynamicInput::DirectConstantReference { original, .. } => original.clone(),
            DynamicInput::Constant { original, .. } => original.clone(),
            DynamicInput::LinearCombination { variable_names, coefficients, .. } => {
                // Reconstruct the expression with current optimized weights
                if variable_names.is_empty() {
                    return "0.0".to_string();
                }

                let terms: Vec<String> = variable_names.iter()
                    .zip(coefficients.iter())
                    .map(|(var, weight)| {
                        // Always include the coefficient to maintain LinearCombination on round-trip
                        format!("{} * {}", format_f64(*weight), var)
                    })
                    .collect();

                if terms.is_empty() {
                    "0.0".to_string()
                } else {
                    terms.join(" + ")
                }
            }
            DynamicInput::Function { expression, .. } => expression.clone(),
        }
    }

    /// Get the original unmodified expression string
    pub fn original_string(&self) -> &str {
        match self {
            DynamicInput::None { original } => original.as_str(),
            DynamicInput::DirectReference { original, .. } => original.as_str(),
            DynamicInput::DirectReferenceWithOffset { original, .. } => original.as_str(),
            DynamicInput::DirectConstantReference { original, .. } => original.as_str(),
            DynamicInput::Constant { original, .. } => original.as_str(),
            DynamicInput::LinearCombination { original, .. } => original.as_str(),
            DynamicInput::Function { expression, .. } => expression.as_str(),
        }
    }
}

/// Transform a ParsedFunction to an OptimizedExpressionNode
fn transform_to_optimised_ast(
    parsed: &crate::functions::parser::ParsedFunction,
    data_variable_map: &HashMap<String, usize>,
    constant_variable_map: &HashMap<String, usize>,
    tables: &TableRegistry
) -> Result<OptimizedExpressionNode, String> {
    OptimizedExpressionNode::from_expression_node(parsed.get_ast(), data_variable_map, constant_variable_map, tables)
}

/// Lower a parsed function call into its specialised hot-path form, validating
/// the function name and argument count once, at construction. This is what
/// makes `OptimizedExpressionNode::evaluate` infallible and allocation-free:
/// by the time an expression is evaluated, no unknown-function or wrong-arity
/// condition can exist.
///
/// Named (non-built-in) references resolve against the model's lookup tables
/// when they carry the `table.` prefix; anything else is rejected here: model
/// evaluation has no FunctionRegistry — context functions like
/// `lin_range`/`log_range`/`g` are only meaningful inside the optimisation
/// parameter path.
fn lower_function_call(
    func: &FunctionRef,
    mut args: Vec<OptimizedExpressionNode>,
    tables: &TableRegistry,
) -> Result<OptimizedExpressionNode, String> {
    use BuiltinFunction as B;

    let builtin = match func {
        FunctionRef::Builtin(b) => *b,
        FunctionRef::Named(name) => {
            if let Some(bare_name) = name.strip_prefix("table.") {
                return lower_table_call(bare_name, args, tables);
            }
            return Err(format!(
                "Unknown function '{}' (context functions like lin_range/log_range/g \
                 are only available in optimisation parameter expressions)",
                name
            ));
        }
    };

    let arity_err = |expected: &str, got: usize| {
        Err(format!(
            "Function '{}' expects {} argument(s), got {}",
            builtin.name(), expected, got
        ))
    };

    // Single-argument built-ins lower to a plain function pointer.
    let f1: Option<fn(f64) -> f64> = match builtin {
        B::Abs => Some(f64::abs),
        B::Sqrt => Some(f64::sqrt),
        B::Sin => Some(f64::sin),
        B::Cos => Some(f64::cos),
        B::Tan => Some(f64::tan),
        B::Asin => Some(f64::asin),
        B::Acos => Some(f64::acos),
        B::Atan => Some(f64::atan),
        B::Exp => Some(f64::exp),
        B::Ln => Some(f64::ln),
        B::Log10 => Some(f64::log10),
        B::Log2 => Some(f64::log2),
        B::Ceil => Some(f64::ceil),
        B::Floor => Some(f64::floor),
        B::Round => Some(f64::round),
        B::Sign => Some(crate::functions::functions::sign),
        _ => None,
    };
    if let Some(f) = f1 {
        if args.len() != 1 {
            return arity_err("1", args.len());
        }
        return Ok(OptimizedExpressionNode::Func1 { f, arg: Box::new(args.remove(0)) });
    }

    match builtin {
        B::Pow | B::Atan2 => {
            if args.len() != 2 {
                return arity_err("2", args.len());
            }
            let b = args.pop().unwrap();
            let a = args.pop().unwrap();
            let f: fn(f64, f64) -> f64 = if builtin == B::Pow { f64::powf } else { f64::atan2 };
            Ok(OptimizedExpressionNode::Func2 { f, a: Box::new(a), b: Box::new(b) })
        }
        B::If => {
            if args.len() != 3 {
                return arity_err("3", args.len());
            }
            let else_branch = args.pop().unwrap();
            let then_branch = args.pop().unwrap();
            let cond = args.pop().unwrap();
            Ok(OptimizedExpressionNode::If {
                cond: Box::new(cond),
                then_branch: Box::new(then_branch),
                else_branch: Box::new(else_branch),
            })
        }
        B::Min | B::Max => {
            if args.len() < 2 {
                return arity_err("at least 2", args.len());
            }
            let op = if builtin == B::Min { FoldOp::Min } else { FoldOp::Max };
            Ok(OptimizedExpressionNode::Fold { op, args })
        }
        B::Sum | B::Mean => {
            if args.is_empty() {
                return arity_err("at least 1", args.len());
            }
            let op = if builtin == B::Sum { FoldOp::Sum } else { FoldOp::Mean };
            Ok(OptimizedExpressionNode::Fold { op, args })
        }
        // All single-argument built-ins were handled above.
        _ => unreachable!("built-in '{}' not handled in lowering", builtin.name()),
    }
}

/// Lower a `table.<name>(...)` call by resolving the name against the model's
/// table registry. Dimensionality and argument count are checked once, here;
/// the resulting node embeds an Arc of the concrete table, so evaluation does
/// no lookup or dispatch.
fn lower_table_call(
    bare_name: &str,
    mut args: Vec<OptimizedExpressionNode>,
    tables: &TableRegistry,
) -> Result<OptimizedExpressionNode, String> {
    let Some(table) = tables.get(bare_name) else {
        return Err(format!(
            "Unknown table 'table.{}' (no [table.{}] section is defined in the model)",
            bare_name, bare_name
        ));
    };

    match table {
        LookupTable::OneD(t) => {
            if args.len() != 1 {
                return Err(format!(
                    "1D lookup table 'table.{}' expects 1 argument, got {}",
                    bare_name, args.len()
                ));
            }
            Ok(OptimizedExpressionNode::Lookup1D {
                table: t.clone(),
                arg: Box::new(args.remove(0)),
            })
        }
        LookupTable::TwoD(t) => {
            if args.len() != 2 {
                return Err(format!(
                    "2D lookup table 'table.{}' expects 2 arguments (column key, row key), got {}",
                    bare_name, args.len()
                ));
            }
            let row_key = args.pop().unwrap();
            let col_key = args.pop().unwrap();
            Ok(OptimizedExpressionNode::Lookup2D {
                table: t.clone(),
                col_key: Box::new(col_key),
                row_key: Box::new(row_key),
            })
        }
    }
}

