use std::collections::HashMap;
use super::{Link, Node};
use crate::misc::misc_functions::make_result_name;
use crate::data_cache::DataCache;
use crate::misc::componenet_identification::ComponentIdentification;
use crate::misc::location::Location;

#[derive(Default)]
#[derive(Clone)]
pub struct ConfluenceNode {
    pub name: String,
    pub location: Location,

    //Links
    pub us_link: Link,
    pub ds_link_primary: Link,

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
            ..Default::default()
        }
    }
}

impl Node for ConfluenceNode {
    /*
    Initialise node before model run
    */
    fn initialise(&mut self, data_cache: &mut DataCache, node_dictionary: &HashMap<String, usize>) {
        self.us_link.flow = 0_f64;
        self.ds_link_primary.flow = 0_f64;
        self.us_flow = 0_f64;
        self.ds_flow = 0_f64;
        self.storage = 0_f64;

        //Initialize result recorders
        let node_name = self.name.clone();
        self.recorder_idx_dsflow = data_cache.get_series_idx(make_result_name(node_name.as_str(), "dsflow").as_str(), false);

        //Initialize the links by converting any named links to indexed links.
        match &self.ds_link_primary.node_identification {
            ComponentIdentification::Named {name: n } => {
                let idx = node_dictionary[n];
                self.ds_link_primary = Link::new_indexed_link(idx);
            },
            _ => {}
        }
    }


    /*
    Get the name of the node
     */
    fn get_name(&self) -> String { self.name.to_string() }


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

    fn get_ds_links(&self) -> [Link; 2] {
        [self.ds_link_primary.clone(), Link::new_unconnected_link()]
    }
}

