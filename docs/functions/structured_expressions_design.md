# Structured Expressions — Design

*Extending the DynamicExpression language into a small structured language:
blocks, local variables, assert, temporal (windowed) functions, user-defined
functions, and model-level variables.*

**Status: design agreed 2026-07. Phases 1–4 implemented July 2026: grammar
core (`;`, blocks, locals, `assert`, `clamp`), stateful builtins
(`moving_*`, `*_since`, `sim.new_*`), `[fn]` user-defined functions
(inline expansion, hygienic, `this.` rebinding, DAG-checked, conditional
execution in branches), and `[var.*]` blocks (flow phase at file position;
`phase = order` rejected pending its ordering-system interleave). Phase 5
(IDE lockstep — linter grammar/functions/sections, autocomplete) July 2026.** This document records the decisions and their
reasons. A manifesto will be harvested after implementation proves the
calls out (per `on-manifestos §4`).

---

## 1. Purpose and scope

Today an expression is a single pure formula evaluated per timestep. This
design adds, for v1:

- **Blocks** — multi-statement programs as values, with local variables
- **`assert()`** — a runtime check that kills the run, loudly
- **Windowed functions** — `moving_*` over the last n steps
- **Event-windowed functions** — `*_since` a reset condition last fired
- **Calendar boundary flags** — `sim.new_month`, `sim.new_year`, `sim.new_day`
- **User-defined functions** — a `[fn]` section, called as `fn.name(...)`
- **Model variables** — `[var.*]` blocks: published, scheduled, recordable

Deliberately **excluded** from v1 (each with its reason, §12): loops,
arrays, strings, argument defaults, overloads, recursion, windowed
percentiles, fn-internal observability.

## 2. The governing principle: bounded cost, known at load

Everything rests on the engine's existing contract: *parse once, lower once,
evaluate every timestep, infallible and allocation-free on the hot path*
(`performance §3`). The structured language must preserve it, so:

> **Every program's execution cost is bounded and known at model load.**

Concretely: no loops, no recursion, window lengths are literals, locals
compile to slots in a pre-sized frame, window state lives in a pre-sized
arena. Nothing a modeller can type is slow, and nothing can fail at step
10⁶ that could not be rejected at load.

This is the same position NumPy, SQL, and vectorised MATLAB/R converged on:
a rich vocabulary of fast primitives and no user loop. The cautionary tale
is RiverWare's RPL — an interpreted rules language whose user loops made the
*platform* wear the slowness its *rules* earned. Kalix refuses the loop and
supplies the primitives instead — and the primitives are not merely as fast
as a well-written loop, they are faster than any loop could be, because the
engine owns state across timesteps (§7): a 30-step moving sum is one add and
one subtract per step, not thirty reads.

## 3. Statements, blocks, and locals

### 3.1 The `;` terminator

`;` terminates a statement. It was reclaimed from INI comment duty in
July 2026 (`#` is the only comment character everywhere). Newlines are
whitespace; long statements flow across INI continuation lines (leading
space), which the file format already supports.

### 3.2 Blocks

A **block** is `{ statement; statement; ... ; final-expression }`:

```ini
pond_demand = {
    target = table.monthly_demand(sim.month);
    recent = moving_mean(node.headwater.ds_1, 30, 0.0);
    assert(target >= 0);
    min(target, recent * c.demand_fraction)
    }
```

Rules:

1. **A block is legal only as the entire right-hand side of a value.** No
   blocks nested inside expressions. A value is either a plain expression or
   one block — decided by its first non-space character.
2. **The block's value is its final statement, which must be a bare
   expression with no `;`.** A block whose last statement is terminated (or
   is an assignment) is a **load error** ("program has no result value").
   The Rust-style convention is safe here precisely because it can only fail
   loudly at load, never silently at runtime.

### 3.3 Locals

- Assignment is plain `x = expr;` — no `let` keyword (modellers write maths,
  not Rust). Re-assignment is allowed (needed for staged calculations).
