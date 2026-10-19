use crate::model::Model;
use crate::io::csv_io;
use crate::nodes::sacramento_node::SacramentoNode;
use crate::hydrology::rainfall_runoff::sacramento::Sacramento;
use crate::nodes::NodeEnum;
use crate::model_inputs::DynamicInput;


/// Create a Sacramento node, add it to a model, and test results
/// against a data read from a CSV file.
#[test]
fn test_sacramento_node_with_timeseries() {

    //Creat a new model. Add some data.
    let mut m = Model::new();
    m.load_input_data("./src/tests/example_data/fors/rain_infilled.csv", None).expect("TODO: panic message");
    m.load_input_data("./src/tests/example_data/fors/mpot_rolled.csv", None).expect("TODO: panic message");

    //Create a node and add it to the model
    let mut n = SacramentoNode::new();
    n.name = "my_sac_node".to_owned();
    n.area_km2 = 228.0;
    n.rain_mm_input = DynamicInput::from_string("data.rain_infilled_csv.by_name.value", &mut m.data_cache, true, None)
        .expect("Failed to parse rain expression");
    n.evap_mm_input = DynamicInput::from_string("data.mpot_rolled_csv.by_name.value", &mut m.data_cache, true, None)
        .expect("Failed to parse evap expression");
    n.sacramento_model.set_params(0.0,45.0,60.0,0.01,
                 0.01,150.0,0.0,0.11,
                 1.5,0.0,0.2,0.01,
                 25.0,0.2,47.0,15.0,0.1);
    m.add_node(NodeEnum::SacramentoNode(n));

    //Specify some outputs
    m.outputs.push("node.my_sac_node.dsflow".to_owned());

    //Configure and run the model
    m.configure().expect("Configuration error");
    m.run().expect("Simulation error");

    //Check the results
    let dsflow_idx = m.data_cache.get_series_idx("node.my_sac_node.dsflow", false).unwrap();
    let total_runoff = m.data_cache.series[dsflow_idx].sum();
    let correct_answer = &csv_io::read_ts("./src/tests/example_data/fors/modelled_flow.csv").unwrap()[0];
    let correct_answer_total_runoff = correct_answer.sum();
    //println!("total_runoff: {}", total_runoff);
    //println!("correct_answer: {}", correct_answer_total_runoff);
    assert!((total_runoff - correct_answer_total_runoff).abs() < 0.00001);
}



/// Create a Sacramento node, add it to a model, and test results
/// against a data read from a CSV file.
#[test]
fn test_sacramento_node_with_timeseries_by_index() {

    //Creat a new model. Add some data.
    let mut m = Model::new();
    m.load_input_data("./src/tests/example_data/fors/rain_infilled.csv", None).expect("TODO: panic message");
    m.load_input_data("./src/tests/example_data/fors/mpot_rolled.csv", None).expect("TODO: panic message");

    //Create a node and add it to the model
    let mut n = SacramentoNode::new();
    n.name = "my_sac_node".to_owned();
    n.area_km2 = 228.0;
    n.rain_mm_input = DynamicInput::from_string("data.rain_infilled_csv.by_index.1", &mut m.data_cache, true, None)
        .expect("Failed to parse rain expression");
    n.evap_mm_input = DynamicInput::from_string("data.mpot_rolled_csv.by_index.1", &mut m.data_cache, true, None)
        .expect("Failed to parse evap expression");
    n.sacramento_model.set_params(0.0,45.0,60.0,0.01,
                                  0.01,150.0,0.0,0.11,
                                  1.5,0.0,0.2,0.01,
                                  25.0,0.2,47.0,15.0,0.1);
    m.add_node(NodeEnum::SacramentoNode(n));

    //Specify some outputs
    m.outputs.push("node.my_sac_node.dsflow".to_owned());

    //Configure and run the model
    m.configure().expect("Configuration error");
    m.run().expect("Simulation error");

    //Check the results
    let dsflow_idx = m.data_cache.get_series_idx("node.my_sac_node.dsflow", false).unwrap();
    let total_runoff = m.data_cache.series[dsflow_idx].sum();
    let correct_answer = &csv_io::read_ts("./src/tests/example_data/fors/modelled_flow.csv").unwrap()[0];
    let correct_answer_total_runoff = correct_answer.sum();
    //println!("total_runoff: {}", total_runoff);
    //println!("correct_answer: {}", correct_answer_total_runoff);
    assert!((total_runoff - correct_answer_total_runoff).abs() < 0.00001);
}

