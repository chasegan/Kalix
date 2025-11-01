use crate::io::csv_io::read_ts;
use crate::io::ini_model_io::IniModelIO;
use crate::numerical::opt::{DEConfig, DifferentialEvolution, ObjectiveFunction, OptimisationProblem, ParameterMapping, ParameterMappingConfig, Transform};


#[test]
// This test is intentionally ignored by default because it is very slow.
// Run it explicitly with: `cargo test -- --ignored test_model_constant_optimisation`
#[ignore]
fn test_model_constant_optimisation() {
    let ini_reader = IniModelIO::new();

    //Read the model
    let model_filename = "./src/tests/example_models/5/model.ini";
    println!("model_file = {}", model_filename);
    let mut m= ini_reader.read_model_file(model_filename).unwrap();

    m.configure().expect("Configuration error");
    m.run().expect("Simulation error");

    // Configure the optimisation
    let mut par_map = ParameterMappingConfig::new();
    par_map.add_mapping(ParameterMapping {
        target: "c.a".to_string(),
        gene_index: 1,
        transform: Transform::Linear { min: 0.0, max: 10.0 },
    });
    par_map.add_mapping(ParameterMapping {
        target: "c.b".to_string(),
        gene_index: 2,
        transform: Transform::Linear { min: 0.0, max: 10.0 },
    });

    // Set optimisation target
    let all_data = read_ts("./src/tests/example_models/5/data.csv").unwrap();
    let observed_data = all_data[0].values.clone();
    let target_model_output_name = "node.node2.ds_1".to_string();

    //
    let mut problem = OptimisationProblem::new(m,
            par_map, observed_data, target_model_output_name).with_objective(ObjectiveFunction::NashSutcliffe);

    // Create DE optimiser
    let de_config = DEConfig {
        population_size: 50,
        termination_evaluations: 200,
        f: 0.8,
        cr: 0.9,
        seed: Some(42),
        n_threads: 1,
        progress_callback: None,
    };
    let optimiser = DifferentialEvolution::new(de_config);

    // Run optimisation
    let result = optimiser.optimise(&mut problem);

    println!("evaluations: {}", result.n_evaluations);
    println!("best_objective: {}", result.best_objective);
    print!("best_params: [");
    for px in result.best_params {
        print!("{}, ", px)
    }
    println!("]");
}
