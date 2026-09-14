//! Per-step mass closure of the routing node under `loss_rate` and
//! `dead_storage`: in − out − loss − ΔV = 0 on every step, for every routing
//! law, with the reach driven through the regimes a loss creates - outflow
//! rounding to zero, a loss taking all the live water, a Newton solve whose
//! warm start sits far above the root. Whole-run closure would pass some of
//! these; the per-step check does not.

use crate::io::ini_model_io::IniModelIO;
use crate::model::Model;

/// Write a daily `timestamp,inflow,loss` CSV to a unique temp directory and
/// return its path. The series name inside the model is `data.series_csv`.
fn write_series(inflow: &[f64], loss: &[f64]) -> std::path::PathBuf {
    assert_eq!(inflow.len(), loss.len());
    assert!((2..=31).contains(&inflow.len()), "two rows to fix the timestep, one calendar month to keep the end date simple");
    let dir = std::env::temp_dir().join("kalix_tests").join(uuid::Uuid::new_v4().to_string());
    std::fs::create_dir_all(&dir).unwrap();
    let mut csv = String::from("timestamp,inflow,loss\n");
    for (i, (q, l)) in inflow.iter().zip(loss).enumerate() {
        csv.push_str(&format!("2024-01-{:02},{q},{l}\n", i + 1));
    }
    let path = dir.join("series.csv");
    std::fs::write(&path, csv).unwrap();
    path
}

