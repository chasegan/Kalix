use crate::io::ini_model_io::IniModelIO;
use crate::model::Model;
use crate::nodes::Node;
use crate::tests::test_helpers::print_text_diff;

#[test]
fn test_model_1_io_ini_read() {
    let ini_reader = IniModelIO::new();

    //Read the model
    let model_filename = "./src/tests/example_models/1/first_model.ini";
    println!("model_file = {}", model_filename);
    let mut m= match  ini_reader.read_model_file(model_filename) {
        Ok(v) => {
            println!("Model read okay from file.");
            println!("number of inputs = {}", v.inputs.len());
            v.print_inputs();
            println!("inputs[0]:");
            v.inputs[0].print();
            v
        },
        Err(s) => {
            panic!("Model not read due to error: {}", s);
        }
    };

    m.configure().expect("Configuration error");
    println!("Config: {:?}", m.configuration);

    m.run().expect("Simulation error");
    println!("Done!");

    //Check the number of nodes
    assert_eq!(m.nodes.len(), 1);

    //Outputs
    for output_name in &m.outputs {
        let idx = m.data_cache.get_existing_series_idx(output_name).unwrap();
        let ts = m.data_cache.series[idx].clone();
        println!("output: {}, idx: {}, len:{}", output_name, idx, ts.len());
    }

    // Writing outputs
    let output_filename = "./src/tests/example_models/1/outputs.csv";
    let _output_write_result = m.write_outputs(output_filename);

    // Printing data cache
    println!(" ");
    println!("Printing the data cache");
    m.data_cache.print();
    println!(" ");
    for temp in &m.data_cache.series {
         temp.print();
    }
}

#[test]
fn test_model_2_io_ini_read() {
    let ini_reader = IniModelIO::new();
    let mut m = ini_reader.read_model_file("./src/tests/example_models/2/model.ini").unwrap();
    m.configure().expect("Configuration error");
    m.run().expect("Simulation error");

    //Check the number of nodes
    assert_eq!(m.nodes.len(), 1);

    //Check the results
    let ds_idx = m.data_cache.get_series_idx("node.my_inflow_node.dsflow", false).unwrap();
    let ans = &m.data_cache.series[ds_idx];
    assert_eq!(ans.len(), 6);
    assert_eq!(ans.sum(), 38.1);
    println!("Timestamps: {:?}", ans.timestamps);

    //Write the results
    m.write_outputs("./src/tests/example_models/2/output.csv").expect("Csv write failed");
}

#[test]
fn test_model_3_io_ini_read() {
    let ini_reader = IniModelIO::new();

    //Read the model
    let model_filename = "./src/tests/example_models/3/model_3.ini";
    println!("model_file = {}", model_filename);
    let mut m= match  ini_reader.read_model_file(model_filename) {
        Ok(v) => {
            println!("Model read okay from file.");
            println!("number of inputs = {}", v.inputs.len());
            v.print_inputs();
            println!("inputs[0]:");
            v.inputs[0].print();
            v
        },
        Err(s) => {
            panic!("Model not read due to error: {}", s);
        }
    };

    m.configure().expect("Configuration error");
    println!("Config: {:?}", m.configuration);

    m.run().expect("Simulation error");
    println!("Done!");

    //Check the number of nodes
    assert_eq!(m.nodes.len(), 2);

    //Outputs
    for output_name in &m.outputs {
        let idx = m.data_cache.get_existing_series_idx(output_name).unwrap();
        let ts = m.data_cache.series[idx].clone();
        println!("output: {}, idx: {}, len:{}", output_name, idx, ts.len());
    }

    //
    let output_filename = "./src/tests/example_models/3/outputs.csv";
    let _ = m.write_outputs(output_filename);
}



#[test]
fn test_model_3_minimal_version() {

    fn run_model(model_filename: &str, output_filename: &str) -> Result<(), String> {
        let ini_reader = IniModelIO::new();
        let mut m = ini_reader.read_model_file(model_filename)?;
        m.configure()?;
        m.run()?;
        m.write_outputs(output_filename)?;
        Ok(())
    }

    let r = run_model("./src/tests/example_models/3/model_3.ini",
                      "./src/tests/example_models/3/outputs.csv");
    assert!(r.is_ok());
}



