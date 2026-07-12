/// Inline expansion of `fn.*` calls — function semantics, macro implementation.
///
/// Every call site is expanded at model load, before lowering: arguments are
/// bound once to hidden locals (evaluate-once, design §8.2), the body's
/// parameters and locals are renamed per call site (hygiene — they can never
/// collide with the caller's names), `this.` rebinds to the calling node,
/// and nested calls expand recursively with a cycle check. After inlining,
/// nothing downstream — lowering, evaluation, the optimiser — knows
/// functions exist, and a call costs exactly what the pasted body would.
///
/// Hidden locals are named with a `#` (e.g. `fn#1#pop`), a character the
/// tokenizer cannot produce in an identifier, so collision with user names
/// is structurally impossible.
///
/// ## Conditional execution (deliberate; owner decision July 2026)
///
/// A call's argument bindings and body statements hoist to statement
/// position — but when the call sits inside an `if` branch or a
/// short-circuit operand, the hoisted statements go into a [`Stmt::Cond`]
/// group, so they execute only when that branch is taken. Consequences:
/// - A body `assert` is a **precondition**: it fires only when the call
///   actually runs. (An invariant that must hold always belongs at
///   statement level in the caller, where asserts always run.)
/// - An untaken pure body costs nothing — a function call costs what the
///   equivalent pasted expression would, including its short-circuiting.
/// - Stateful builtins still advance every step on BOTH sides (the untaken
///   side runs silently: assignments execute so state samples live inputs,
///   asserts stay quiet) — window and *_since values remain
///   path-independent per design §5.

use crate::functions::ast::{ExpressionNode, FunctionRef, Program, Stmt};
use crate::functions::fn_registry::FnRegistry;
use crate::functions::functions::BuiltinFunction;
use crate::functions::operators::BinaryOperator;

/// Does this expression reference `fn.*` anywhere (calls or bare names)?
/// Cheap pre-check so values without functions skip inlining entirely and
/// take their existing paths untouched.
pub fn expr_references_fns(e: &ExpressionNode) -> bool {
    match e {
        ExpressionNode::Constant { .. } => false,
        ExpressionNode::Variable { name }
        | ExpressionNode::VariableWithOffset { name, .. } => {
            name.len() >= 3 && name[..3].eq_ignore_ascii_case("fn.")
        }
        ExpressionNode::BinaryOp { left, right, .. } => {
            expr_references_fns(left) || expr_references_fns(right)
        }
        ExpressionNode::UnaryOp { operand, .. } => expr_references_fns(operand),
        ExpressionNode::FunctionCall { func, args } => {
            let named = matches!(func, FunctionRef::Named(n)
                if n.len() >= 3 && n[..3].eq_ignore_ascii_case("fn."));
            named || args.iter().any(expr_references_fns)
        }
    }
}

/// Program-level form of [`expr_references_fns`].
pub fn references_fns(program: &Program) -> bool {
    fn stmt_refs(s: &Stmt) -> bool {
        match s {
            Stmt::Assign { expr, .. } | Stmt::Assert { expr, .. } => expr_references_fns(expr),
            Stmt::Cond { cond, then_stmts, else_stmts } => {
                expr_references_fns(cond)
                    || then_stmts.iter().any(stmt_refs)
                    || else_stmts.iter().any(stmt_refs)
            }
        }
    }
    program.stmts.iter().any(stmt_refs) || expr_references_fns(&program.result)
}

/// Expand every `fn.*` call in `program`, returning a self-contained program.
///
/// `self_context` is the calling node's expanded prefix (e.g.
/// `"node.my_dam"`) used to rebind `this.` inside function bodies; a body
/// that uses `this.` called from a context without one is a load error.
pub fn inline_fn_calls(
    program: Program,
    registry: &FnRegistry,
    self_context: Option<&str>,
) -> Result<Program, String> {
    let mut inliner = Inliner {
        registry,
        self_context,
        counter: 0,
        stack: Vec::new(),
    };

    let mut out_stmts: Vec<Stmt> = Vec::new();
    inliner.rewrite_stmts_into(program.stmts, &mut out_stmts, &Renames::none(), None)?;
    let result = inliner.rewrite(program.result, &mut out_stmts, &Renames::none())?;

    Ok(Program { stmts: out_stmts, result })
}