/// Each soil moisture store output carries the right store: every recorded
/// step matches a standalone model driven by the same rain and evap, and stays
/// within the store's capacity.
#[test]
fn test_sacramento_node_store_outputs() {

    //Create a new model. Add some data.
    let mut m = Model::new();
    m.load_input_data("./src/tests/example_data/fors/rain_infilled.csv", None).expect("rain data should load");
    m.load_input_data("./src/tests/example_data/fors/mpot_rolled.csv", None).expect("evap data should load");

    //Create a node and add it to the model
    let (lzfpm, lzfsm, lztwm, side, uzfwm, uztwm) = (45.0, 60.0, 150.0, 0.2, 25.0, 47.0);
    let set_params = |s: &mut Sacramento| {
        s.set_params(0.1,lzfpm,lzfsm,0.01,
                     0.01,lztwm,0.05,0.11,
                     1.5,0.0,side,0.01,
                     uzfwm,0.2,uztwm,15.0,0.1);
    };
    let mut n = SacramentoNode::new();
    n.name = "my_sac_node".to_owned();
    n.area_km2 = 228.0;
    n.rain_mm_input = DynamicInput::from_string("data.rain_infilled_csv.by_name.value", &mut m.data_cache, true, None)
        .expect("Failed to parse rain expression");
    n.evap_mm_input = DynamicInput::from_string("data.mpot_rolled_csv.by_name.value", &mut m.data_cache, true, None)
        .expect("Failed to parse evap expression");
    set_params(&mut n.sacramento_model);
    m.add_node(NodeEnum::SacramentoNode(n));

    //Specify some outputs
    let stores = ["uztw", "uzfw", "lztw", "lzfp", "lzfs"];
    for output in ["rain", "evap"].iter().chain(stores.iter()) {
        m.outputs.push(format!("node.my_sac_node.{}", output));
    }

    //Configure and run the model
    m.configure().expect("Configuration error");
    m.run().expect("Simulation error");

    //Read the results
    let mut series = |name: &str| -> Vec<f64> {
        let idx = m.data_cache.get_series_idx(&format!("node.my_sac_node.{}", name), false).unwrap();
        m.data_cache.series[idx].values.clone()
    };
    let rain = series("rain");
    let evap = series("evap");
    let recorded: Vec<Vec<f64>> = stores.iter().map(|s| series(s)).collect();
    let n_steps = rain.len();
    assert!(n_steps > 0);
    for (store, values) in stores.iter().zip(&recorded) {
        assert_eq!(values.len(), n_steps, "{}", store);
    }

    //Each output must match its own store in a standalone model, step by step.
    let mut reference = Sacramento::new();
    set_params(&mut reference);
    reference.initialize_state_empty();
    for i in 0..n_steps {
        reference.run_step(rain[i], evap[i]);
        let expected = [
            reference.get_uztw(),
            reference.get_uzfw(),
            reference.get_lztw(),
            reference.get_lzfp(),
            reference.get_lzfs(),
        ];
        for (j, store) in stores.iter().enumerate() {
            assert_eq!(recorded[j][i], expected[j], "step {}: {}", i, store);
        }
    }

    //The comparison above only pins each name to its store if the stores differ.
    for j in 0..stores.len() {
        for k in (j + 1)..stores.len() {
            assert!(recorded[j] != recorded[k], "{} and {} are identical series", stores[j], stores[k]);
        }
    }

    //Each store stays within its capacity. The lower zone free water stores
    //have the side component divided out, so lzfpm and lzfsm bound them.
    let capacities = [uztwm, uzfwm, lztwm, lzfpm, lzfsm];
    for (j, store) in stores.iter().enumerate() {
        let max = recorded[j].iter().cloned().fold(f64::MIN, f64::max);
        let min = recorded[j].iter().cloned().fold(f64::MAX, f64::min);
        assert!(min >= 0.0, "{}: min {}", store, min);
        assert!(max > 0.0 && max <= capacities[j] + 1e-9, "{}: max {} outside (0, {}]", store, max, capacities[j]);
    }
}

// ---------------------------------------------------------------------------
// Ratio parameters (sarvaonpctim, lzpkonlzsk)
// ---------------------------------------------------------------------------

use crate::numerical::opt::optimisable_component::{Derived, OptimisableComponent};

