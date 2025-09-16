use super::{make_result_name, InputDataDefinition, Link, Node};
use uuid::Uuid;
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


#[derive(Default)]
#[derive(Clone)]
pub struct DiversionNode {
    //Generic Node stuff
    pub name: String,
    pub id: Uuid,
    pub location: Location,

    //Links
    us_link: Link,
    ds_link_primary: Link,

    //Inputs
    pub demand_def: InputDataDefinition,

    //Other vars including for calculations
    //and reporting
    us_flow: f64,
    pub ds_flow: f64,
    storage: f64,

    //Recorders
    recorder_idx_usflow: Option<usize>,
    recorder_idx_dsflow: Option<usize>,
    recorder_idx_diversion: Option<usize>,
    recorder_idx_demand: Option<usize>,
}



impl DiversionNode {
    /*
    Constructor
    */
    pub fn new() -> Self {
        Self {
            name: "".to_string(),
            id: Uuid::new_v4(),
            ..Default::default()
        }
    }
}

impl Node for DiversionNode {
    /*
    Initialise node before model run
    */
    fn initialise(&mut self, data_cache: &mut DataCache) {
        self.us_link.flow = 0_f64;
        self.ds_link_primary.flow = 0_f64;
        self.us_flow = 0_f64;
        self.ds_flow = 0_f64;
        self.storage = 0_f64;

        //Initialize input series
        self.demand_def.add_series_to_data_cache_if_required_and_get_idx(data_cache, true);

        //Initialize result recorders
        let node_name = self.name.clone();
        self.recorder_idx_usflow = data_cache.get_series_idx(make_result_name(node_name.as_str(), "dsflow").as_str(), false);
        self.recorder_idx_diversion = data_cache.get_series_idx(make_result_name(node_name.as_str(), "diversion").as_str(), false);
        self.recorder_idx_demand = data_cache.get_series_idx(make_result_name(node_name.as_str(), "demand").as_str(), false);
    }


    /*
    Get the id of the node
    */
    fn get_id(&self) -> Uuid {
        self.id
    }


    /*
    Runs the node for the current timestep and updates the node state
     */
    fn run_flow_phase(&mut self, data_cache: &mut DataCache) {

        //Get flow from the upstream link
        self.us_flow = self.us_link.remove_flow();

        // Get driving data
        let mut demand = 0_f64;
        if let Some(idx) = self.demand_def.idx {
            demand = data_cache.get_current_value(idx);
        }

        //For diversion nodes
        let diversion = demand.min(self.us_flow);
        self.ds_flow = self.us_flow - diversion;

        //Give all the ds_flow water to the downstream link
        self.ds_link_primary.flow = self.ds_flow;

        //Record results
        if let Some(idx) = self.recorder_idx_dsflow {
            data_cache.add_value_at_index(idx, self.ds_flow)
        }
        if let Some(idx) = self.recorder_idx_usflow {
            data_cache.add_value_at_index(idx, self.us_flow)
        }
        if let Some(idx) = self.recorder_idx_diversion {
            data_cache.add_value_at_index(idx, diversion)
        }
        if let Some(idx) = self.recorder_idx_demand {
            data_cache.add_value_at_index(idx, demand)
        }
    }


    #[allow(unused_variables)]
    // TODO: remove unused index i?
    fn add_inflow(&mut self, v: f64, i: i32) {
        self.us_link.flow += v;
    }

    #[allow(unused_variables)]
    // TODO: remove unused index i?
    fn remove_outflow(&mut self, i: i32) -> f64 {
        self.ds_link_primary.remove_flow()
    }
}
