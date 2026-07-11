/// Named lookup tables for the DynamicExpression language.
///
/// Tables are defined in `[table.<name>]` INI sections and referenced from
/// expressions as `table.<name>(x)` (1D) or `table.<name>(col_key, row_key)`
/// (2D). All parsing and validation happens here at model load (cold path),
/// so the lookup methods on the hot path are infallible except for the
/// documented exact-match panic on 2D column misses.
///
/// # Semantics
///
/// - **1D** (`n_cols = 2`, the default): rows of `(x, y)` breakpoints with x
///   strictly ascending. Lookup is linear interpolation, **clamped** to the
///   endpoint values outside the table range (no extrapolation).
/// - **2D** (`n_cols > 2`): the first row is a non-numeric corner marker
///   followed by `n_cols - 1` column keys; each following row is a row key and
///   `n_cols - 1` values. The first argument selects a column by **exact
///   match** (panics with context otherwise); the second interpolates down
///   that column with the same clamped-linear rule as 1D.
///
/// Line breaks carry no meaning in the `values` property: a table may be
/// written on one line or spread over many continuation lines.

use std::sync::Arc;
use rustc_hash::FxHashMap;

/// A 1D lookup table: clamped linear interpolation over (x, y) breakpoints.
#[derive(Debug, Clone)]
pub struct LookupTable1D {
    /// Bare table name (without the `table.` prefix), used in error messages.
    name: String,
    /// Breakpoints, strictly ascending.
    xs: Vec<f64>,
    /// Values, one per breakpoint.
    ys: Vec<f64>,
    /// Optional text header labels, preserved for serialization.
    header: Option<(String, String)>,
}

/// A 2D lookup table: exact-match column selection, then clamped linear
/// interpolation down the selected column.
#[derive(Debug, Clone)]
pub struct LookupTable2D {
    /// Bare table name (without the `table.` prefix), used in error messages.
    name: String,
    /// The non-numeric corner marker from the key row, preserved for serialization.
    corner: String,
    /// Column keys (top row), strictly ascending. Matched exactly.
    col_keys: Vec<f64>,
    /// Row keys (first column), strictly ascending. Interpolated.
    row_keys: Vec<f64>,
    /// Values stored column-major (`values[c * nrows + r]`) so the
    /// interpolation walk down a selected column is contiguous in memory.
    values: Vec<f64>,
}

/// A parsed, validated lookup table of either dimensionality.
///
/// The `Arc` lives inside the variants so that expression lowering can embed
/// a clone of the concrete table directly in an optimised AST node — the
/// dimensionality is decided once at lowering, never re-dispatched per
/// evaluation. Cloning is cheap (an `Arc` bump).
#[derive(Debug, Clone)]
pub enum LookupTable {
    OneD(Arc<LookupTable1D>),
    TwoD(Arc<LookupTable2D>),
}

/// Clamped linear interpolation over strictly-ascending breakpoints.
///
/// Outside the range of `xs` the endpoint value is returned (no
/// extrapolation). NaN propagates: a NaN `x` yields NaN. A single-breakpoint
/// table is a constant everywhere.
#[inline]
fn clamped_lerp(xs: &[f64], ys: &[f64], x: f64) -> f64 {
    let n = xs.len();
    if n == 1 {
        // Degenerate single-row table: constant everywhere, but NaN still propagates.
        return if x.is_nan() { f64::NAN } else { ys[0] };
    }
    if x <= xs[0] {
        return ys[0];
    }
    if x >= xs[n - 1] {
        return ys[n - 1];
    }
    // First index with xs[i] >= x; the guards above bound i to 1..=n-1 for
    // ordinary x. For NaN every comparison is false and partition_point
    // returns 0, so clamp to 1 and let the arithmetic yield NaN.
    let i = xs.partition_point(|k| *k < x).max(1);
    let (x0, x1) = (xs[i - 1], xs[i]);
    ys[i - 1] + (x - x0) * (ys[i] - ys[i - 1]) / (x1 - x0)
}

impl LookupTable1D {
    /// Interpolate the table at `x`, clamping to the endpoint values outside
    /// the table range.
    #[inline]
    pub fn lookup(&self, x: f64) -> f64 {
        clamped_lerp(&self.xs, &self.ys, x)
    }
}

impl LookupTable2D {
    /// Select the column whose key exactly matches `col_key`, then
    /// interpolate down that column at `row_key` (clamped at the ends).
    ///
    /// Panics with the table name, the offending value, and the available
    /// keys when no column matches: a missed exact match is a structural
    /// modelling error, and a loud failure cannot be laundered into a finite
    /// value downstream the way NaN can (per performance.md §6).
    #[inline]
    pub fn lookup(&self, col_key: f64, row_key: f64) -> f64 {
        let c = self.find_column(col_key);
        let nrows = self.row_keys.len();
        let column = &self.values[c * nrows..(c + 1) * nrows];
        clamped_lerp(&self.row_keys, column, row_key)
    }