- Locals are **bare identifiers**. This is available because bare names have
  no other meaning as values: everything real is namespaced (`data.*`,
  `node.*`, `c.*`, `sim.*`, `table.*`, `var.*`, `fn.*`, `this.`), per
  `expression-naming §1.3`.
- A local may **not shadow a builtin function name** — load error, with the
  usual did-you-mean diagnostic (`expression-naming §2.4`).
- Locals are private scratch: invisible outside their block, no series, no
  cost beyond a slot in a pre-sized frame (§11).
- Repeated subexpressions should be named as locals; a named local evaluates
  once (`performance §6` — never leave speed on the table).

## 4. `assert(cond)`

- **Fails the run when `cond` is 0 — or NaN.** The NaN case is the one the
  modeller most needs caught, and it is exactly the case a naive `!= 0.0`
  truthiness test would wave through.
- **Message is engine-composed**: the expression text, the owning
  node/var/fn context, and the timestep. There is no string type in the
  language, and assert does not take a message argument.
- An assert is an ordinary statement inside a block (it is the reason blocks
  need statements at all). Cost is one predictable branch per evaluation,
  which the modeller opted into; the failure semantics are required by
  `performance §6.2` — a fast silence where a signal was needed is the
  expensive option.

## 5. Windowed functions: `moving_*`

| Function | Meaning over the last n steps |
|---|---|
| `moving_sum(x, n, default)` | sum |
| `moving_mean(x, n, default)` | arithmetic mean |
| `moving_min(x, n, default)` | minimum |
| `moving_max(x, n, default)` | maximum |

- **One common signature** `(x, n, default)`. All three arguments required.
- `x` is any expression; `n` is a positive integer literal (bounded cost,
  §2); `default` is the **element default**: at initialisation the window is
  pre-filled with it, so the statistic is well-defined from step 0 and warms
  up from a known state. (The modeller states what history to assume — the
  same both-or-neither explicitness as the `[offset, default]` syntax.)
- **State advances every timestep, unconditionally** — including when the
  call sits in an untaken `if` branch. `moving_mean(data.flow, 30, 0)`
  *reads* as a property of the series, so its value must never depend on
  which branches past evaluations happened to take. What you read is what
  runs (`node-definition-order §1`, applied to time instead of space).
- Implementation is incremental and O(1) per step: ring buffer plus running
  sum for `sum`/`mean`; monotonic deque for `min`/`max` (amortised O(1)).
  Per-instance state is fixed-size, allocated at load (§11).

## 6. Event-windowed functions: `*_since`

| Function | Meaning since the reset condition last fired |
|---|---|
| `sum_since(x, reset)` | sum of x |
| `min_since(x, reset)` | minimum of x |
| `max_since(x, reset)` | maximum of x |
| `count_since(cond, reset)` | number of steps on which cond held |
| `steps_since(reset)` | number of steps elapsed |

- **The last argument of every `*_since` function is the reset condition** —
  any expression, truthy when non-zero. The first argument is the tracked
  quantity (a value for sum/min/max, a condition for count). `steps_since`
  is the degenerate family member: its tracked quantity is the step counter
  itself, so only the reset remains.
- **Reset-then-accumulate.** When the reset fires at step t, the accumulator
  clears first and step t's contribution is then included. Worked example:
  on 1 July, "usage this water year" equals that day's usage, not zero — a
  zero would mean the year's first day of pumping is counted nowhere.
  Consequences, all deliberate: `steps_since` reads 0 on the step its event
  occurs; `min_since`/`max_since` equal the current value on a reset step;
  the window is never empty, so no function needs an empty-window sentinel.
- **Run start is an implicit reset event.** At step 0 every `*_since` is
  well-defined by the same rule. This implicitly assumes the condition
  occurred just before the run began; the modeller owns that assumption.
- State advances unconditionally, as in §5. Cost is O(1) per step.

## 7. Calendar boundaries — and why there is no water year

