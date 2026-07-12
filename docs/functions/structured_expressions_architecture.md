# Structured Expressions — Architecture Addendum

*Implementation-level decisions for `structured_expressions_design.md`,
locked after reading the engine as it stands (July 2026). Written for the
implementing agent/developer: where the design doc says **what**, this says
**how**, at the junctures where a plausible-looking alternative would be
slower or less idiomatic.*

Read first: `/CLAUDE.md`, `manifestos/performance.md`,
`manifestos/expression-naming.md`, `docs/functions/structured_expressions_design.md`.

---

## 1. Where state lives: one arena, inside `DataCache`, behind `&mut`

**Decision.** All new runtime mutability — locals frames, `moving_*` ring
buffers, `*_since` accumulators, per-program step guards — lives in a single
flat arena owned by `DataCache`:

```rust
pub struct ExprStateArena {
    pub f: Vec<f64>,      // locals slots, ring-buffer storage, accumulators
    pub u: Vec<usize>,    // ring heads, deque indices, last-advanced steps
    init_f: Vec<f64>,     // template: arena state at step 0
    init_u: Vec<usize>,
}
```

Regions are allocated at lowering time (each stateful instance and each
program frame gets a `(offset, len)` range baked into its lowered node), and
the whole arena resets at run start with two memcpys from the init template.
The signature change is:

```rust
// dynamic_input.rs
pub fn get_value(&self, data_cache: &mut DataCache) -> f64
// OptimizedExpressionNode
pub fn evaluate(&self, data_cache: &mut DataCache) -> f64
```

**Why this and not the alternatives.** Every production call site of
`get_value` already holds `&mut DataCache` (verified: all node
`run_order_phase`/`run_flow_phase` implementations, `check_if_exists`, and
`simple_nodewise_ordering.rs`), so call sites do not change textually —
only tests need touching. The rejected alternatives:

- *State inline in AST nodes via `Cell`* — works (models are `Send` per
  optimiser thread, never `Sync`-shared), but locals slots are written by
  one node and read by others, forcing `Rc<Cell<...>>` sharing — a pointer
  chase per local access (`performance §3.4`) and interior mutability
  where a `&mut` would have been compiler-checked.
- *State owned by each `DynamicInput`* — forces `get_value(&mut self)`,
  scatters reset logic across every owner (nodes, vars, fn call sites),
  and loses the single-memcpy reset.

**Reset semantics.** `Model` calls `data_cache.expr_state.reset()` wherever
a run begins (the same place `set_current_step(0)` happens). The init
template encodes the design doc's warm-up rules: window buffers pre-filled
with their element default, running sums = `n * default`, `*_since`
accumulators at their implicit-reset-at-run-start values.

## 2. The AST is a closed enum — keep it that way

**DONE** (branch `refactor/ast-enum-not-trait`, July 2026, prep for phase 1):
the old `Box<dyn ASTNode>` trait object and its 12 `Any`-downcast sites are
gone. `FunctionParser` returns `ExpressionNode` directly; children are
`Box<ExpressionNode>`; `FunctionCall` args are a plain `Vec<ExpressionNode>`;
`ParsedFunction::get_ast()` returns `&ExpressionNode`; `evaluate`/
`get_variables` are inherent methods. Net −95 lines, no behaviour change
(354 unit tests + 31 regression models green), cold path only.

The standing rule for all new work: **the AST is closed by design.** New
parse-side types — `Stmt`, `Program`, new `ExpressionNode` variants — are
plain enums with `Box<Self>` children, matched exhaustively. No node
traits, no `Any`, no downcasts. The module doc in `src/functions/ast.rs`
records the decision: abstract behind a trait only if a second, genuinely
distinct implementor ever exists.

## 3. Parse and lowered representations

### 3.1 Parse side

```rust
enum Stmt {
    Assign { name: String, expr: ExpressionNode },
    Assert { expr: ExpressionNode, source_text: String },
    Expr(ExpressionNode),            // legal only as final statement
}
struct Program { stmts: Vec<Stmt> } // final Stmt must be Stmt::Expr — enforced
                                    // at parse with the "no result value" error
```

