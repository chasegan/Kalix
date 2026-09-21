---
title: "ADR-0008: State ordering knowledge per node type, in exhaustive matches"
---

# ADR-0008: State ordering knowledge per node type, in exhaustive matches

- **Status**: Proposed
- **Date**: 2026-09-21
- **Deciders**: Chas Egan (@chasegan)
- **Tags**: engine

## Context and problem statement

The ordering system has to know a few things about every node type. Does a
link leaving it start a regulated zone, continue one, or end it? Does water
take time to pass through it? Can it originate an order, or only pass one
on? Does it keep a delay buffer, and how long? What order does it send
upstream?

Until September 2026 the answers sat in `match` statements inside
`SimpleNodewiseOrderingSystem::initialize`, most of them ending in a
`_ => {}` arm. When the `field` node type was added it needed three edits in
the ordering system. The compiler pointed to one of them, the exhaustive
match in `run_ordering_phase`. The other two sat behind wildcard arms, and
leaving either out gave a model that loaded and ran: a node whose delay
buffer was never sized acts on an order the day it is placed, whatever its
travel time, and a node missing from the list of order originators is never
visited, so it is never supplied. A review of the module found six such
places, not two.

The same review found that travel time was worked out per incoming link,
inside the per-type match, and that the node types combined their links by
different rules. And the next piece of work needed the first question to
change shape: an unregulated user's `ds_1` continues the river's zone while
its `ds_2` starts a new one, so "starts a zone" belongs to a node *and an
outlet*.

The question was where this knowledge should live, and how a new node type
is made to supply it.

## Decision drivers

- Manifesto §2.3 — correctness first, and behaviour on failure never so lean
  that a problem becomes untraceable. A node type that was forgotten should
  stop the build, not run.
- Manifesto §2.6 — clarity off the hot path; use structure and the type
  system to make features robust in the face of future changes.
- Manifesto §2.4 and [ADR-0004](0004-performance-on-the-hot-path.md) —
  setup is cold and the order phase is hot. A tidy-up must not reshape the
  hot loop for the sake of how it reads.
- Manifesto §2.7 — a real need outranks an elegant idea. The need was a
  compiler-forced answer per node type, and one rule for travel time.

## Considered options

1. **Leave the structure, add arms as node types arrive** — the smallest
   change each time.
2. **Move the knowledge into the nodes, behind `Node` trait methods with
   defaults** — `starts_regulated_zone`, `order_lag`, `generates_orders`
   and so on, so the ordering system asks each node and knows no types.
3. **The same trait methods, with no defaults** — every node type writes
   every method.
4. **Keep the knowledge in the ordering module, in exhaustive matches** —
   one function per question, every node type named, no wildcard arm.

## Decision

Chosen option: **option 4**. It gets the compiler to force an answer for
every node type, which is the need, and it keeps every node type's answer to
a question on one screen beside the others. Ordering is a system-level
algorithm and most node types take no special part in it; spreading its
questions across every node file would make it harder to read, not
easier.

1. **What the ordering system needs to know about a node type is stated in
   the ordering module**, one function or match per question
   (`zone_role`, `routing_lag`, `can_originate_orders`,
   `named_order_pathways`, `size_order_buffers`, and the upstream order in
   `run_ordering_phase`). The answers to one question read together, in one
   place.