New `sim.*` flags, each a pure calendar fact computed once per step:

- `sim.new_month` — true when this step's month differs from the previous
  step's (and at step 0)
- `sim.new_year`, `sim.new_day` — same rule on year and day

These are timestep-agnostic. The naive spelling
`sim.month == 7 && sim.day == 1` is a trap at sub-daily timesteps (true for
every hourly step of 1 July, resetting an account all day long); the flags
exist so the correct thing is also the easy thing.

**The engine has no water-year concept.** Water year start varies valley to
valley and is deliberately not a global Kalix setting. The boundary is an
idiom, written at the point of use:

```ini
used_wy = sum_since(node.town.diversion, sim.new_month && sim.month == 7)
```

and a model wanting a single point of truth defines it itself — as a
constant (`c.wy_month = 7`) or, most readably, a zero-argument function:

```ini
[fn]
new_wy() = sim.new_month && sim.month == 7
```

The would-be config key becomes one visible line of the model. Build the
dials, not the named systems.

## 8. User-defined functions: `[fn]`

### 8.1 Declaration

One section, `[fn]` (section names are unique; the pattern `[fn.scheme_name]`
is **reserved** for future namespaced function groups). Each key is the
signature, each value the body:

```ini
[fn]
storage_frac(v, cap) = v / cap
net_demand(pop, doy) = {
    base = pop * c.per_capita;
    peak = 1 + 0.3 * sin(2 * 3.14159 * doy / 365);
    base * peak
    }
new_wy() = sim.new_month && sim.month == 7
```

- The signature is the key, so **the name appears exactly once** — no
  name-twice mismatch class of errors, and the definition reads in the shape
  it is called.
- **Fixed signatures**: no default arguments, no overloads, ever. `args` is
  an ordered list; calls bind positionally. Zero-argument functions are
  legal (`new_wy()` above is the motivating idiom).
- **Duplicate names are a load error**, even at different arities.
- Calls are namespaced — `fn.net_demand(...)` — never bare
  (`expression-naming §2.5`, same forward-compatibility argument as
  `table.*`).
- **Definitions may live anywhere in the file**, including after use.
  Functions are *passive* — they have no execution time of their own — and
  follow the table precedent. (Contrast vars, §9.)
- Bodies may reference `data.*`, `node.*`, `c.*`, `sim.*`, `table.*`,
  `var.*`, other `fn.*`, and `this.`.

### 8.2 Semantics: function semantics, macro implementation

- **Hygiene**: argument names and body locals live in their own scope; they
  can never collide with names at the call site.
- **Arguments evaluate exactly once**, bound to hidden local slots. An
  argument used three times in the body does not evaluate three times. This
  is semantically load-bearing: a stateful `moving_mean(...)` passed as an
  argument must not multi-advance its state.
- **`this.` is the one deliberate exception** — it is late-bound to the
  *calling node*, which is what turns a function from shared arithmetic into
  a reusable rule template applied at many nodes.
- **No recursion.** The `fn.` call graph must be a DAG, checked at load,
  with any cycle named in the error. This is what makes the implementation
  possible:
- **Implementation is inline expansion at lowering time.** Every call site
  gets the body inlined (arguments pre-bound to slots), so after lowering a
  model using a function at fifty nodes is indistinguishable from fifty
  pasted copies — zero call overhead, per `performance §3.5`. Stateful
  builtins inside a body therefore get **per-call-site state** (three nodes
  using `fn.baseflow` containing a `moving_mean` = three independent ring
  buffers), which is the only semantics a modeller would expect.

## 9. Model variables: `[var.*]`

### 9.1 Shape

```ini
[var.accounting]
phase = flow                 # 'order' | 'flow' (default)
used_wy = sum_since(node.township.diversion, fn.new_wy())
headroom = {
    cap = c.annual_cap;
    assert(cap > 0);
    cap - var.accounting.used_wy
    }
```

