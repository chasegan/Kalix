# Overview

<!-- 
Describe your changes at a broad scale - what changes for the modeller or developer? 
Please ensure you have read CONTRIBUTING.md.
-->

Closes <!-- issue; new features and behaviour changes need an agreed approach (GOVERNANCE) -->

# Technical details

<!-- A quick dot point list of what you changed. Cite ADRs by clause. 
For example:
- Added a new enum tracking X.
  - Naming aligns with existing enum Y (per Manifesto §2.6).
- Refactored Z to achieve W. Measured U% speed-up on tests 4 and 5 (per ADR-0004 §3.4).
-->

# Decisions

<!-- Does this make a decision worth an ADR (ADR-0001 §2)? Does it go against any accepted ADR? "None" is a fine answer. -->

# Testing

<!-- Tests added/run; regression suite; speed tests for engine hot-path changes; bit-identity where output must not drift. Manual checks in the IDE. -->

# Docs and release notes

- [ ] User docs updated, or none exist (flag it here)
- [ ] Bullet added to `website/data/releases/<next>.md.txt`

# Follow-ups

<!-- Items deliberately out of scope (e.g. requires decision); to be picked up later. -->

# Checklist

- [ ] `cargo test` (engine) / `./gradlew build` (IDE) pass
- [ ] `mkdocs build --strict` passes
- [ ] New node property: reader, writer, linter schema, docs
