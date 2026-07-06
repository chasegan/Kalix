---
title: Conventions
---

# Conventions

# Node Names

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

# Tables

Tables in Kalix are typically embedded in the model file itself. Tables are represented by a list of elements, although you can format across multiple lines for readability. Here is a dimensions table for a simple 100ML waterhole:

```toml
dimensions = Level, Volume, Area, Spill,
             90,    0,      0,    0, 
             91,    100,    1,    0, 
             91.1,  101,    1,    1e8, 
             92,    102,    1,    1e8
```

Notice the commas at the end of each row except for the last. It’s entirely up to you how you want to distribute the values across rows. Kalix pays no attention to the line-breaks. It knows how many columns to expect, given the context (where the table is being used) and simply interprets the values on that basis.

The table below will be interpreted exactly the same as the one above (scroll left-right):

```toml
dimensions = Level,Volume,Area,Spill,90,0,0,0,91,100,1,0,91.1,101,1,1e8,92,102,1,1e8
```

The column headers are entirely optional. If the first character is a non-numerical character (e.g. above “L”) then Kalix will expect a column name for each of the table columns. But you can launch straight into the values if you want:

```toml
dimensions = 90,0,0,0,91,100,1,0,91.1,101,1,1e8,92,102,1,1e8
```
