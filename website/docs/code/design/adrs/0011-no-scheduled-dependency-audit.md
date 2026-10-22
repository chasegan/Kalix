---
title: "ADR-0011: Guard dependencies with a committed lockfile, not a scheduled audit"
---

# ADR-0011: Guard dependencies with a committed lockfile, not a scheduled audit

- **Status**: Accepted
- **Date**: 2026-10-19
- **Deciders**: Chas Egan (@chasegan)
- **Tags**: build, process

## Context and problem statement

In October 2026 the maintainers of the `zip` crate yanked every release
below 9.0.3 after three advisories in its extraction code. Kalix never
extracts an archive and was not exposed, but the Python wheel build broke
on every branch: `python/` was a standalone crate with its lockfile
gitignored, so each build resolved dependencies afresh and found nothing to
resolve to. The engine build survived because cargo keeps using a yanked
version that is already in a committed lockfile.

Pull request [#477](https://github.com/chasegan/Kalix/pull/477) fixed this
by making `python/` a workspace member sharing the root `Cargo.lock`, built
with `--locked` in CI. It also proposed a weekly `cargo-deny` workflow to
flag new advisories and yanks. The question was whether Kalix wants that
standing audit.

## Decision drivers

- Manifesto §2.8 — less is more. A scheduled job, a policy file and the
  habit of triaging its output are a permanent cost.
- Manifesto §2.7 — grounded in real use. The audit answers a threat Kalix
  does not face: it is a local tool reading the user's own files, with no
  network or privilege boundary, and every advisory in play was in code
  Kalix does not call.
- Manifesto §2.3 — correctness first. The thing that actually broke, a
  build that re-resolved dependencies, is fixed structurally and does not
  need monitoring.

## Considered options

1. **Committed lockfile only** — every build is `--locked`; a yank or
   advisory is dealt with when someone next touches dependencies.
2. **Committed lockfile plus a weekly `cargo-deny` audit** — as proposed in
   #477.
3. **Committed lockfile plus GitHub Dependabot alerts** — a repository
   setting, no files in the tree.

## Decision

Chosen option: **1**, with option 3 available to the lead as a setting
rather than as doctrine.

1. **Every build path uses the committed root `Cargo.lock`.** The engine,
   the wheel and the source distribution all build `--locked`, so a yank
   upstream cannot break a build and a stale lockfile fails loudly instead
   of re-resolving. This is the fix for what went wrong.
2. **There is no scheduled dependency audit.** Under the lockfile a yank
   costs whoever next does dependency work, not CI, and the project has no
   boundary at which an advisory in unused code becomes a risk. The audit
   would have been red on day one: merging the workspace brought `pyo3`
   into the lockfile with three open advisories, all in functions Kalix does
   not use.
3. **Revisit if Kalix gains a network or privilege boundary**, for example
   a server mode or a feature that processes files from untrusted sources.
   That is new information in the sense of ADR-0001 §1, and the audit should
   be proposed again against it.

### Consequences

- ✅ Nothing in CI goes red because of upstream churn in code Kalix does
  not run.
- ✅ No policy file or workflow to maintain.
- ❌ The project learns of a dependency problem only when a person looks.
  Accepted under the threat model above; Dependabot alerts cover the gap at
  no cost in the tree if the lead turns them on.

## Pros and cons of the options

### Option 1: Committed lockfile only

- ✅ Fixes the actual failure structurally; zero ongoing cost.
- ❌ No channel for a dependency problem that does matter.

### Option 2: Lockfile plus weekly `cargo-deny`

- ✅ Catches yanks, which Dependabot does not.
- ❌ A workflow and a policy file for a threat Kalix does not face; red from
  the first run; emails one maintainer about upstream churn.

### Option 3: Lockfile plus Dependabot alerts

- ✅ One setting, covers Rust, Gradle and pip together, nothing in the tree.
- ❌ Does not flag yanks, and is less complete than RustSec for Rust crates.

## Worked example

A contributor opens a pull request adding a `cargo audit` step to CI after
reading about a new advisory. The reviewer replies: *declined per ADR-0011
§2; if the advisory touches code Kalix calls, open an issue and bump the
crate in `Cargo.lock`.* If the contributor is adding a server mode, §3
applies and the audit is back on the table.

## Enforcement

- **Structural**: §1 is held by `--locked` on the wheel builds in
  `ci.yml` and `release.yml`, and by `bump-version.py`, which keeps the
  workspace crates' versions in `Cargo.lock` in step with the manifests.
- **Advisory**: §2 and §3 are held by review and by the citing habit.

## Links and references

- Pull request: [#477](https://github.com/chasegan/Kalix/pull/477), of
  which the first three commits were taken and the fourth declined.
- Related: [ADR-0001](0001-record-decisions-in-adrs.md) §1 on recording
  rejected ideas.

## Amendments

*None yet.*
