# How INI load/save and the linting rules stay in sync

How the IDE reads, edits, and validates the text-based model format without the
pieces drifting apart — as of the July 2026 review programme. Concrete classes
are named so this doc can be verified against the code (and should be updated
when they move).

## The core principle: the text is the model

The IDE has **no object→INI serializer**. Opening and saving a model is plain
text I/O (`managers/FileOperationsManager` reads and writes the editor buffer
verbatim), and every structured feature — the schematic map, node renames, the
parameter sheet — works by making **surgical edits to the text**, after which
the model is re-parsed from the text. There is exactly one authority and it is
the file. This is why load→save is always byte-faithful: nothing ever
round-trips the model through objects.

Consequences:

- The map never "saves". Dragging a node rewrites its `loc =` line in the
  document (`interaction/TextCoordinateUpdater`); the document change event
  re-parses the text back into the map model (`document/KalixDocument`, which
  coalesces re-parses and memoizes the parse on the document's modification
  count). The loop terminates because model updates never write back to text.
- Renames (`editor/commands/CommandExecutor`) compute **exact column spans**
  from anchored regex matches and splice those ranges — never
  `String.replace` on a line — so a rename cannot touch look-alike text in
  values or comments.
- Numeric text written into the model is locale-pinned: coordinate writes use
  `Locale.ROOT` explicitly, and `KalixIDE.main` pins the JVM's FORMAT-category
  locale, so a comma-decimal OS locale can never corrupt a `loc =` line.

## One section grammar for everything that touches node sections

Historically four call sites each encoded their own idea of "what is a node
section" and they drifted (drags silently failed when a comment contained `[`;
duplicate node names were resolved differently by the reader and the writer).
Now there is one implementation:

- **`model/NodeSectionLocator`** — finds a node's `[node.<name>]` header, its
  section bounds, its `loc` line span, and all `ds_N = <name>` references,
  using the *same* trimming and inline-comment rules as the parser, with
  **last-section-wins** duplicate resolution matching the parser.
- Its consumers: `interaction/TextCoordinateUpdater` (drag write-back, node
  deletion including cleanup of dangling `ds_N` references in *other*
  sections), `interaction/MapClipboardManager` (cut/copy bounds), and
  `editor/EnhancedTextEditor.scrollToNode`.