/// Per-expansion rename table: parameter and body-local names (lowercase) to
/// their hidden per-call-site names.
struct Renames(std::collections::HashMap<String, String>);

impl Renames {
    fn none() -> Self {
        Renames(std::collections::HashMap::new())
    }
    fn lookup(&self, name: &str) -> Option<&String> {
        self.0.get(&name.to_lowercase())
    }
}

struct Inliner<'a> {
    registry: &'a FnRegistry,
    self_context: Option<&'a str>,
    /// Per-value counter making every expansion's hidden names unique.
    counter: usize,
    /// Expansion stack for cycle reporting (belt-and-braces with the
    /// registry's load-time DAG check).
    stack: Vec<String>,
}

impl<'a> Inliner<'a> {
    /// Rewrite a statement list into `out`, expanding calls as we go.
    /// `assert_prefix` labels asserts spliced from a function body so the
    /// panic message names the function.
    fn rewrite_stmts_into(
        &mut self,
        stmts: Vec<Stmt>,
        out: &mut Vec<Stmt>,
        renames: &Renames,
        assert_prefix: Option<&str>,
    ) -> Result<(), String> {
        for stmt in stmts {
            match stmt {
                Stmt::Assign { name, expr } => {
                    let expr = self.rewrite(expr, out, renames)?;
                    // Body locals were pre-seeded into `renames` by
                    // expand_call; top-level (caller) locals pass through.
                    let name = renames.lookup(&name).cloned().unwrap_or(name);
                    out.push(Stmt::Assign { name, expr });
                }
                Stmt::Assert { expr, source_text } => {
                    let expr = self.rewrite(expr, out, renames)?;
                    let source_text = match assert_prefix {
                        Some(p) => format!("{}: {}", p, source_text),
                        None => source_text,
                    };
                    out.push(Stmt::Assert { expr, source_text });
                }
                Stmt::Cond { cond, then_stmts, else_stmts } => {
                    // Only reachable when re-inlining an already-inlined
                    // program (defensive): sides stay conditional.
                    let cond = self.rewrite(cond, out, renames)?;
                    let mut t = Vec::new();
                    self.rewrite_stmts_into(then_stmts, &mut t, renames, assert_prefix)?;
                    let mut e = Vec::new();
                    self.rewrite_stmts_into(else_stmts, &mut e, renames, assert_prefix)?;
                    out.push(Stmt::Cond { cond, then_stmts: t, else_stmts: e });
                }
            }
        }
        Ok(())
    }

