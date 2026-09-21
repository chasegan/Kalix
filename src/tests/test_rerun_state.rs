// Running the same model object twice must give the same results twice.
//
// The optimiser does exactly this - one model, run once per evaluation - so
// any state that survives from the end of one run into the start of the next
// silently changes every evaluation after the first. Each test here pins one
// piece of state that once leaked.

use crate::io::ini_model_io::IniModelIO;
use crate::model::Model;

fn load(ini: &str) -> Model {
    let mut model = IniModelIO::read_model_string(ini).expect("model should load");
    model.configure().expect("model should configure");
    model
}

fn series(model: &mut Model, name: &str) -> Vec<f64> {
    let idx = model.data_cache.get_series_idx(name, false)
        .unwrap_or_else(|| panic!("missing series {}", name));
    model.data_cache.series[idx].values.clone()
}

/// A regulated user two steps below its supply storage, with a rising order
/// (1, 2, 3, ...) and a tributary that puts water in the river from the first
/// step. The user's order buffer must be rebuilt for every run: if the first
/// run's buffer survives, its final orders fall due on the first steps of the
/// second run, and the tributary water is there to be diverted against them.
#[test]
fn regulated_user_order_buffer_is_rebuilt_for_every_run() {
    let mut model = load("
[kalix]
start = 2020-01-01
end = 2020-01-10

[var.patterns]
phase = ras
p1 = var.patterns.p1[-1, 0] + 1

[node.dam]
type = storage
loc = 0, 0
initial_volume = 12000
dimensions = 0,  0,     0,  0,
             10, 12000, 10, 0,
             11, 12100, 10, 1000,
ds_1 = reach

[node.reach]
type = routing
loc = 0, 10
lag = 2
ds_1 = tributary

[node.tributary]
type = inflow
loc = 0, 20
inflow = 5
ds_1 = user

[node.user]
type = regulated_user
loc = 0, 30
order = var.patterns.p1

[outputs]
node.user.order_due
node.user.diversion
");
    model.run().expect("first run");
    let first_due = series(&mut model, "node.user.order_due");
    let first_diversion = series(&mut model, "node.user.diversion");
    assert_eq!(&first_due[..4], &[0.0, 0.0, 1.0, 2.0], "nothing is due until the 2-step travel time has passed");

    model.run().expect("second run");
    assert_eq!(series(&mut model, "node.user.order_due"), first_due);
    assert_eq!(series(&mut model, "node.user.diversion"), first_diversion);
}
