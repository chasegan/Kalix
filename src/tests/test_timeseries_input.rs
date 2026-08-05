#[test]
fn test_load() {
    use crate::timeseries_input::TimeseriesInput;
    let vts = match TimeseriesInput::load("./src/tests/example_models/1/rex_mpot.csv", None) {
        Ok(v) => v,
        Err(e) => panic!("{}", e),
    };

    //Print
    println!("vts[0].timeseries.len() = {}", vts[0].timeseries.len());
    println!("vts[0].len() = {}", vts[0].len());

    // Assert
    let sum = vts[0].timeseries.sum();
    assert!((sum - 251302.61119047567).abs() < 0.00001);
}


#[test]
fn test_load_2() {
    use crate::timeseries_input::TimeseriesInput;
    let vts = match TimeseriesInput::load("./src/tests/example_models/1/rex_rain.csv", None) {
        Ok(v) => v,
        Err(e) => panic!("{}", e),
    };

    //Print
    println!("vts[0].timeseries.len() = {}", vts[0].timeseries.len());
    println!("vts[0].len() = {}", vts[0].len());

    // Assert
    let sum = vts[0].timeseries.sum();
    println!("sum = {}", sum);
    assert!((sum - 310683.1999999939).abs() < 0.00001);
}

#[test]
fn test_name_sanitization() {
    use crate::timeseries_input::TimeseriesInput;

    // Load a CSV file
    let vts = match TimeseriesInput::load("./src/tests/example_models/1/rex_rain.csv", None) {
        Ok(v) => v,
        Err(e) => panic!("{}", e),
    };

    // Check that filename is sanitized (dots converted to underscores)
    // "rex_rain.csv" should become "rex_rain_csv"
    assert_eq!(vts[0].source_name, "rex_rain_csv");

    // Check that paths are constructed correctly with sanitization
    // The paths should only contain lowercase a-z, 0-9, underscores, and dots (as delimiters)
    assert!(vts[0].full_colname_path.chars().all(|c|
        c.is_ascii_lowercase() || c.is_ascii_digit() || c == '_' || c == '.'
    ));
    assert!(vts[0].full_colindex_path.chars().all(|c|
        c.is_ascii_lowercase() || c.is_ascii_digit() || c == '_' || c == '.'
    ));

    // Print for debugging
    println!("source_name: {}", vts[0].source_name);
    println!("full_colname_path: {}", vts[0].full_colname_path);
    println!("full_colindex_path: {}", vts[0].full_colindex_path);
}

#[test]
fn test_timeseries_input_with_alias() {
    use crate::timeseries_input::TimeseriesInput;

    // Load a file with an alias
    let inputs = TimeseriesInput::load("./src/tests/example_data/test.csv", Some("mydata"))
        .expect("Failed to load with alias");

    assert_eq!(inputs.len(), 1);
    let input = &inputs[0];

    // Check that alias is set
    assert_eq!(input.alias, Some("mydata".to_string()));

    // Check auto-generated paths still exist
    assert_eq!(input.full_colname_path, "data.test_csv.by_name.value");
    assert_eq!(input.full_colindex_path, "data.test_csv.by_index.1");

    // Check alias paths are generated
    assert_eq!(
        input.alias_colname_path,
        Some("data.mydata.by_name.value".to_string())
    );
    assert_eq!(
        input.alias_colindex_path,
        Some("data.mydata.by_index.1".to_string())
    );
}

// ---- Pixie input sources ----
//
// The fixtures are built at run time by round-tripping an existing CSV through
// the Pixie writer, so the CSV and Pixie paths are fed byte-identical data and
// any divergence is the reader's doing, not the fixture's. 64-bit precision is
// used deliberately: the Pixie default is f32 (see write_series), which would
// blur an exact-equality assertion into an approximate one and hide small
// reader bugs.

/// Write `src/tests/example_data/test.csv` out as a Pixie pair in a unique temp
/// directory. Returns the base path (no extension); the caller adds `.pxt` or
/// `.pxb` to name whichever sibling it wants to exercise.
#[cfg(test)]
fn make_pixie_fixture(test_name: &str) -> std::path::PathBuf {
    use crate::io::{csv_io, pixie_io};

    let dir = std::env::temp_dir()
        .join("kalix_tests")
        .join(format!("{}_{}", test_name, uuid::Uuid::new_v4()));
    std::fs::create_dir_all(&dir).unwrap();

    let series = csv_io::read_ts("./src/tests/example_data/test.csv").expect("read source csv");
    let base_path = dir.join("test");
    let refs: Vec<&crate::timeseries::Timeseries> = series.iter().collect();
    pixie_io::write_series_with_precision(base_path.to_str().unwrap(), &refs, true)
        .expect("write pixie fixture");

    base_path
}

#[cfg(test)]
fn cleanup_fixture(base_path: &std::path::Path) {
    let _ = std::fs::remove_dir_all(base_path.parent().unwrap());
}

/// A Pixie source is named by its `.pxt` half, and the source name is just the
/// sanitized file name — no canonicalisation, because only one spelling is
/// accepted in the first place.
#[test]
fn test_pixie_source_is_named_by_its_pxt() {
    use crate::timeseries_input::TimeseriesInput;

    let base_path = make_pixie_fixture("pixie_source_name");
    let inputs = TimeseriesInput::load(&format!("{}.pxt", base_path.display()), None)
        .expect("load via .pxt");

    assert_eq!(inputs[0].source_name, "test_pxt");
    assert_eq!(inputs[0].full_colname_path, "data.test_pxt.by_name.value");

    cleanup_fixture(&base_path);
}