2. **A match that decides something about a node type names every type. It
   has no wildcard arm.** A new `NodeEnum` variant then fails to compile
   until each question has been answered for it. A wildcard arm is a default
   nobody chose: it lets a forgotten node type run. A pattern that only
   picks out one node type in order to process that type's own property
   (the confluence's `regulated =`) decides nothing about the others and is
   not covered.
3. **A question whose answer can differ between a node's outlets takes the
   outlet.** `zone_role(node, outlet)`, not `zone_role(node)`. Its answers
   are that a link leaving the outlet *starts* a regulated zone (a supply),
   *continues* the one the node is in (almost everything), or *ends* it (a
   drain, up which no order travels and which carries no travel time on).
4. **The travel time to a node is worked out once, from the topology, and
   every node is sized from it.** It is the travel time of the node's
   longest regulated incoming link. It is accumulated down the network as a
   real number and rounded to whole steps where it is used:
   round(accumulated travel time), never the sum of rounded parts. The two
   differ, and the difference is hydrologically significant. The confluence
   is the one exception to "the longest": it keeps a travel time per branch,
   because it delays orders up the shorter one.
5. **Setup is separate steps**: resolve named pathways; work out the
   topology, as a function that changes nothing; size the buffers; build the
   list of nodes to visit. A node with no regulated incoming link is given
   zero-length buffers, so what a buffer holds never depends on an earlier
   run of the same model object.
6. **The hot loop is not reshaped for the sake of how it reads.**
   `run_ordering_phase` runs per node per step. A change to it, or to
   anything in this module, is measured per ADR-0004 §4, and that includes
   setup code: see the 2026-09-21 amendment to ADR-0004.
7. **Rejected: ordering questions as `Node` trait methods with default
   implementations** (option 2). A default is the same silent omission as a
   wildcard arm, moved to a different file. It is not to be re-proposed
   without new information.

### Consequences

- ✅ Adding a node type produces a compile error at every ordering question,
  beside every other type's answer.
- ✅ One rule for travel time. No node type's timing depends on the order
  its links are defined in.
- ✅ The topology can be read, and tested, without following buffer sizing
  through it.
- ❌ Each question lists every node type, most of them in one or-pattern
  that answers "no". Accepted: that list is the point. It is what
  the compiler checks, and it is one arm.
- ❌ The ordering module knows node types by name. Accepted: it always did;
  now it says so in one place per question.

## Pros and cons of the options

### Option 1: Leave the structure, add arms as needed

- ✅ No change to working code.
- ❌ Every new node type is a chance to miss an arm the compiler does not
  ask for.
- ❌ Travel time stays a per-link, per-type calculation.

### Option 2: `Node` trait methods with defaults

- ✅ The ordering system no longer names node types.
- ✅ A node's ordering behaviour sits with the node.
- ❌ A node type that forgets `generates_orders` is never visited and never
  supplied — the failure this decision exists to remove.
- ❌ The answers to one question are spread over every node file.
- ❌ Grows the `Node` trait, which is kept lean, with cold methods.

### Option 3: `Node` trait methods with no defaults

- ✅ Compiler-enforced, like option 4.
- ❌ About sixty trivial method bodies, most of them `false`, `0.0` or `{}`,
  written by reflex.
- ❌ Still spreads each question over every node file.

### Option 4: Exhaustive matches in the ordering module

- ✅ Compiler-enforced.
- ✅ Each question, and every type's answer to it, on one screen.
- ✅ No change to the `Node` trait, to any node struct, or to the hot loop.
- ❌ Repeats the list of node types once per question.

## Worked example

The question, and every node type's answer to it:

```rust
fn zone_role(node: &NodeEnum, _outlet: u8) -> ZoneRole {
    match node {
        NodeEnum::StorageNode(n) => if n.order_through { ZoneRole::Continues } else { ZoneRole::Starts },
        NodeEnum::FieldNode(_) => ZoneRole::Ends,
        NodeEnum::BlackholeNode(_) |
        NodeEnum::ConfluenceNode(_) |
        // ... every other node type, by name ...
        NodeEnum::SurmNode(_) => ZoneRole::Continues,
    }
}
```

When the `FieldNode` variant was added to `NodeEnum`, on a branch begun
before this decision, rebasing it onto the restructured module stopped the
build in six places: here, and at `routing_lag`, `can_originate_orders`,
`named_order_pathways`, `size_order_buffers` and `run_ordering_phase`, each
with "pattern `NodeEnum::FieldNode(_)` not covered". Before this decision the
build stopped at the last of those only.

When an unregulated user gains a `ds_2` that starts a zone, clause 3 is where
it goes: `NodeEnum::UnregulatedUserNode(_) => if outlet >= 1 { ZoneRole::Starts }
else { ZoneRole::Continues }`.

## Enforcement

- **Structural** — §2 for `NodeEnum` variants: the matches are exhaustive,
  so the compiler refuses a node type that has not answered.
- **Structural, held by test** — §2 against a wildcard arm coming back:
  `src/tests/test_ordering_exhaustive.rs` reads the ordering module's source
  and fails if it contains a `_ =>` arm, naming this ADR. If a wildcard is
  ever wanted over something that is not a node type, narrow the test and
  say why there.
- **Structural, held by test** — §4 "the longest, whatever order the links
  are defined in": `src/tests/test_ordering_inlets.rs` runs each junction
  arrangement with its branches defined both ways round.
- **Advisory** — §1, §3, §5 and §6, held by review and by citing them.

## Links and references

- Related: [ADR-0004](0004-performance-on-the-hot-path.md) — the
  hot/cold boundary this decision respects, and the measurements from this
  work (amendment of 2026-09-21).
- Related: [ADR-0005](0005-node-definition-order.md) — links point down the
  file, which the topology step and the order phase both rely on.
- Source: `41dd2e84` on `refactor/ordering-setup`.

## Amendments

*None.*
