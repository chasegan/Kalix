# Node definition order

*Definition order is execution order. The engine validates it; it never sorts it.*

A Kalix model file lists its nodes in the order they execute — upstream before
downstream, always. This is not a parser limitation awaiting a topological
sort; it is doctrine. The file **is** the model, and the order you read is the
order that runs.

## 1. Principle: what you read is what runs

Kalix's deepest commitment is transparency: text-based model files with
nothing hidden from the modeller. A hidden sort would break that in a way no
amount of documentation could repair — the file would say one order, the
engine would run another, and the modeller could no longer reason about their
model by reading it. Keeping definition order authoritative means:

- **The file reads downstream.** A reader follows the water: headwaters at the
  top, outlet at the bottom, the same journey the simulation takes. A Kalix
  model file is a legible description of a river system, not a bag of sections.
- **Diffs and reviews follow the physics.** Version control shows changes in
  network order; two modellers reviewing a file walk it the same way the
  engine does.
- **Debugging needs no mental machinery.** "What ran before this node?" is
  answered by scrolling up — never by simulating a sort algorithm in your head.

## 2. Rules

1. **Every link's upstream node is defined before its downstream node.** The
   engine checks this at initialisation and refuses to run otherwise, naming
   the offending pair: `Node 'X' must be defined before 'Y'`. It never
   reorders on the modeller's behalf.
2. **The engine contains no topological sort, and none may be added.** If a
   future need arises to arrange nodes automatically (e.g. an IDE
   convenience), it belongs in tooling that **rewrites the file, visibly** —
   never in engine memory, where the file and the execution would silently
   disagree.
3. **Independent branches run in file order.** Where the network alone does
   not force an order (parallel tributaries), the modeller's ordering is the
   execution order. This is a feature: a sorted engine would have to invent a
   tie-break rule, and execution order among branches would then depend on
   invisible machinery. In Kalix the modeller decides, by writing the file.
4. **Model-generating tools emit topological order.** Anything that writes a
   Kalix model (the IDE, scripts, `generate_models.py`-style generators) must
   emit nodes upstream-first, because the engine will not repair order for
   them.

## 3. Rationale: why refuse rather than sort

A topological sort is easy to write — twenty lines, once. It was considered
during the 2026-07 engine review and rejected, because its cost is paid in
the currency Kalix values most:

- **It makes the file lie.** The moment order stops mattering, the file stops
  being a faithful account of execution. Every downstream promise —
  reviewability, diff-ability, "read the file to understand the model" — is
  quietly weakened.
- **Topological orders are not unique.** A sorter must break ties, and any
  tie-break (definition order? alphabetical? insertion order of links?) is a
  hidden rule the modeller must learn. Refusing keeps the one rule visible:
  the file's order.
- **The error is a teaching moment.** A modeller who writes a downstream node
  too early learns something true about their network the moment they run it.
  A silent sort would teach nothing and hide a misunderstanding.

The price is that modellers (and tools) must maintain order as networks grow.
That price is accepted deliberately: it is the same price as keeping any text
artifact organised, and the payoff is a file a stranger can read.

## 4. Worked example

Valid — the file reads downstream, and executes exactly as written:

```ini
[node.headwater]      ; runs 1st
type = inflow
ds_1 = weir

[node.weir]           ; runs 2nd
type = storage
ds_1 = outlet

[node.outlet]         ; runs 3rd
type = gauge
```

Invalid — `weir` links downstream to a node defined above it. The engine
refuses with `Node 'weir' must be defined before 'outlet'` rather than
quietly reordering:

```ini
[node.outlet]         ; defined 1st, but receives weir's water
type = gauge

[node.weir]
type = storage
ds_1 = outlet         ; error: links may only point down the file
```

---

*Enforcement: structural — `Model::check_execution_order` (src/model.rs)
refuses out-of-order files at initialisation, and no sorting code exists in
the engine. Rule 4 (tool obligations) is Advisory, held by review.*
