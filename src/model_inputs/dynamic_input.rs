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
use crate::functions::ast::{ExpressionNode, FunctionRef, Program, evaluate_binary_op, evaluate_unary_op};
use crate::functions::functions::BuiltinFunction;
use crate::functions::operators::{BinaryOperator, UnaryOperator};
use crate::model_inputs::linear_combination::{detect_linear_combination, equal_weight_u_params, invert_stick_breaking_weights};
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

/// Expand bare `this` in a [var.*] definition to the var's own series name:
/// `this[-1, 0]` becomes `var.<block>.<key>[-1, 0]`. Per expression-naming
/// §2.8, `this` names the enclosing definition — for a var that is the
/// series itself, so it takes no field (`this.x` is an error) and, because
/// a var can never read its own not-yet-written value, it must carry an
/// offset. Purely textual, on the definition's own text only: `this`
/// inside an [fn] body names the fn, never the calling var, so fn bodies
/// are deliberately not expanded with the var's context.
pub fn expand_var_this(expression: &str, series_name: &str) -> Result<String, String> {
    let pattern = b"this";
    let bytes = expression.as_bytes();
    let mut result = String::with_capacity(expression.len());
    let mut i = 0;
    while i < bytes.len() {
        if i + pattern.len() <= bytes.len() && &bytes[i..i + pattern.len()] == pattern {
            let before_ok = i == 0 || !(bytes[i - 1].is_ascii_alphanumeric() || bytes[i - 1] == b'_');
            let after = bytes.get(i + pattern.len()).copied();
            let after_ok = !matches!(after, Some(c) if c.is_ascii_alphanumeric() || c == b'_');
            if before_ok && after_ok {
                if after == Some(b'.') {
                    return Err("'this' in a var definition is the var's own series and takes no \
                        field — write this[-1, 0]".to_string());
                }
                // Skip whitespace to check for the offset bracket.
                let mut j = i + pattern.len();
                while j < bytes.len() && bytes[j].is_ascii_whitespace() { j += 1; }
                if bytes.get(j) != Some(&b'[') {
                    return Err("a var's self-reference reads its own history and needs an \
                        offset, e.g. this[-1, 0] (its current value is not written yet)".to_string());
                }
                result.push_str(series_name);
                i += pattern.len();
                continue;
            }
        }
        result.push(bytes[i] as char);
        i += 1;
    }
    Ok(result)
}

/// The two calendar-at-offset lookups (CALENDAR_FUNCTIONS in functions.rs):
/// what the calendar looks like `n` days from the current simulation date.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum CalendarAtKind {
    /// `month_at(n)`: month (1-12) at current date + n days
    Month,
    /// `days_in_month_at(n)`: leap-aware length of that month
    DaysInMonth,
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
    /// Calendar-day boundary: 1.0 when this step's date differs from the
    /// previous step's, and at step 0 (structured_expressions_design.md §7)
    NewDay,
    /// Calendar-month boundary: 1.0 when this step's month differs from the
    /// previous step's, and at step 0
    NewMonth,
    /// Calendar-year boundary: 1.0 when this step's year differs from the
    /// previous step's, and at step 0
    NewYear,
    /// Days in the current month (28-31, leap-aware)
    DaysInMonth,
    /// Days in the current year (365 or 366)
    DaysInYear,
    /// 1.0 in a leap year, else 0.0
    IsLeap,
}

