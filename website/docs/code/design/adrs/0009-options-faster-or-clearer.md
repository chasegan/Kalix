---
title: "ADR-0009: Add an option only when it is faster or clearer than what a modeller can already write"
---

# ADR-0009: Add an option only when it is faster or clearer than what a modeller can already write

- **Status**: Proposed
- **Date**: 2026-09-25
- **Deciders**: Chas Egan (@chasegan)
- **Tags**: engine, io, ide, process

## Context and problem statement

Many proposals request dedicated features for something that can already 
be achieved with existing functionality. Normally Kalix's less-is-more
philosophy (Manifesto §2.7) would encourage denying the request. But 
Kalix's expression language is general enough that MOST behaviours you 
can imaginable are achievable with expressions. Does this mean we deny 
all future features? No, certainly not! 

So how do we know whether Kalix's less-is-more value applies, or whether 
the new feature is allowed?

One recent example was a request to introduce a units system. Conversion 
can be a step in the data preparation before a run (fast and clear), or 
using a constant factor in an expression (also works). The feature request
was closed.

Another recent example is the pull request ([#438](https://github.com/chasegan/Kalix/pull/438)) 
which proposed interpolation methods and extrapolation policies for lookup 
tables. The core bilinear interpolation was accepted but the interp/extrap
methods were excluded. The reasons were stated in the review.

This ADR is written to clearly record the rationale behind the above 
decisions, so that they apply to future proposals.

The question is what a new option has to demonstrate before it is added,
given that the same thing can already be written without it.

## Decision drivers

- Manifesto §2.2 — what you read is what runs. A choice moved out of the
  model file and into the engine is a choice the reader can no longer see.
- Manifesto §2.4 and [ADR-0004](0004-performance-on-the-hot-path.md) —
  anything the engine does slows down everybody's models. An option that
  costs the models not using it is a sin.
- Manifesto §2.6 — clarity, and one thing having one name. Every option is
  one more thing a reader must know.
- Manifesto §2.7 and §3 — a real need outranks an elegant idea, and
  transparency is never traded for convenience.

## Considered options

1. **Judge each proposal in review** — the practice until now. Each proposal
   is assessed on its merits when it arrives.
2. **Push back on a convenience option when it is slower and otherwise
   redundant with existing functionality** — the first formulation of the
   rule.
3. **Accept an option only if it is faster or clearer than the way it can
   already be written, and costs nothing for models that do not use it** —
   the refinement of option 2.

## Decision

Chosen option: **3**. Option 1 gives contributors nothing to design against,
and produces rejections that read as taste. Option 2 is nearly right but
bundles two different costs into "slower", the cost to the modeller who uses
the option and the cost to everyone who does not, and it does not say what
"redundant" allows, when the expression language makes almost everything
redundant. Option 3 separates the two costs, makes the second one absolute,
and replaces "redundant" with a comparison against the way the thing can
already be written.

1. **A new option is judged against the way the same thing can already be
   written.** Redundancy is not the objection; the expression language
   guarantees it. The objection is a dedicated construct that is no better
   than the composition it replaces.
2. **Better means faster, or clearer, and nothing else.** These are the only
   two currencies the Manifesto recognises for the model file (§2.4, §2.6).
   Convenience is not one of them; the Manifesto mentions convenience only as
   the thing transparency is never traded for (§3).
3. **Clarity is judged by the reader of the model file, not the writer.**
   Clearer means the reader needs less knowledge to understand what runs. An
   option that moves a decision out of the file and into the engine is more
   convenient to write and less clear to read, and is declined on that ground
   alone. An explicit table row or an explicit constant factor is clearer than
   a keyword whose meaning lives in the engine, however tedious it is to type.
4. **The option must cost nothing for models that do not use it.** In
   practice this means the choice is resolved at load or lowering into a
   distinct type or AST variant (ADR-0004 §3.5), never carried as a field
   that a hot function branches on. This test is absolute and is checked by
   measurement (ADR-0004 §4).
5. **Every option is a permanent addition to what every reader, the IDE, the
   linter, the docs and the engine must know, and options multiply.** Kalix
   keeps its option set small on purpose, so that a model file can be read
   with little knowledge of the engine. This is the cost that clause 4 does
   not capture, and it is why "zero cost to non-users" is necessary but not
   sufficient.
6. **A proposal states its case in these terms.** A pull request adding an
   option says what it is faster or clearer than, and shows the cost to
   models not using it is zero. That is what the review checks.

### Consequences

- ✅ Contributors have a test to apply before writing code, and reviews can
  cite a clause instead of re-arguing.
- ✅ The option set stays small, and model files stay readable by someone who
  knows the file and not the engine.
- ✅ Genuinely better constructs still get in: tables were accepted over `if`
  chains, and bilinear lookup over per-column tables, because each is both
  faster and clearer.
- ❌ Some things are more work to write in Kalix than in tools that offer the
  option. Accepted because the work is done once by the writer and the
  clarity is paid to every reader.
- ❌ "Clearer" needs judgement. Accepted because clause 3 fixes whose
  judgement it is, and because the alternative is judgement without a stated
  standard.

## Pros and cons of the options

### Option 1: Judge each proposal in review

- ✅ No rule to maintain.
- ❌ Inconsistent outcomes, and nothing a contributor can design against.

### Option 2: Slower and otherwise redundant

- ✅ Captures the two cases that prompted the rule.
- ❌ "Slower" for whom is not stated; an option resolved at load time is not
  slower for anyone, and can still fail Manifesto §2.2.
- ❌ "Redundant" does not distinguish a construct that duplicates existing
  functionality from one that is better than it; tables are redundant with
  `if` chains and were rightly added.

### Option 3: Faster or clearer, and free for non-users

- ✅ Aligns with the Manifesto's two currencies and its ranking in §3.
- ✅ Explains why one review can say yes to bilinear and no to the options
  around it.
- ❌ Requires the reviewer to name the composition being compared against,
  which is some work; that work is the review.

## Worked example

**Extrapolation past a table's end.** Today a table clamps. A proposal added
`on_extrapolation = clamp | warn | deny`. The way it can already be written
is one more row that states the value to use past the end. The row is faster,
since clamping is two comparisons, and clearer, since it shows the value
rather than a keyword. Declined under clauses 1 to 3. As implemented it also
branched on the policy per evaluation, failing clause 4.

**Tables, and bilinear lookup.** A 1D table can be written as a chain of `if`
expressions. The table is faster and far clearer, so it was added. A bilinear
2D lookup can be composed from per-column 1D tables and an expression, and
the dedicated form is again both faster and clearer, so it is accepted, as a
single property on 2D tables resolved at lowering into its own AST variant so
the existing lookup path is untouched.

## Enforcement

- **Structural**: clause 4 is held by ADR-0004's benchmark requirement and by
  the lowering pattern, since a choice resolved into a distinct AST variant
  cannot be branched on per step.
- **Advisory**: clauses 1 to 3, 5 and 6 are held by review and by the citing
  habit. A pull request that adds an option without saying what it is faster
  or clearer than is asked to.

## Links and references

- Related: [ADR-0004](0004-performance-on-the-hot-path.md),
  [ADR-0006](0006-expression-naming.md)
- Issue: [#412](https://github.com/chasegan/Kalix/issues/412)
- Pull request: [#438](https://github.com/chasegan/Kalix/pull/438)

## Amendments