Tokenizer grows `{`, `}`, `;`. A value whose first non-space char is `{` is
parsed as a block; anything else takes the existing expression path
untouched (zero change to the existing fast paths for plain expressions —
constants, direct references, and linear-combination detection are reached
exactly as today).

### 3.2 Lowered side

```rust
struct OptimizedProgram {
    stmts: Vec<OptStmt>,                 // flat, in order — not a tree
    result: OptimizedExpressionNode,
    frame: ArenaRange,                   // this program's locals slots
    step_guard: usize,                   // arena.u index (see §5)
}
enum OptStmt {
    Assign { slot: usize, expr: OptimizedExpressionNode },
    Assert { expr: OptimizedExpressionNode, meta: u32 },
}
```

New `OptimizedExpressionNode` variants:

- `Local { slot: usize }` — one indexed arena read, same cost class as
  `ConstantReference`.
- `MovingWindow { op, range: ArenaRange, arg: Box<...> }` — op ∈
  {Sum, Mean, Min, Max}.
- `Since { op, range: ArenaRange, arg: Box<...>, reset: Box<...> }` — op ∈
  {Sum, Min, Max, Count, Steps} (`Steps` has no `arg`).
- `SimFlag { field }` — NewDay / NewMonth / NewYear.

Locals are resolved to slots **at lowering**, names discarded — the same
resolve-once move as `data_variable_map`. Name resolution order inside a
program: locals (declared above the use, this program only) → existing
namespace rules. Shadowing a builtin function name is a load error with a
did-you-mean diagnostic.

**Assert metadata is cold.** `meta: u32` indexes a side table (source text,
owning node/var context) stored outside the hot structures. The failure path
is a `#[cold] #[inline(never)]` panic exactly in the style of
`DataCache::unwritten_value_panic`, and the message includes the timestep
via `u64_to_iso_datetime_string(current_timestamp)`. The hot path is one
comparison: fail when `v == 0.0 || v.is_nan()` — note `!(v != 0.0)` is NOT
equivalent (it passes NaN); write the two-condition check explicitly and
test it.

## 4. Stateful builtins: layout and algorithms

- **Ring buffers** (`moving_sum`/`mean`): `n` f64s + running sum in the f64
  arena, head index in the usize arena. Advance = subtract evicted, add
  incoming, bump head with a compare-and-zero wrap (`if h == n { 0 }`), NOT
  `%` (integer division on the hot path). No power-of-two rounding — memory
  is the modeller's `n`, the wrap branch predicts perfectly.
- **Monotonic deques** (`moving_min`/`max`): capacity-`n` (value, step)
  pairs, amortised O(1) push/evict. Values in the f64 arena, steps and
  head/tail in the usize arena.
- **`*_since`**: two or three scalars each (accumulator, plus count for
  mean-like forms if ever added). Reset-then-accumulate per design §6.
- **NaN discipline**: NaN entering a window propagates for exactly `n`
  steps then leaves (the subtract-on-evict must not poison the running sum
  forever — on evicting a NaN, recompute the running sum from the buffer;
  this is a cold event, gate it with `evicted.is_nan()`). Add a regression
  test for a NaN transient.

## 5. When state advances: first evaluation per step, guarded

State advances **inside the owning program's evaluation**, not in a global
model pass — a window sampling `node.headwater.ds_1` must read the value
computed *this* timestep at the expression's scheduled position, which a
global end-of-step pass cannot see correctly.

Advance must happen exactly once per step even though `get_value` may be
called more than once (order phase + flow phase both read some inputs —
e.g. `storage_node` reads `target_level` in `run_order_phase` and flow
logic can read it again). The guard is the existing idiom:

```rust
if arena.u[self.step_guard] != data_cache.current_step {
    arena.u[self.step_guard] = data_cache.current_step;
    self.advance_state(data_cache);      // walks ALL stateful nodes in this
                                         // program, taken branches or not
}
```

`advance_state` is a full-tree walk in the exact shape of the existing
`validate_reads` (which stays, unchanged, as the step-0 read validator).
One predicted compare per evaluation thereafter — the same cost class as
the `current_step == 0` check already on this path. Statement expressions
and untaken `if` branches advance identically (design §5): the walk visits
every `MovingWindow`/`Since` node unconditionally.