/// Naming the `.pxb` half is a plausible mistake -- it's the file holding the
/// values -- so it must be turned away with the `.pxt` name to use instead,
/// not accepted as a second spelling of the same source.
#[test]
fn test_pixie_pxb_is_rejected_with_guidance() {
    use crate::timeseries_input::TimeseriesInput;

    let base_path = make_pixie_fixture("pixie_pxb_rejected");

    // TimeseriesInput isn't Debug, so unwrap the Result by hand.
    let msg = match TimeseriesInput::load(&format!("{}.pxb", base_path.display()), None) {
        Ok(_) => panic!("should refuse the .pxb half"),
        Err(e) => e.to_string(),
    };
    assert!(
        msg.contains("test.pxt"),
        "error should name the .pxt to use instead, got: {}",
        msg
    );

    cleanup_fixture(&base_path);
}

/// A Pixie source must read back exactly what the equivalent CSV does --
/// same column paths (bar the source name), same values.
#[test]
fn test_pixie_matches_csv_source() {
    use crate::timeseries_input::TimeseriesInput;

    let base_path = make_pixie_fixture("pixie_vs_csv");
    let from_pixie = TimeseriesInput::load(&format!("{}.pxt", base_path.display()), None)
        .expect("load pixie");
    let from_csv =
        TimeseriesInput::load("./src/tests/example_data/test.csv", None).expect("load csv");

    assert_eq!(from_pixie.len(), from_csv.len());
    assert_eq!(from_pixie[0].col_name, from_csv[0].col_name);
    assert_eq!(from_pixie[0].full_colname_path, "data.test_pxt.by_name.value");
    assert_eq!(from_csv[0].full_colname_path, "data.test_csv.by_name.value");
    assert_eq!(
        from_pixie[0].timeseries.values,
        from_csv[0].timeseries.values
    );
    assert_eq!(
        from_pixie[0].timeseries.start_timestamp,
        from_csv[0].timeseries.start_timestamp
    );
    assert_eq!(
        from_pixie[0].timeseries.step_size,
        from_csv[0].timeseries.step_size
    );

    cleanup_fixture(&base_path);
}

/// Naming one sibling when the other is absent is the likeliest user error the
/// pair convention creates, so it must name the missing file rather than
/// surfacing a bare "no such file" about a path the user never wrote.
#[test]
fn test_pixie_missing_companion_is_reported() {
    use crate::timeseries_input::TimeseriesInput;

    let base_path = make_pixie_fixture("pixie_missing_companion");
    std::fs::remove_file(format!("{}.pxb", base_path.display())).expect("remove companion");

    // TimeseriesInput isn't Debug, so unwrap the Result by hand rather than
    // reaching for expect_err.
    let msg = match TimeseriesInput::load(&format!("{}.pxt", base_path.display()), None) {
        Ok(_) => panic!("should refuse a half-present pair"),
        Err(e) => e.to_string(),
    };
    assert!(
        msg.contains("companion") && msg.contains(".pxb"),
        "error should name the missing companion, got: {}",
        msg
    );

    cleanup_fixture(&base_path);
}

/// Create a model and load data with an alias
#[test]
fn test_model_with_aliased_data() {
    use crate::model::Model;
    use crate::model_inputs::DynamicInput;
    use crate::nodes::inflow_node::InflowNode;
    use crate::nodes::NodeEnum;
    
    let mut m = Model::new();
    m.load_input_data("./src/tests/example_data/test.csv", Some("flow_data"))
        .expect("Failed to load input data with alias");

    // Create an inflow node using the aliased reference
    let mut n = InflowNode::new();
    n.name = "my_inflow".to_string();
    n.inflow_input = DynamicInput::from_string(
        "data.flow_data.by_name.value",
        &mut m.data_cache,
        true,
        None,
    )
    .expect("Failed to parse aliased reference");
    m.add_node(NodeEnum::InflowNode(n));

    // Specify outputs
    m.outputs.push("node.my_inflow.dsflow".to_string());

    // Configure and run
    m.configure().expect("Configuration error");
    m.run().expect("Simulation error");

    // Check results
    let ds_idx = m
        .data_cache
        .get_series_idx("node.my_inflow.dsflow", false)
        .unwrap();
    let ans = m.data_cache.series[ds_idx].clone();
    assert_eq!(ans.len(), 6);
    assert_eq!(ans.sum(), 38.1);
}

/// The same model as `test_model_with_aliased_data`, fed from a Pixie pair
/// instead of a CSV -- an aliased Pixie source must run and produce identical
/// results, through `configure()`'s step-size validation and all.
#[test]
fn test_model_with_pixie_data() {
    use crate::model::Model;
    use crate::model_inputs::DynamicInput;
    use crate::nodes::inflow_node::InflowNode;
    use crate::nodes::NodeEnum;

    let base_path = make_pixie_fixture("pixie_model_run");

    let mut m = Model::new();
    m.load_input_data(&format!("{}.pxt", base_path.display()), Some("flow_data"))
        .expect("Failed to load Pixie input data with alias");

    let mut n = InflowNode::new();
    n.name = "my_inflow".to_string();
    n.inflow_input = DynamicInput::from_string(
        "data.flow_data.by_name.value",
        &mut m.data_cache,
        true,
        None,
    )
    .expect("Failed to parse aliased reference");
    m.add_node(NodeEnum::InflowNode(n));

    m.outputs.push("node.my_inflow.dsflow".to_string());

    m.configure().expect("Configuration error");
    m.run().expect("Simulation error");

    let ds_idx = m
        .data_cache
        .get_series_idx("node.my_inflow.dsflow", false)
        .unwrap();
    let ans = m.data_cache.series[ds_idx].clone();
    assert_eq!(ans.len(), 6);
    assert_eq!(ans.sum(), 38.1);

    cleanup_fixture(&base_path);
}