#[test]
fn test_model_4() {

    fn run_model(model_filename: &str, output_filename: &str) -> Result<Model, String> {
        let ini_reader = IniModelIO::new();
        let mut m = ini_reader.read_model_file(model_filename)?;
        m.configure()?;
        m.run()?;
        m.write_outputs(output_filename)?;
        Ok(m)
    }

    let result_model = run_model("./src/tests/example_models/4/linked_model.ini",
                      "./src/tests/example_models/4/outputs.csv");

    match result_model {
        Err(s) => {
            println!("Model not read due to error: {}", s);
            assert!(false);
        },
        Ok(model) => {

            // println!("Execution order: {:?}", model.execution_order);
            // for i in 0..model.execution_order.len() {
            //     let node_idx = model.execution_order[i];
            //     println!("Execution order[{}]: {:?} {}", i, node_idx, model.nodes[node_idx].get_name());
            // }

            let sim_len = model.configuration.sim_nsteps;

            let node3_dsflow = model.data_cache.series[model.data_cache.get_existing_series_idx("node.node3.dsflow").unwrap()].clone();
            //println!("node3_dsflow: {}", node3_dsflow.mean());

            assert!((node3_dsflow.mean() - 300.0).abs() < 1e-12);
            assert_eq!(sim_len, 62);
        }
    };
}

#[test]
fn test_model_with_every_node_type_save() {
    let ini_io = IniModelIO::new();

    // Load the model
    let model_filename = "./src/tests/example_models/6/model_with_every_node_type.ini";
    println!("Loading model from: {}", model_filename);
    let mut m = ini_io.read_model_file(model_filename)
        .expect("Failed to load model");

    println!("Model loaded successfully");
    println!("Number of nodes: {}", m.nodes.len());

    // Configure the model
    m.configure().expect("Failed to configure model");
    println!("Model configured successfully");

    // Run the model
    m.run().expect("Failed to run model");
    println!("Model ran successfully");

    // Save the model to a new file
    let output_filename = "./src/tests/example_models/6/model_with_every_node_type_saved.ini";
    let ini_string = ini_io.model_to_string(&m);

    std::fs::write(output_filename, ini_string)
        .expect("Failed to write model file");

    println!("Model saved to: {}", output_filename);
}

#[test]
fn test_routing_nlm_does_not_emit_pwl() {
    // A routing node defined with NLM (k, m) must serialise to `nlm` only — never
    // a stray `pwl`, since the two are mutually exclusive. We mutate the node so
    // its section re-canonicalises through the public save path (an unchanged node
    // would just be preserved verbatim).
    let ini = "[kalix]\n\
               \n\
               [node.r]\n\
               type = routing\n\
               loc = 29, 765\n\
               lag = 1\n\
               nlm = 2.0, 0.8\n\
               ds_1 = bh\n\
               \n\
               [node.bh]\n\
               type = blackhole\n\
               loc = 1, 2\n";

    let ini_io = IniModelIO::new();
    let mut model = ini_io.read_model_string(ini).expect("model should parse");

    // Force the routing section to re-render canonically.
    for node in &mut model.nodes {
        if let crate::nodes::NodeEnum::RoutingNode(n) = node {
            n.set_lag(2);
        }
    }

    let out = ini_io.model_to_string(&model);
    assert!(out.contains("nlm = 2, 0.8"), "expected canonical nlm line, got:\n{}", out);
    assert!(!out.contains("pwl"), "must not emit a pwl line for an NLM node, got:\n{}", out);
}

#[test]
fn test_baseline_canonical_captured_at_load() {
    // Phase 1 of the formatting-preserving saver: loading a model must capture a
    // canonical render of the model as-loaded, holding canonical (writer-formatted)
    // values rather than the raw source text. e.g. "30.000" -> "30".
    let ini = "[kalix]\n\
               \n\
               [node.g]\n\
               type = gr4j\n\
               loc = 10, 20\n\
               area = 30.000\n\
               params = 350.0, 0.0, 90.0, 1.7\n\
               ds_1 = bh\n\
               \n\
               [node.bh]\n\
               type = blackhole\n\
               loc = 1, 2\n";

    let ini_io = IniModelIO::new();
    let model = ini_io.read_model_string(ini).expect("model should parse");

    let baseline = model.baseline_canonical.as_ref()
        .expect("baseline canonical should be captured at load");
    assert_eq!(baseline.get_property("node.g", "area"), Some("30"));
    assert_eq!(baseline.get_property("node.g", "params"), Some("350, 0, 90, 1.7"));
    assert_eq!(baseline.get_property("node.g", "loc"), Some("10, 20"));
}