- Section name is the namespace; keys are bare; references elsewhere are
  `var.accounting.used_wy` — exactly parallel to `node.x.output`.
- Keys evaluate **top to bottom** within the block; later keys may read
  earlier ones.
- Each var is backed by a **data-cache series**, so it is: computed exactly
  once per timestep (readers can never observe two values in one step);
  readable anywhere as a zero-overhead `DirectReference`; recordable in
  `[outputs]` with no extra machinery; and reachable through the offset
  syntax (`var.accounting.used_wy[-1, 0.0]`).
- This retires the dummy-gauge / `reference_flow` scratch-node workaround.

### 9.2 Scheduling: position, not properties

**Passive things live anywhere; active things have a position.** Tables and
functions are looked up — they may sit at the end of the file, out of the
way. A var *executes*: it reads node outputs computed earlier in the same
timestep, so its position relative to the nodes is part of its meaning.

Therefore `[var.*]` blocks interleave with `[node.*]` sections and execute
in **file position within their phase** (`node-definition-order §1` extended
to calculations). `phase` selects which of the engine's two per-timestep
passes evaluates the block: `order` or `flow` (default `flow`).

Reading a value that does not exist yet (e.g. a downstream node's
this-timestep output) is caught by the existing step-0 validation walk —
first step, every run, deterministically. No additional phase rules needed.

## 10. Naming decisions (per `expression-naming`)

- `fn.` — the call prefix and section name (terse; typed constantly).
- `moving_*`, not `running_*` — "running" conventionally means
  cumulative-since-start, which is `sum_since`'s job; ours are fixed-window.
- `steps_since`, not `days_since` — timestep-agnostic, matches `sim.step`.
- `phase` with noun values `order` / `flow`.
- `clamp(x, lo, hi)` joins the pure builtins (the clearer spelling of
  `min(max(x, lo), hi)`).
- One spelling each, no aliases, lowercase; rejected spellings get
  did-you-mean diagnostics (`expression-naming §2.1, §2.4`).

## 11. Implementation sketch

- **Tokenizer/parser**: add `{`, `}`; `;` as statement terminator; statement
  sequences; assignment statements; `assert`. Grammar note: `[` after a
  *dotted* identifier is the offset syntax; `[` after a *bare* identifier is
  reserved (array indexing, deferred §12) — a parse error for now.
- **Locals** lower to integer slots in a per-input, pre-sized value frame —
  the same resolve-names-to-indices-at-load move the data cache already
  uses. No hash lookups, no allocation per step (`performance §3`).
- **Window/`*_since` state** lives in a pre-sized state arena, one region
  per stateful call instance (post-inlining), allocated at load and reset at
  run start. The hot-path signature grows a `&mut` state argument (or the
  arena rides in the DataCache alongside `current_step`).
- **Unconditional state advance** (§5, §6): a per-step update walk over all
  stateful instances, independent of which branches evaluation takes —
  structurally similar to the existing `validate_reads` full-tree walk.
- **`[fn]`**: parse signatures (same tokenizer), build call-graph, DAG
  check, inline at lowering with args bound to hidden slots; `this.`
  expansion happens per call site, before lowering.
- **`[var.*]`**: each key becomes a scheduled evaluation writing to its
  data-cache series; blocks slot into the model's execution list at their
  file position, tagged with their phase.
- **IDE (lockstep, per `expression-naming` enforcement)**: highlighting for
  `{}`/`;`/locals, linter awareness of `[fn]` signatures and `var.*`
  references, go-to-definition keyed on parsed names, `KNOWN_FUNCTIONS`
  extended to mirror the engine exactly.
- Benchmarks re-run before/after: the lowering touches
  `OptimizedExpressionNode` (`performance §4`).

## 12. Exclusions from v1 — each with its reason

