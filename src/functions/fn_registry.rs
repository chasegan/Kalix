/// Registry of user-defined functions from the `[fn]` section.
///
/// Functions are declared signature-as-key (`net_demand(pop, doy) = body`)
/// — the name appears exactly once — and called as `fn.net_demand(...)`
/// (namespaced, never bare, per expression-naming §2.5). Bodies are parsed
/// once here at model load; every call site is then inlined at lowering
/// (function semantics, macro implementation — see
/// `crate::functions::inline`), so nothing downstream of inlining knows
/// functions exist and calls cost nothing at runtime.
///
/// Definitions may live anywhere in the model file: functions are passive
/// (no execution time of their own), following the `[table.*]` precedent.

use rustc_hash::FxHashMap;
use std::sync::Arc;
use crate::functions::ast::{ExpressionNode, Program, Stmt};
use crate::functions::functions::BuiltinFunction;

/// Names that parse as calls but are not user-definable: the stateful
/// builtins are resolved by the lowering, not the builtin enum, so they
/// need their own reservation here.
const RESERVED_STATEFUL: [&str; 9] = [
    "moving_sum", "moving_mean", "moving_min", "moving_max",
    "sum_since", "min_since", "max_since", "count_since", "steps_since",
];

fn is_reserved_name(lower: &str) -> bool {
    BuiltinFunction::from_name(lower).is_some()
        || RESERVED_STATEFUL.contains(&lower)
        || lower == "assert"
        || lower == "this"
}

/// One user-defined function: parsed body plus the original text for
/// round-trip serialization.
#[derive(Debug)]
pub struct FnDef {
    /// Lowercase name (matching is case-insensitive, like everything).
    pub name: String,
    /// Ordered, lowercase parameter names. Calls bind positionally.
    pub params: Vec<String>,
    /// The parsed body. Expression bodies are normalized to a Program with
    /// zero statements, so the inliner handles one shape.
    pub body: Program,
    /// Key exactly as written in the model file (for serialization).
    pub original_key: String,
    /// Body exactly as written (for serialization).
    pub original_body: String,
}

/// The `[fn]` section, parsed. Insertion order is preserved for round-trip
/// serialization. Defs are Arc-shared so cloning a model's DataCache stays
/// cheap (the `TableRegistry` pattern).
#[derive(Debug, Clone, Default)]
pub struct FnRegistry {
    map: FxHashMap<String, Arc<FnDef>>,
    order: Vec<String>,
}

impl FnRegistry {
    pub fn new() -> Self {
        Self::default()
    }

    pub fn is_empty(&self) -> bool {
        self.map.is_empty()
    }

    /// Look up a function by its lowercase bare name (no `fn.` prefix).
    pub fn get(&self, bare_name: &str) -> Option<&Arc<FnDef>> {
        self.map.get(bare_name)
    }

    /// Iterate definitions in file order (for serialization).
    pub fn iter_in_order(&self) -> impl Iterator<Item = &Arc<FnDef>> {
        self.order.iter().filter_map(|n| self.map.get(n))
    }

    /// Parse one `[fn]` line — `name(params...) = body` arrives as
    /// key = "name(params...)", value = body text — and insert it.
    ///
    /// Validation: bare lowercase-folded names; parameters distinct; neither
    /// name nor parameters may shadow a builtin or reserved word; duplicate
    /// function names are fatal regardless of arity (fixed signatures, no
    /// overloads — design §8.1).
    pub fn parse_and_insert(&mut self, key: &str, body_text: &str) -> Result<(), String> {
        let (name, params) = parse_signature(key)?;

        if self.map.contains_key(&name) {
            return Err(format!(
                "duplicate function '{}' in [fn] section (one definition per name; \
                 there are no overloads)",
                name
            ));
        }

        let body = parse_body(body_text)
            .map_err(|e| format!("in function '{}': {}", name, e))?;

        self.order.push(name.clone());
        self.map.insert(name.clone(), Arc::new(FnDef {
            name,
            params,
            body,
            original_key: key.trim().to_string(),
            original_body: body_text.trim().to_string(),
        }));
        Ok(())
    }