/// Parse a `sim.*` variable name into a SimField
fn parse_sim_field(name: &str) -> Option<SimField> {
    match name {
        "sim.year" => Some(SimField::Year),
        "sim.month" => Some(SimField::Month),
        "sim.day" => Some(SimField::Day),
        "sim.day_of_year" => Some(SimField::DayOfYear),
        "sim.step" => Some(SimField::Step),
        "sim.new_day" => Some(SimField::NewDay),
        "sim.new_month" => Some(SimField::NewMonth),
        "sim.new_year" => Some(SimField::NewYear),
        "sim.days_in_month" => Some(SimField::DaysInMonth),
        "sim.days_in_year" => Some(SimField::DaysInYear),
        "sim.is_leap" => Some(SimField::IsLeap),
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

    /// Calendar lookup at a day offset from the current simulation date:
    /// `month_at(n)` / `days_in_month_at(n)`. The offset (usually a
    /// constant) is rounded to whole days; evaluation is one O(1) civil-date
    /// conversion of the offset timestamp — the engine owns the calendar, so
    /// models stop hand-rolling leap logic that is only valid for one
    /// month-boundary crossing.
    CalendarAt {
        kind: CalendarAtKind,
        arg: Box<OptimizedExpressionNode>,
    },

    /// A program-local variable, resolved at lowering to an absolute slot in
    /// the DataCache expression-state arena. Same cost class as
    /// ConstantReference: one indexed read.
    Local {
        slot: usize,
    },

    /// Fixed-window statistic over the last n steps: moving_sum/mean/min/max.
    /// State lives in the arena, allocated at lowering; reading the statistic
    /// is one or two indexed loads. State advances once per step via
    /// `advance_state`, unconditionally — even inside untaken `if` branches —
    /// so the value is a property of the series, never of evaluation paths
    /// (structured_expressions_design.md §5).
    ///
    /// Arena layout (allocated per instance):
    /// - Sum/Mean: f region = [ring buffer; n] + [running sum] (n+1 slots);
    ///   u region = [head] (1 slot). O(1) advance: subtract evicted, add
    ///   incoming; the sum is recomputed from the ring on every wrap and on
    ///   NaN eviction, bounding both float drift and NaN poisoning.
    /// - Min/Max: f region = [deque values; n+1]; u region =
    ///   [deque expiry steps; n+1] + [head] + [len]. Monotonic deque,
    ///   amortised O(1); NaN inputs are skipped (window min/max suppress NaN
    ///   exactly as the min/max builtins do); an all-NaN window reads NaN.
    MovingWindow {
        op: WindowOp,
        /// Boxed so this variant doesn't set the size of EVERY expression
        /// tree node: three inline usizes grew the enum from 32 to 40 bytes,
        /// a measured cache-pressure regression across all models. One extra
        /// deref per window instance per step is far cheaper.
        slots: Box<WindowSlots>,
        arg: Box<OptimizedExpressionNode>,
    },

    /// Event-windowed statistic since a reset condition last fired:
    /// sum/min/max/count/steps_since. State is a single f64 accumulator.
    /// Reset-then-accumulate: when the reset condition is truthy at step t,
    /// the accumulator clears first and step t's contribution is then
    /// included (design §6). Run start is an implicit reset, encoded in the
    /// init template (sum/count 0, steps -1, min/max NaN — NaN-suppressing
    /// min/max bootstrap to the first value).
    Since {
        op: SinceOp,
        f_off: usize,
        /// Tracked quantity; None for steps_since (the step counter itself).
        arg: Option<Box<OptimizedExpressionNode>>,
        reset: Box<OptimizedExpressionNode>,
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

/// Statistic computed by a fixed-window `moving_*` call.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum WindowOp {
    Sum,
    Mean,
    Min,
    Max,
}

/// Arena addressing for one moving-window instance, boxed off the
/// OptimizedExpressionNode variant to keep the enum at 32 bytes.
#[derive(Debug, Clone)]
pub struct WindowSlots {
    pub n: usize,
    pub f_off: usize,
    pub u_off: usize,
}

/// Statistic computed by a `*_since` call.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum SinceOp {
    Sum,
    Min,
    Max,
    /// count_since(cond, reset): steps on which cond held since the reset.
    Count,
    /// steps_since(reset): steps elapsed since the reset (0 on a reset step).
    Steps,
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
                SimField::NewDay => if data_cache.is_new_day() { 1.0 } else { 0.0 },
                SimField::NewMonth => if data_cache.is_new_month() { 1.0 } else { 0.0 },
                SimField::NewYear => if data_cache.is_new_year() { 1.0 } else { 0.0 },
                SimField::DaysInMonth => crate::tid::utils::days_in_month(
                    data_cache.get_timestamp_year() as i64, data_cache.get_timestamp_month()) as f64,
                SimField::DaysInYear =>
                    if crate::tid::utils::is_leap_year(data_cache.get_timestamp_year() as i64) { 366.0 } else { 365.0 },
                SimField::IsLeap =>
                    if crate::tid::utils::is_leap_year(data_cache.get_timestamp_year() as i64) { 1.0 } else { 0.0 },
            },

            OptimizedExpressionNode::CalendarAt { kind, arg } => {
                // Whole-day offset from the current timestamp. Timestamps are
                // offset-encoded u64 seconds (tid::utils::wrap_to_i64), and
                // wrapping_add of a two's-complement delta is exact under
                // that encoding, so negative offsets look back correctly.
                let n = arg.evaluate(data_cache).round() as i64;
                let ts = data_cache.current_timestamp.wrapping_add((n * 86400) as u64);
                let (y, m, _d, _s) = crate::tid::utils::u64_to_year_month_day_and_seconds(ts);
                match kind {
                    CalendarAtKind::Month => m as f64,
                    CalendarAtKind::DaysInMonth => crate::tid::utils::days_in_month(y as i64, m) as f64,
                }
            }

            OptimizedExpressionNode::Local { slot } => data_cache.expr_state.f[*slot],

            // Reading a window statistic never touches the input expression —
            // sampling happened in advance_state. Sum/Mean read the running
            // sum; Min/Max read the deque front (NaN when the deque is empty,
            // i.e. every real value in the window was NaN).
            OptimizedExpressionNode::MovingWindow { op, slots, .. } => match op {
                WindowOp::Sum => data_cache.expr_state.f[slots.f_off + slots.n],
                WindowOp::Mean => data_cache.expr_state.f[slots.f_off + slots.n] / slots.n as f64,
                WindowOp::Min | WindowOp::Max => {
                    let head = data_cache.expr_state.u[slots.u_off + slots.n + 1];
                    let len = data_cache.expr_state.u[slots.u_off + slots.n + 2];
                    if len == 0 { f64::NAN } else { data_cache.expr_state.f[slots.f_off + head] }
                }
            },

            OptimizedExpressionNode::Since { f_off, .. } => data_cache.expr_state.f[*f_off],

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
            | OptimizedExpressionNode::Local { .. }
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
            OptimizedExpressionNode::CalendarAt { arg, .. } => arg.validate_reads(data_cache),
            // Stateful inputs are read unconditionally every step by
            // advance_state, so they are validated like any other read.
            OptimizedExpressionNode::MovingWindow { arg, .. } => arg.validate_reads(data_cache),
            OptimizedExpressionNode::Since { arg, reset, .. } => {
                if let Some(a) = arg {
                    a.validate_reads(data_cache);
                }
                reset.validate_reads(data_cache);
            }
        }
    }

    /// Advance all stateful nodes in this subtree by one step, sampling their
    /// input expressions. Called exactly once per timestep per owning input
    /// (guarded by the owner — see DynamicInput::StatefulFunction and
    /// OptimizedProgram::evaluate), and the walk is UNCONDITIONAL: `if`
    /// branches and short-circuit operands are all visited, so window and
    /// since state never depends on which branches past evaluations took
    /// (structured_expressions_design.md §5).
    ///
    /// Children advance before their parent samples: for nested stateful
    /// calls like moving_sum(moving_mean(x, 5, 0), 10, 0), the inner window
    /// advances first and the outer then samples the advanced value.
    pub fn advance_state(&self, data_cache: &mut DataCache) {
        match self {
            OptimizedExpressionNode::Constant { .. }
            | OptimizedExpressionNode::ConstantReference { .. }
            | OptimizedExpressionNode::SimContext { .. }
            | OptimizedExpressionNode::Local { .. }
            | OptimizedExpressionNode::DataCacheReference { .. }
            | OptimizedExpressionNode::DataCacheReferenceWithOffset { .. } => {}

            OptimizedExpressionNode::BinaryOp { left, right, .. } => {
                left.advance_state(data_cache);
                right.advance_state(data_cache);
            }
            OptimizedExpressionNode::UnaryOp { operand, .. } => operand.advance_state(data_cache),
            OptimizedExpressionNode::Func1 { arg, .. } => arg.advance_state(data_cache),
            OptimizedExpressionNode::Func2 { a, b, .. } => {
                a.advance_state(data_cache);
                b.advance_state(data_cache);
            }
            OptimizedExpressionNode::If { cond, then_branch, else_branch } => {
                cond.advance_state(data_cache);
                then_branch.advance_state(data_cache);
                else_branch.advance_state(data_cache);
            }
            OptimizedExpressionNode::Fold { args, .. } => {
                for a in args {
                    a.advance_state(data_cache);
                }
            }
            OptimizedExpressionNode::Lookup1D { arg, .. } => arg.advance_state(data_cache),
            OptimizedExpressionNode::Lookup2D { col_key, row_key, .. } => {
                col_key.advance_state(data_cache);
                row_key.advance_state(data_cache);
            }
            OptimizedExpressionNode::CalendarAt { arg, .. } => arg.advance_state(data_cache),

            OptimizedExpressionNode::MovingWindow { op, slots, arg } => {
                arg.advance_state(data_cache);
                let x = arg.evaluate(data_cache);
                match op {
                    WindowOp::Sum | WindowOp::Mean => {
                        advance_ring(data_cache, slots.n, slots.f_off, slots.u_off, x);
                    }
                    WindowOp::Min => advance_deque(data_cache, slots.n, slots.f_off, slots.u_off, x, true),
                    WindowOp::Max => advance_deque(data_cache, slots.n, slots.f_off, slots.u_off, x, false),
                }
            }

            OptimizedExpressionNode::Since { op, f_off, arg, reset } => {
                if let Some(a) = arg {
                    a.advance_state(data_cache);
                }
                reset.advance_state(data_cache);
                // Truthiness matches the language everywhere: non-zero is
                // true, including NaN (NaN != 0.0), same as if()/&&/||.
                let reset_fired = reset.evaluate(data_cache) != 0.0;
                let x = arg.as_ref().map(|a| a.evaluate(data_cache));
                let acc_slot = *f_off;
                let acc = data_cache.expr_state.f[acc_slot];
                // Reset-then-accumulate: the reset step's own contribution is
                // included (design §6 — 1 July's usage counts toward the new
                // water year). Run start is handled by the init template:
                // sum/count start at 0, steps at -1, min/max at NaN (which
                // f64::min/max suppress, bootstrapping to the first value).
                data_cache.expr_state.f[acc_slot] = match op {
                    SinceOp::Sum => {
                        let x = x.unwrap();
                        if reset_fired { x } else { acc + x }
                    }
                    SinceOp::Min => {
                        let x = x.unwrap();
                        if reset_fired { x } else { acc.min(x) }
                    }
                    SinceOp::Max => {
                        let x = x.unwrap();
                        if reset_fired { x } else { acc.max(x) }
                    }
                    SinceOp::Count => {
                        let hit = if x.unwrap() != 0.0 { 1.0 } else { 0.0 };
                        if reset_fired { hit } else { acc + hit }
                    }
                    SinceOp::Steps => {
                        if reset_fired { 0.0 } else { acc + 1.0 }
                    }
                };
            }
        }
    }

    /// Transform an ExpressionNode to an OptimizedExpressionNode by resolving variables to indices.
    ///
    /// `locals` maps program-local names (lowercased) to their absolute arena
    /// slots, containing only the locals assigned by statements *above* the
    /// expression being lowered — so a use-before-assign reference simply
    /// isn't in the map and falls through to the bare-name error below.
    /// Plain (non-program) expressions pass an empty map.
    fn from_expression_node(
        node: &ExpressionNode,
        data_variable_map: &HashMap<String, usize>,
        constant_variable_map: &HashMap<String, usize>,
        locals: &HashMap<String, usize>,
        arena: &mut crate::data_management::data_cache::ExprStateArena,
        tables: &TableRegistry
    ) -> Result<Self, String> {
        match node {
            ExpressionNode::Constant { value } => {
                Ok(OptimizedExpressionNode::Constant { value: *value })
            }
            ExpressionNode::Variable { name } => {
                // Convert to lowercase for case-insensitive lookup (maps use lowercase keys)
                let lower_name = name.to_lowercase();

                // Program locals first: bare names, cannot collide with the
                // dotted model namespaces.
                if let Some(&slot) = locals.get(&lower_name) {
                    return Ok(OptimizedExpressionNode::Local { slot });
                }

                // Check for sim.* namespace (no map lookup needed)
                if let Some(field) = parse_sim_field(&lower_name) {
                    return Ok(OptimizedExpressionNode::SimContext { field });
                }

                // Try constant (const.* variables)
                if let Some(&idx) = constant_variable_map.get(&lower_name) {
                    return Ok(OptimizedExpressionNode::ConstantReference { cache_index: idx });
                }
                // Try data cache (data.* and node.* variables)
                if let Some(&idx) = data_variable_map.get(&lower_name) {
                    return Ok(OptimizedExpressionNode::DataCacheReference { cache_index: idx });
                }
                // self.* resolves through `locals` when lowering a [ras.*]
                // action argument; reaching here means we are anywhere else.
                if lower_name.starts_with("self.") {
                    return Err("self.* references are only available inside [ras.*] action arguments, \
                        and must appear directly in the action text (not inside [fn] definitions)".to_string());
                }
                Err(format!("Variable '{}' not found in variable maps", name))
            }
            ExpressionNode::VariableWithOffset { name, offset, default_value } => {
                // Convert to lowercase for case-insensitive lookup
                let lower_name = name.to_lowercase();

                // Constants don't support offset (they don't vary over time)
                if lower_name.starts_with("const.") {
                    return Err(format!("Offset syntax not supported for constants: {}", name));
                }

                // Simulation context variables don't support offset
                if lower_name.starts_with("sim.") {
                    return Err(format!("Offset syntax not supported for simulation context: {}", name));
                }

                // self reads the account's live state — no history to offset into
                if lower_name.starts_with("self.") {
                    return Err(format!("Offset syntax not supported for self references: {}", name));
                }

                // Node outputs and var values cannot look forward - future values
                // have not been computed
                if (lower_name.starts_with("node.") || lower_name.starts_with("var.")) && *offset > 0 {
                    return Err(format!("Forward lookup not supported for computed series: {}", name));
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
                let left_opt = Self::from_expression_node(left, data_variable_map, constant_variable_map, locals, arena, tables)?;
                let right_opt = Self::from_expression_node(right, data_variable_map, constant_variable_map, locals, arena, tables)?;

                Ok(OptimizedExpressionNode::BinaryOp {
                    left: Box::new(left_opt),
                    op: *op,
                    right: Box::new(right_opt),
                })
            }
            ExpressionNode::UnaryOp { op, operand } => {
                let operand_opt = Self::from_expression_node(operand, data_variable_map, constant_variable_map, locals, arena, tables)?;

                Ok(OptimizedExpressionNode::UnaryOp {
                    op: *op,
                    operand: Box::new(operand_opt),
                })
            }
            ExpressionNode::FunctionCall { func, args } => {
                let args_opt: Result<Vec<_>, String> = args
                    .iter()
                    .map(|arg| Self::from_expression_node(arg, data_variable_map, constant_variable_map, locals, arena, tables))
                    .collect();

                lower_function_call(func, args_opt?, arena, tables)
            }
        }
    }
}


