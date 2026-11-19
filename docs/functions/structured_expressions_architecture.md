# Structured Expressions — Architecture Addendum

*Implementation-level decisions for `structured_expressions_design.md`,
locked after reading the engine as it stands (July 2026). Written for the
implementing agent/developer: where the design doc says **what**, this says
**how**, at the junctures where a plausible-looking alternative would be
slower or less idiomatic.*

Read first: `/CLAUDE.md`, `ADR-0004`,
`ADR-0006`, `docs/functions/structured_expressions_design.md`.

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
```

**Refinement discovered in phase 1** (implemented this way): only the
*statement executor* (`OptimizedProgram::evaluate`) needs `&mut DataCache` —
locals are written between expression evaluations, never during them, so
`OptimizedExpressionNode::evaluate` keeps its immutable `&DataCache`
signature and the entire existing expression hot path is untouched. Phase 2
should follow the same shape: the unconditional state advance (§5) is a
separate `&mut` pass before immutable evaluation, not `&mut` threaded
through `evaluate` itself.

**Why this and not the alternatives.** Every production call site of
`get_value` already holds `&mut DataCache` (verified: all node
`run_order_phase`/`run_flow_phase` implementations, `check_if_exists`, and
`simple_nodewise_ordering.rs`), so call sites do not change textually —
only tests need touching. The rejected alternatives:

- *State inline in AST nodes via `Cell`* — works (models are `Send` per
  optimiser thread, never `Sync`-shared), but locals slots are written by
  one node and read by others, forcing `Rc<Cell<...>>` sharing — a pointer
  chase per local access (`ADR-0004 §3.4`) and interior mutability
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
}
struct Program {
    stmts: Vec<Stmt>,
    result: ExpressionNode,   // the final bare expression, as its own field
}
```

(Phase 1 implemented `result` as a separate typed field rather than a
`Stmt::Expr` variant policed at parse: a Program without a result value is
unrepresentable, so the "no result value" rule is enforced by the type
system and the parser merely reports it well.)

Tokenizer grows `{`, `}`, `;`. A value whose first non-space char is `{` is
parsed as a block; anything else takes the existing expression path
untouched (zero change to the existing fast paths for plain expressions —
constants, direct references, and linear-combination detection are reached
exactly as today).

### 3.2 Lowered side

```rust
struct OptimizedProgram {
    stmts: Box<[OptStmt]>,               // flat, in order — not a tree
    result: OptimizedExpressionNode,
    result_stateful: Box<[StatefulNode]>, // stateful calls in `result` (§5)
    assert_meta: Box<[String]>,
    advance_guard: Option<usize>,        // arena.u index (see §5)
}
enum OptStmt {
    Assign { slot: usize, expr: OptimizedExpressionNode, stateful: Box<[StatefulNode]> },
    Assert { expr: OptimizedExpressionNode, meta: u32, stateful: Box<[StatefulNode]> },
    Cond { cond, cond_stateful, then_stmts, else_stmts, then_has_state, else_has_state },
}
```

(`Cond` is emitted only by the `[fn]` inliner, §6. The lists are boxed
slices so `DynamicInput` stays at 128 bytes.)

New `OptimizedExpressionNode` variants:

- `Local { slot: usize }` — one indexed arena read, same cost class as
  `ConstantReference`.
- `MovingWindow { op, slots }` — the read side of every `moving_*` call;
  op ∈ {Sum, Mean, Min, Max} and the calendar ops of §4.
- `StateSlot { f_off }` — the read side of `*_since` and `latch`. Reading
  it is one load.

A stateful call's sampled inputs are not in the tree. Lowering moves them
into a `StatefulNode` (`Window`, `Since` or `Latch`) on the owner's list,
which §5 advances; the tree keeps only the read node.

