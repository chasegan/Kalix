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
The first was what the engine should do with a name it does not know.
Outside `kalix convert` it has always read and written such a file as CSV,
and the review asked whether it should refuse instead. The second was a
compressed Source result, `.res.csv.zip` or `.res.csv.gz`. It ends in a
compressed CSV extension, so the IDE opened it as a compressed CSV and
showed the Source header as rows, with no error.

## Decision drivers

- Manifesto §2.2 — what you read is what runs. The model file names the
  data file, and the name alone must say how it is read.
- Manifesto §2.3 — behaviour on failure must be traceable. A file Kalix
  cannot read must fail with the file and line named, and must never be
  shown as data.
- Manifesto §2.5 — built for practitioners who want control. A modeller may
  have reasons for a file name that Kalix does not need to know.
- Manifesto §2.8 — less is more. A list of permitted extensions is one more
  thing to carry and to extend.

## Considered options

1. **An unknown extension is CSV** — the engine reads and writes any name it
   does not recognise as plain CSV.
2. **A fixed list of extensions** — the engine refuses any name outside
   `.csv`, `.csv.zip`, `.csv.gz` and `.pxt`.

For a compressed Source result, the options were to refuse it by name
everywhere, or only where Kalix knows what a Source result is.

## Decision

Chosen option: **1**, because it keeps a freedom modellers already have and
loses nothing in correctness. A compressed Source result is refused by name
in the IDE and in `kalix convert`, and nowhere else. §4 and §6 were already
in force and are recorded here so the subject sits in one place.

1. **The file name decides the format.** `.pxt` is Pixie, and so is `.pxb`
   for an output or in `kalix convert`. `.csv.zip` is a CSV in a one-entry
   zip archive, and `.csv.gz` is a CSV in a gzip stream. Model inputs and
   outputs ignore case in this test. The name is what the modeller sees, so
   nothing else decides the bytes. `save_results` is the one place with a
   `format` argument. There it chooses between CSV and Pixie, and among the
   CSV forms the name alone decides the compression.
2. **Any other name is plain CSV in the engine.** This holds for model
   inputs and for model outputs. A modeller can name
   inputs `rain.001` and `rain.002`, or `flows.txt`, and they load. That
   freedom costs nothing to keep, and a file that is not CSV fails at the
   row that cannot be read, with the file and line named.
3. **The engine does not single out Source results.** For model inputs and
   outputs a `.res.csv` is a CSV name under §2, and a `.res.csv.zip` or
   `.res.csv.gz` is a compressed CSV name under §1. A Source result named
   in `[data]` fails in the CSV parser at its header, compressed or not.
   Refusing only the compressed form by name would treat the two
   differently for no reason.
4. **`kalix convert` accepts only the extensions it knows.** A conversion
   is defined by its two formats, and the names are the only statement of
   them. An unknown name there is an error, and so is a Source result,
   compressed or not.
5. **The IDE refuses a compressed Source result by name.** The IDE reads
   Source results, but only uncompressed. Its classifier does not count
   `.res.csv.zip` or `.res.csv.gz` as compressed CSV. Opening one, loading
   it into a plot or a dataset, comparing it, or saving under such a name
   gives a not-supported message. Without this nothing fails: the header is
   shown as data.
6. **A Pixie input is named by its `.pxt`.** Naming the `.pxb` in `[data]`
   is an error that gives the `.pxt` to use. One spelling means one source
   name.

### Consequences

- ✅ A modeller reads the format off the name in the model file.
- ✅ No list of permitted extensions to maintain for model inputs.
- ✅ The engine's rule for model inputs and outputs does not single out
  any kind of file.
- ❌ A Source result named in `[data]` fails with a date-parse error at its
  header, not with a message that says what the file is. Accepted: the
  error names the file and the line.
- ❌ A wrongly named binary file, such as a plain `.zip` in `[data]`, is
  read as CSV and fails with a parse error, not a format error. Accepted as
  the price of §2.
- ❌ The IDE's data features recognise only the extensions in §1 and
  `.res.csv`. A file named `rain.001` runs in the engine and opens in the
  IDE as text.

## Pros and cons of the options

### Option 1: An unknown extension is CSV

- ✅ Existing models with unusual file names keep loading.
- ✅ One rule, with no exceptions in the engine.
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
under §1. The third is decompressed under §3 and stops the load in the CSV
parser, at the second line of the Source header. The error names the model
line and the full path, and ends:

```
Could not parse date 'Missing data value' with any known format in '<path>/data/results.res.csv.gz' line 2
```

In the IDE, opening `results.res.csv.gz` gives the message from §5.
`kalix convert rain.001 rain.pxt` is refused under §4. Renaming the input to
`rain.csv` makes the conversion run.

## Enforcement

- **Structural**: for §1, the compression is decided by one classifier per
  stack, `csv_io::csv_compression` in the engine and
  `CompressedCsvFormat.compressionOf` in the IDE, which the read and write
  paths both use. §2 is pinned by
  `test_an_unknown_extension_is_read_and_written_as_csv`. §4 is pinned by
  the tests in `convert.rs`. For §5, `CsvGzDataViewTest`,
  `FileCategoryTest` and `SeriesFileWriterTest` pin the classifier, the
  file category and the two writers. §6 is held by
  `TimeseriesInput::read_source`.
- **Advisory**: the Pixie test in §1 is repeated at each read and write
  site. §3 is the absence of a check. The messages §5 shows on opening,
  plotting, dataset loading, comparing and Save Results have no tests,
  since each needs a display. All three are held by review, as is the claim
  that no further path reads a data file by some other rule.

## Links and references

- Pull request: [#529](https://github.com/chasegan/Kalix/pull/529)
- Issue: [#521](https://github.com/chasegan/Kalix/issues/521)
- Related: [#409](https://github.com/chasegan/Kalix/issues/409), which
  introduced `.csv.zip` and the rule that the name decides the bytes.

## Amendments

*None yet.*