## 6. `[fn]`: inline at parse level, before lowering

- Parse each body once into a `Program`/`ExpressionNode`, with `this.`
  **kept as an unresolved marker** — do NOT run the textual `expand_this`
  on fn bodies at parse time. Expansion happens per call site.
- Build the call graph; DAG-check with the cycle named (load error).
- Inline in reverse topological order at **AST level**: each call site
  clones the body, substitutes `this.` markers with the caller's node
  context, and rewrites the body's locals and parameters into fresh slots
  in the *caller's* frame. Arguments lower to hidden `Assign` statements
  prepended at the call point — evaluate-once semantics (design §8.2) for
  free, and per-call-site stateful instances fall out because each inlined
  copy allocates its own arena ranges.
- After inlining, nothing downstream (lowering, evaluation, optimiser
  integration) knows functions exist. `transform_to_optimised_ast` sees
  plain expressions.
- Signature keys (`net_demand(pop, doy)`) are parsed with the same
  tokenizer; duplicate names across the section are fatal at load.

## 7. `[var.*]`: interleaved execution items

Replace the flow loop's `execution_order: Vec<usize>` with:

```rust
enum ExecItem { Node(usize), VarBlock(usize) }
execution_order: Vec<ExecItem>
```

`Model::run_timestep` matches on the item; `check_execution_order` extends
naturally (link validation unchanged — it operates on node indices; the
"defined before use" guarantee for vars needs no new machinery because the
existing fail-fast read contract in `get_current_value` + the step-0
`validate_reads` walk already catch premature reads with a good message).

A `VarBlock` is a `Vec` of `(series_idx, DynamicInput)` evaluated top to
bottom; each result is written with the existing
`data_cache.add_value_at_index(series_idx, value)`, which makes vars
recordable, offset-addressable, and once-per-step by construction. Vars
with `phase = order` are evaluated at their file position within the
ordering pass — read `simple_nodewise_ordering.rs` before wiring this;
the flow-phase path is the template.

Var series are registered at load as non-critical (like `node.*` series).

## 8. `sim.new_*` flags

Computed in `DataCache::update_current_timestamp` (which already decomposes
the date once per step): compare new y/m/d against the previous step's,
store three `bool`s, all true at step 0. Expression side is a `SimFlag`
lowered variant reading the bool — same cost class as `SimContext`. Do not
compute flags per-expression; they are per-step facts.

## 9. What must not regress

- **Plain expressions keep their exact current lowered forms.** A value
  with no block, no stateful call, and no fn reference must produce
  byte-identical variants to today (`DirectReference`, `Constant`,
  `LinearCombination`, ...). Guard this with tests on
  `DynamicInput::from_string` variant selection.
- **Round-trip serialisation**: `original_string()` returns the exact text
  including braces; blocks are never reformatted on save.
- **Benchmarks** (`performance §4`): run `benchmarks/` before phase 1 and
  after every phase. Add one benchmark: a model with a block +
  `moving_mean` + two `*_since` per node across ~100 nodes, so the arena
  and guard costs are visible, not asserted.
- **IDE lockstep** (`expression-naming` enforcement): `KNOWN_FUNCTIONS`
  mirrors the engine's new builtins in the same PR wave; the drift test
  pins rejected spellings (`running_mean`, `days_since`, `avg`, `log`).

## 10. Phase mapping (design §14) with the decisions above

1. **Grammar core** — §3 parse+lowered shapes on the clean enum baseline
   (§2, already landed), locals via arena (§1 signature change lands
   here), `assert` (§3.2), `clamp`. No stateful anything.
2. **Stateful builtins** — §4 layouts, §5 guard+advance walk, §8 flags,
   arena init templates and reset.
3. **`[fn]`** — §6 in full.
4. **`[var.*]`** — §7 in full.
5. **IDE wave** — §9 last bullet, plus highlighting for `{}`/`;`/locals.

Each phase: full `cargo test`, regression suite, benchmark run, and the
variant-selection guard tests from §9.