- The reader it must agree with: **`model/ModelParser`** (the map's parser).
  Agreement is not by convention — `NodeSectionLocatorTest` (28 tests)
  includes explicit parser-agreement cases: comments containing `[`,
  commented-out `# loc =` lines, keys ending in "loc" (`refloc`), indented
  headers, trailing whitespace after `]`, duplicates, CRLF, sections at EOF.

The linter has its own parser, **`linter/parsing/INIModelParser`**, because it
needs a different product (sections + typed properties + line numbers +
continuation chains). Where the two parsers share a rule, the rule lives in one
place:

- Comments and section headers: `linter/parsing/IniSyntax` is the one copy of
  the engine's line grammar (`#` is the only comment marker, inert inside double
  quotes; `;` never is; a header may carry a trailing comment). Every IDE
  scanner of model text — `INIModelParser`, `ModelParser`, `NodeSectionLocator`,
  the syntax highlighter, `ParameterSheetWindow`, the Run Manager's outputs
  scan — routes through it, so `[node.x] # note` (issue #142) cannot again be a
  header to one and a stray line to another. `IniSyntaxTest` pins the rule; the
  same cases are pinned on the Rust side in `custom_ini_parser.rs`.
- Continuation lines: `linter/parsing/IniContinuation` documents the chain rule
  and its coupling to `INIModelParser.collectContinuationLines`; both LF and
  CRLF now parse identically (a trailing `\r` is stripped per line, verified by
  LF-vs-CRLF parse-identity tests).
- The `ds_N` recogniser: `ValidationUtils.DSNODE_PARAM_PATTERN` (`^ds_\d+$`),
  consumed via `ValidationUtils.isDsNodeParam` by `ReferenceValidator`,
  `NodeOrderingValidator`, and `INIModelParser.getDownstreamReferences` — so
  `ds_1_outlet` can never again be treated as a node reference by one validator
  and not another.

## The engine boundary: the Rust parser is the judge

The IDE's parsers are advisory (visualization, linting). The authoritative
parse happens in the engine when the model is loaded over STDIO:
`load_model_string` → `IniModelIO::read_model_string`
(`src/apis/stdio/commands.rs`, `src/io/ini_model_io.rs`). The optimised model
returned by a calibration is serialized by the same Rust class
(`model_to_string`), so engine-emitted INI is engine-canonical by construction.

Where the IDE must *reproduce* an engine rule rather than merely tolerate it,
the rule is pinned to the Rust source:

| Engine rule | Rust home | IDE mirror | Sync mechanism |
|---|---|---|---|
| Name sanitisation (aliases, `data.*` refs) | `src/misc/misc_functions.rs::sanitize_name` | `utils/EngineNames` | Javadoc cites the Rust fn; `EngineNamesTest` pins known pairs (`MyData.csv` → `mydata_csv`) |
| Expression operators (`&&`, `\|\|`, `==`, `^`/`**`…) | `src/functions/operators.rs`, `parser.rs` | `linter/validators/FunctionExpressionValidator` | Test comments cite `operators.rs`; operator tests assert the engine's convention (single `&`/`\|`/`=` are errors with fix-it hints) |
| Timestamp units at IO boundaries | `src/io/pixie_io.rs` (epoch seconds) | `io/PixieWriter`/`PixieReader` | Stated in `docs/kalixcli-stdio-spec.md` + `kalixide/CLAUDE.md`; round-trip tests assert timestamps |
| Gorilla bitstream | `src/io/compression/gorilla.rs` | `io/compression/gorilla/GorillaCompressor` | Two cross-language pinned fixtures (incl. a NaN-payload one) that fail either suite if the encoding moves |

Note the display-name sanitisers (`managers/DatasetLoaderManager.
sanitizeToIdentifier`, `composeDatasetSeriesName`) are deliberately **not**
`EngineNames` — they sanitise tree labels, not engine-facing references, and
have different documented semantics.

## The linting rules: schema-driven, and now honest

- The rule data lives in **`src/main/resources/linter/kalix-model-schema.json`**
  (node types, parameters, allowed outputs, data-type patterns, named rules),
  loaded by `linter/SchemaManager` into `linter/LinterSchema` and consumed by
  the strategy validators in `linter/validators/`.
- **Every rule in the schema is enforced, or it isn't in the schema.** The
  review found decorative toggles; now `coordinate_format` is routed through
  its rule (enabled flag and severity honoured by `NodeValidator`), and
  `data_reference_files` was removed rather than shipped as a dead switch.
  `LinterPreferencesPanel` builds its rule rows *from* the schema, so the UI
  cannot offer a toggle the validators ignore.
- Rule/schema changes propagate live: `SchemaManager` fires `onSchemaChanged()`
  on any change and `LinterManager` revalidates immediately; hover help
  (`linter/ui/PropertyHoverTooltipManager`) resolves the schema per lookup
  rather than capturing an instance, so a reloaded schema is reflected
  everywhere at once. Autocomplete (`editor/autocomplete/
  KalixCompletionProvider`) reads the same `SchemaManager`, so completion and
  validation can't disagree about what a node type accepts.

## Remaining manual seams (know these; they are by convention)

1. **The schema JSON is hand-maintained against the engine.** The engine has a
   draft schema (`src/io/model_schemas/json_schema_draft.json`) but nothing
   generates `kalix-model-schema.json` from Rust yet. Adding a node type or
   parameter to the engine requires a matching schema edit. This is the
   biggest open drift risk; a generator would close it.
2. **The expression grammar exists twice**: the engine's parser
   (`src/functions/parser.rs`) and the validator's recursive descent
   (`FunctionExpressionValidator`). Function names and sim variables are
   hardcoded in the Java side. The operator tests pin the overlap, but a new
   engine function needs a matching `KNOWN_FUNCTIONS` entry.
3. **`ModelParser` vs `INIModelParser`** remain two readers with one shared
   locator and shared constants — not one parser. They agree today because the
   shared pieces are extracted and tested; a future change to either's line
   handling should ask "does this belong in `NodeSectionLocator`/
   `IniContinuation` instead?"

## The doctrine in one line

One rule, one home, cross-referenced and test-pinned — and wherever the rule is
the engine's, the Rust source is named as the point of truth and the Java side
carries a test that fails if they part.