fn model_ini(series: &std::path::Path, reach_lines: &str, days: usize) -> String {
    format!("\
[kalix]
start = 2024-01-01
end = 2024-01-{days:02}

[data]
{}

[node.source]
type = inflow
loc = 0, 0
inflow = data.series_csv.by_name.inflow
ds_1 = reach

[node.reach]
type = routing
loc = 0, 10
loss_rate = data.series_csv.by_name.loss
{reach_lines}
ds_1 = sink

[node.sink]
type = blackhole
loc = 0, 20

[outputs]
node.reach.usflow
node.reach.dsflow
node.reach.volume
node.reach.loss
", series.display())
}

fn run(inflow: &[f64], loss: &[f64], reach_lines: &str) -> Model {
    let path = write_series(inflow, loss);
    let mut model = IniModelIO::read_model_string(&model_ini(&path, reach_lines, inflow.len()))
        .unwrap_or_else(|e| panic!("model should load: {e}"));
    model.configure().unwrap_or_else(|e| panic!("configure should succeed: {e}"));
    model.run().unwrap_or_else(|e| panic!("run should succeed: {e}"));
    let _ = std::fs::remove_dir_all(path.parent().unwrap());
    model
}

fn series(model: &Model, name: &str) -> Vec<f64> {
    let idx = model.data_cache.get_existing_series_idx(name)
        .unwrap_or_else(|| panic!("series '{name}' should exist"));
    model.data_cache.series[idx].values.clone()
}

/// Every step: what came in went out, was lost, or changed the storage; and
/// neither the outflow nor the storage ever goes negative.
fn assert_closes_each_step(model: &Model, initial_volume: f64, label: &str) {
    let us = series(model, "node.reach.usflow");
    let ds = series(model, "node.reach.dsflow");
    let lost = series(model, "node.reach.loss");
    let vol = series(model, "node.reach.volume");
    let mut prev = initial_volume;
    for t in 0..us.len() {
        let residual = us[t] - ds[t] - lost[t] - (vol[t] - prev);
        assert!(residual.abs() < 1e-8,
            "{label}: step {t} does not close: in {} - out {} - lost {} - dV {} = {residual}",
            us[t], ds[t], lost[t], vol[t] - prev);
        assert!(ds[t] >= 0.0, "{label}: step {t} outflow {} < 0", ds[t]);
        assert!(vol[t] >= -1e-12, "{label}: step {t} storage {} < 0", vol[t]);
        prev = vol[t];
    }
}

const PWL_TABLE: &str = "pwl = Flow [ML], Travel Time [steps],\n      0,   2,\n      100, 1,\n      1000, 0.5,";

#[test]
fn pwl_keeps_a_booked_loss_when_outflow_rounds_to_zero() {
    // The loss request exceeds the live water, so the bound takes the division
    // to exactly zero outflow: qr = x*qin, and the quadratic returns it with
    // rounding on either side. When it lands at -epsilon the upstream-flow
    // clamp fires; the storage must still carry the loss that was booked, and
    // drain_pool then takes the rest of the request from what is left.
    let reach = format!("n_divs = 1\nx = 0.2\n{PWL_TABLE}");
    let model = run(&[167.099776, 0.0], &[121.344307, 0.0], &reach);
    let lost = series(&model, "node.reach.loss");
    let ds = series(&model, "node.reach.dsflow");
    let vol = series(&model, "node.reach.volume");
    assert!((lost[0] - 121.344307).abs() < 1e-9, "the whole request is met: {lost:?}");
    assert!(ds[0].abs() < 1e-9, "outflow is zero: {ds:?}");
    assert!((vol[0] - (167.099776 - 121.344307)).abs() < 1e-9, "storage is what came in minus what was lost: {vol:?}");
    assert_closes_each_step(&model, 0.0, "pwl x=0.2 rounding");
}

#[test]
fn nlm_loss_that_takes_all_the_live_water_leaves_only_the_pool() {
    // Day 1 fills the reach above its dead level. Day 2 has no inflow and a
    // loss larger than the live water: the bound takes all the live water and
    // the balance leaves no reference flow to solve for (b = 0). The storage
    // must drop by the booked loss, and the rest of the request must come out
    // of the pool. Day 3, below the dead level, releases nothing.
    let reach = "n_divs = 1\nx = 0.3\nnlm = 20000, 0.8\ndead_storage = 100";
    let model = run(&[300.0, 0.0, 0.0], &[0.0, 60.0, 0.0], reach);
    let lost = series(&model, "node.reach.loss");
    let ds = series(&model, "node.reach.dsflow");
    let vol = series(&model, "node.reach.volume");
    assert!(vol[0] > 100.0, "day 1 leaves live water above the pool: {vol:?}");
    assert!((lost[1] - 60.0).abs() < 1e-9, "the whole request is met: {lost:?}");
    assert!((vol[1] - (vol[0] - 60.0)).abs() < 1e-9, "storage drops by the loss: {vol:?}");
    assert!(vol[1] < 100.0, "the pool covered part of it: {vol:?}");
    assert!(ds[1].abs() < 1e-9 && ds[2].abs() < 1e-9, "nothing flows on days 2 and 3: {ds:?}");
    assert_closes_each_step(&model, 100.0, "nlm x=0.3 dead");
}

#[test]
fn nlm_newton_converges_when_a_loss_leaves_little_live_water() {
    // Day 2's loss leaves a sliver of live water, so b is tiny while the warm
    // start is last step's reference flow, hundreds of ML. An unconverged
    // solve releases more than the reach holds and drives storage negative.
    let reach = "n_divs = 1\nx = 0.3\nnlm = 20000, 0.8";
    let model = run(&[300.0, 0.0, 0.0], &[0.0, 24.5, 0.0], reach);
    let ds = series(&model, "node.reach.dsflow");
    let vol = series(&model, "node.reach.volume");
    let live_after_loss = vol[0] - 24.5;
    assert!(live_after_loss > 0.0 && live_after_loss < 1.0, "the scenario leaves a sliver: {vol:?}");
    assert!(ds[1] <= live_after_loss + 1e-9, "day 2 cannot release more than it holds: {ds:?} vs {live_after_loss}");
    assert_closes_each_step(&model, 0.0, "nlm x=0.3 sliver");
}

#[test]
fn random_series_close_every_step_under_every_routing_law() {
    // A month of jumpy inflows and losses, with dry days and days the loss
    // exceeds what is present, through each law with and without dead storage.
    let mut state: u64 = 0x2545_F491_4F6C_DD1D;
    let mut next = || { state = state.wrapping_mul(6364136223846793005).wrapping_add(1442695040888963407); state >> 33 };
    let inflow: Vec<f64> = (0..31).map(|_| { let r = next(); if r % 4 == 0 { 0.0 } else { (r % 400) as f64 + 0.37 } }).collect();
    let loss: Vec<f64> = (0..31).map(|_| { let r = next(); if r % 3 == 0 { 0.0 } else { (r % 150) as f64 + 0.61 } }).collect();

    let pwl3 = format!("n_divs = 3\nx = 0.2\n{PWL_TABLE}");
    let pwl_x1 = format!("n_divs = 2\nx = 1\n{PWL_TABLE}");
    let laws: [(&str, &str); 5] = [
        ("lag-only", ""),
        ("pwl x=0.2 three divisions", &pwl3),
        ("pwl x=1 two divisions", &pwl_x1),
        ("nlm x=0.3 two divisions", "n_divs = 2\nx = 0.3\nnlm = 20000, 0.8"),
        ("nlm x=1", "n_divs = 1\nx = 1\nnlm = 20000, 0.8"),
    ];
    for (label, law) in laws {
        for dead in [0.0, 90.0] {
            let reach = format!("{law}\ndead_storage = {dead}");
            let model = run(&inflow, &loss, &reach);
            assert_closes_each_step(&model, dead, &format!("{label}, dead_storage = {dead}"));
        }
    }
}