/// Advance a moving_sum/mean ring buffer by one step: evict the oldest value,
/// store the incoming one, and maintain the running sum incrementally (one
/// subtract + one add). The sum is recomputed from the ring in two cold
/// cases, both O(n) but amortised away:
/// - on every head wrap (once per n steps), bounding float drift so a long
///   run cannot accumulate error against the true window sum;
/// - on evicting a NaN, so one bad value poisons the sum for exactly n steps
///   and then leaves (an incremental `sum += x - NaN` would poison forever).
fn advance_ring(data_cache: &mut DataCache, n: usize, f_off: usize, u_off: usize, x: f64) {
    let arena = &mut data_cache.expr_state;
    let head = arena.u[u_off];
    let evicted = arena.f[f_off + head];
    arena.f[f_off + head] = x;
    let new_head = if head + 1 == n { 0 } else { head + 1 };
    arena.u[u_off] = new_head;

    let sum_slot = f_off + n;
    if evicted.is_nan() || new_head == 0 {
        let mut s = 0.0;
        for i in 0..n {
            s += arena.f[f_off + i];
        }
        arena.f[sum_slot] = s;
    } else {
        arena.f[sum_slot] += x - evicted;
    }
}

/// Advance a moving_min/max monotonic deque by one step. Amortised O(1):
/// every element is pushed and popped at most once.
///
/// Layout: values in f[f_off..f_off+n+1]; expiry steps in u[u_off..u_off+n+1];
/// head index at u[u_off+n+1]; length at u[u_off+n+2]. Entries are stored
/// circularly; an element sampled at step s expires after step s + n - 1.
/// NaN inputs are skipped entirely: the window min/max suppress NaN exactly
/// as the min/max builtins do, and an empty deque (all real values NaN)
/// reads as NaN at evaluation.
fn advance_deque(data_cache: &mut DataCache, n: usize, f_off: usize, u_off: usize, x: f64, is_min: bool) {
    let step = data_cache.current_step;
    let arena = &mut data_cache.expr_state;
    let cap = n + 1;
    let head_slot = u_off + cap;
    let len_slot = head_slot + 1;

    let mut head = arena.u[head_slot];
    let mut len = arena.u[len_slot];

    // 1) Expire the front while it has fallen out of the window.
    while len > 0 && arena.u[u_off + head] < step {
        head = if head + 1 == cap { 0 } else { head + 1 };
        len -= 1;
    }

    // 2) Push x, discarding dominated entries from the back. A dominated
    //    entry can never be the window statistic again: it is both older and
    //    no better than x.
    if !x.is_nan() {
        while len > 0 {
            let mut back = head + len - 1;
            if back >= cap {
                back -= cap;
            }
            let bv = arena.f[f_off + back];
            let dominated = if is_min { bv >= x } else { bv <= x };
            if dominated {
                len -= 1;
            } else {
                break;
            }
        }
        let mut tail = head + len;
        if tail >= cap {
            tail -= cap;
        }
        arena.f[f_off + tail] = x;
        arena.u[u_off + tail] = step + n - 1;
        len += 1;
    }

    arena.u[head_slot] = head;
    arena.u[len_slot] = len;
}

/// A lowered program statement. Few by design: assignments and asserts only
/// (see FunctionParser::parse_program for why bare expression statements are
/// rejected at parse).
#[derive(Debug, Clone)]
pub enum OptStmt {
    /// Write the expression's value into an arena slot (a program local).
    Assign {
        slot: usize,
        expr: OptimizedExpressionNode,
    },
    /// Fail the run when the expression is 0 or NaN. `meta` indexes the
    /// owning program's assert_meta table (cold data, only touched on failure).
    Assert {
        expr: OptimizedExpressionNode,
        meta: u32,
    },
    /// Conditional statement group (produced only by the fn inliner, for
    /// calls inside `if` branches and short-circuit operands).
    ///
    /// Execution rules, per side:
    /// - Taken side: executes normally (asserts fire).
    /// - Untaken side, stateless (`*_has_state == false`): skipped entirely —
    ///   an untaken heavy pure body costs nothing.
    /// - Untaken side containing stateful nodes: executes SILENTLY on the
    ///   once-per-step advance pass — assignments run so window/*_since
    ///   inputs (including hidden argument bindings) stay live, keeping
    ///   state path-independent (design §5), but asserts are suppressed.
    ///
    /// Either way a function-body assert is a precondition: it fires only
    /// when its call's branch is taken.
    Cond {
        cond: OptimizedExpressionNode,
        then_stmts: Vec<OptStmt>,
        else_stmts: Vec<OptStmt>,
        /// Does each side's lowered tree contain stateful nodes? Computed at
        /// lowering; decides whether the untaken side runs silently or is
        /// skipped.
        then_has_state: bool,
        else_has_state: bool,
    },
}

/// A lowered `{ ... }` program: statements executed in order, then the result
/// expression. Locals were resolved to absolute arena slots at lowering, so
/// evaluation is exactly as infallible and allocation-free as a plain
/// expression — the only difference is the statement loop and slot writes.
#[derive(Debug, Clone)]
pub struct OptimizedProgram {
    stmts: Vec<OptStmt>,
    result: OptimizedExpressionNode,
    /// Source text for each assert, indexed by OptStmt::Assert.meta.
    /// Cold: read only when composing a failure message.
    assert_meta: Vec<String>,
    /// Arena u-slot guarding once-per-step state advance, present only when
    /// the program contains stateful calls (moving_*/*_since). Stores
    /// last-advanced step + 1, so the init value 0 means "never" and the
    /// run-start arena reset re-arms it. Stateless programs pay one
    /// predictable None check.
    advance_guard: Option<usize>,
}