/// Setting a ratio writes its numerator from the denominator the node holds
/// at that moment, and the ratio reads back.
#[test]
fn test_ratio_param_sets_its_numerator() {
    let mut n = SacramentoNode::new();
    n.set_param("pctim", 0.08).unwrap();
    n.set_param("sarvaonpctim", 0.5).unwrap();
    assert!((n.sacramento_model.sarva - 0.04).abs() < 1e-12);
    assert_eq!(n.sacramento_model.pctim, 0.08);

    n.set_param("lzsk", 0.2).unwrap();
    n.set_param("lzpkonlzsk", 0.1).unwrap();
    assert!((n.sacramento_model.lzpk - 0.02).abs() < 1e-12);

    assert!((n.get_param("sarvaonpctim").unwrap() - 0.5).abs() < 1e-12);
    assert!((n.get_param("lzpkonlzsk").unwrap() - 0.1).abs() < 1e-12);
}

/// The node keeps no ratio: a denominator set afterwards leaves the numerator
/// where it was. Ordering is the optimiser's job (see the problem tests).
#[test]
fn test_ratio_param_is_not_remembered() {
    let mut n = SacramentoNode::new();
    n.set_param("pctim", 0.08).unwrap();
    n.set_param("sarvaonpctim", 0.5).unwrap();
    n.set_param("pctim", 0.02).unwrap();
    assert!((n.sacramento_model.sarva - 0.04).abs() < 1e-12);

    n.set_param("sarva", 0.03).unwrap();
    n.set_param("lztwm", 100.0).unwrap();
    assert_eq!(n.sacramento_model.sarva, 0.03);
}

/// Only the ratios are derived, and each names its numerator and denominator.
#[test]
fn test_ratio_params_name_what_they_write() {
    let n = SacramentoNode::new();
    assert_eq!(n.derives("sarvaonpctim"), Some(Derived { writes: "sarva", from: "pctim" }));
    assert_eq!(n.derives("lzpkonlzsk"), Some(Derived { writes: "lzpk", from: "lzsk" }));
    for name in ["sarva", "lzpk", "pctim", "lzsk", "lztwm", "nonsense"] {
        assert_eq!(n.derives(name), None, "'{}'", name);
    }
}


// ---------------------------------------------------------------------------
// Ratio parameters through an OptimisationProblem
// ---------------------------------------------------------------------------

use crate::io::csv_io::read_ts;
use crate::io::ini_model_io::IniModelIO;
use crate::numerical::opt::objectives::{ObjectiveFunction, SdebObjective};
use crate::numerical::opt::optimisable::Optimisable;
use crate::numerical::opt::{OptimisationProblem, ParameterMappingConfig};

fn picnic_problem(mappings: Vec<&str>) -> OptimisationProblem {
    let model = IniModelIO::read_model_file("./src/tests/example_models/8/picnic_sacr.ini").unwrap();
    let observed = read_ts("./src/tests/example_models/8/formatted_11000A.csv").unwrap()[0].clone();
    OptimisationProblem::single_comparison(
        model,
        ParameterMappingConfig::from_strings(mappings).unwrap(),
        observed,
        "node.my_sac.ds_1".to_string(),
        ObjectiveFunction::SDEB(SdebObjective::new()),
    )
}

fn picnic_sacramento(problem: &OptimisationProblem) -> &SacramentoNode {
    match problem.model.get_node("my_sac") {
        Some(NodeEnum::SacramentoNode(n)) => n,
        _ => panic!("my_sac is not a Sacramento node"),
    }
}

/// The numerators land in the model whichever side of its denominator a
/// ratio is written: the problem sets the ratios last. One denominator is
/// searched and the other held by a constant expression.
#[test]
fn test_ratio_params_reach_the_model() {
    let mut problem = picnic_problem(vec![
        "node.my_sac.sarvaonpctim = lin_range(g(1), 0, 1)",
        "node.my_sac.pctim = 0.05",
        "node.my_sac.lzsk = lin_range(g(2), 0, 0.5)",
        "node.my_sac.lzpkonlzsk = lin_range(g(3), 0, 1)",
    ]);
    assert_eq!(problem.n_params(), 3);

    problem.set_params(&[0.5, 0.4, 0.2]).unwrap();
    let sac = &picnic_sacramento(&problem).sacramento_model;
    assert!((sac.pctim - 0.05).abs() < 1e-12);
    assert!((sac.sarva - 0.025).abs() < 1e-12);
    assert!((sac.lzsk - 0.2).abs() < 1e-12);
    assert!((sac.lzpk - 0.04).abs() < 1e-12);

    // The derived numerators are reported apart from the mappings
    let derived = problem.derived_params();
    assert_eq!(derived.len(), 2);
    assert_eq!(derived[0].0, "node.my_sac.sarva");
    assert!((derived[0].1 - 0.025).abs() < 1e-12);
    assert_eq!(derived[1].0, "node.my_sac.lzpk");
    assert!((derived[1].1 - 0.04).abs() < 1e-12);
}

