/// Tests for SCE-UA algorithm components
///
/// This file tests individual functions of the SCE-UA implementation
/// to verify correctness of the algorithm's building blocks.

use crate::numerical::opt::sce::{Sce, Individual};
use crate::numerical::opt::{OptimisationProblem, ParameterMappingConfig};
use crate::numerical::opt::objectives::{ObjectiveFunction, SdebObjective};
use crate::numerical::opt::optimisable::Optimisable;
use crate::io::ini_model_io::IniModelIO;
use crate::io::csv_io::read_ts;

#[test]
fn test_compute_centroid_two_individuals() {
    // Test with two individuals as described:
    // Individual 1: [1, 3, 3, 1]
    // Individual 2: [2, 2, 2, 2]
    // Expected centroid: [1.5, 2.5, 2.5, 1.5]

    let ind1 = Individual::new(vec![1.0, 3.0, 3.0, 1.0]);
    let ind2 = Individual::new(vec![2.0, 2.0, 2.0, 2.0]);

    let individuals = vec![ind1, ind2];
    let centroid = Sce::compute_centroid(&individuals);

    // Check each parameter value
    assert_eq!(centroid.params.len(), 4);
    assert!((centroid.params[0] - 1.5).abs() < 1e-10);
    assert!((centroid.params[1] - 2.5).abs() < 1e-10);
    assert!((centroid.params[2] - 2.5).abs() < 1e-10);
    assert!((centroid.params[3] - 1.5).abs() < 1e-10);
}

#[test]
fn test_compute_centroid_three_individuals() {
    // Test with three individuals
    // Individual 1: [0, 0]
    // Individual 2: [3, 0]
    // Individual 3: [0, 3]
    // Expected centroid: [1, 1]

    let ind1 = Individual::new(vec![0.0, 0.0]);
    let ind2 = Individual::new(vec![3.0, 0.0]);
    let ind3 = Individual::new(vec![0.0, 3.0]);

    let individuals = vec![ind1, ind2, ind3];
    let centroid = Sce::compute_centroid(&individuals);

    assert_eq!(centroid.params.len(), 2);
    assert!((centroid.params[0] - 1.0).abs() < 1e-10);
    assert!((centroid.params[1] - 1.0).abs() < 1e-10);
}

#[test]
fn test_compute_centroid_single_dimension() {
    // Test with single parameter dimension
    let ind1 = Individual::new(vec![1.0]);
    let ind2 = Individual::new(vec![5.0]);
    let ind3 = Individual::new(vec![3.0]);

    let individuals = vec![ind1, ind2, ind3];
    let centroid = Sce::compute_centroid(&individuals);

    assert_eq!(centroid.params.len(), 1);
    assert!((centroid.params[0] - 3.0).abs() < 1e-10);
}