impl OptimizedProgram {
    /// Execute the statements in order, then evaluate and return the result.
    ///
    /// On the first evaluation of each step (guarded), stateful nodes advance
    /// *interleaved* with statement execution: each statement's subtree
    /// advances immediately before that statement evaluates, so a stateful
    /// call sampling a program local sees the local as of its own statement's
    /// position — with every earlier assignment applied, exactly as the
    /// program reads.
    pub fn evaluate(&self, data_cache: &mut DataCache) -> f64 {
        let advance = match self.advance_guard {
            Some(g) => {
                let tag = data_cache.current_step + 1;
                if data_cache.expr_state.u[g] != tag {
                    data_cache.expr_state.u[g] = tag;
                    true
                } else {
                    false
                }
            }
            None => false,
        };

        self.run_stmts(&self.stmts, data_cache, advance, true);
        if advance {
            self.result.advance_state(data_cache);
        }
        self.result.evaluate(data_cache)
    }

    /// Execute a statement list. `advance` interleaves the once-per-step
    /// state advance immediately before each statement evaluates (so
    /// stateful calls sample locals at their own statement's position).
    /// `fire_asserts` is false when running an untaken Cond side silently:
    /// assignments execute (keeping stateful inputs live), asserts are
    /// neither checked nor evaluated.
    fn run_stmts(&self, stmts: &[OptStmt], data_cache: &mut DataCache, advance: bool, fire_asserts: bool) {
        for stmt in stmts {
            match stmt {
                OptStmt::Assign { slot, expr } => {
                    if advance {
                        expr.advance_state(data_cache);
                    }
                    let v = expr.evaluate(data_cache);
                    data_cache.expr_state.f[*slot] = v;
                }
                OptStmt::Assert { expr, meta } => {
                    if advance {
                        expr.advance_state(data_cache);
                    }
                    if fire_asserts {
                        let v = expr.evaluate(data_cache);
                        // Fails on 0 AND on NaN. Written as two explicit
                        // conditions: `!(v != 0.0)` would pass NaN through,
                        // which is exactly the case the modeller most needs
                        // caught.
                        if v == 0.0 || v.is_nan() {
                            self.assert_failed(*meta, v, data_cache);
                        }
                    }
                }
                OptStmt::Cond { cond, then_stmts, else_stmts, then_has_state, else_has_state } => {
                    if advance {
                        cond.advance_state(data_cache);
                    }
                    let taken_then = cond.evaluate(data_cache) != 0.0;
                    let (taken, untaken, untaken_has_state) = if taken_then {
                        (then_stmts, else_stmts, *else_has_state)
                    } else {
                        (else_stmts, then_stmts, *then_has_state)
                    };
                    // A stateful untaken side runs silently on the advance
                    // pass: its assignments keep window/*_since inputs live
                    // (path-independent state, design §5) but its asserts
                    // stay quiet. A pure untaken side is skipped outright.
                    // Ordering: untaken first, so the taken side's writes —
                    // including the shared result local — land last.
                    if advance && untaken_has_state {
                        self.run_stmts(untaken, data_cache, true, false);
                    }
                    self.run_stmts(taken, data_cache, advance, fire_asserts);
                }
            }
        }
    }

    /// Step-0 read validation: walk every statement and the result, exactly
    /// as OptimizedExpressionNode::validate_reads does for plain expressions.
    #[cold]
    #[inline(never)]
    pub fn validate_reads(&self, data_cache: &DataCache) {
        fn walk(stmts: &[OptStmt], data_cache: &DataCache) {
            for stmt in stmts {
                match stmt {
                    OptStmt::Assign { expr, .. } => expr.validate_reads(data_cache),
                    OptStmt::Assert { expr, .. } => expr.validate_reads(data_cache),
                    OptStmt::Cond { cond, then_stmts, else_stmts, .. } => {
                        cond.validate_reads(data_cache);
                        walk(then_stmts, data_cache);
                        walk(else_stmts, data_cache);
                    }
                }
            }
        }
        walk(&self.stmts, data_cache);
        self.result.validate_reads(data_cache);
    }