    /// Rewrite an expression: rename per `renames`, rebind `this.`, and
    /// expand `fn.*` calls by hoisting into `out_stmts`.
    fn rewrite(
        &mut self,
        expr: ExpressionNode,
        out_stmts: &mut Vec<Stmt>,
        renames: &Renames,
    ) -> Result<ExpressionNode, String> {
        match expr {
            ExpressionNode::Constant { .. } => Ok(expr),

            ExpressionNode::Variable { name } => Ok(ExpressionNode::Variable {
                name: self.rewrite_name(name, renames)?,
            }),

            ExpressionNode::VariableWithOffset { name, offset, default_value } => {
                // A parameter or body local is a bound value, not a series:
                // a temporal offset on one is meaningless. Reject with the
                // user's own name, before lowering could leak a hidden one.
                if renames.lookup(&name).is_some() {
                    return Err(format!(
                        "cannot apply a temporal offset to '{}' in fn.{}: it is a \
                         parameter or local, not a series reference",
                        name,
                        self.stack.last().map(|s| s.as_str()).unwrap_or("?")
                    ));
                }
                Ok(ExpressionNode::VariableWithOffset {
                    name: self.rewrite_name(name, renames)?,
                    offset,
                    default_value,
                })
            }

            ExpressionNode::BinaryOp { left, op, right } => {
                let left = self.rewrite(*left, out_stmts, renames)?;

                // && and || short-circuit their right operand. If expanding
                // it hoists statements, those statements must stay
                // conditional — same treatment as `if` branches below.
                if matches!(op, BinaryOperator::And | BinaryOperator::Or) {
                    let mut rhs_stmts = Vec::new();
                    let right = self.rewrite(*right, &mut rhs_stmts, renames)?;
                    if !rhs_stmts.is_empty() {
                        return self.restructure_short_circuit(op, left, rhs_stmts, right, out_stmts);
                    }
                    return Ok(ExpressionNode::BinaryOp {
                        left: Box::new(left),
                        op,
                        right: Box::new(right),
                    });
                }

                let right = self.rewrite(*right, out_stmts, renames)?;
                Ok(ExpressionNode::BinaryOp { left: Box::new(left), op, right: Box::new(right) })
            }

            ExpressionNode::UnaryOp { op, operand } => {
                let operand = self.rewrite(*operand, out_stmts, renames)?;
                Ok(ExpressionNode::UnaryOp { op, operand: Box::new(operand) })
            }

            ExpressionNode::FunctionCall { func, args } => {
                // `if(cond, a, b)` evaluates only the taken branch. When a
                // branch's expansion hoists statements, the whole `if`
                // restructures into a conditional statement group so those
                // statements execute only when their branch is taken —
                // a body assert is a precondition, and an untaken heavy
                // body costs nothing (state still advances on both sides;
                // see OptStmt::Cond).
                if matches!(&func, FunctionRef::Builtin(BuiltinFunction::If)) && args.len() == 3 {
                    return self.rewrite_if(args, out_stmts, renames);
                }

                let fn_name = match &func {
                    FunctionRef::Named(n)
                        if n.len() >= 3 && n[..3].eq_ignore_ascii_case("fn.") =>
                    {
                        Some(n[3..].to_lowercase())
                    }
                    _ => None,
                };

                let mut new_args = Vec::with_capacity(args.len());
                for a in args {
                    new_args.push(self.rewrite(a, out_stmts, renames)?);
                }

                match fn_name {
                    Some(bare) => self.expand_call(&bare, new_args, out_stmts),
                    None => Ok(ExpressionNode::FunctionCall { func, args: new_args }),
                }
            }
        }
    }

    /// Restructure `if(cond, a, b)` when either branch's expansion hoists
    /// statements: the branches become a Cond statement group assigning a
    /// hidden result local on both sides, and the `if` expression is
    /// replaced by that local. When neither branch hoists, the `if` passes
    /// through untouched — existing expressions keep their exact shape.
    fn rewrite_if(
        &mut self,
        args: Vec<ExpressionNode>,
        out_stmts: &mut Vec<Stmt>,
        renames: &Renames,
    ) -> Result<ExpressionNode, String> {
        let mut it = args.into_iter();
        let cond = self.rewrite(it.next().unwrap(), out_stmts, renames)?;

        let mut then_stmts = Vec::new();
        let then_expr = self.rewrite(it.next().unwrap(), &mut then_stmts, renames)?;
        let mut else_stmts = Vec::new();
        let else_expr = self.rewrite(it.next().unwrap(), &mut else_stmts, renames)?;

        if then_stmts.is_empty() && else_stmts.is_empty() {
            return Ok(ExpressionNode::FunctionCall {
                func: FunctionRef::Builtin(BuiltinFunction::If),
                args: vec![cond, then_expr, else_expr],
            });
        }

        self.counter += 1;
        let res = format!("fn#{}#if", self.counter);
        then_stmts.push(Stmt::Assign { name: res.clone(), expr: then_expr });
        else_stmts.push(Stmt::Assign { name: res.clone(), expr: else_expr });
        out_stmts.push(Stmt::Cond { cond, then_stmts, else_stmts });
        Ok(ExpressionNode::Variable { name: res })
    }