    /// Verify the call graph is a DAG (no recursion, direct or mutual),
    /// naming the cycle in the error. Run once at model load so even an
    /// UNUSED cyclic definition is rejected — the inliner's expansion stack
    /// would only catch cycles that are actually called.
    pub fn check_dag(&self) -> Result<(), String> {
        // Iterative DFS with three-colour marking over fn->fn edges.
        #[derive(Clone, Copy, PartialEq)]
        enum Mark { White, Grey, Black }
        let mut marks: FxHashMap<&str, Mark> =
            self.map.keys().map(|k| (k.as_str(), Mark::White)).collect();

        for start in self.map.keys() {
            if marks[start.as_str()] != Mark::White {
                continue;
            }
            // Stack of (node, remaining callees); path for cycle reporting.
            let mut path: Vec<&str> = Vec::new();
            let mut stack: Vec<(&str, Vec<String>)> =
                vec![(start.as_str(), self.callees(start))];
            marks.insert(start.as_str(), Mark::Grey);
            path.push(start.as_str());

            while let Some((node, callees)) = stack.last_mut() {
                if let Some(callee) = callees.pop() {
                    // Unknown callees are reported at inline time with call
                    // context; the DAG check only follows known names.
                    let Some((key, _)) = self.map.get_key_value(&callee) else {
                        continue;
                    };
                    match marks[key.as_str()] {
                        Mark::Black => {}
                        Mark::Grey => {
                            let mut chain: Vec<&str> = path
                                .iter()
                                .skip_while(|n| **n != key.as_str())
                                .copied()
                                .collect();
                            chain.push(key.as_str());
                            return Err(format!(
                                "function definitions are recursive, which is not allowed: fn.{}",
                                chain.join(" -> fn.")
                            ));
                        }
                        Mark::White => {
                            marks.insert(key.as_str(), Mark::Grey);
                            path.push(key.as_str());
                            stack.push((key.as_str(), self.callees(key)));
                        }
                    }
                } else {
                    marks.insert(node, Mark::Black);
                    path.pop();
                    stack.pop();
                }
            }
        }
        Ok(())
    }

    /// Bare lowercase names of fn.* calls made by `name`'s body.
    fn callees(&self, name: &str) -> Vec<String> {
        let mut out = Vec::new();
        if let Some(def) = self.map.get(name) {
            for stmt in &def.body.stmts {
                match stmt {
                    Stmt::Assign { expr, .. } | Stmt::Assert { expr, .. } => {
                        collect_fn_call_names(expr, &mut out)
                    }
                }
            }
            collect_fn_call_names(&def.body.result, &mut out);
        }
        out
    }
}

/// Collect the bare lowercase names of all `fn.*` calls in an expression.
pub fn collect_fn_call_names(expr: &ExpressionNode, out: &mut Vec<String>) {
    match expr {
        ExpressionNode::Constant { .. }
        | ExpressionNode::Variable { .. }
        | ExpressionNode::VariableWithOffset { .. } => {}
        ExpressionNode::BinaryOp { left, right, .. } => {
            collect_fn_call_names(left, out);
            collect_fn_call_names(right, out);
        }
        ExpressionNode::UnaryOp { operand, .. } => collect_fn_call_names(operand, out),
        ExpressionNode::FunctionCall { func, args } => {
            if let crate::functions::ast::FunctionRef::Named(name) = func {
                if let Some(bare) = name.to_lowercase().strip_prefix("fn.") {
                    out.push(bare.to_string());
                }
            }
            for a in args {
                collect_fn_call_names(a, out);
            }
        }
    }
}

/// Parse a signature key: `name(a, b)` or `name()`. Names fold to lowercase.
fn parse_signature(key: &str) -> Result<(String, Vec<String>), String> {
    let key = key.trim();
    let open = key.find('(').ok_or_else(|| format!(
        "invalid [fn] key '{}': expected a signature like name(a, b) or name()",
        key
    ))?;
    if !key.ends_with(')') {
        return Err(format!("invalid [fn] key '{}': signature must end with ')'", key));
    }

    let name = key[..open].trim().to_lowercase();
    validate_bare_name(&name, "function name")?;

    let inner = key[open + 1..key.len() - 1].trim();
    let mut params: Vec<String> = Vec::new();
    if !inner.is_empty() {
        for p in inner.split(',') {
            let p = p.trim().to_lowercase();
            validate_bare_name(&p, "parameter")?;
            if params.contains(&p) {
                return Err(format!("duplicate parameter '{}' in signature '{}'", p, key));
            }
            params.push(p);
        }
    }
    Ok((name, params))
}

fn validate_bare_name(name: &str, what: &str) -> Result<(), String> {
    if name.is_empty() {
        return Err(format!("{} is empty", what));
    }
    if name.contains('.') {
        return Err(format!("{} '{}' must be a bare name (no '.')", what, name));
    }
    let mut chars = name.chars();
    let first_ok = chars.next().map(|c| c.is_ascii_alphabetic() || c == '_').unwrap_or(false);
    if !first_ok || !name.chars().all(|c| c.is_ascii_alphanumeric() || c == '_') {
        return Err(format!(
            "{} '{}' must start with a letter and contain only letters, digits, and underscores",
            what, name
        ));
    }
    if is_reserved_name(name) {
        return Err(format!(
            "{} '{}' collides with a builtin or reserved word",
            what, name
        ));
    }
    Ok(())
}

/// Parse a function body: a `{ ... }` block or a plain expression, either
/// way normalized to a Program.
fn parse_body(text: &str) -> Result<Program, String> {
    let trimmed = text.trim();
    if trimmed.is_empty() {
        return Err("function body is empty".to_string());
    }
    if trimmed.starts_with('{') {
        let parsed = crate::functions::parse_program(trimmed)
            .map_err(|e| format!("failed to parse body: {}", e))?;
        Ok(parsed.program().clone())
    } else {
        let parsed = crate::functions::parse_function(trimmed)
            .map_err(|e| format!("failed to parse body: {}", e))?;
        Ok(Program { stmts: vec![], result: parsed.get_ast().clone() })
    }
}