    #[inline]
    fn find_column(&self, key: f64) -> usize {
        let i = self.col_keys.partition_point(|k| *k < key);
        if i < self.col_keys.len() && self.col_keys[i] == key {
            return i;
        }
        self.column_miss(key)
    }

    #[cold]
    #[inline(never)]
    fn column_miss(&self, key: f64) -> ! {
        panic!(
            "Lookup table 'table.{}' has no column with key {} (column keys: {:?}). \
             The first argument of a 2D table lookup must exactly match a column key.",
            self.name, key, self.col_keys
        );
    }
}

impl LookupTable {
    /// Parse and validate a table from the joined `values` string of a
    /// `[table.<name>]` section. `n_cols = 2` produces a 1D table, `n_cols > 2`
    /// a 2D table. Cold path: every structural error is rejected here so the
    /// lookup methods never need to.
    pub fn from_ini_data(name: &str, data: &str, ncols: usize) -> Result<LookupTable, String> {
        if ncols < 2 {
            return Err(format!("Table 'table.{}': n_cols must be at least 2, got {}", name, ncols));
        }

        // Tokenize: split on commas, trimming a trailing comma and whitespace
        // (same tolerance as node tables in Table::from_csv_string).
        let trimmed = data.trim_end_matches(|c: char| c == ',' || c.is_whitespace());
        if trimmed.trim().is_empty() {
            return Err(format!("Table 'table.{}': values is empty", name));
        }
        let tokens: Vec<&str> = trimmed.split(',').map(str::trim).collect();

        if ncols == 2 {
            Self::parse_1d(name, &tokens)
        } else {
            Self::parse_2d(name, &tokens, ncols)
        }
    }

    fn parse_1d(name: &str, tokens: &[&str]) -> Result<LookupTable, String> {
        // Optional text header: if the first cell is non-numeric, the first
        // two cells are labels and both must be non-numeric.
        let (header, body) = if parse_cell(tokens[0]).is_none() {
            if tokens.len() < 2 || parse_cell(tokens[1]).is_some() {
                return Err(format!(
                    "Table 'table.{}': a 1D table header must have exactly 2 non-numeric labels",
                    name
                ));
            }
            (Some((tokens[0].to_string(), tokens[1].to_string())), &tokens[2..])
        } else {
            (None, tokens)
        };

        if body.is_empty() {
            return Err(format!("Table 'table.{}': no data rows", name));
        }
        if body.len() % 2 != 0 {
            return Err(format!(
                "Table 'table.{}': a 1D table needs an even number of values (rows of x, y), got {}",
                name,
                body.len()
            ));
        }

        let nrows = body.len() / 2;
        let mut xs = Vec::with_capacity(nrows);
        let mut ys = Vec::with_capacity(nrows);
        for row in 0..nrows {
            xs.push(parse_numeric_cell(name, body[row * 2])?);
            ys.push(parse_numeric_cell(name, body[row * 2 + 1])?);
        }
        assert_strictly_ascending(name, "x values", &xs)?;

        Ok(LookupTable::OneD(Arc::new(LookupTable1D {
            name: name.to_string(),
            xs,
            ys,
            header,
        })))
    }

    fn parse_2d(name: &str, tokens: &[&str], ncols: usize) -> Result<LookupTable, String> {
        // The key row: a non-numeric corner marker, then ncols-1 column keys.
        // Requiring the corner keeps every row ncols wide, so a well-formed
        // table's total element count is an exact multiple of ncols.
        if parse_cell(tokens[0]).is_some() {
            return Err(format!(
                "Table 'table.{}': a 2D table must start with a non-numeric corner marker (e.g. 'x') \
                 followed by its column keys, got '{}'",
                name, tokens[0]
            ));
        }
        if tokens.len() % ncols != 0 {
            return Err(format!(
                "Table 'table.{}': number of values ({}) must be a multiple of n_cols ({})",
                name,
                tokens.len(),
                ncols
            ));
        }
        let nrows = tokens.len() / ncols - 1;
        if nrows < 1 {
            return Err(format!("Table 'table.{}': no data rows after the column-key row", name));
        }

        let corner = tokens[0].to_string();
        let mut col_keys = Vec::with_capacity(ncols - 1);
        for tok in &tokens[1..ncols] {
            col_keys.push(parse_numeric_cell(name, tok)?);
        }
        assert_strictly_ascending(name, "column keys", &col_keys)?;

        let mut row_keys = Vec::with_capacity(nrows);
        let mut values = vec![0.0; nrows * (ncols - 1)];
        for r in 0..nrows {
            let row = &tokens[(r + 1) * ncols..(r + 2) * ncols];
            row_keys.push(parse_numeric_cell(name, row[0])?);
            for c in 0..ncols - 1 {
                // Column-major so a selected column is contiguous at lookup time.
                values[c * nrows + r] = parse_numeric_cell(name, row[c + 1])?;
            }
        }
        assert_strictly_ascending(name, "row keys", &row_keys)?;

        Ok(LookupTable::TwoD(Arc::new(LookupTable2D {
            name: name.to_string(),
            corner,
            col_keys,
            row_keys,
            values,
        })))
    }