The `sim.new_*` flags are `SimContext` fields (§8).

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
- **Calendar windows** (`moving_{sum,min,max}_{years,months,days}`,
  `WindowOp::{Sum,Min,Max}{Years,Months,Days}` in `dynamic_input.rs`):
  the same statistics over whole water years, calendar months or days
  ("buckets") instead of steps, advanced by one of two functions depending
  on whether the current step is a period boundary.
  - **Sum** keeps a ring of bucket totals plus a running total
    (`f[f_off..f_off+n]` + `f[f_off+n]`, head at `u[u_off]`). A
    non-boundary step (`accumulate_ring`) adds into the current bucket and
    the total; a boundary step (`advance_ring`, shared with the plain ring)
    evicts the oldest bucket, starts a new one at the incoming value and
    corrects the total (`sum += x - evicted`).
  - **Min/max** keep the plain windows' monotonic deque over *closed*
    buckets, keyed on bucket index, plus the extremum of the bucket in
    progress (layout documented on `publish_bucket_extreme`). A
    non-boundary step (`accumulate_bucket_extreme`) folds into the bucket
    in progress with `f64::min`/`f64::max` (NaN-suppressing); a boundary
    (`advance_bucket_extreme`) closes it into the deque. Both publish the
    combined result to one slot that the read node loads. Eviction is
    amortised O(1), which matters because at a daily timestep every step
    is a `moving_*_days` boundary.
  - **Correctness trap already hit once**: `advance_ring` must evict and
    write against the **post-increment** head (`new_head`), not the
    pre-increment one — otherwise the slot `advance_ring` just initialised
    and the slot `accumulate_ring` subsequently writes into for the rest of
    the period are different ring slots, silently stranding one step's
    contribution forever (a mass leak, invisible at `n == 1` since the
    wrap-around coincidentally lands on the same slot; caught at `n >= 2`).
  - **Boundary predicate is the only thing that varies** across the three
    families: annual is `is_new_month() && get_timestamp_month() ==
    wy_month`; monthly is `is_new_month()` alone (no anchor); daily is
    `is_new_day()` alone. At a daily timestep `is_new_day()` is true every
    step, so `moving_*_days` degenerates to exactly the plain ring/deque
    above with no element default — this is deliberately used as a
    regression test (`test_moving_sum_days_equals_plain_moving_sum_at_daily_timestep`
    in `test_stateful_functions.rs`), cross-checking the new machinery
    against the already-trusted one on identical input.
  - `StatefulNode::uses_calendar_flags` must report `true` for every
    calendar op directly (not by walking into `arg`), since these read
    `is_new_month()`/`is_new_day()`/`get_timestamp_month()` in
    `StatefulNode::advance` without going through a `SimContext` node — otherwise
    `DataCache.needs_calendar_flags` never gets set and the boundary flags
    silently stay false past step 0.

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
if arena.u[guard] != data_cache.current_step + 1 {
    arena.u[guard] = data_cache.current_step + 1;
    advance_all(stateful, data_cache);   // every stateful call in this
                                         // input, taken branches or not
}
```

What is advanced is a flat list, not the tree. Lowering splits each
stateful call in two: a read node that stays in the tree
(`MovingWindow`, or `StateSlot` for `*_since`/`latch`) and a
`StatefulNode` holding the sampled inputs, pushed onto the owner's list.
A call's arguments are lowered before the call is pushed, so the list is
in post-order. Nested calls advance before the calls that sample them.
Every call is pushed, so untaken `if` branches advance like any other
(design §5), and the stateless tree around a call costs nothing at
advance time. A program keeps one list per statement, advanced just
before that statement evaluates. After the first evaluation in a step,
the guard costs one predicted compare, the same cost class as the
`current_step == 0` check already on this path.

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
- **Conditional execution (owner decision, July 2026):** a call inside an
  `if` branch or short-circuit operand expands into a `Stmt::Cond`
  statement group — taken side executes, pure untaken side is skipped
  (calls keep expression short-circuiting economics), stateful untaken
  side runs silently (assignments keep window inputs live per design §5,
  asserts suppressed). A body assert is therefore a precondition, firing
  only when its call actually runs.
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
recordable, offset-addressable, and once-per-step by construction.

**Scheduling direction (owner decision, July 2026):** nodes and var blocks
execute in file-definition order, and the two per-timestep passes traverse
that order in opposite directions — the **flow phase walks top to bottom**
(file order, as the flow loop already does) and the **order phase walks
bottom to top** (reverse file order, matching how ordering propagates
upstream). `phase = flow` vars therefore slot into the existing flow loop
at their `ExecItem` position. `phase = order` vars (#493) are items in
the ordering system's own bottom-up visit list: at initialisation, the
ordering system merges each block into that list by its file position
(`set_order_var_blocks`; it alone knows which nodes it visits), the order
phase's counterpart of `flow_exec_items`, and each step it walks the merged
list. `has_order_var_blocks` picks the entry point once per step, as
`flow_has_var_blocks` does, so a model without order vars runs the plain
ordering phase: the same visits, over the plain list, with no block items.

Var series are registered at load as non-critical (like `node.*` series).

## 8. `sim.new_*` flags

Computed in `DataCache::update_calendar_flags`, once per step, from the date
the cache holds: compare new y/m/d against the previous step's,
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
- **Benchmarks** (`ADR-0004 §4`): run `benchmarks/` before phase 1 and
  after every phase. Add one benchmark: a model with a block +
  `moving_mean` + two `*_since` per node across ~100 nodes, so the arena
  and guard costs are visible, not asserted.
- **IDE lockstep** (`ADR-0006` enforcement): `KNOWN_FUNCTIONS`
  mirrors the engine's new builtins in the same PR wave; the drift test
  pins rejected spellings (`running_mean`, `days_since`, `avg`, `log`).

## 10. Phase mapping (design §14) with the decisions above

1. **Grammar core** — §3 parse+lowered shapes on the clean enum baseline
   (§2, already landed), locals via arena (§1 signature change lands
   here), `assert` (§3.2), `clamp`. No stateful anything.
2. **Stateful builtins** — §4 layouts, §5 guard + advance list, §8 flags,
   arena init templates and reset.
3. **`[fn]`** — §6 in full.
4. **`[var.*]`** — §7 in full.
5. **IDE wave** — §9 last bullet, plus highlighting for `{}`/`;`/locals.

Each phase: full `cargo test`, regression suite, benchmark run, and the
variant-selection guard tests from §9.
