//! Hotswaps in-memory runtime data in for a Model's **declared** inputs, never
//! reading or mutating its `IniDocument` (the INI DOM).

use crate::misc::misc_functions::sanitize_name;
use crate::model::Model;
use crate::timeseries::Timeseries;
use crate::timeseries_input::{SourceOrigin, TimeseriesInput, TimeseriesInputDefinition};

/// One named column of in-memory data, sharing the `start_timestamp`/`step_size`
/// passed alongside it. The Python layer is responsible for pulling this out of
/// a `pd.DataFrame`/`pd.Series` (column names, float64 values) — this module
/// never sees a DataFrame.
pub struct InMemoryColumn {
    pub name: String,
    pub values: Vec<f64>,
}

/// Supply in-memory data for a declared `[inputs]` alias (`set_input()`).
///
/// `alias` must already name a source in `[inputs]` — either a bare
/// declaration (`observed_flows =`) or an aliased file (`climate_data =
/// climate.csv`, still overridable: supplied data takes precedence over the
/// file). An alias that isn't declared is rejected rather than silently
/// creating an unused dataset — the model definition stays the single source
/// of truth for what it consumes.
pub fn set_input(
    model: &mut Model,
    alias: &str,
    start_timestamp: u64,
    step_size: u64,
    columns: Vec<InMemoryColumn>,
) -> Result<(), String> {
    let alias_sanitized = sanitize_name(alias);

    let source_idx = model
        .input_sources
        .iter()
        .position(|s| s.alias().map(sanitize_name).as_deref() == Some(alias_sanitized.as_str()))
        .ok_or_else(|| format!(
            "input '{alias}' is not declared in [inputs] -- set_input() supplies values \
             for an existing declaration, it does not add one (declare it first, e.g. via patch)"
        ))?;

    // Preserve how this source re-declares itself in the INI so a save/
    // to_string() round trip keeps naming the file or bare alias it stands in
    // for, exactly as it did before the data was supplied.
    let origin = match &model.input_sources[source_idx] {
        TimeseriesInputDefinition::Declaration { alias } => SourceOrigin::Alias(alias.clone()),
        TimeseriesInputDefinition::FileDefinition { origin, .. } => origin.clone(),
        TimeseriesInputDefinition::InMemoryDefinition { origin, .. } => origin.clone(),
    };

    let ts_columns = columns
        .into_iter()
        .enumerate()
        .map(|(i, col)| build_column(&alias_sanitized, i + 1, col, start_timestamp, step_size))
        .collect();

    model.input_sources[source_idx] = TimeseriesInputDefinition::InMemoryDefinition {
        origin,
        columns: ts_columns,
    };
    Ok(())
}

/// Build one column's `TimeseriesInput`, addressed as `data.<alias>.*` —
/// exactly as if a file had been loaded under that alias (there is no
/// separate source-name identity to also address it by, since there is no
/// file).
fn build_column(
    alias_sanitized: &str,
    col_index: usize,
    col: InMemoryColumn,
    start_timestamp: u64,
    step_size: u64,
) -> TimeseriesInput {
    let col_name_sanitized = sanitize_name(&col.name);

    let mut timeseries = Timeseries::new(step_size);
    timeseries.name = col.name.clone();
    timeseries.start_timestamp = start_timestamp;
    timeseries.values = col.values;

    TimeseriesInput {
        source_path: String::new(),
        source_name: alias_sanitized.to_string(),
        alias: Some(alias_sanitized.to_string()),
        col_index,
        col_name: col.name,
        full_colname_path: format!("data.{}.by_name.{}", alias_sanitized, col_name_sanitized),
        full_colindex_path: format!("data.{}.by_index.{}", alias_sanitized, col_index),
        alias_colindex_path: None,
        alias_colname_path: None,
        timeseries,
        reload_on_run: false,
    }
}
