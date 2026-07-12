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
/// ## Hoisting semantics (deliberate)
///
/// A call's argument bindings and body statements are hoisted to statement
/// position in the enclosing program. A call inside an untaken `if` branch
/// therefore still executes its body statements each step. For pure
/// arithmetic this is unobservable; for the two effectful things it is the
/// consistent choice by design: stateful builtins advance unconditionally
/// anyway (design §5), and an `assert` inside a function body checks its
/// invariant every step regardless of which branch the caller takes — the
/// file says the invariant holds, not "holds when the branch is taken".

use crate::functions::ast::{ExpressionNode, FunctionRef, Program, Stmt};
use crate::functions::fn_registry::FnRegistry;

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
    program.stmts.iter().any(|s| match s {
        Stmt::Assign { expr, .. } | Stmt::Assert { expr, .. } => expr_references_fns(expr),
    }) || expr_references_fns(&program.result)
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
    for stmt in program.stmts {
        match stmt {
            Stmt::Assign { name, expr } => {
                let expr = inliner.rewrite(expr, &mut out_stmts, &Renames::none())?;
                out_stmts.push(Stmt::Assign { name, expr });
            }
            Stmt::Assert { expr, source_text } => {
                let expr = inliner.rewrite(expr, &mut out_stmts, &Renames::none())?;
                out_stmts.push(Stmt::Assert { expr, source_text });
            }
        }
    }
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
                let right = self.rewrite(*right, out_stmts, renames)?;
                Ok(ExpressionNode::BinaryOp { left: Box::new(left), op, right: Box::new(right) })
            }

            ExpressionNode::UnaryOp { op, operand } => {
                let operand = self.rewrite(*operand, out_stmts, renames)?;
                Ok(ExpressionNode::UnaryOp { op, operand: Box::new(operand) })
            }

            ExpressionNode::FunctionCall { func, args } => {
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
        self.stack.push(bare.to_string());
        for stmt in def.body.stmts.clone() {
            match stmt {
                Stmt::Assign { name, expr } => {
                    let expr = self.rewrite(expr, out_stmts, &renames)?;
                    let hidden = renames.lookup(&name).cloned().expect("local was pre-renamed");
                    out_stmts.push(Stmt::Assign { name: hidden, expr });
                }
                Stmt::Assert { expr, source_text } => {
                    let expr = self.rewrite(expr, out_stmts, &renames)?;
                    out_stmts.push(Stmt::Assert {
                        expr,
                        // The panic message names the function so a template
                        // used at fifty nodes still reads clearly.
                        source_text: format!("fn.{}: {}", bare, source_text),
                    });
                }
            }
        }
        let result = self.rewrite(def.body.result.clone(), out_stmts, &renames)?;
        self.stack.pop();

        Ok(result)
    }
}