#[test]
fn test_save_noop_is_byte_identical() {
    // Phase 2: loading then saving with NO change must reproduce the source
    // byte-for-byte, including original number formatting, spacing, comments,
    // continuations and the awkward node types.
    let original = include_str!(
        "../../regression_tests/simulations/5_model_with_every_node/model_with_every_node_type.ini"
    );
    // Load via read_model_file so the working directory is the model's folder and
    // the relative input CSVs resolve (they sit alongside the model).
    let path = concat!(env!("CARGO_MANIFEST_DIR"),
        "/regression_tests/simulations/5_model_with_every_node/model_with_every_node_type.ini");
    let ini_io = IniModelIO::new();
    let model = ini_io.read_model_file(path).expect("model should parse");

    let saved = ini_io.model_to_string(&model);

    print_text_diff(original, &saved);

    assert_eq!(original, saved, "a no-op save must be byte-identical to the source");
}

#[test]
fn test_save_localises_single_param_change() {
    // Changing one node's params should re-render only that node's section; every
    // other section stays byte-identical to the source.
    let original = "[kalix]\n\
                    \n\
                    [node.g]\n\
                    type = gr4j\n\
                    loc = 10.00, 20.00\n\
                    area = 30.000\n\
                    params = 350.0, 0.0, 90.0, 1.7\n\
                    ds_1 = bh\n\
                    \n\
                    [node.bh]\n\
                    type = blackhole\n\
                    loc = 1.00, 2.00\n";

    let ini_io = IniModelIO::new();
    let mut model = ini_io.read_model_string(original).expect("model should parse");

    // Mutate one GR4J parameter directly on the node (as the optimiser would).
    for node in &mut model.nodes {
        if let crate::nodes::NodeEnum::Gr4jNode(n) = node {
            n.gr4j_model.x1 = 400.0;
        }
    }

    let saved = ini_io.model_to_string(&model);

    // The changed node re-renders canonically (new value, canonical formatting)...
    assert!(saved.contains("params = 400, 0, 90, 1.7"), "changed params, got:\n{}", saved);
    // ...but the untouched blackhole section is preserved verbatim, including its
    // original "1.00, 2.00" formatting (would be "1, 2" if re-canonicalised).
    assert!(saved.contains("loc = 1.00, 2.00"), "untouched node preserved, got:\n{}", saved);
}

#[test]
fn test_changed_storage_keeps_target_level_and_order_through() {
    // target_level and order_through must be emitted by the writer. Previously they
    // were dropped, so a *changed* storage node (which re-renders canonically)
    // would silently lose them. We change initial_volume to force re-rendering.
    let ini = "[kalix]\n\
               \n\
               [node.s]\n\
               type = storage\n\
               loc = 5, 6\n\
               target_level = 5\n\
               order_through = true\n\
               initial_volume = 100\n\
               dimensions = 0, 0, 0, 0,\n\
               \x20            1, 1000, 3, 0\n\
               ds_1 = bh\n\
               \n\
               [node.bh]\n\
               type = blackhole\n\
               loc = 1, 2\n";

    let ini_io = IniModelIO::new();
    let mut model = ini_io.read_model_string(ini).expect("model should parse");

    // Force the storage section to re-render canonically.
    for node in &mut model.nodes {
        if let crate::nodes::NodeEnum::StorageNode(n) = node {
            n.vol_initial = 200.0;
        }
    }

    let saved = ini_io.model_to_string(&model);

    assert!(saved.contains("initial_volume = 200"), "expected changed initial_volume, got:\n{}", saved);
    assert!(saved.contains("target_level"), "changed storage must keep target_level, got:\n{}", saved);
    assert!(saved.contains("order_through = true"), "changed storage must keep order_through, got:\n{}", saved);
}

#[test]
fn test_changed_unregulated_user_keeps_account() {
    // The account definition must be re-emitted (reconstructed from the account
    // manager via the node's registered index). A changed user node previously
    // dropped it. We add annual_cap to force the section to re-render.
    let ini = "[kalix]\n\
               \n\
               [node.u]\n\
               type = unregulated_user\n\
               loc = 5, 6\n\
               demand = 10\n\
               account = myacc, general, 1000, 7\n\
               ds_1 = bh\n\
               \n\
               [node.bh]\n\
               type = blackhole\n\
               loc = 1, 2\n";

    let ini_io = IniModelIO::new();
    let mut model = ini_io.read_model_string(ini).expect("model should parse");

    // Force the user section to re-render canonically.
    for node in &mut model.nodes {
        if let crate::nodes::NodeEnum::UnregulatedUserNode(n) = node {
            n.annual_cap = Some(500.0);
            n.annual_cap_reset_month = 6;
        }
    }

    let saved = ini_io.model_to_string(&model);

    assert!(saved.contains("annual_cap"), "expected changed section, got:\n{}", saved);
    assert!(saved.contains("account = myacc, general, 1000, 7"),
            "changed unregulated_user must keep its account, got:\n{}", saved);
}