    /// Cold assert-failure path: only reached on a run that is already dead,
    /// so the message can afford to be helpful (statement as written, the
    /// offending value, and the timestep).
    #[cold]
    #[inline(never)]
    fn assert_failed(&self, meta: u32, value: f64, data_cache: &DataCache) -> ! {
        panic!(
            "Assertion failed at {}: {} (condition evaluated to {})",
            crate::tid::utils::u64_to_iso_datetime_string(data_cache.current_timestamp),
            self.assert_meta[meta as usize],
            value
        )
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
/// The closed set of per-target fields a `[ras.*]` action argument may read,
/// in arena-slot order (slot = base + position here). `self.pair.*` reads
/// the target's paired account (the `pair` column, readable from either end
/// of the pairing) and requires every target to be paired (checked at load).
/// This is the whole self surface: `self` is a context, not a namespace that
/// grows by accident.
pub const RAS_SELF_FIELDS: [&str; 6] = [
    "self.balance", "self.size", "self.allocation",
    "self.pair.balance", "self.pair.size", "self.pair.allocation",
];

/// A parsed `[ras.*]` action argument: the value expression plus the self
/// plumbing the RAS needs at run time (see `from_string_ras_action`).
pub struct RasActionArg {
    pub input: DynamicInput,
    /// Arena base of the six self slots — Some iff the argument references
    /// `self.*`, which is also the signal for per-target evaluation.
    pub self_slots: Option<usize>,
    /// Whether any `self.pair.*` field is read (drives the load-time
    /// every-target-must-be-paired check).
    pub uses_pair: bool,
}

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
    /// Form: a1 * data1 + a2 * data2 + ... where the ai come from the
    /// Beta-corrected stick-breaking map over (bias, u_params) — see
    /// `linear_combination::compute_stick_breaking_weights`. A uniform search
    /// of the u-box is a uniform, exchangeable search of the weight simplex.
    LinearCombination {
        /// Indices into data cache for each data source
        data_indices: Vec<usize>,
        /// Variable names for each data source (preserved for serialization)
        variable_names: Vec<String>,
        /// Current weights (updated when optimization parameters change)
        coefficients: Vec<f64>,
        /// Distribution parameters u_i in [0,1] (optimizable), length n-1 for
        /// n stations. Initialised at load by inverting the parsed
        /// coefficients, so (bias, u_params) and `coefficients` always agree.
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

    /// `{ ... }` program block: statements (local assignments, asserts) and a
    /// result expression. Locals live in the DataCache expression-state arena,
    /// resolved to absolute slots at lowering.
    Program {
        expression: String,  // Original text including braces, for serialization
        program: OptimizedProgram
    },

    /// Function expression containing stateful calls (moving_*/*_since).
    /// A separate variant so the plain Function hot path pays nothing for
    /// the feature: only trees that actually carry state check the guard.
    /// `guard` is an arena u-slot holding last-advanced step + 1 (0 = never;
    /// re-armed by the run-start arena reset).
    StatefulFunction {
        expression: String,
        optimised_ast: OptimizedExpressionNode,
        guard: usize,
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
        Self::from_string_impl(expression, data_cache, flag_as_critical, self_context, None)
    }

    /// Parse a `[ras.*]` action argument. Exactly `from_string`, plus the one
    /// context nowhere else has: `self.*` fields (RAS_SELF_FIELDS), read
    /// per target account at each firing. When the argument references self,
    /// six arena slots are allocated and every `self.<field>` lowers to a
    /// Local read of its slot; the RAS writes the slots for each target
    /// before evaluating. Arguments without self keep the evaluate-once
    /// semantics (and cost) they have today.
    pub fn from_string_ras_action(expression: &str, data_cache: &mut DataCache) -> Result<RasActionArg, String> {
        let trimmed = expression.trim();

        // Peek at the argument's own variables to decide whether to allocate
        // the self frame. [fn] bodies cannot contribute self references —
        // they are rejected at lowering — so this pre-inline scan is
        // complete. A parse error here is ignored: the main path below
        // reports it with its usual context.
        let vars: Vec<String> = if trimmed.starts_with('{') {
            crate::functions::parse_program(trimmed)
                .map(|p| p.program().get_external_variables().iter().cloned().collect())
                .unwrap_or_default()
        } else {
            parse_function(trimmed)
                .map(|p| p.get_variables().iter().cloned().collect())
                .unwrap_or_default()
        };
        let self_vars: Vec<String> = vars.iter()
            .map(|v| v.to_lowercase())
            .filter(|v| v.starts_with("self."))
            .collect();

        if self_vars.is_empty() {
            let input = Self::from_string(trimmed, data_cache, true, None)?;
            return Ok(RasActionArg { input, self_slots: None, uses_pair: false });
        }

        for v in &self_vars {
            if !RAS_SELF_FIELDS.contains(&v.as_str()) {
                return Err(format!("Unknown self field '{}'. Available: {}", v, RAS_SELF_FIELDS.join(", ")));
            }
        }

        // NaN-initialised so an unwritten slot poisons visibly rather than
        // reading as a plausible zero. The RAS overwrites all six before
        // every per-target evaluation.
        let base = data_cache.expr_state.alloc_f(&[f64::NAN; 6]);
        let self_map: HashMap<String, usize> = RAS_SELF_FIELDS.iter().enumerate()
            .map(|(i, name)| (name.to_string(), base + i))
            .collect();
        let input = Self::from_string_impl(trimmed, data_cache, true, None, Some(&self_map))?;
        let uses_pair = self_vars.iter().any(|v| v.starts_with("self.pair."));
        Ok(RasActionArg { input, self_slots: Some(base), uses_pair })
    }

    fn from_string_impl(
        expression: &str,
        data_cache: &mut DataCache,
        flag_as_critical: bool,
        self_context: Option<&str>,
        self_map: Option<&HashMap<String, usize>>,
    ) -> Result<Self, String> {
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

        // A leading '{' means a program block — the block form of a value
        // (structured_expressions_design.md §3.2). Blocks are only legal as
        // the entire value, so this one character decides the parse.
        if working_copy.trim_start().starts_with('{') {
            return Self::program_from_string(trimmed, &working_copy, data_cache, flag_as_critical, self_context, self_map);
        }

        // Parse the expression (using the expanded form)
        let parsed = parse_function(&working_copy)
            .map_err(|e| format!("Failed to parse expression '{}': {}", trimmed, e))?;

        // A bare `table.foo` or `fn.foo` (no call parentheses) parses as a
        // variable, and would otherwise silently register a phantom data
        // series. Reject here, before linear-combination detection can
        // capture it.
        for var_name in parsed.get_variables() {
            let lower_name = var_name.to_lowercase();
            if lower_name.starts_with("table.") {
                return Err(format!(
                    "'{}' is a lookup table reference and must be called with arguments, e.g. {}(x)",
                    var_name, lower_name
                ));
            }
            if lower_name.starts_with("fn.") {
                return Err(format!(
                    "'{}' is a function reference and must be called with parentheses, e.g. {}(...)",
                    var_name, lower_name
                ));
            }
        }

        // fn.* calls: inline before anything else inspects the tree, then
        // lower the result as a (possibly statement-free) program. Values
        // that call no functions skip this entirely and take their existing
        // paths untouched.
        if crate::functions::inline::expr_references_fns(parsed.get_ast()) {
            let program = Program {
                stmts: vec![],
                result: parsed.get_ast().clone(),
            };
            let inlined = crate::functions::inline::inline_fn_calls(program, &data_cache.fns, self_context)
                .map_err(|e| format!("in '{}': {}", trimmed, e))?;
            return Self::lower_program(trimmed, &inlined, data_cache, flag_as_critical, true, self_map);
        }

        // self.* references resolve through self_map (RAS action arguments
        // only) and are validated before anything else can misread them: the
        // linear-combination detector and the series-registration loop below
        // would otherwise capture 'self.balance' as a phantom data series.
        let has_self_vars = parsed.get_variables().iter()
            .any(|v| v.to_lowercase().starts_with("self."));
        if has_self_vars {
            let Some(map) = self_map else {
                return Err("self.* references are only available inside [ras.*] action arguments, \
                    and must appear directly in the action text (not inside [fn] definitions)".to_string());
            };
            for var_name in parsed.get_variables() {
                let lower_name = var_name.to_lowercase();
                if lower_name.starts_with("self.") && !map.contains_key(&lower_name) {
                    return Err(format!("Unknown self field '{}'. Available: {}",
                        var_name, RAS_SELF_FIELDS.join(", ")));
                }
            }
        }

        // Check if it's a linear combination pattern first
        if let Some(linear_info) = (!has_self_vars).then(|| detect_linear_combination(parsed.get_ast())).flatten() {
            // It's a linear combination! Create the LinearCombination variant
            let mut data_indices = Vec::new();

            // Resolve variable names to data cache indices
            for var_name in &linear_info.variables {
                let lower_name = var_name.to_lowercase();
                // node.*, var.* and acc.* references are not critical inputs (they
                // are computed during the run, not loaded)
                let is_critical = flag_as_critical
                    && !lower_name.starts_with("node.")
                    && !lower_name.starts_with("var.")
                    && !lower_name.starts_with("acc.");
                let idx = data_cache.get_or_add_new_series(&lower_name, is_critical);
                data_indices.push(idx);
            }

            // Initialise (bias, u_params) as the exact stick-breaking
            // inversion of the parsed coefficients, so the internal state
            // agrees with what the modeller wrote: warm-starts reproduce the
            // file's weights, and setting a subset of rf_* parameters leaves
            // the rest of the distribution intact. Negative or all-zero
            // weight vectors are not representable — fall back to
            // equal-weight u defaults (bias still the coefficient sum).
            let n = data_indices.len();
            let coefficients = linear_info.coefficients.clone();
            let (u_params, bias) = match invert_stick_breaking_weights(&coefficients) {
                Some((u, b)) => (u, b),
                None => (equal_weight_u_params(n), coefficients.iter().sum()),
            };

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
            } else if lower_name.starts_with("self.") {
                // Validated above; resolves to an arena slot at lowering,
                // never to a series.
                continue;
            } else if lower_name.starts_with("const.") {
                // Resolve to constants cache
                let idx = data_cache.constants.add_if_needed_and_get_idx(&lower_name);
                constant_variable_map.insert(lower_name.clone(), idx);
            } else if lower_name.starts_with("node.") || lower_name.starts_with("var.")
                || lower_name.starts_with("acc.") {
                // Resolve to data cache but NOT as critical input (node outputs,
                // var values and account state are computed during the run, not loaded)
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
            if lower_var.starts_with("const.") {
                return Err(format!("Offset syntax not supported for constants: {}", var_name));
            }

            // Simulation context variables don't support offset
            if lower_var.starts_with("sim.") {
                return Err(format!("Offset syntax not supported for simulation context: {}", var_name));
            }

            // self reads the account's live state — there is no history to offset into
            if lower_var.starts_with("self.") {
                return Err(format!("Offset syntax not supported for self references: {}", var_name));
            }

            // Node outputs and var values cannot look forward
            if (lower_var.starts_with("node.") || lower_var.starts_with("var.")) && offset > 0 {
                return Err(format!("Forward lookup not supported for computed series: {}", var_name));
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

            // sim.* and self.* variables need to go through the Function path
            if lower_var.starts_with("sim.") || lower_var.starts_with("self.") {
                Self::function_from_parsed(trimmed, &parsed, &data_variable_map, &constant_variable_map, data_cache, self_map)
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
            Self::function_from_parsed(trimmed, &parsed, &data_variable_map, &constant_variable_map, data_cache, self_map)
        }
    }

    /// Lower a parsed plain expression to Function or StatefulFunction.
    /// Statefulness is detected by arena growth during lowering — a tree that
    /// allocated window/since state needs the once-per-step advance guard;
    /// anything else stays a plain Function, byte-identical to before the
    /// stateful builtins existed.
    fn function_from_parsed(
        trimmed: &str,
        parsed: &crate::functions::parser::ParsedFunction,
        data_variable_map: &HashMap<String, usize>,
        constant_variable_map: &HashMap<String, usize>,
        data_cache: &mut DataCache,
        self_map: Option<&HashMap<String, usize>>,
    ) -> Result<Self, String> {
        let DataCache { expr_state, tables, needs_calendar_flags, .. } = data_cache;
        let f_mark = expr_state.f.len();
        let u_mark = expr_state.u.len();
        let optimised_ast = transform_to_optimised_ast(parsed, data_variable_map, constant_variable_map, expr_state, tables, self_map)?;
        if !*needs_calendar_flags && uses_calendar_flags(&optimised_ast) {
            *needs_calendar_flags = true;
        }
        if expr_state.f.len() > f_mark || expr_state.u.len() > u_mark {
            let guard = expr_state.alloc_u(&[0]);
            Ok(DynamicInput::StatefulFunction {
                expression: trimmed.to_string(),
                optimised_ast,
                guard,
            })
        } else {
            Ok(DynamicInput::Function {
                expression: trimmed.to_string(),
                optimised_ast,
            })
        }
    }

    /// Create a DynamicInput::Program from a `{ ... }` block.
    ///
    /// External (dotted) references register in the cache exactly as for
    /// plain expressions. Locals get one arena slot per distinct assigned
    /// name, and statements are lowered *sequentially*: each expression sees
    /// only the locals assigned above it, so use-before-assign cannot
    /// resolve. (The up-front bare-name check catches those with a specific
    /// message before any series could be registered.)
    fn program_from_string(
        original: &str,
        working_copy: &str,
        data_cache: &mut DataCache,
        flag_as_critical: bool,
        self_context: Option<&str>,
        self_map: Option<&HashMap<String, usize>>,
    ) -> Result<Self, String> {
        let parsed = crate::functions::parse_program(working_copy)
            .map_err(|e| format!("Failed to parse program '{}': {}", original, e))?;

        // Expand fn.* calls before anything else looks at the tree. Values
        // that reference no functions skip this entirely.
        let mut program = parsed.program().clone();
        if crate::functions::inline::references_fns(&program) {
            program = crate::functions::inline::inline_fn_calls(program, &data_cache.fns, self_context)
                .map_err(|e| format!("in '{}': {}", original, e))?;
        }

        // Block-syntax values always lower to the Program variant, even with
        // zero statements — `{ data.x }` is a Program by declaration.
        Self::lower_program(original, &program, data_cache, flag_as_critical, false, self_map)
    }

    /// Lower an inlined, self-contained Program. `unwrap_empty` lets the
    /// plain-expression-with-fn-calls path recover the Function /
    /// StatefulFunction variants when inlining produced no statements
    /// (zero-argument functions with expression bodies) — block-syntax
    /// callers pass false so `{ expr }` stays a Program.
    fn lower_program(
        original: &str,
        program: &Program,
        data_cache: &mut DataCache,
        flag_as_critical: bool,
        unwrap_empty: bool,
        self_map: Option<&HashMap<String, usize>>,
    ) -> Result<Self, String> {
        use crate::functions::ast::Stmt;

        // Register external references, partitioned by namespace exactly as
        // the plain-expression path does.
        let mut data_variable_map = HashMap::new();
        let mut constant_variable_map = HashMap::new();
        for var_name in program.get_external_variables() {
            let lower_name = var_name.to_lowercase();

            if lower_name.starts_with("self.") {
                // Resolves to an arena slot at lowering (RAS action
                // arguments only), never to a series.
                let Some(map) = self_map else {
                    return Err("self.* references are only available inside [ras.*] action arguments, \
                        and must appear directly in the action text (not inside [fn] definitions)".to_string());
                };
                if !map.contains_key(&lower_name) {
                    return Err(format!("Unknown self field '{}'. Available: {}",
                        var_name, RAS_SELF_FIELDS.join(", ")));
                }
                continue;
            }

            if lower_name.starts_with("table.") {
                return Err(format!(
                    "'{}' is a lookup table reference and must be called with arguments, e.g. {}(x)",
                    var_name, lower_name
                ));
            }
            if lower_name.starts_with("fn.") {
                return Err(format!(
                    "'{}' is a function reference and must be called with parentheses, e.g. {}(...)",
                    var_name, lower_name
                ));
            }
            if !lower_name.contains('.') {
                // A bare name not bound by an assignment above its use.
                // Caught here, before it could register a phantom data series.
                return Err(format!(
                    "local variable '{}' is used before it is assigned (locals must be \
                     assigned above their first use; model references need a namespace \
                     prefix like data. or node.)",
                    var_name
                ));
            }

            if lower_name.starts_with("sim.") {
                continue;
            } else if lower_name.starts_with("const.") {
                let idx = data_cache.constants.add_if_needed_and_get_idx(&lower_name);
                constant_variable_map.insert(lower_name, idx);
            } else if lower_name.starts_with("node.") || lower_name.starts_with("var.")
                || lower_name.starts_with("acc.") {
                // Computed during the run, not loaded: never critical.
                let idx = data_cache.get_or_add_new_series(lower_name.as_str(), false);
                data_variable_map.insert(lower_name, idx);
            } else {
                let idx = data_cache.get_or_add_new_series(lower_name.as_str(), flag_as_critical);
                data_variable_map.insert(lower_name, idx);
            }
        }

        // Allocate the locals frame: one slot per distinct assigned name,
        // zero-initialised (a local is always written before it is read —
        // the sequential lowering below guarantees it; a Cond result local
        // is written on whichever side is taken).
        fn count_assigns<'s>(stmts: &'s [Stmt], seen: &mut Vec<&'s str>) {
            for stmt in stmts {
                match stmt {
                    Stmt::Assign { name, .. } => {
                        if !seen.iter().any(|s| s.eq_ignore_ascii_case(name)) {
                            seen.push(name);
                        }
                    }
                    Stmt::Assert { .. } => {}
                    Stmt::Cond { then_stmts, else_stmts, .. } => {
                        count_assigns(then_stmts, seen);
                        count_assigns(else_stmts, seen);
                    }
                }
            }
        }
        let mut n_slots = 0usize;
        {
            let mut seen: Vec<&str> = Vec::new();
            count_assigns(&program.stmts, &mut seen);
            n_slots = seen.len();
        }
        // Split borrows for the lowering section: stateful calls allocate in
        // the arena while table lookups resolve against the registry.
        let DataCache { expr_state, tables, needs_calendar_flags, .. } = data_cache;
        let frame_offset = expr_state.alloc_f(&vec![0.0; n_slots]);
        let f_mark = expr_state.f.len();
        let u_mark = expr_state.u.len();

        // Lower statements in order; `locals` grows as assignments bind.
        // Cond sides share the outer locals map: hidden names are hygienic
        // by construction, and a Cond result local resolves to one slot from
        // both sides.
        struct LowerCtx<'c> {
            data_variable_map: &'c HashMap<String, usize>,
            constant_variable_map: &'c HashMap<String, usize>,
            locals: HashMap<String, usize>,
            next_slot: usize,
            assert_meta: Vec<String>,
        }
        fn lower_stmts(
            in_stmts: &[Stmt],
            ctx: &mut LowerCtx,
            expr_state: &mut crate::data_management::data_cache::ExprStateArena,
            tables: &TableRegistry,
        ) -> Result<Vec<OptStmt>, String> {
            let mut out: Vec<OptStmt> = Vec::with_capacity(in_stmts.len());
            for stmt in in_stmts {
                match stmt {
                    Stmt::Assign { name, expr } => {
                        let lowered = OptimizedExpressionNode::from_expression_node(
                            expr, ctx.data_variable_map, ctx.constant_variable_map, &ctx.locals, expr_state, tables)?;
                        let next = &mut ctx.next_slot;
                        let slot = *ctx.locals.entry(name.to_lowercase()).or_insert_with(|| {
                            let s = *next;
                            *next += 1;
                            s
                        });
                        out.push(OptStmt::Assign { slot, expr: lowered });
                    }
                    Stmt::Assert { expr, source_text } => {
                        let lowered = OptimizedExpressionNode::from_expression_node(
                            expr, ctx.data_variable_map, ctx.constant_variable_map, &ctx.locals, expr_state, tables)?;
                        let meta = ctx.assert_meta.len() as u32;
                        ctx.assert_meta.push(source_text.clone());
                        out.push(OptStmt::Assert { expr: lowered, meta });
                    }
                    Stmt::Cond { cond, then_stmts, else_stmts } => {
                        let cond = OptimizedExpressionNode::from_expression_node(
                            cond, ctx.data_variable_map, ctx.constant_variable_map, &ctx.locals, expr_state, tables)?;
                        // Arena growth while lowering a side tells us whether
                        // that side carries stateful nodes — the same
                        // detection the Function/StatefulFunction split uses.
                        let mark = (expr_state.f.len(), expr_state.u.len());
                        let then_stmts = lower_stmts(then_stmts, ctx, expr_state, tables)?;
                        let then_has_state = (expr_state.f.len(), expr_state.u.len()) != mark;
                        let mark = (expr_state.f.len(), expr_state.u.len());
                        let else_stmts = lower_stmts(else_stmts, ctx, expr_state, tables)?;
                        let else_has_state = (expr_state.f.len(), expr_state.u.len()) != mark;
                        out.push(OptStmt::Cond {
                            cond,
                            then_stmts,
                            else_stmts,
                            then_has_state,
                            else_has_state,
                        });
                    }
                }
            }
            Ok(out)
        }

        let mut ctx = LowerCtx {
            data_variable_map: &data_variable_map,
            constant_variable_map: &constant_variable_map,
            // Seed the self slots (dotted keys, so no collision with bare
            // program locals is possible) — self.* then resolves through the
            // ordinary locals-first lookup.
            locals: self_map.cloned().unwrap_or_default(),
            next_slot: frame_offset,
            assert_meta: Vec::new(),
        };
        let stmts = lower_stmts(&program.stmts, &mut ctx, expr_state, tables)?;
        let locals = ctx.locals;
        let assert_meta = ctx.assert_meta;

        let result = OptimizedExpressionNode::from_expression_node(
            &program.result, &data_variable_map, &constant_variable_map, &locals, expr_state, tables)?;

        let grew = expr_state.f.len() > f_mark || expr_state.u.len() > u_mark;

        if !*needs_calendar_flags
            && (stmts_use_calendar_flags(&stmts) || uses_calendar_flags(&result))
        {
            *needs_calendar_flags = true;
        }

        // Inlining a plain expression can produce a statement-free program
        // (zero-argument functions with expression bodies): recover the
        // plain variants so such values cost exactly what the pasted
        // expression would.
        if unwrap_empty && stmts.is_empty() {
            return Ok(if grew {
                let guard = expr_state.alloc_u(&[0]);
                DynamicInput::StatefulFunction {
                    expression: original.to_string(),
                    optimised_ast: result,
                    guard,
                }
            } else {
                DynamicInput::Function {
                    expression: original.to_string(),
                    optimised_ast: result,
                }
            });
        }

        // Frame slots don't need the advance guard — only window/since state
        // allocated during statement/result lowering does.
        let advance_guard = if grew {
            Some(expr_state.alloc_u(&[0]))
        } else {
            None
        };

        Ok(DynamicInput::Program {
            expression: original.to_string(),
            program: OptimizedProgram { stmts, result, assert_meta, advance_guard },
        })
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
    ///
    /// Takes `&mut DataCache` because Program variants write their locals into
    /// the cache's expression-state arena. Every production call site already
    /// holds `&mut DataCache` (node phases, the ordering system), so callers
    /// pass `data_cache` unchanged; the read-only variants simply never write.
    /// Kept `#[inline]` with the Program/StatefulFunction arms outlined into
    /// separate methods: nodes call get_value several times per step, and the
    /// simple variants (direct references, constants) only stay inlined into
    /// node code if this body stays small. Growing it with the phase-3/4 arms
    /// was a measured regression on expression-light models.
    #[inline]
    pub fn get_value(&self, data_cache: &mut DataCache) -> f64 {
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
            DynamicInput::Program { program, .. } => Self::get_value_program(program, data_cache),
            DynamicInput::StatefulFunction { optimised_ast, guard, .. } => {
                Self::get_value_stateful(optimised_ast, *guard, data_cache)
            }
        }
    }

    /// Program-variant body of get_value, outlined to keep the hot dispatch
    /// small (see get_value).
    #[inline(never)]
    fn get_value_program(program: &OptimizedProgram, data_cache: &mut DataCache) -> f64 {
        // Same step-0 validation contract as the Function arm.
        if data_cache.current_step == 0 {
            program.validate_reads(data_cache);
        }
        program.evaluate(data_cache)
    }

    /// StatefulFunction-variant body of get_value, outlined (see get_value).
    #[inline(never)]
    fn get_value_stateful(
        optimised_ast: &OptimizedExpressionNode,
        guard: usize,
        data_cache: &mut DataCache,
    ) -> f64 {
        // Advance state exactly once per step, at this input's first
        // evaluation — a second call in the same step (e.g. order phase then
        // flow phase) sees identical state. The sampled input expressions are
        // read every step by the advance walk, so step-0 validation covers
        // them like any other read.
        let tag = data_cache.current_step + 1;
        if data_cache.expr_state.u[guard] != tag {
            data_cache.expr_state.u[guard] = tag;
            if data_cache.current_step == 0 {
                optimised_ast.validate_reads(data_cache);
            }
            optimised_ast.advance_state(data_cache);
        }
        optimised_ast.evaluate(data_cache)
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
            DynamicInput::Program { expression, .. } => expression.clone(),
            DynamicInput::StatefulFunction { expression, .. } => expression.clone(),
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
            DynamicInput::Program { expression, .. } => expression.as_str(),
            DynamicInput::StatefulFunction { expression, .. } => expression.as_str(),
        }
    }
}

/// Does this lowered tree read any sim.new_* calendar flag? Cold: walked
/// once per input at load, so update_current_timestamp only computes the
/// flags for models that use them (a measured per-step cost otherwise).
fn uses_calendar_flags(node: &OptimizedExpressionNode) -> bool {
    match node {
        OptimizedExpressionNode::SimContext { field } => matches!(
            field,
            SimField::NewDay | SimField::NewMonth | SimField::NewYear
        ),
        OptimizedExpressionNode::Constant { .. }
        | OptimizedExpressionNode::DataCacheReference { .. }
        | OptimizedExpressionNode::DataCacheReferenceWithOffset { .. }
        | OptimizedExpressionNode::ConstantReference { .. }
        | OptimizedExpressionNode::Local { .. } => false,
        OptimizedExpressionNode::BinaryOp { left, right, .. } => {
            uses_calendar_flags(left) || uses_calendar_flags(right)
        }
        OptimizedExpressionNode::UnaryOp { operand, .. } => uses_calendar_flags(operand),
        OptimizedExpressionNode::Func1 { arg, .. } => uses_calendar_flags(arg),
        OptimizedExpressionNode::Func2 { a, b, .. } => {
            uses_calendar_flags(a) || uses_calendar_flags(b)
        }
        OptimizedExpressionNode::If { cond, then_branch, else_branch } => {
            uses_calendar_flags(cond)
                || uses_calendar_flags(then_branch)
                || uses_calendar_flags(else_branch)
        }
        OptimizedExpressionNode::Fold { args, .. } => args.iter().any(uses_calendar_flags),
        OptimizedExpressionNode::CalendarAt { arg, .. } => uses_calendar_flags(arg),
        OptimizedExpressionNode::MovingWindow { arg, .. } => uses_calendar_flags(arg),
        OptimizedExpressionNode::Since { arg, reset, .. } => {
            arg.as_deref().map(uses_calendar_flags).unwrap_or(false) || uses_calendar_flags(reset)
        }
        OptimizedExpressionNode::Lookup1D { arg, .. } => uses_calendar_flags(arg),
        OptimizedExpressionNode::Lookup2D { col_key, row_key, .. } => {
            uses_calendar_flags(col_key) || uses_calendar_flags(row_key)
        }
    }
}

/// Statement-list form of [`uses_calendar_flags`].
fn stmts_use_calendar_flags(stmts: &[OptStmt]) -> bool {
    stmts.iter().any(|s| match s {
        OptStmt::Assign { expr, .. } | OptStmt::Assert { expr, .. } => uses_calendar_flags(expr),
        OptStmt::Cond { cond, then_stmts, else_stmts, .. } => {
            uses_calendar_flags(cond)
                || stmts_use_calendar_flags(then_stmts)
                || stmts_use_calendar_flags(else_stmts)
        }
    })
}

/// Transform a ParsedFunction to an OptimizedExpressionNode
fn transform_to_optimised_ast(
    parsed: &crate::functions::parser::ParsedFunction,
    data_variable_map: &HashMap<String, usize>,
    constant_variable_map: &HashMap<String, usize>,
    arena: &mut crate::data_management::data_cache::ExprStateArena,
    tables: &TableRegistry,
    self_map: Option<&HashMap<String, usize>>,
) -> Result<OptimizedExpressionNode, String> {
    // Plain expressions have no locals frame — only the (dotted, so
    // collision-free) self slots of a RAS action argument, when present.
    let locals = match self_map {
        Some(map) => map.clone(),
        None => HashMap::new(),
    };
    OptimizedExpressionNode::from_expression_node(parsed.get_ast(), data_variable_map, constant_variable_map, &locals, arena, tables)
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
    arena: &mut crate::data_management::data_cache::ExprStateArena,
    tables: &TableRegistry,
) -> Result<OptimizedExpressionNode, String> {
    use BuiltinFunction as B;

    let builtin = match func {
        FunctionRef::Builtin(b) => *b,
        FunctionRef::Named(name) => {
            if let Some(bare_name) = name.strip_prefix("table.") {
                return lower_table_call(bare_name, args, tables);
            }
            if let Some(node) = lower_calendar_call(name, &mut args)? {
                return Ok(node);
            }
            if let Some(node) = lower_stateful_call(name, args, arena)? {
                return Ok(node);
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
        B::IsLeapYear => Some(crate::functions::functions::is_leap_year_f),
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
        B::Clamp => {
            if args.len() != 3 {
                return arity_err("3", args.len());
            }
            // Lowered as min(max(x, lo), hi); identical semantics to the generic
            // path, including lo > hi => hi (the outer Min applied last wins) and
            // NaN suppression (FoldOp::Max/Min use f64::max/min, so a NaN operand
            // is dropped rather than propagated, matching the min/max builtins).
            let hi = args.pop().unwrap();
            let lo = args.pop().unwrap();
            let x = args.pop().unwrap();
            let inner = OptimizedExpressionNode::Fold { op: FoldOp::Max, args: vec![x, lo] };
            Ok(OptimizedExpressionNode::Fold { op: FoldOp::Min, args: vec![inner, hi] })
        }
        // All single-argument built-ins were handled above.
        _ => unreachable!("built-in '{}' not handled in lowering", builtin.name()),
    }
}

/// Extract a load-time constant from a lowered argument, or explain why not.
/// The stateful builtins require their window length and element default to
/// be literals: state is sized and pre-filled at load (bounded cost known at
/// load — the language's governing rule).
fn constant_arg(args: &[OptimizedExpressionNode], idx: usize, func: &str, what: &str) -> Result<f64, String> {
    match &args[idx] {
        OptimizedExpressionNode::Constant { value } => Ok(*value),
        _ => Err(format!(
            "{}'s {} must be a constant (state is sized at model load)",
            func, what
        )),
    }
}

/// Lower a stateful builtin (moving_*/*_since) if `name` is one, allocating
/// its arena state. Returns Ok(None) for names that are not stateful
/// builtins, letting the caller fall through to its unknown-function error.
///
/// Init templates encode the warm-up semantics
/// (structured_expressions_design.md §5-§6):
/// - moving windows pre-fill with the element default (running sum =
///   n * default; the min/max deque starts with one default entry expiring
///   when the last pre-filled element would leave the window);
/// - *_since accumulators start as if reset fired just before the run
///   (sum/count 0, steps -1, min/max NaN which f64::min/max suppress).
/// Lower a calendar-at-offset call (`month_at`/`days_in_month_at` — the
/// CALENDAR_FUNCTIONS tier): context functions resolved here, like the
/// stateful family, because they read the simulation clock at evaluation.
/// Returns Ok(None) for names that are not calendar functions.
fn lower_calendar_call(
    name: &str,
    args: &mut Vec<OptimizedExpressionNode>,
) -> Result<Option<OptimizedExpressionNode>, String> {
    let kind = match name {
        "month_at" => CalendarAtKind::Month,
        "days_in_month_at" => CalendarAtKind::DaysInMonth,
        _ => return Ok(None),
    };
    if args.len() != 1 {
        return Err(format!(
            "Function '{}' expects 1 argument (a day offset from the current date), got {}",
            name, args.len()
        ));
    }
    Ok(Some(OptimizedExpressionNode::CalendarAt { kind, arg: Box::new(args.remove(0)) }))
}

fn lower_stateful_call(
    name: &str,
    mut args: Vec<OptimizedExpressionNode>,
    arena: &mut crate::data_management::data_cache::ExprStateArena,
) -> Result<Option<OptimizedExpressionNode>, String> {
    // Fixed-window family: moving_*(x, n, default)
    let window_op = match name {
        "moving_sum" => Some(WindowOp::Sum),
        "moving_mean" => Some(WindowOp::Mean),
        "moving_min" => Some(WindowOp::Min),
        "moving_max" => Some(WindowOp::Max),
        _ => None,
    };
    if let Some(op) = window_op {
        if args.len() != 3 {
            return Err(format!(
                "Function '{}' expects 3 arguments (x, n, default), got {}",
                name, args.len()
            ));
        }
        let n_val = constant_arg(&args, 1, name, "window length (2nd argument)")?;
        if n_val.fract() != 0.0 || n_val < 1.0 {
            return Err(format!(
                "{}'s window length must be a positive integer, got {}",
                name, n_val
            ));
        }
        let n = n_val as usize;
        let default = constant_arg(&args, 2, name, "element default (3rd argument)")?;
        let arg = Box::new(args.swap_remove(0));

        let (f_off, u_off) = match op {
            WindowOp::Sum | WindowOp::Mean => {
                // [ring; n] + [running sum]
                let mut f_init = vec![default; n];
                f_init.push(default * n as f64);
                (arena.alloc_f(&f_init), arena.alloc_u(&[0]))
            }
            WindowOp::Min | WindowOp::Max => {
                // [values; n+1] / [expires; n+1] + [head] + [len]
                let mut f_init = vec![0.0; n + 1];
                let mut u_init = vec![0usize; n + 3];
                if n >= 2 && !default.is_nan() {
                    // One entry stands for all n-1 pre-filled defaults; the
                    // newest of them (entered at step -1) expires after step
                    // n-2, and equal values collapse to one deque entry.
                    f_init[0] = default;
                    u_init[0] = n - 2;
                    u_init[n + 2] = 1; // len
                }
                (arena.alloc_f(&f_init), arena.alloc_u(&u_init))
            }
        };
        return Ok(Some(OptimizedExpressionNode::MovingWindow {
            op,
            slots: Box::new(WindowSlots { n, f_off, u_off }),
            arg,
        }));
    }

    // Event-window family: last argument is always the reset condition.
    let (since_op, arity, acc_init) = match name {
        "sum_since" => (SinceOp::Sum, 2, 0.0),
        "min_since" => (SinceOp::Min, 2, f64::NAN),
        "max_since" => (SinceOp::Max, 2, f64::NAN),
        "count_since" => (SinceOp::Count, 2, 0.0),
        "steps_since" => (SinceOp::Steps, 1, -1.0),
        _ => return Ok(None),
    };
    if args.len() != arity {
        return Err(format!(
            "Function '{}' expects {} argument(s) (the last is the reset condition), got {}",
            name, arity, args.len()
        ));
    }
    let f_off = arena.alloc_f(&[acc_init]);
    let reset = Box::new(args.pop().unwrap());
    let arg = args.pop().map(Box::new);
    Ok(Some(OptimizedExpressionNode::Since { op: since_op, f_off, arg, reset }))
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


#[cfg(test)]
mod size_tests {
    /// Cache-pressure guard: OptimizedExpressionNode is the tree node of
    /// every lowered expression - its size sets the allocation size of every
    /// Box'd child of every expression in every model. Growing it past 32
    /// bytes caused a measured 7-15% simulation regression (July 2026); a
    /// new variant needing more than 24 bytes of payload must Box its data
    /// (see MovingWindow/WindowSlots).
    #[test]
    fn optimized_expression_node_stays_32_bytes() {
        assert!(std::mem::size_of::<super::OptimizedExpressionNode>() <= 32,
            "OptimizedExpressionNode grew past 32 bytes ({}). Box the new variant's payload.",
            std::mem::size_of::<super::OptimizedExpressionNode>());
    }
}
