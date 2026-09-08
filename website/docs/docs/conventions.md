---
title: "Conventions"
---

# Conventions

## Node Names

Node names are defined as part of the node declaration. Node naming follows the conventions below:

- Node names are not case sensitive.

- They must start with an alphabetical character.

- They may only contain
  - alphabetical characters ‘a’-’z’
  - numerical characters ‘0’-’9’
  - underscores ‘\_’

- They must not contain
  - spaces or other whitespace characters,
  - punctuation including fullstops, commas, question-marks, exclamation points, quotation marks,
  - symbols including ‘#’, ‘$’, ‘%’,
  - mathematical symbols ‘+’, ‘-’, ‘\*’, ‘^’, ‘=’, ‘/’
  - brackets.

## Tables

Tables in Kalix are typically embedded in the model file itself. Tables are represented by a list of elements, although you can format across multiple lines for readability. Here is a dimensions table for a simple 100ML waterhole:

```ini
dimensions = Level, Volume, Area, Spill,
             90,    0,      0,    0, 
             91,    100,    1,    0, 
             91.1,  101,    1,    1e8, 
             92,    102,    1,    1e8
```

Notice the commas at the end of each row except for the last. It’s entirely up to you how you want to distribute the values across rows. Kalix pays no attention to the line-breaks. It knows how many columns to expect, given the context (where the table is being used) and simply interprets the values on that basis.

The table below will be interpreted exactly the same as the one above (scroll left-right):

```ini
dimensions = Level,Volume,Area,Spill,90,0,0,0,91,100,1,0,91.1,101,1,1e8,92,102,1,1e8
```

The column headers are entirely optional. If the first character is a non-numerical character (e.g. above “L”) then Kalix will expect a column name for each of the table columns. But you can launch straight into the values if you want:

```ini
dimensions = 90,0,0,0,91,100,1,0,91.1,101,1,1e8,92,102,1,1e8
```

## Comments

A `#` starts a comment that runs to the end of the line. Comments are allowed on every kind of line — on a line of their own, after a section header, after a `key = value` pair, after a continuation line, and after a bare entry in `[data]` or `[outputs]`:

```ini
# A whole-line comment.

[node.uaw]          # a section header may carry a comment
type = gr4j         # so may a property ...
params = 350, 0.5,  # ... and each line of a multi-line value
         40, 2.5

[outputs]
node.uaw.dsflow     # and so may a list entry
```

Two things are *not* comments:

- **`;` is not a comment character.** Some INI dialects use it, but in Kalix a semicolon is ordinary text. Inside a `{ ... }` expression block it terminates a statement (see [dynamic expressions](dynamic-expressions.md)), so `; note` at the start of a line is an error, not a comment, and KalixIDE's linter will say so.
- **A `#` inside double quotes is text**, not a comment: `"weird # name.csv"` is a file name.

Kalix keeps your comments when it rewrites a model — for example after a calibration, or a `kalix resave` with the standard save method — so they are a safe place for the notes that make a model legible to the next person.

## Multi-line values

Any line that begins with whitespace continues the value of the property above it. Kalix joins the pieces with a single space, so a long table or expression can be laid out for readability (see [Tables](#tables) above). A blank line, a section header, or a line that starts flush-left ends the value.
