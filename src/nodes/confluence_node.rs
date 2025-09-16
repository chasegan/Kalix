use super::{make_result_name, Link, Node};
use uuid::Uuid;
use crate::data_cache::DataCache;
use crate::misc::location::Location;

#[derive(Default)]
#[derive(Clone)]
pub struct ConfluenceNode {
    pub name: String,
    pub id: Uuid,
    pub location: Location,

    //Links
    us_link: Link,
    ds_link_primary: Link,

    //Other vars including for calculations
    //and reporting
    us_flow: f64,
    ds_flow: f64,
    storage: f64,

    //Recorders
    recorder_idx_dsflow: Option<usize>,
}

impl ConfluenceNode {
    /*
    Constructor
    */
    pub fn new() -> ConfluenceNode {
        ConfluenceNode {
            name: "".to_string(),
            id: Uuid::new_v4(),
            ..Default::default()
        }
    }
}

impl Node for ConfluenceNode {
    /*
    Initialise node before model run
    */
    fn initialise(&mut self, data_cache: &mut DataCache) {
        self.us_link.flow = 0_f64;
        self.ds_link_primary.flow = 0_f64;
        self.us_flow = 0_f64;
        self.ds_flow = 0_f64;
        self.storage = 0_f64;

        //Initialize result recorders
        let node_name = self.name.clone();
        self.recorder_idx_dsflow = data_cache.get_series_idx(make_result_name(node_name.as_str(), "dsflow").as_str(), false);
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
        //Get flow from the upstream terminal if one has been defined
        self.us_flow = self.us_link.remove_flow();

        //For confluence nodes, ds_flow is equal to us_flow
        self.ds_flow = self.us_flow;

        //Give all the ds_flow water to the downstream link
        self.ds_link_primary.flow = self.ds_flow;

        //Record results
        if let Some(idx) = self.recorder_idx_dsflow {
            data_cache.add_value_at_index(idx, self.ds_flow)
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