    /// Bare table name (without the `table.` prefix).
    pub fn name(&self) -> &str {
        match self {
            LookupTable::OneD(t) => &t.name,
            LookupTable::TwoD(t) => &t.name,
        }
    }

    /// Grid width: 2 for 1D tables, row-key column included for 2D.
    pub fn ncols(&self) -> usize {
        match self {
            LookupTable::OneD(_) => 2,
            LookupTable::TwoD(t) => t.col_keys.len() + 1,
        }
    }

    /// Number of expression-call arguments this table takes: 1 for 1D, 2 for 2D.
    pub fn arity(&self) -> usize {
        match self {
            LookupTable::OneD(_) => 1,
            LookupTable::TwoD(_) => 2,
        }
    }

    /// Render the `values` property canonically: one grid row per continuation
    /// line, indented by `n_spaces`, in the same style as
    /// `format_vec_as_multiline_table` used for node tables.
    pub fn format_data(&self, n_spaces: usize) -> String {
        let indent = " ".repeat(n_spaces);
        let mut lines: Vec<String> = Vec::new();
        match self {
            LookupTable::OneD(t) => {
                if let Some((a, b)) = &t.header {
                    lines.push(format!("{}, {},", a, b));
                }
                for (x, y) in t.xs.iter().zip(t.ys.iter()) {
                    lines.push(format!("{}, {},", x, y));
                }
            }
            LookupTable::TwoD(t) => {
                let key_row: Vec<String> = std::iter::once(t.corner.clone())
                    .chain(t.col_keys.iter().map(|k| k.to_string()))
                    .collect();
                lines.push(format!("{},", key_row.join(", ")));
                let nrows = t.row_keys.len();
                for r in 0..nrows {
                    let row: Vec<String> = std::iter::once(t.row_keys[r].to_string())
                        .chain((0..t.col_keys.len()).map(|c| t.values[c * nrows + r].to_string()))
                        .collect();
                    lines.push(format!("{},", row.join(", ")));
                }
            }
        }
        lines.join(&format!("\n{}", indent))
    }
}

/// Parse a cell as a finite number. Returns None for non-numeric text and for
/// NaN/inf spellings: table cells must be finite, and a token like "nan" in a
/// key row must read as text (a corner marker), never as a numeric key.
fn parse_cell(token: &str) -> Option<f64> {
    token.parse::<f64>().ok().filter(|v| v.is_finite())
}

fn parse_numeric_cell(name: &str, token: &str) -> Result<f64, String> {
    parse_cell(token)
        .ok_or_else(|| format!("Table 'table.{}': could not parse '{}' as a finite number", name, token))
}

fn assert_strictly_ascending(name: &str, what: &str, values: &[f64]) -> Result<(), String> {
    for i in 1..values.len() {
        if values[i] <= values[i - 1] {
            return Err(format!(
                "Table 'table.{}': {} must be strictly ascending, but {} follows {}",
                name,
                what,
                values[i],
                values[i - 1]
            ));
        }
    }
    Ok(())
}

/// The model's named lookup tables, keyed by bare table name (lowercase, no
/// `table.` prefix). Tables are `Arc`-shared: expressions that reference a
/// table hold a clone of the `Arc`, so the registry is only consulted at
/// model load (cold path).
#[derive(Debug, Clone, Default)]
pub struct TableRegistry {
    tables: FxHashMap<String, LookupTable>,
}

impl TableRegistry {
    /// Register a table. Errors on a duplicate name.
    pub fn insert(&mut self, table: LookupTable) -> Result<(), String> {
        let name = table.name().to_string();
        if self.tables.contains_key(&name) {
            return Err(format!("Duplicate table definition 'table.{}'", name));
        }
        self.tables.insert(name, table);
        Ok(())
    }

    /// Look up a table by bare name (lowercase, no `table.` prefix).
    pub fn get(&self, name: &str) -> Option<&LookupTable> {
        self.tables.get(name)
    }

    pub fn is_empty(&self) -> bool {
        self.tables.is_empty()
    }

    /// Tables sorted by name, for deterministic canonical serialization.
    pub fn iter_sorted(&self) -> Vec<(&String, &LookupTable)> {
        let mut entries: Vec<_> = self.tables.iter().collect();
        entries.sort_by(|a, b| a.0.cmp(b.0));
        entries
    }
}