#[test]
fn test_evaluate_sdeb_with_sacramento_parameters() {
    // This test evaluates the SDEB objective function with Sacramento model
    // using 17 normalized parameter values as would be used in SCE-UA

    // Load the Sacramento model
    let ini_reader = IniModelIO::new();
    let model_filename = "./src/tests/example_models/8/picnic_sacr.ini";
    let model = ini_reader.read_model_file(model_filename).unwrap();

    // Load observed data
    let all_data = read_ts("./src/tests/example_models/8/formatted_11000A.csv").unwrap();
    let observed_timeseries = all_data[0].clone();

    // Define parameter mappings for all 17 Sacramento parameters with typical bounds
    let par_map = ParameterMappingConfig::from_strings(vec![
        "node.my_sac.adimp = log_range(g(1), 1e-05, 0.15)",
        "node.my_sac.lzfpm = log_range(g(2), 1.0, 300.0)",
        "node.my_sac.lzfsm = log_range(g(3), 1.0, 350.0)",
        "node.my_sac.lzpk = log_range(g(4), 0.001, 0.6)",
        "node.my_sac.lzsk = log_range(g(5), 0.001, 0.9)",
        "node.my_sac.lztwm = log_range(g(6), 10.0, 600.0)",
        "node.my_sac.pctim = log_range(g(7), 1e-05, 0.11)",
        "node.my_sac.pfree = log_range(g(8), 0.01, 0.5)",
        "node.my_sac.rexp = log_range(g(9), 1.0, 6.0)",
        "node.my_sac.sarva = log_range(g(10), 1e-05, 0.11)",
        "node.my_sac.side = log_range(g(11), 1e-05, 0.1)",
        "node.my_sac.ssout = log_range(g(12), 1e-05, 0.1)",
        "node.my_sac.uzfwm = log_range(g(13), 5.0, 155.0)",
        "node.my_sac.uzk = log_range(g(14), 0.1, 1.0)",
        "node.my_sac.uztwm = log_range(g(15), 12.0, 180.0)",
        "node.my_sac.zperc = log_range(g(16), 1.0, 600.0)",
        "node.my_sac.laguh = lin_range(g(17), 0.0, 3.0)",
    ]).unwrap();

    // Create optimisation problem with SDEB objective
    let target_model_output_name = "node.my_sac.ds_1".to_string();
    let mut problem = OptimisationProblem::single_comparison(
        model,
        par_map,
        observed_timeseries,
        target_model_output_name,
        ObjectiveFunction::SDEB(SdebObjective::new()),
    );

    //Generation #1, Member #1
    let norm_params = [0.184152062501156,0.7804365141068352,0.9370437553745078,0.21417840875840471,
        0.6229863987871346,0.24843421672438853,0.4169457650204489,0.17389884732804192,
        0.59472426347051,0.6130591703593801,0.06999470354359641,0.28148779281497044,
        0.34410924278904237,0.15527159959316963,0.12508329065004467,0.6812450743392768,
        0.5668231608655752];
    problem.set_params(&norm_params).expect("Failed to set parameters");
    let objective = problem.evaluate().expect("Failed to evaluate");
    assert_eq!(objective, 586370.2927733948);
    println!("objective: {}", objective);

    //Generation #1, Member #1 (again!)
    let norm_params = [0.184152062501156,0.7804365141068352,0.9370437553745078,0.21417840875840471,
        0.6229863987871346,0.24843421672438853,0.4169457650204489,0.17389884732804192,
        0.59472426347051,0.6130591703593801,0.06999470354359641,0.28148779281497044,
        0.34410924278904237,0.15527159959316963,0.12508329065004467,0.6812450743392768,
        0.5668231608655752];
    problem.set_params(&norm_params).expect("Failed to set parameters");
    let objective = problem.evaluate().expect("Failed to evaluate");
    assert_eq!(objective, 586370.2927733948);
    println!("objective: {}", objective);

    //Generation #1, Member #100
    let norm_params = [0.1332199515084964,0.7184534103526298,0.5637513935474487,0.8794715789993357,
        0.557925927504513,0.9082313446062785,0.1986718420728399,0.8963977027377558,
        0.5802171516751735,0.237996892515185,0.35868193870018045,0.31902470610347994,
        0.5925034196117108,0.9529410786204627,0.22423641199699768,0.9301188089460828,
        0.3080289381321884];
    problem.set_params(&norm_params).expect("Failed to set parameters");
    let objective = problem.evaluate().expect("Failed to evaluate");
    assert_eq!(objective, 2946660.1050290554,);
    println!("objective: {}", objective);

    //Generation #2, Member #20
    let norm_params = [0.5711821824792123,0.6248866760417706,0.7695380331789731,0.023824289262190712,
        0.6296849727420029,0.15565645658500893,0.5298848052685393,0.924080931721645,
        0.544607144270757,0.6788697864087676,0.5614458021095019,0.4326430256409206,
        0.37976091902051046,0.22494395147144153,0.17539715743789996,0.7158364352640383,
        0.9044542732080423];
    problem.set_params(&norm_params).expect("Failed to set parameters");
    let objective = problem.evaluate().expect("Failed to evaluate");
    assert_eq!(objective, 1531118.4885771151);
    println!("objective: {}", objective);


    //Generation #68, Member #1
    let norm_params = [0.7452562936199134,0.6462312167116433,0.9732757553176257,0.6374854013183846,
        0.3814951045378321,0.1729820698320862,0.345184732117515,0.6862544952950089,
        0.3455951519233075,0.401481664230263,0.7291773166973192,0.3682920033778686,
        0.6609209376496183,0.3676272664326994,0.3551585108818486,0.7116439308856575,
        0.4322361432468632];
    problem.set_params(&norm_params).expect("Failed to set parameters");
    let objective = problem.evaluate().expect("Failed to evaluate");
    assert_eq!(objective, 143928.3770721163);
    println!("objective: {}", objective);
}
