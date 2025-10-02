use super::Node;
use crate::misc::misc_functions::make_result_name;
use crate::misc::input_data_definition::InputDataDefinition;
use crate::data_cache::DataCache;
use crate::misc::location::Location;

//------- IDEAS FOR ORDERING IN KALIX ----------//
// A couple of thoughts are:
//   - Having an ordering phase means you kinda have to know everything before
//     any flows occur, and it really limits parallelization.
//   - Ordering up the network means when you place your order, you dont know
//     when it will arrive. Additionally, Source mixes up the concepts of "order"
//     and "demand". This leads to all the muddiness:
//        - For a timeseries I'll order in advance, and take my water on the date.
//        - For a function I'll order on the date, and take my water in arrears.
//        - At each point there is an array of orders. This is what I want today,
//          this is what I want tomorrow, but if you ask tomorrow I might change
//          my mind.
//        - Computational overhead is insane.
//   - It is nice having the operations kinda happen automatically in Source
//     as long as your storage has an outlet. Having some robust default behaviour
//     is a must.
//----------------------------------------------//
// So in light of all that I wonder if there's a pragmatic operational view
// that's just a lot simpler. Something like...
//   - All nodes know which storage outlet they're being delivered by. And at
//     start of the run they report themselves to the outlet.
//   - There is no ordering phase.
//   - When a storage runs (flow phase), it asks all its outlets how much water
//     they want. Each outlet turns to it's registered users and asks something
//     like.
//        "Hey user A, if I release today, I can get you 1000ML in 1 day. How much do you want?"
//        "Hey user B, if I release today, I can get you 1000ML in 3 days. How much do you want?"
//        "Hey user C, if I release today, I can get you 1000ML in 3 days. How much do you want?"
//     These numbers then have to go into a table, with the inflows (recession
//     factors?), and the losses etc.
//   - The water user is therefore only ever saying how much they will want in
//     3 days time (in this example). They're not saying "well if I can get it today
//     I'll take blah". This sounds like a limitation, but I think it's exactly
//     limitation that's been
//   - Distinguish between "order" and "demand":
//        - The order is what I'm going to ask the storage to release today.
//        - The demand is what I'm going to try to extract today.
//   - The basic version of the above is for the demand to lag behind the order
//     by x days. But maybe the demand is zero (for a non-consumptive user) or
//     maybe the order was zero (for an unregulated user).
//
//----------------------------------------------//

#[derive(Default, Clone)]
pub struct UserNode {
    pub name: String,
    pub location: Location,
    pub demand_def: InputDataDefinition,

    // Internal state only
    usflow: f64,
    dsflow_primary: f64,
    diversion: f64,
    storage: f64,

    // Recorders
    recorder_idx_usflow: Option<usize>,
    recorder_idx_demand: Option<usize>,
    recorder_idx_diversion: Option<usize>,
    recorder_idx_dsflow: Option<usize>,
    recorder_ids_ds_1: Option<usize>,
}


impl UserNode {

    /// Base constructor
    pub fn new() -> Self {
        Self {
            name: "".to_string(),
            ..Default::default()
        }
    }

    /// Base constructor with node name
    pub fn new_named(name: &str) -> Self {
        Self {
            name: name.to_string(),
            ..Default::default()
        }
    }
}

impl Node for UserNode {
    fn initialise(&mut self, data_cache: &mut DataCache) -> Result<(), String> {
        // Initialize only internal state
        self.usflow = 0.0;
        self.dsflow_primary = 0.0;
        self.diversion = 0.0;
        self.storage = 0.0;

        // Initialize input series
        self.demand_def.add_series_to_data_cache_if_required_and_get_idx(data_cache, true);

        // Initialize result recorders
        self.recorder_idx_usflow = data_cache.get_series_idx(
            make_result_name(&self.name, "usflow").as_str(), false
        );
        self.recorder_idx_demand = data_cache.get_series_idx(
            make_result_name(&self.name, "demand").as_str(), false
        );
        self.recorder_idx_diversion = data_cache.get_series_idx(
            make_result_name(&self.name, "diversion").as_str(), false
        );
        self.recorder_idx_dsflow = data_cache.get_series_idx(
            make_result_name(&self.name, "dsflow").as_str(), false
        );
        self.recorder_ids_ds_1 = data_cache.get_series_idx(
            make_result_name(&self.name, "ds_1").as_str(), false
        );

        // Return
        Ok(())
    }

    fn get_name(&self) -> &str { &self.name }

    fn run_flow_phase(&mut self, data_cache: &mut DataCache) {
        // Get demand from data_cache
        let mut demand = 0.0;
        if let Some(idx) = self.demand_def.idx {
            demand = data_cache.get_current_value(idx);
        }

        // Calculate diversion (take minimum of demand and available flow)
        self.diversion = demand.min(self.usflow);
        self.dsflow_primary = self.usflow - self.diversion;

        // Record results
        if let Some(idx) = self.recorder_idx_usflow {
            data_cache.add_value_at_index(idx, self.usflow);
        }
        if let Some(idx) = self.recorder_idx_demand {
            data_cache.add_value_at_index(idx, demand);
        }
        if let Some(idx) = self.recorder_idx_diversion {
            data_cache.add_value_at_index(idx, self.diversion);
        }
        if let Some(idx) = self.recorder_idx_dsflow {
            data_cache.add_value_at_index(idx, self.dsflow_primary);
        }
        if let Some(idx) = self.recorder_ids_ds_1 {
            data_cache.add_value_at_index(idx, self.dsflow_primary);
        }

        // Reset upstream inflow for next timestep
        self.usflow = 0.0;
    }

    fn add_usflow(&mut self, flow: f64, _inlet: u8) {
        self.usflow += flow;
    }

    fn remove_dsflow(&mut self, outlet: u8) -> f64 {
        match outlet {
            0 => {
                let outflow = self.dsflow_primary;
                self.dsflow_primary = 0.0;
                outflow
            }
            _ => 0.0,
        }
    }
}