| Excluded | Reason |
|---|---|
| Loops (`for`, `while`) | The platform wears the slowness a bad loop earns (RiverWare's RPL). The `moving_*`/`*_since` builtins are faster than any user loop could be (§2). Revisit only if real models surface needs the vocabulary cannot cover. |
| Arrays | Strongest use case (monthly values by `sim.month`) is `table.*` territory; loops are gone; windows are builtins. Grammar slot (`bare_name[i]`) reserved. |
| Strings | Only conceivable use was assert messages; assert composes its own (§4). |
| Argument defaults / overloads | Fixed signatures forever; defaults are a one-way door constraining every future signature change. |
| Recursion | Breaks bounded cost and inline lowering; DAG-checked at load (§8.2). |
| Windowed percentile | Breaks the O(1)-per-step pattern; wait for a real user request to force the design. |
| `decay_sum` (exponential decay accumulator) | Cut from v1 inventory by owner decision, 2026-07. |
| fn-internal observability (recording inlined locals, e.g. `node.x.fn.f.base`) | Deferred; the lowering must not *preclude* it, but no v1 surface. |

## 13. Worked example

```ini
# '#' is the only comment character; ';' terminates statements inside blocks.

[kalix]
start = 1990-01-01
end = 2020-12-31

[constants]
c.demand_fraction = 0.85
c.low_flow_threshold = 10.0
c.annual_cap = 15000.0
c.per_capita = 0.00042

[inputs]
./data.csv

[table.monthly_demand]      # tables: passive, live anywhere
n_cols = 2
values = 1,  8.0,
         2,  7.9,
         3,  6.5,
         4,  5.1,
         5,  4.0,
         6,  3.6,
         7,  3.8,
         8,  4.4,
         9,  5.3,
         10, 6.2,
         11, 7.1,
         12, 7.8,

[node.headwater]            # file reads downstream; order = execution
type = inflow
loc = 0, 0
inflow = data.data_csv.gauge_410001
ds_1 = dam

[node.dam]
type = storage
loc = 0, 100
initial_volume = 6705
pond_demand = {
    target = table.monthly_demand(sim.month);
    recent = moving_mean(node.headwater.ds_1, 30, 0.0);
    assert(target >= 0);
    min(target, recent * c.demand_fraction)
    }
ds_1 = township

[node.township]
type = regulated_user
loc = 0, 200
order = fn.net_demand(data.data_csv.town_pop, sim.day_of_year)
ds_1 = outlet

[var.accounting]            # active: executes at this file position
phase = flow
used_wy = sum_since(node.township.diversion, fn.new_wy())
spill_days = count_since(node.dam.ds_1_spill > 0, fn.new_wy())
dry_spell = steps_since(node.headwater.ds_1 > c.low_flow_threshold)
headroom = {
    cap = c.annual_cap;
    assert(cap > 0);
    cap - var.accounting.used_wy
    }

[node.outlet]
type = gauge
loc = 0, 300

[fn]                        # passive: lives anywhere, even after use
storage_frac(v, cap) = v / cap
new_wy() = sim.new_month && sim.month == 7
net_demand(pop, doy) = {
    base = pop * c.per_capita;
    peak = 1 + 0.3 * sin(2 * 3.14159 * doy / 365);
    base * peak
    }

[outputs]
node.dam.volume
node.township.diversion
var.accounting.used_wy
var.accounting.headroom
```

## 14. Suggested implementation phases

1. **Grammar core** — `;`, blocks, locals, `assert`, `clamp`; parser + AST +
   lowering + locals frame. No new state. (Everything after this phase is
   usable modeller-facing.)
2. **Stateful builtins** — state arena, unconditional-advance walk,
   `moving_*` then `*_since`; `sim.new_month`/`new_year`/`new_day`.
3. **`[fn]`** — signature parsing, DAG check, inline lowering, `this.`
   rebinding.
4. **`[var.*]`** — scheduled blocks, phase tag, series-backed keys,
   `[outputs]` integration.
5. **IDE wave** — highlighting, linter, autocomplete, go-to-definition,
   in lockstep per phase where practical.

Each phase lands with regression tests and a benchmark run (`performance §4`).