    /// Restructure `left && right` / `left || right` when the right operand's
    /// expansion hoists statements. Truthiness matches the operators exactly
    /// (non-zero incl. NaN): the 1/0 coercion is an `if` node, not a `!= 0`
    /// comparison, because NotEqual is epsilon-based.
    fn restructure_short_circuit(
        &mut self,
        op: BinaryOperator,
        left: ExpressionNode,
        rhs_stmts: Vec<Stmt>,
        right: ExpressionNode,
        out_stmts: &mut Vec<Stmt>,
    ) -> Result<ExpressionNode, String> {
        let truthy_01 = |e: ExpressionNode| ExpressionNode::FunctionCall {
            func: FunctionRef::Builtin(BuiltinFunction::If),
            args: vec![e, ExpressionNode::Constant { value: 1.0 }, ExpressionNode::Constant { value: 0.0 }],
        };

        self.counter += 1;
        let res = format!(
            "fn#{}#{}",
            self.counter,
            if op == BinaryOperator::And { "and" } else { "or" }
        );

        let (then_stmts, else_stmts) = if op == BinaryOperator::And {
            // left truthy: evaluate rhs, result is rhs truthiness. Else 0.
            let mut t = rhs_stmts;
            t.push(Stmt::Assign { name: res.clone(), expr: truthy_01(right) });
            (t, vec![Stmt::Assign { name: res.clone(), expr: ExpressionNode::Constant { value: 0.0 } }])
        } else {
            // left truthy: 1. Else evaluate rhs, result is rhs truthiness.
            let mut e = rhs_stmts;
            e.push(Stmt::Assign { name: res.clone(), expr: truthy_01(right) });
            (vec![Stmt::Assign { name: res.clone(), expr: ExpressionNode::Constant { value: 1.0 } }], e)
        };

        out_stmts.push(Stmt::Cond { cond: left, then_stmts, else_stmts });
        Ok(ExpressionNode::Variable { name: res })
    }

    /// Rename a variable: body locals/params first, then `this.` rebinding.
    fn rewrite_name(&self, name: String, renames: &Renames) -> Result<String, String> {
        if let Some(hidden) = renames.lookup(&name) {
            return Ok(hidden.clone());
        }
        if name.len() >= 5 && name[..5].eq_ignore_ascii_case("this.") {
            return match self.self_context {
                Some(ctx) => Ok(format!("{}.{}", ctx, &name[5..])),
                None => Err(format!(
                    "function '{}' uses '{}' but is called from a context with no node \
                     ('this.' only has meaning inside a node's parameters)",
                    self.stack.last().map(|s| s.as_str()).unwrap_or("?"),
                    name
                )),
            };
        }
        Ok(name)
    }

    /// Expand one call: bind arguments to hidden locals (evaluate-once),
    /// splice the body's statements in with hygienic renames, and return the
    /// renamed result expression in place of the call.
    fn expand_call(
        &mut self,
        bare: &str,
        args: Vec<ExpressionNode>,
        out_stmts: &mut Vec<Stmt>,
    ) -> Result<ExpressionNode, String> {
        if self.stack.iter().any(|s| s == bare) {
            let mut chain = self.stack.clone();
            chain.push(bare.to_string());
            return Err(format!(
                "recursive function call: fn.{}",
                chain.join(" -> fn.")
            ));
        }
        let Some(def) = self.registry.get(bare) else {
            return Err(format!(
                "unknown function 'fn.{}' (no matching definition in the [fn] section)",
                bare
            ));
        };
        let def = def.clone(); // Arc bump; releases the registry borrow
        if args.len() != def.params.len() {
            return Err(format!(
                "fn.{} expects {} argument(s), got {}",
                bare, def.params.len(), args.len()
            ));
        }

        self.counter += 1;
        let id = self.counter;

        // Bind arguments once, in call order, to hidden locals.
        let mut renames = Renames::none();
        for (param, arg) in def.params.iter().zip(args) {
            let hidden = format!("fn#{}#{}", id, param);
            out_stmts.push(Stmt::Assign { name: hidden.clone(), expr: arg });
            renames.0.insert(param.clone(), hidden);
        }
        // Body locals get hidden names too.
        for stmt in &def.body.stmts {
            if let Stmt::Assign { name, .. } = stmt {
                let lower = name.to_lowercase();
                renames.0.entry(lower.clone())
                    .or_insert_with(|| format!("fn#{}#{}", id, lower));
            }
        }

        // Splice the body's statements, renamed and recursively expanded.
        // The assert prefix names the function in panic messages, so a
        // template used at fifty nodes still reads clearly.
        self.stack.push(bare.to_string());
        let prefix = format!("fn.{}", bare);
        self.rewrite_stmts_into(def.body.stmts.clone(), out_stmts, &renames, Some(&prefix))?;
        let result = self.rewrite(def.body.result.clone(), out_stmts, &renames)?;
        self.stack.pop();

        Ok(result)
    }
}