/// A configuration that maps every parameter the node lists, which is what
/// the IDE generates by default, resolves and applies.
#[test]
fn test_every_listed_parameter_can_be_mapped_together() {
    let listed = picnic_sacramento(&picnic_problem(vec![])).list_params();
    let lines: Vec<String> = listed.iter().enumerate()
        .map(|(i, name)| format!("node.my_sac.{} = lin_range(g({}), 0.1, 0.2)", name, i + 1))
        .collect();
    let mut problem = picnic_problem(lines.iter().map(String::as_str).collect());

    problem.set_params(&vec![0.5; listed.len()]).unwrap();
    let sac = &picnic_sacramento(&problem).sacramento_model;
    assert!((sac.sarva - 0.15 * 0.15).abs() < 1e-12);
    assert!((sac.lzpk - 0.15 * 0.15).abs() < 1e-12);
}

/// The same candidate applied twice, and after another candidate, gives the
/// same numerators: nothing is carried over between candidates.
#[test]
fn test_ratio_candidates_do_not_carry_over() {
    let mut problem = picnic_problem(vec![
        "node.my_sac.sarvaonpctim = lin_range(g(1), 0, 1)",
        "node.my_sac.pctim = lin_range(g(2), 0, 0.1)",
    ]);
    problem.set_params(&[0.5, 0.4]).unwrap();
    assert!((picnic_sacramento(&problem).sacramento_model.sarva - 0.02).abs() < 1e-12);
    problem.set_params(&[0.9, 1.0]).unwrap();
    assert!((picnic_sacramento(&problem).sacramento_model.sarva - 0.09).abs() < 1e-12);
    problem.set_params(&[0.5, 0.4]).unwrap();
    assert!((picnic_sacramento(&problem).sacramento_model.sarva - 0.02).abs() < 1e-12);
}

/// A ratio problem scores exactly as the same search written with tied genes,
/// on the problem itself and on a parallel worker's clone.
#[test]
fn test_ratio_params_match_tied_genes() {
    let mut ratio = picnic_problem(vec![
        "node.my_sac.sarvaonpctim = lin_range(g(1), 0, 1)",
        "node.my_sac.pctim = lin_range(g(2), 0, 0.1)",
        "node.my_sac.lzpkonlzsk = lin_range(g(3), 0, 1)",
        "node.my_sac.lzsk = lin_range(g(4), 0, 0.5)",
    ]);
    let mut tied = picnic_problem(vec![
        "node.my_sac.sarva = lin_range(g(1), 0, 1) * lin_range(g(2), 0, 0.1)",
        "node.my_sac.pctim = lin_range(g(2), 0, 0.1)",
        "node.my_sac.lzpk = lin_range(g(3), 0, 1) * lin_range(g(4), 0, 0.5)",
        "node.my_sac.lzsk = lin_range(g(4), 0, 0.5)",
    ]);

    let genes = [0.3, 0.6, 0.2, 0.4];
    ratio.set_params(&genes).unwrap();
    tied.set_params(&genes).unwrap();
    let expected = tied.evaluate().unwrap();
    assert_eq!(ratio.evaluate().unwrap(), expected);

    // Move the worker elsewhere first, so it cannot pass on inherited values.
    let mut worker = ratio.clone_for_parallel();
    worker.set_params(&[0.9, 0.1, 0.8, 0.9]).unwrap();
    assert_ne!(worker.evaluate().unwrap(), expected);
    worker.set_params(&genes).unwrap();
    assert_eq!(worker.evaluate().unwrap(), expected);
}

/// The node offers the ratios in place of `sarva` and `lzpk`, yet a
/// configuration that maps those two directly still resolves and applies.
#[test]
fn test_numerators_are_accepted_but_not_listed() {
    let mut problem = picnic_problem(vec![
        "node.my_sac.sarva = lin_range(g(1), 0, 0.1)",
        "node.my_sac.lzpk = lin_range(g(2), 0, 0.5)",
    ]);
    let listed = picnic_sacramento(&problem).list_params();
    for name in ["sarva", "lzpk"] {
        assert!(!listed.contains(&name.to_string()), "'{}' is listed", name);
    }
    for name in ["sarvaonpctim", "pctim", "lzpkonlzsk", "lzsk"] {
        assert!(listed.contains(&name.to_string()), "'{}' is not listed", name);
    }

    problem.set_params(&[0.5, 0.2]).unwrap();
    let sac = &picnic_sacramento(&problem).sacramento_model;
    assert!((sac.sarva - 0.05).abs() < 1e-12);
    assert!((sac.lzpk - 0.1).abs() < 1e-12);
}

