# Data File Viewer

Status: **V1.1 delivered** (engine, projections, tab mount, external-change
refresh with live tail, and the table tools — stages below); the "Later" items
remain open.

The viewer for data files (`.csv`, `.res.csv`; pixie later) opened as tabs in the
IDE. Built for the realities of modelling data: files up to ~1GB, sometimes on
network folders, where the UI must stay beautiful and responsive despite the
physical limits on how fast bytes can be read and parsed.

## Settled decisions (discussed September 2026)

1. **Text is primary, always.** A data tab is
   `[ text (primary) | table (context view) ]` at every file size — per the
   multi-document architecture's decision 3 and the Kalix ethos: *show the
   modeller what is really there*. Modellers open CSVs to fix header lines,
   check the delimiter, and check number encoding (scientific vs natural
   notation); the raw text is the ground truth and the table is a declared
   interpretation of it. Table-only was considered and rejected.

2. **View-only first.** V1 is an excellent viewer; editing (column ops,
   cell edits, cut/copy/rename) layers on later. The virtual design keeps that
   path open: future edits become a sparse overlay composited over the
   immutable base file, saved by streamed rewrite. The one edit modellers most
   need on huge files — fixing header lines — is a natural V2 feature (the head
   region is tiny; edit it in memory, stream-copy the rest on save).

3. **A 50MB gate on the *editable* text component** (preference, in
   Editor → Load and Save). Below it, the real text editor: fully editable, as
   today. Above it, a **virtual read-only text view** showing the true bytes —
   you always see everything; only live editing is gated. The number is
   physics, not taste: an editable gap-buffer document costs ~3× the file size
   in heap plus one object per line, and a keystroke near the top of a 100MB
   buffer memmoves ~200MB (~20–40ms — jank); at 50MB the worst case stays
   crisp. Java's contiguous-array document also has a hard ~2GB ceiling.

4. **Format scope for V1:** `.csv` (delimiter-sniffed — comma, semicolon, tab,
   pipe, consistent with the existing importers; no `.tsv` extension, which
   Kalix has never supported) and `.res.csv` (extended header handled by the
   existing marker-driven reader; data region indexed from its end offset).
   Pixie later — binary, series-oriented, its own presentation.

5. **Table first, plot later.** The plot view will reuse FlowViz's renderer and
   needs large-series downsampling; it is its own stage.

6. **The interpretation declares itself.** A status strip on the tab states
   what the sniffer decided: delimiter, quote, encoding, line endings, row
   count / indexing progress. A wrong guess is visible, never silent.

## Architecture: index fast, parse lazily, never block

A viewer never needs the whole file parsed — it needs the visible rows parsed
and the row count known. Two decoupled layers (package `com.kalix.ide.dataview`;
the engine is Swing-free and headless-tested):

- **One streaming pass** (`CsvIndexer`): a single sequential read — the only
  access pattern network shares are good at — building **two** checkpoint
  indexes at once (`CheckpointIndex`, offset of every Nth item, ~KBs per GB):
  - *rows* (quote-aware: quoted fields may contain newlines) → the table;
  - *lines* (every newline) → the raw text view.
  Interruptible every chunk; closing the tab aborts within one read. The
  indexes are usable while incomplete — the table/scrollbar grow live with a
  progress ticker ("412 MB / 1.0 GB · 8.3M rows").

- **Lazy block access** (`RowBlockParser` + `RowStore`): a block is one index
  stride; any row is one checkpointed seek plus a bounded parse; parsed blocks
  live in a bounded cache (insertion-order eviction — approximate LRU is
  deliberately traded for lock-free reads, and prefetch hides the occasional
  re-fetch). Steady-state heap is the cache bound regardless of file size. The EDT never touches I/O or parsing: a cache miss renders a
  placeholder for a frame and repaints when the block lands (the
  `DirectoryLister` doctrine, applied to file contents).

Consistency invariant: indexer and parser use the same bare quote-parity
convention, so they can never disagree about where a row starts. A stray
unbalanced quote makes the rest of the file one long logical row (honest RFC
behaviour) — the physical-line index and the text view are unaffected, which is
one more reason both projections exist. V1 engine supports ASCII-compatible
charsets (UTF-8, with or without BOM); a UTF-16 BOM is detected and reported so
the host can refuse honestly.

## Stages

- **V1a — the engine, no UI**: `DialectSniffer`, `CheckpointIndex`,
  `CsvIndexer`, `RowBlockParser`, `RowStore`. Pure logic, heavily unit-tested
  (CRLF, quoted newlines, BOM, missing trailing newline, cancellation).
- **V1b — the two projections**: virtual table + virtual read-only text
  components over the shared store; background fetch, placeholder-on-miss,
  direction-aware prefetch; live growth during indexing.
- **V1c — the mount**: `DocumentKind.DATA`, tab composition on the
  `getContextView()` seam (editable editor below the gate / virtual text
  above), the gate preference in Editor → Load and Save, the status strip,
  `.res.csv` dispatch.
- **V1.1 — refresh & table tools** (September 2026): external-change refresh
  with append-resume live tail; the table context menu — Find… / Find Next
  (streamed, case-insensitive, on its own channel so the block caches stay
  untouched), Show in File (row → physical line via the byte offsets both
  indexes share, quoted newlines handled; lands in the virtual text view above
  the gate or the real editor below it, offset by any extended header), and
  Copy (JTable's native tab-delimited copy).
- **Later**: plot view via FlowViz; pixie; head-region editing; overlay-based
  editing; column stats.

## V1 limitations (known, accepted)

- **External changes refresh the views (V1.1).** The auto-reload watcher routes
  every data-file change (and every save from the document's own editor)
  through `refreshDataViewFromDisk`. A pure append — a running simulation
  writing results — is handled in place by append-resume: the indexed region's
  clean end and unchanged tail bytes are verified, indexing continues from the
  old end, and the views simply keep growing ("live tail"). Anything else
  rebuilds the session and swaps it into the views. Both the probe and the
  rebuild run on a single background refresh worker — never the EDT — whose
  drain loop coalesces bursts of change events without ever losing the
  trailing one. The table never parses stale offsets.
- **`.res.csv` virtual views show the data region only** (text and table both
  start past `EOH`). Below the gate the real editor still shows the whole file,
  extended header included.
- **The gate applies to DATA files only.** A giant `.txt`/log still loads fully
  into an editor buffer, exactly as before this work; gating TEXT files through
  the same virtual view is future work.
- **UTF-16 data files** are detected and reported but not virtually viewable
  (the byte scanner is ASCII-compatible-charset only); below the gate they open
  in the editor as always.
