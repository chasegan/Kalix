# Code review: `feat/structured-expressions-p5` (IDE lockstep)

**Scope:** `git diff main...HEAD` at e9a5099 — FnSectionValidator, VarSectionValidator,
FunctionExpressionValidator extensions (program blocks, `fn.`/`var.` refs, temporal
builtins), `var.*` output refs in ValidationUtils, autocomplete builtin/sim tables.
**Reviewed:** 2026-07-13, 8 independent review angles + adversarial verification of every
candidate against the Rust engine (all findings below were CONFIRMED; one was verified
end-to-end by building the CLI and running a model). Engine line numbers cite the code
as of this branch.

**Overall:** solid work, and much of the lockstep is exact — see "Verified clean" at the
bottom before re-checking anything there. Every confirmed bug is the same shape: a
hand-re-derived engine rule that diverges on a boundary (case folding, split semantics,
a reserved key, a literal-only rule). Fix them individually (Part 1), but the durable fix
is structural (Part 3).

---

## Part 1 — Correctness fixes (do these before merge)

### 1. Recursion scan: case-sensitive regex + phantom edges
`FnSectionValidator.java:51` — `FN_CALL = \bfn\.([a-z][a-z0-9_]*)` scans raw body text.

- **Missed cycle:** the engine resolves `fn.` calls case-insensitively
  (`fn_registry.rs:210`, `name.to_lowercase().strip_prefix("fn.")`), and the IDE's own
  `fnArity` lowercases too — but the regex only matches lowercase. `a(x) = fn.B(x)` /
  `b(x) = fn.a(x)`: IDE clean, engine rejects at load ("function definitions are
  recursive").
- **Phantom edge:** `\b` holds between `.` and `f`, so `a() = data.fn.a * 2` (input
  alias named `fn`) matches inside the DATA_REF and produces a false
  "recursive: fn.a -> fn.a" error, though the tokenizer itself reads it correctly.

**Fix at the mechanism level:** don't regex-scan raw text. Walk the body with the
existing Tokenizer and collect lowercased `FN_REF` tokens — this kills both bugs at once
(see Part 3, item B, which subsumes this).

### 2. `var.<block>.phase` accepted in expressions
`FunctionExpressionValidator.java:1176` — the key-existence check in
`validateVarReference` matches **any** section key including `phase`. The engine skips
`phase` when registering series (`ini_doc_model_io_0_0_1.rs:722`), so `var.acct.phase`
fails at load — while this same diff's `[outputs]` path (`ValidationUtils.java:189`)
*does* exclude it. The linter currently contradicts itself about the same reference in
the same file. **Fix:** exclude `phase` on the expression path too (or, better, Part 3
item C).

### 3. `moving_*` window/default: literal-only rule unchecked
`FunctionExpressionValidator.java:96` — only arity is checked. The engine
(`dynamic_input.rs` `lower_stateful_call`, ~1925–1953) requires args 2 and 3 of
`moving_sum/mean/min/max` to be load-time constants and the window to be a positive
integer. Crucially, **the engine does no constant folding**: `constant_arg` matches only
a literal `Constant` node, so `c.window` and `2+3` are rejected too. The accepted set is
exactly "bare numeric literal" — which makes this trivially lintable: record whether
each parsed argument was a lone numeric literal (allow leading unary minus — add a test
for how the engine folds that) and check args 2–3 of `moving_*` are literals with the
window a positive integer. `*_since` functions have **no** such constraints — don't
add any there.

Missed today: `moving_mean(data.q, data.window, 0)`, `moving_sum(data.q, 2.5, 0)`,
`moving_mean(data.q, c.n, 0)` all lint clean and all fail engine load.

### 4. Signature parsing: trailing-comma divergence + internal disagreement
`FnSectionValidator.java:171` and `FunctionExpressionValidator.java:1128`.

Empirically verified behavior table for `[fn]` keys:

| Input | Java `parseSignature` | Java `fnArity` | Rust engine |
|---|---|---|---|
| `foo(a,)` | accepted, params `[a]` | **2** | error: "parameter is empty" |
| `foo(,a)` | error (empty param) | 2 (moot) | error: "parameter is empty" |
| `foo(,)`  | accepted, params `[]` | **2** | error: "parameter is empty" |

Java's `split(",")` drops trailing empty segments; Rust's `split(',')` keeps them. So
the IDE accepts definitions the engine rejects, **and** its two validators disagree with
each other (a call `fn.foo(5)` against `foo(a,)` gets a bogus "expects 2 arguments").
**Fix:** `inner.split(",", -1)` to mirror Rust; derive `fnArity` from the same shared
signature parse instead of counting commas (Part 3, item B).

### 5. `[fn]` name/param rule stricter than the engine
`FnSectionValidator.java:158` (and the param check at ~177) — `VALID_NAME =
^[a-z][a-z0-9_]*$` is applied to the **raw** key. The engine lowercases first
(`fn_registry.rs:232` for the name, `:239` for params) and its `validate_bare_name`
(`:258`) accepts a leading underscore. So `Frac(V, Cap) = V / Cap` and `_helper(x) = x`
load and run in the engine but get ERROR-severity flags in the IDE.

⚠️ **Owner decision required (see Part 2a) before coding this** — the recommended
resolution is to tighten the *engine*, in which case the IDE is already correct.
Note: `[var.*]` uses the engine's `is_valid_variable_name` (`misc_functions.rs:104`),
which *is* strictly lowercase-first — `VarSectionValidator`'s identical regex is correct
there. Do not "fix" the var path.

### 6. `[outputs]` var refs: exact-case lookup vs case-insensitive engine
`ValidationUtils.java:176` (section lookup `"var." + blockName`) and `:189` (key
compare `k.equals(varName)`). The engine registers var series lowercased
(`ini_doc_model_io_0_0_1.rs:730`) and resolves output refs via
`name_lookup.get(&name.to_lowercase())` (`data_cache.rs:378`). So
`[outputs] var.Acct.headroom` against `[var.acct]` is a false positive — while the
identical reference in an expression passes (that path lowercases). **Fix:** lowercase
both sides here, matching the expression path (or Part 3, item C).

### 7. Stateful builtins as block locals: IDE rejects, engine runs
`FunctionExpressionValidator.java:744` — the local-assignment guard uses
`KNOWN_FUNCTIONS`, which includes the 9 stateful names. The engine's guard
(`parser.rs:599`) checks only `BuiltinFunction::from_name` — the 24 pure math names.
`RESERVED_STATEFUL` (`fn_registry.rs:22`) is applied only to `[fn]` names/params, never
to locals. Verified end-to-end: `x = { steps_since = sim.step; steps_since * 2 }` in a
`[var.*]` block loads and simulates correctly, while the IDE flags it.

⚠️ **Owner decision required (see Part 2b)** — recommended resolution is to tighten the
engine, in which case the IDE is already correct.

### 8. `count_since` tooltip describes the wrong function
`KalixCompletionProvider.java:442` — the tooltip says "steps counted since reset last
fired", which is `steps_since`'s semantics. Per the design spec §6 and the engine
(`dynamic_input.rs:293`), `count_since(cond, reset)` counts **steps on which the
condition held**, and its first argument is a condition, not a value `x`. **Fix:** e.g.
`count_since(cond, reset) - steps on which cond held since reset last fired`.

### 9. Autocomplete never learned the new language (lockstep gap)
`KalixCompletionProvider.java` — three gaps, all confirmed:
- `addOutputRecorderCompletions` (~:344) offers only `node.name.recorder`; no
  `var.<block>.<key>` even though the linter now accepts them in `[outputs]`.
- `addGeneralValueCompletions` (~:476) offers no `fn.<name>(` or `var.<block>.<key>`
  completions even when the model defines them. `addTableCompletions` (~:517) is the
  exact template — it iterates `model.getSections()` for `startsWith("table.")`; write
  the analogous loops for `fn`/`var.`.
- Section-header completions (~:255) offer `[node.` / `[table.` but not `[fn]` / `[var.`.

---

## Part 2 — Engine-side decisions for the owner (do not resolve unilaterally)

Verification exposed that the **engine itself** is inconsistent in two places, so
"mirror the engine" is ambiguous until the owner picks a side. Recommended: harmonize
the engine to the strict rule in both cases (smaller behavioral surface, matches the IDE
as written, avoids grandfathering odd names into model files). Each is a small Rust patch.

**(a) Naming rules differ between `[fn]` and `[var.*]`.** Fn names/params case-fold and
accept a leading underscore (`validate_bare_name`); var block/key names are strictly
lowercase-first (`is_valid_variable_name`). The engine's own error message ("must start
with a letter") contradicts its code (which accepts `_`). If the engine adopts the
strict rule for `[fn]`, IDE finding #5 needs no Java change; if not, lowercase-fold in
`parseSignature` before `VALID_NAME`/`RESERVED` and allow leading `_`.

**(b) Stateful names are not reserved as block locals.** `RESERVED_STATEFUL` guards
`[fn]` names/params but the program parser's local guard doesn't include it, so locals
can shadow `steps_since` etc. If the engine adds `RESERVED_STATEFUL` (and arguably
`assert`) to the local-assignment guard, IDE finding #7 needs no Java change; if not,
split the IDE check so locals are only checked against the 24 pure names + `assert`/`this`.

---

## Part 3 — Recommended redesign (the durable fix)

The owner has asked for the cleanest, most idiomatic shape and is open to redesign.
These three changes eliminate the *classes* of bug above rather than the instances:

**A. One Java home for the language definition.** The expression language now lives in
five hand-synced tables: `KNOWN_FUNCTIONS` + `KNOWN_SIM_VARIABLES`
(FunctionExpressionValidator), `RESERVED` (FnSectionValidator), `BUILTIN_FUNCTIONS` +
`SIM_VARIABLES` (KalixCompletionProvider), held together by "keep in sync" comments.
Collapse into a single `ExpressionLanguage` definition (per builtin: name, arity,
signature string, description, statefulness; plus sim variables and reserved words)
consumed by all three, with a unit test asserting the sets agree. The Rust mirror stays
manual either way — but one Java copy instead of five. (Alternative home:
`kalix-model-schema.json`, which `LinterSchema` already loads; it currently carries no
function metadata, so this extends the pattern rather than reusing a table.)

**B. Parse `[fn]` once per lint into a registry.** A small `FnRegistry` (lowercased
name → params + line), built by one shared signature parser and carried on
`ValidationContext` (builder field). Consumers: `parseFnCall` existence/arity checks
(currently `fnArity` re-parses **all** `[fn]` keys per call site — O(fns × call-sites)
string work per 300 ms lint pass), the DAG check (walk tokenized `FN_REF`s from bodies
instead of the raw-text regex — fixes finding #1 structurally), and duplicate detection.
This also makes finding #4 unfixable-wrong-twice: one parser, one arity.

**C. One `var.<block>.<key>` existence check.** Put it in `ValidationUtils` (whose
javadoc already claims "all validation logic centralized here"): case-insensitive,
`phase`-excluded, used by both the expression path and the outputs path. Fixes findings
#2 and #6 at the same altitude and prevents re-divergence.

**D. Longer-term (fine to defer past this merge):** `FunctionExpressionValidator` is now
~1,380 lines containing a tokenizer, a recursive-descent parser, and per-namespace
validators; parser modes are set by field pokes after construction
(`parser.allowLateThis = true; parser.programLocals = ...`), where `programLocals`'
null-ness silently switches bare-identifier semantics. Extract Tokenizer/Parser to
`linter/parsing` beside `INIModelParser`, pass modes at construction, and give the
Parser one `parseValue()` entry that owns the `{`-means-program decision (currently
duplicated as `startsWith("{")` at two call sites plus `validateFnBody`).

---

## Part 4 — Polish (small, uncontroversial)

- **Dead branches:** the `contains("..")` checks in `parseFnCall` (~:1069) and
  `validateVarReference` (~:1139) are unreachable — `readDottedReference` breaks on a
  `.` not followed by an identifier char, so a token can end in one trailing dot but
  never contain `..`. Delete both.
- **Drain-to-EOF loop** in `parseProgram` (~:786) exists only to stop callers stacking
  "Unexpected tokens after closing '}'" on top of a real error. Instead, guard the
  callers' trailing-token check with `errors.isEmpty() &&` and delete the loop.
- **Dead lowercasing** in `FnSectionValidator` (~:107, ~:210): names have already passed
  `^[a-z]...` (or errored), so `toLowerCase()` is an identity op that falsely implies
  case-insensitive dup detection. (Revisit if Part 2a changes the rule.)
- **`validateVarOutputReference`** (`ValidationUtils.java:188`): the stream
  `anyMatch(k -> !k.equals("phase") && k.equals(varName))` is
  `!varName.equals("phase") && props.containsKey(varName)` — O(1), and honest about the
  actual rule. Also hoist the thrice-repeated lineNumber/fallback lookup to one local
  (or extract `outputRefReportLine(model, ref)` — there are six more pre-existing copies
  in the same file).
- **Static completions rebuilt per request** (`KalixCompletionProvider`): the library's
  `addCompletion` re-sorts the whole list after every add, so the 41 static builtin/sim
  completions cost 41 sorts + 41 allocations per completion request. Build the
  `List<Completion>` once in the constructor and re-add via `addCompletions(List)`
  (one sort). Per `manifestos/performance.md`, don't leave this on the table.
- **`validateVarReference`** (~:1177): hoist `segments[2].toLowerCase()` out of the
  per-key lambda; a plain loop matches the file's style.
- **`VarSectionValidator`** (~:68): the ValidationContext is identical for every
  section — build once in `validate()`, not per `[var.*]` block.

Not a bug, noted for later: `type = ` / `phase = ` receive expression completions, but
that's the pre-existing GENERAL_VALUE design (main behaves the same with node/data/table
refs), not a regression from this branch. A schema-aware suppression for enum-like
params would be a separate improvement.

---

## Verified clean — do not re-litigate

Checked explicitly against the engine during this review; all in exact lockstep:

- All 33 function names and arities across `KNOWN_FUNCTIONS`, `BUILTIN_FUNCTIONS`,
  `RESERVED` vs engine `BuiltinFunction` + `RESERVED_STATEFUL`; `moving_*` is fixed
  3-arity in the engine (`default` NOT optional); `min/max` variadic ≥2, `sum/mean`
  variadic ≥1; all 8 `sim.*` variables incl. the `new_*` flags.
- Program-block grammar: no-result rule (incl. trailing `;}`), assert-as-statement,
  dotted-assignment rejection, use-before-assign — engine `dynamic_input.rs:1382` region
  matches Java nearly verbatim. Tokenizer lexes lone `=` as OPERATOR; two-char operator
  set is closed, so `==` can't be misread as assignment.
- `this.` late-binding in `[fn]` bodies vs rejection in `[var.*]` (engine passes no
  self-context); phase rules (`flow` ok / `order` unimplemented / other invalid,
  messages mirror the engine); `[fn.something]` reserved (both sides error); forward
  offsets rejected for `var.*` in both; `[offset, default]` brackets on var refs in
  both; `var.<block>.<key>` legal in `[outputs]` (engine `model.rs:200`; the Java
  existence lint is stricter-but-useful, consistent with the node-output lint).
- DAG check runs over unused definitions in both (engine `check_dag` at load,
  `ini_doc_model_io_0_0_1.rs:109`); fn bodies may reference `data./node./var./table./sim.`
  in both; duplicate identical signatures: Java errors, engine silently last-wins —
  Java stricter, judged desirable.
- `INIModelParser` joins indented continuation lines, so multi-line `{...}` bodies reach
  validators intact; `SectionValidator` only checks schema-listed sections, so
  `[fn]`/`[var.*]` need no schema registration and nothing double-reports; no
  duplicate completions across popup rebuilds (`clear()` per request); `ModelLinter`
  and validators are constructed once, not per lint pass; branch tests pass
  (`FnSectionValidatorTest`, `VarSectionValidatorTest`, `FunctionExpressionValidatorTest`,
  `ReferenceValidatorOutputRefTest`).

## Suggested order of work

1. Part 2 decisions from the owner (a and b) — they gate findings #5 and #7.
2. Part 3 A–C (the registry + shared lookups), absorbing findings #1, #2, #4, #6.
3. Findings #3 and #8 (literal-rule lint; tooltip) — independent, small.
4. Finding #9 (autocomplete completions for `fn.`/`var.`/section headers).
5. Part 4 polish in one sweep; Part 3 D as a follow-up branch if preferred.
6. Add the cross-sync unit test from Part 3 A so the next builtin can't drift.
