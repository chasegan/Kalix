// Tests for regulated orders on a splitter's effluent (ds_2) link.
//
// The splitter sends orders from both outlets upstream, and when the ordered
// water arrives it diverts the effluent's share down ds_2. "When it arrives"
// is the supply travel time, so the splitter must delay effluent orders by
// exactly the travel time the effluent's users assume for themselves.

use crate::io::ini_model_io::IniModelIO;
use crate::model::Model;

const DAM_DIMENSIONS: &str = "dimensions = 0,  0,     0,  0,
             10, 12000, 10, 0,
             11, 12100, 10, 1000,";

/// A rising order pattern (1, 2, 3, ...), so a timing error of any number of
/// steps shows up as a different value rather than hiding in a constant.
const HEADER: &str = "
[kalix]
start = 2020-01-01
end = 2020-01-12

[var.patterns]
phase = ras
p1 = var.patterns.p1[-1, 0] + 1
";

/// The splitter, a gauge on the main channel, and a regulated user on the
/// effluent.
const SPLITTER_AND_BELOW: &str = "
[node.splitter_1]
type = splitter
loc = 5, 20
table = 0,   0,
        100, 0,
ds_1 = gauge_1
ds_2 = user_2

[node.gauge_1]
type = gauge
loc = 5, 30

[node.user_2]
type = regulated_user
loc = 15, 30
order = var.patterns.p1

[outputs]
node.splitter_1.ds_2
node.splitter_1.ds_2_order_due
node.user_2.order_due
node.user_2.diversion
";

/// A supply storage feeding the splitter through a pure lag.
fn supply(name: &str, lag: usize) -> String {
    format!("
[node.dam_{name}]
type = storage
loc = 0, 0
initial_volume = 12000
{DAM_DIMENSIONS}
ds_1 = lag_{name}

[node.lag_{name}]
type = routing
loc = 0, 10
lag = {lag}
ds_1 = splitter_1
")
}

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

/// One supply path, two steps away: the effluent user's order is diverted
/// down ds_2 on the step it falls due, and the user takes all of it.
#[test]
fn effluent_order_is_diverted_on_the_step_it_is_due() {
    let ini = format!("{HEADER}{}{SPLITTER_AND_BELOW}", supply("a", 2));
    let mut model = load(&ini);
    model.run().expect("simulation should run");

    let due = series(&mut model, "node.user_2.order_due");
    assert_eq!(&due[..5], &[0.0, 0.0, 1.0, 2.0, 3.0], "user travel time should be the 2-step lag");
    assert_eq!(series(&mut model, "node.splitter_1.ds_2_order_due"), due);
    assert_eq!(series(&mut model, "node.splitter_1.ds_2"), due);
    assert_eq!(series(&mut model, "node.user_2.diversion"), due);
}

/// Two supply paths of different length into one splitter. The effluent user
/// assumes the longest travel time, so the splitter must too - whichever
/// order the two branches are defined in. (Sizing the delay from each
/// incoming link in turn let the last-defined branch win.)
#[test]
fn delay_follows_the_longest_supply_path_in_either_definition_order() {
    for (first, second) in [(("a", 3), ("b", 1)), (("a", 1), ("b", 3))] {
        let ini = format!("{HEADER}{}{}{SPLITTER_AND_BELOW}",
                          supply(first.0, first.1), supply(second.0, second.1));
        let mut model = load(&ini);
        model.run().expect("simulation should run");

        let due = series(&mut model, "node.user_2.order_due");
        assert_eq!(&due[..5], &[0.0, 0.0, 0.0, 1.0, 2.0],
                   "user travel time should be the 3-step branch (lags {} then {})", first.1, second.1);
        assert_eq!(series(&mut model, "node.splitter_1.ds_2_order_due"), due,
                   "splitter delay should match the user's (lags {} then {})", first.1, second.1);
    }
}

/// Running the same model object twice gives the same effluent diversions:
/// no orders from the end of the first run leak into the start of the second.
#[test]
fn rerun_starts_with_an_empty_order_buffer() {
    let ini = format!("{HEADER}{}{SPLITTER_AND_BELOW}", supply("a", 2));
    let mut model = load(&ini);

    model.run().expect("first run");
    let first_due = series(&mut model, "node.splitter_1.ds_2_order_due");
    let first_ds_2 = series(&mut model, "node.splitter_1.ds_2");

    model.run().expect("second run");
    assert_eq!(series(&mut model, "node.splitter_1.ds_2_order_due"), first_due);
    assert_eq!(series(&mut model, "node.splitter_1.ds_2"), first_ds_2);
}
