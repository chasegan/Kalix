---
title: "ADR-0021: A data file's name decides its format, and an unknown extension is CSV"
---

# ADR-0021: A data file's name decides its format, and an unknown extension is CSV

- **Status**: Proposed
- **Date**: 2026-11-15
- **Deciders**: Chas Egan (@chasegan)
- **Tags**: engine, io, cli, ide

## Context and problem statement

Kalix reads timeseries from files named in `[data]` and `observed_file`, and
writes them with `kalix simulate -o`, `save_results` and `kalix convert`. The
format is never declared. It is read off the file name.

Pull request [#529](https://github.com/chasegan/Kalix/pull/529) added
`.csv.gz` beside `.csv.zip`, and its review raised two questions about names.
The first was a compressed Source result, `.res.csv.zip` or `.res.csv.gz`.
It ended in a compressed CSV extension, so the engine and the IDE both read
it as a compressed CSV, and the IDE showed the Source header as rows. The
second was what the engine should do with a name it does not know. Outside
`kalix convert` it has always read and written such a file as CSV, and the
review asked whether it should refuse instead.

## Decision drivers

- Manifesto §2.2 — what you read is what runs. The model file names the
  data file, and the name alone must say how it is read.
- Manifesto §2.3 — behaviour on failure must be traceable. A file Kalix
  cannot read should be refused by name, not parsed into nonsense.
- Manifesto §2.5 — built for practitioners who want control. A modeller may
  have reasons for a file name that Kalix does not need to know.
- Manifesto §2.8 — less is more. A list of permitted extensions is one more
  thing to carry and to extend.

## Considered options

1. **An unknown extension is CSV** — the engine reads and writes any name it
   does not recognise as plain CSV.
2. **A fixed list of extensions** — the engine refuses any name outside
   `.csv`, `.csv.zip`, `.csv.gz` and `.pxt`.

For a compressed Source result, the options were to keep reading it as a
compressed CSV, or to refuse it by name.

## Decision

Chosen option: **1**, with compressed Source results refused by name.

1. **The file name decides the format.** `.pxt` is Pixie, `.csv.zip` is a
   CSV in a one-entry zip archive, and `.csv.gz` is a CSV in a gzip stream.
   The test ignores case. A `format` argument, where one exists, only picks
   a default file name. The name is what the modeller sees, so nothing else
   decides the bytes.
2. **Any other name is plain CSV in the engine.** This holds for model
   inputs and for model outputs. A modeller can name inputs `rain.001` and
   `rain.002`, or `flows.txt`, and they load. That freedom costs nothing to
   keep, and a file that is not CSV fails at its first row with the file
   named.
3. **A compressed Source result is refused by name.** `.res.csv.zip` and
   `.res.csv.gz` end in a compressed CSV extension and are not compressed
   CSV. Kalix reads Source results only uncompressed, and only in the IDE.
   The engine's reader and writer and the IDE's classifier each refuse the
   name before touching the file. This is the one exception to §2, made
   because the name looks like something Kalix reads.
4. **`kalix convert` accepts only the extensions it knows.** A conversion
   is defined by its two formats, and the names are the only statement of
   them. An unknown name there is an error.
5. **A Pixie input is named by its `.pxt`.** Naming the `.pxb` in `[data]`
   is an error that gives the `.pxt` to use. One spelling means one source
   name.

### Consequences

- ✅ A modeller reads the format off the name in the model file.
- ✅ No list of permitted extensions to maintain for model inputs.
- ✅ A compressed Source result gives one clear message in the engine and
  the IDE.
- ❌ A wrongly named binary file, such as a plain `.zip` in `[data]`, is
  read as CSV and fails with a parse error, not a format error. Accepted as
  the price of §2.
- ❌ The IDE's data features recognise only the extensions in §1 and
  `.res.csv`. A file named `rain.001` runs in the engine and opens in the
  IDE as text.

## Pros and cons of the options

### Option 1: An unknown extension is CSV

- ✅ Existing models with unusual file names keep loading.
- ✅ One rule, with one named exception.
- ❌ A file of the wrong kind is caught by the parser, not by its name.

### Option 2: A fixed list of extensions

- ✅ Every wrong file is refused the same way, by name.
- ❌ Breaks models whose inputs are CSV under another extension.
- ❌ Takes a choice away from the modeller for no gain in correctness.

## Worked example

```ini
[data]
./data/rain.001
./data/flows.csv.gz
./data/results.res.csv.gz
```

The first file loads as CSV under §2. The second is decompressed and loaded
under §1. The third stops the load under §3:

```
'./data/results.res.csv.gz': unrecognised timeseries format (expected .csv, .csv.zip, .csv.gz, .pxt or .pxb)
```

`kalix convert rain.001 rain.pxt` is refused under §4. Renaming the input to
`rain.csv` makes the conversion run.

## Enforcement

- **Structural**: §1 is held by one classifier per stack,
  `csv_io::csv_compression` in the engine and
  `CompressedCsvFormat.compressionOf` in the IDE, which the read and write
  paths both use. §2 is pinned by
  `test_an_unknown_extension_is_read_and_written_as_csv`. §3 is pinned by
  `test_compressed_source_results_are_refused_by_name_on_read_and_write`
  and, in the IDE, by `CsvGzDataViewTest`, `FileCategoryTest` and
  `SeriesFileWriterTest`. §4 is pinned by the tests in `convert.rs`. §5 is
  held by `TimeseriesInput::read_source`.
- **Advisory**: that no further path reads a data file by some other rule
  is held by review.

## Links and references

- Pull request: [#529](https://github.com/chasegan/Kalix/pull/529)
- Issue: [#521](https://github.com/chasegan/Kalix/issues/521)
- Related: [#409](https://github.com/chasegan/Kalix/issues/409), which
  introduced `.csv.zip` and the rule that the name decides the bytes.

## Amendments