/// A ratio mapped without its denominator derives the numerator from the
/// denominator in the model file, and leaves that denominator alone.
#[test]
fn test_ratio_without_denominator_uses_the_model_value() {
    let mut problem = picnic_problem(vec![
        "node.my_sac.sarvaonpctim = lin_range(g(1), 0, 1)",
    ]);
    let pctim = picnic_sacramento(&problem).sacramento_model.pctim;
    assert!(pctim > 0.0);

    problem.set_params(&[0.5]).unwrap();
    let sac = &picnic_sacramento(&problem).sacramento_model;
    assert_eq!(sac.pctim, pctim);
    assert!((sac.sarva - 0.5 * pctim).abs() < 1e-12);
}

/// A ratio mapped alongside its numerator fails when the candidate is applied.
#[test]
fn test_ratio_with_numerator_fails_set_params() {
    let mut problem = picnic_problem(vec![
        "node.my_sac.sarvaonpctim = lin_range(g(1), 0, 1)",
        "node.my_sac.sarva = lin_range(g(2), 0, 0.1)",
    ]);
    let err = problem.set_params(&[0.5, 0.5]).unwrap_err();
    assert!(err.contains("cannot both be optimised"), "got: {}", err);
}

/// A parameter name the node does not have is refused when the targets are
/// resolved, before any candidate is applied.
#[test]
fn test_unknown_parameter_name_fails_resolve() {
    let mut problem = picnic_problem(vec![
        "node.my_sac.lztwn = lin_range(g(1), 10, 600)",
    ]);
    let err = problem.resolve().unwrap_err();
    assert!(err.contains("node.my_sac.lztwn"), "got: {}", err);
    assert!(err.contains("Unknown Sacramento parameter"), "got: {}", err);

    // The ratio rule is checked at the same point
    let mut problem = picnic_problem(vec![
        "node.my_sac.sarvaonpctim = lin_range(g(1), 0, 1)",
        "node.my_sac.sarva = lin_range(g(2), 0, 0.1)",
    ]);
    assert!(problem.resolve().unwrap_err().contains("cannot both be optimised"));

    let mut problem = picnic_problem(vec![
        "node.my_sac.lztwm = lin_range(g(1), 10, 600)",
    ]);
    problem.resolve().unwrap();
}

/// Write an optimisation config for the picnic model to a temp file.
fn picnic_config_file(parameters: &str) -> String {
    let models = std::env::current_dir().unwrap().join("src/tests/example_models/8");
    let dir = std::env::temp_dir().join("kalix_tests").join(uuid::Uuid::new_v4().to_string());
    std::fs::create_dir_all(&dir).unwrap();
    let path = dir.join("optimisation.ini");
    std::fs::write(&path, format!(
        "[optimisation]\nmodel_file = {}\nobjective_expression = term1\nalgorithm = DE\n\
         population_size = 4\ntermination_evaluations = 8\nn_threads = 1\nrandom_seed = 1\n\n\
         [term.term1]\nsimulated = node.my_sac.ds_1\nobserved_file = {}\nobserved_series = 1\n\
         statistic = SDEB\n\n[parameters]\n{}\n",
        models.join("picnic_sacr.ini").display(),
        models.join("formatted_11000A.csv").display(),
        parameters,
    )).unwrap();
    path.to_string_lossy().into_owned()
}

/// optimise_from_file reports the derived numerator apart from the mapped
/// parameters, and stops on a bad address before running any candidate.
#[test]
fn test_optimise_from_file_reports_derived_and_checks_addresses() {
    let config = picnic_config_file(
        "node.my_sac.sarvaonpctim = lin_range(g(1), 0.1, 1)\nnode.my_sac.lztwm = lin_range(g(2), 50, 200)");
    let outcome = crate::run::optimise_from_file(&config, None, None, None).unwrap();
    assert_eq!(outcome.parameters.len(), 2);
    assert_eq!(outcome.derived_parameters.len(), 1);
    assert_eq!(outcome.derived_parameters[0].0, "node.my_sac.sarva");
    assert!(outcome.derived_parameters[0].1 > 0.0);

    let config = picnic_config_file("node.my_sac.lztwn = lin_range(g(1), 10, 600)");
    let err = crate::run::optimise_from_file(&config, None, None, None).err().expect("a bad address is an error");
    assert!(err.starts_with("Cannot optimise 'node.my_sac.lztwn'"), "got: {}", err);
}
