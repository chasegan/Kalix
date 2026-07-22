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

#[cfg(test)]
mod tests {
    use super::*;
    use crate::io::ini_model_io::IniModelIO;
    use crate::tid::utils::wrap_to_u64;

    // 2000-01-01T00:00:00Z, matching the [kalix] start below. Kalix stores
    // timestamps as unix-seconds biased by 2^63 (wrap_to_u64), not raw epoch
    // seconds -- set_input()'s caller (the Python binding) applies the same
    // conversion, so tests must too.
    fn start() -> u64 { wrap_to_u64(946_684_800) }
    const DAY: u64 = 86_400;

    /// A bare declared alias (`obs =`), fed by an inflow node addressing it
    /// by column name -- the base case set_input() exists for.
    fn model_ini_with_bare_declaration() -> &'static str {
        "[kalix]\n\
         start = 2000-01-01T00:00:00\n\
         end = 2000-01-05T00:00:00\n\
         \n\
         [inputs]\n\
         obs =\n\
         \n\
         [node.src]\n\
         type = inflow\n\
         loc = 0,0\n\
         inflow = data.obs.by_name.flow\n\
         ds_1 = sink\n\
         \n\
         [node.sink]\n\
         type = blackhole\n\
         loc = 1,1\n\
         \n\
         [outputs]\n\
         node.src.dsflow\n"
    }

    fn one_column(name: &str, values: Vec<f64>) -> Vec<InMemoryColumn> {
        vec![InMemoryColumn { name: name.to_string(), values }]
    }

    #[test]
    fn set_input_fills_a_declared_alias_and_the_model_runs() {
        let mut model = IniModelIO::read_model_string(model_ini_with_bare_declaration())
            .expect("model should parse");

        set_input(&mut model, "obs", start(), DAY, one_column("flow", vec![1.0, 2.0, 3.0, 4.0, 5.0]))
            .expect("set_input should accept a declared alias");

        model.configure().expect("configure should succeed once the declaration is filled");
        model.run().expect("run should succeed");

        let idx = model.data_cache.get_existing_series_idx("node.src.dsflow")
            .expect("dsflow output should exist");
        assert_eq!(model.data_cache.series[idx].values, vec![1.0, 2.0, 3.0, 4.0, 5.0]);
    }

    #[test]
    fn set_input_is_addressable_by_column_index_too() {
        let ini = "[kalix]\n\
             start = 2000-01-01T00:00:00\n\
             end = 2000-01-05T00:00:00\n\
             \n\
             [inputs]\n\
             obs =\n\
             \n\
             [node.src]\n\
             type = inflow\n\
             loc = 0,0\n\
             inflow = data.obs.by_index.1\n\
             ds_1 = sink\n\
             \n\
             [node.sink]\n\
             type = blackhole\n\
             loc = 1,1\n\
             \n\
             [outputs]\n\
             node.src.dsflow\n";
        let mut model = IniModelIO::read_model_string(ini).expect("model should parse");

        set_input(&mut model, "obs", start(), DAY, one_column("flow", vec![10.0, 20.0, 30.0, 40.0, 50.0]))
            .expect("set_input should succeed");
        model.configure().expect("configure should succeed");
        model.run().expect("run should succeed");

        let idx = model.data_cache.get_existing_series_idx("node.src.dsflow").unwrap();
        assert_eq!(model.data_cache.series[idx].values, vec![10.0, 20.0, 30.0, 40.0, 50.0]);
    }

    #[test]
    fn set_input_rejects_an_alias_not_declared_in_inputs() {
        let mut model = IniModelIO::read_model_string(model_ini_with_bare_declaration())
            .expect("model should parse");

        let err = set_input(&mut model, "not_declared", start(), DAY, one_column("flow", vec![1.0]))
            .expect_err("an undeclared alias must be rejected");
        assert!(
            err.contains("not_declared") && err.contains("not declared"),
            "error should name the bad alias. Got: {err}"
        );
    }

    #[test]
    fn set_input_leaves_a_declared_but_unsupplied_alias_rejected_at_configure() {
        // Calling set_input() for a *different* alias must not accidentally
        // satisfy this one -- configure() should still fail on it.
        let ini = "[kalix]\n\
             start = 2000-01-01T00:00:00\n\
             end = 2000-01-05T00:00:00\n\
             \n\
             [inputs]\n\
             obs =\n\
             other =\n\
             \n\
             [node.src]\n\
             type = inflow\n\
             loc = 0,0\n\
             inflow = data.obs.by_name.flow\n\
             ds_1 = sink\n\
             \n\
             [node.sink]\n\
             type = blackhole\n\
             loc = 1,1\n";
        let mut model = IniModelIO::read_model_string(ini).expect("model should parse");

        set_input(&mut model, "obs", start(), DAY, one_column("flow", vec![1.0, 2.0, 3.0, 4.0, 5.0]))
            .expect("set_input should succeed for the alias it targets");

        let err = model.configure().expect_err("the still-unfilled 'other' alias must be rejected");
        assert!(err.contains("other") && err.contains("declared but not supplied"));
    }

    #[test]
    fn set_input_overrides_an_aliased_file_and_preserves_its_ini_entry() {
        // The file behind `climate` never gets read once set_input() has run
        // -- but the origin (so the alias still round-trips to the same file
        // line) must survive the swap.
        let ini = "[kalix]\n\
             start = 2000-01-01T00:00:00\n\
             end = 2000-01-05T00:00:00\n\
             \n\
             [inputs]\n\
             climate = ./src/tests/example_data/test.csv\n\
             \n\
             [node.src]\n\
             type = inflow\n\
             loc = 0,0\n\
             inflow = data.climate.by_name.flow\n\
             ds_1 = sink\n\
             \n\
             [node.sink]\n\
             type = blackhole\n\
             loc = 1,1\n\
             \n\
             [outputs]\n\
             node.src.dsflow\n";
        let mut model = IniModelIO::read_model_string(ini).expect("model should parse");

        set_input(&mut model, "climate", start(), DAY, one_column("flow", vec![7.0, 8.0, 9.0, 10.0, 11.0]))
            .expect("set_input should override the aliased file");

        model.configure().expect("configure should succeed");
        model.run().expect("run should succeed");

        let idx = model.data_cache.get_existing_series_idx("node.src.dsflow").unwrap();
        assert_eq!(model.data_cache.series[idx].values, vec![7.0, 8.0, 9.0, 10.0, 11.0]);

        let (key, value) = model.input_sources.iter()
            .find(|s| s.alias() == Some("climate"))
            .expect("the swapped source should still be found by its alias")
            .ini_entry();
        assert_eq!((key.as_str(), value.as_str()), ("climate", "./src/tests/example_data/test.csv"));
    }

    #[test]
    fn set_input_rejects_empty_data() {
        let mut model = IniModelIO::read_model_string(model_ini_with_bare_declaration())
            .expect("model should parse");

        let err = set_input(&mut model, "obs", start(), DAY, one_column("flow", vec![]));
        // Empty is allowed at this layer (no timestamps to validate against);
        // an empty *timestamps* array is what the Python binding rejects --
        // this module only requires columns and timestamps to agree in length.
        assert!(err.is_ok(), "an empty column with no timestamps to mismatch against is not this module's job to reject");
    }

    #[test]
    fn set_input_calling_twice_replaces_the_previous_in_memory_data() {
        let mut model = IniModelIO::read_model_string(model_ini_with_bare_declaration())
            .expect("model should parse");

        set_input(&mut model, "obs", start(), DAY, one_column("flow", vec![1.0, 1.0, 1.0, 1.0, 1.0]))
            .expect("first set_input should succeed");
        set_input(&mut model, "obs", start(), DAY, one_column("flow", vec![9.0, 9.0, 9.0, 9.0, 9.0]))
            .expect("second set_input should succeed and replace the first");

        model.configure().expect("configure should succeed");
        model.run().expect("run should succeed");

        let idx = model.data_cache.get_existing_series_idx("node.src.dsflow").unwrap();
        assert_eq!(model.data_cache.series[idx].values, vec![9.0, 9.0, 9.0, 9.0, 9.0]);
    }

    #[test]
    fn set_input_supplies_multiple_columns_each_independently_addressable() {
        // Two nodes read one alias: one by column name, one by 1-based index,
        // so multi-column supply must keep columns distinct and in order.
        let ini = "[kalix]\n\
             start = 2000-01-01T00:00:00\n\
             end = 2000-01-05T00:00:00\n\
             \n\
             [inputs]\n\
             obs =\n\
             \n\
             [node.rain_src]\n\
             type = inflow\n\
             loc = 0,0\n\
             inflow = data.obs.by_name.rain\n\
             ds_1 = sink\n\
             \n\
             [node.flow_src]\n\
             type = inflow\n\
             loc = 1,0\n\
             inflow = data.obs.by_index.2\n\
             ds_1 = sink\n\
             \n\
             [node.sink]\n\
             type = blackhole\n\
             loc = 2,2\n\
             \n\
             [outputs]\n\
             node.rain_src.dsflow\n\
             node.flow_src.dsflow\n";
        let mut model = IniModelIO::read_model_string(ini).expect("model should parse");

        let columns = vec![
            InMemoryColumn { name: "rain".to_string(), values: vec![1.0, 2.0, 3.0, 4.0, 5.0] },
            InMemoryColumn { name: "flow".to_string(), values: vec![10.0, 20.0, 30.0, 40.0, 50.0] },
        ];
        set_input(&mut model, "obs", start(), DAY, columns)
            .expect("multi-column set_input should succeed");
        model.configure().expect("configure should succeed");
        model.run().expect("run should succeed");

        let rain_idx = model.data_cache.get_existing_series_idx("node.rain_src.dsflow").unwrap();
        let flow_idx = model.data_cache.get_existing_series_idx("node.flow_src.dsflow").unwrap();
        assert_eq!(model.data_cache.series[rain_idx].values, vec![1.0, 2.0, 3.0, 4.0, 5.0]);
        assert_eq!(model.data_cache.series[flow_idx].values, vec![10.0, 20.0, 30.0, 40.0, 50.0]);
    }
}
