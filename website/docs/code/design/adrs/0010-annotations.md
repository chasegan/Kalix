---
title: "ADR-0010: Carry IDE metadata in annotation comments"
---

# ADR-0010: Carry IDE metadata in annotation comments

- **Status**: Accepted
- **Date**: 2026-10-18
- **Deciders**: Chas Egan (@chasegan)
- **Tags**: ide, language, io

## Context and problem statement

The IDE needs to attach to a model file things the engine has no use for: a
readable node name, membership of a cluster the schematic can collapse, and
collapsible text regions (#134, #141, #476, #466). Today the only such datum
is `loc`, and it lives in the grammar, special-cased in every node reader.
Where does IDE metadata live, how is it written, and how do other tools add
their own?

## Decision drivers

- Manifesto §2.2 — metadata must be visibly not part of the model.
- Manifesto §2.8 — one mechanism, a small vocabulary.
- Manifesto §2.6 — the recognition rule must be deterministic.
- ADR-0005 — grouped nodes cannot always be adjacent in the file.
- ADR-0006 §1.3 — Kalix owns bare names; everything else is namespaced.

## Considered options

1. **`#[key = value]` comments**, as sketched in #141.
2. **In-grammar `@key` properties** skipped by the engine, generalising `loc`.
3. **`## Heading` comments** for regions.
4. **`#@key = value` comments** attached to the section below.

## Decision

Chosen option: **4**, for regions as well.

1. **An annotation is a comment. No simulation code reads one.** Anything
   the simulation needs is a property. The INI document layer in Rust, shared
   by the CLI and the Python package, lexes and preserves annotations and may
   expose them to tools.
2. **Syntax.** A line whose first two characters, at column zero, are `#@`,
   followed by `key = value`. The value is the trimmed text after the first
   `=`. Any other prefix, such as `##@` or `# #@`, is an ordinary comment,
   which is how an annotation is disabled. A `#@` line that is not
   `key = value` is a lint error.
3. **Placement.** An annotation attaches to the next section header. Blank
   lines and ordinary comments may lie between; a code line orphans it, which
   is a lint error, as is an annotation anywhere else. Annotations above
   `[kalix]` are file-level. A section's text begins at its first annotation:
   a tool that removes, copies or replaces a section takes its annotations
   with it, and comments before the first annotation stay with the section
   above.
4. **Keys.** A bare key belongs to Kalix, follows the strict bare-name rule
   of ADR-0006 §2.6, and has a typed, linted value; an unknown one is a lint
   warning. A dotted key `tool.key` belongs to the tool named by the prefix.
   Its value is opaque one-line text that Kalix preserves and never
   interprets, so one-line JSON needs no escaping. Kalix never claims a
   dotted prefix.
5. **A key never reuses a property or section name with a different
   meaning.** This excludes `group`, `name` and `alias`.
6. **Vocabulary.** Four keys, and no more until a use case exists.
    1. `aka` — a readable alternative name, any text; empty means none. A
       label in the sense of ADR-0003, never an identity: the schematic shows
       akas in a label mode of their own, and hovering any node reveals its
       name, so identity is never more than a hover away.
    2. `cluster` — a strict bare name. Nodes sharing one may be drawn as one
       icon at their centroid. `.` is reserved for hierarchy later.
    3. `region` — the title of a text region, which runs to the next `region`
       or the end of the file. Regions do not nest and are not clusters.
       Folding a section or a multi-line value is structural and needs no
       annotation.
    4. `loc` — `x, y` on the schematic, replacing the `loc` property.
7. **Tools.** A replace patch keeps a section's annotations unless the
   snippet supplies its own. The Python package reads and writes annotations;
   its API shape belongs to the Python spec.
8. **Transition for `loc`.** The engine keeps accepting the property. The IDE
   reads both forms, prefers the annotation and lints a duplicate, writes
   back the form a node already has, and gives new nodes the annotation.
   Conversion is a visible IDE command; the CLI never writes annotations.
9. **`@` is reserved.** The expression language and file grammar never use
   it, so no legal line can start with it.
10. **Deferred, by name.** A multi-valued `tags` key; nested regions by ranked
   keys; multi-line values by a continuation marker. Each waits for a use
   case per Manifesto §2.7.

### Consequences

- ✅ The engine's grammar does not change, and the document layer already
  preserves comments.
- ✅ Nothing in a comment runs, and nothing outside one is decoration.
- ❌ A small second language to lex. Accepted: one line form, one lexer in
  Rust mirrored in Java, one key registry.

## Pros and cons of the options

- **1, `#[...]`**: ❌ `#[node.x]` is a commented-out header, and the
  repository already contains such lines.
- **2, `@key` properties**: ✅ one mechanism with `loc`; ❌ every key becomes
  grammar the engine tolerates forever, and bare entries in `[data]` and
  `[outputs]` cannot carry one.
- **3, `## Heading`**: ✅ reads well raw; ❌ `### type = gauge` is a
  commented-out comment and a heading under any simple rule.
- **4, `#@`**: ✅ deterministic, same `key = value` shape as the file, and
  comment toggling disables and restores it exactly; ❌ one sigil to learn.

Also rejected: annotations inside the section, since the engine's document
model already gives a header its leading comments; and `endregion` markers,
since pairs can be unbalanced and no model needed arbitrary spans.

## Worked example

```ini
#@region = Teemburra scheme

#@aka = Teemburra Dam
#@cluster = teemburra
#@loc = -202.35, 4078.09
#@pycalib.bounds = {"x1": [10, 2000]}
[node.teemburra]
type = storage
```

## Enforcement

- **Structural.** The model loader has no annotation concept. The lexer is
  `linter/parsing/IniSyntax.java` today; when the Rust document layer gains
  one for §1 and §7, in `src/io/custom_ini_parser.rs`, the Java one mirrors
  it as the comment lexer already does, and the resave and replace-patch
  preservation tests arrive with it. One key registry in the IDE. The §3
  span rule is held by the section locator's tests.
- **Advisory.** §5, §6 and §10 are held by review.

## Links and references

- Related: [ADR-0003](0003-identity-and-labels.md),
  [ADR-0005](0005-node-definition-order.md),
  [ADR-0006](0006-expression-naming.md).
- Issues: [#466](https://github.com/chasegan/Kalix/issues/466),
  [#134](https://github.com/chasegan/Kalix/issues/134),
  [#141](https://github.com/chasegan/Kalix/issues/141),
  [#476](https://github.com/chasegan/Kalix/issues/476),
  [#442](https://github.com/chasegan/Kalix/issues/442).
- External: Rust tool attributes; Java `RetentionPolicy.SOURCE`; Kubernetes
  labels versus annotations.

## Amendments

*None.*
