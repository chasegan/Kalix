use std::collections::HashMap;
use super::{Link, Node};
use crate::misc::misc_functions::make_result_name;
use crate::misc::input_data_definition::InputDataDefinition;
use crate::numerical::table::Table;
use crate::data_cache::DataCache;
use crate::misc::componenet_identification::ComponentIdentification;
use crate::misc::location::Location;

const LEVL: usize = 0;
const VOLU: usize = 1;
const AREA: usize = 2;
const SPIL: usize = 3;
const EPSILON: f64 = 1e-9;


#[derive(Default)]
#[derive(Clone)]
pub struct StorageNode {
    //Generic Node stuff
    pub name: String,
    pub location: Location,

    //Links
    pub us_link: Link,
    pub ds_link_primary: Link,
    pub ds_link_secondary: Link,

    //Storage vars including for calculations and reporting
    us_flow: f64,
    ds_flow: f64,
    storage: f64, //TODO: what is this? It looks like all nodes have "storage". Maybe i should use this instead of 'v'
    level: f64,
    pub d: Table,       //Level m, Volume ML, Area ha, Spill ML
    //d_delta: Table,     //d_Level m, d_Volume_ML, d_Area ha, d_Spill ML
    pub v: f64,
    pub v_initial: f64,
    pub area0: f64,

    //Daily inputs
    pub rain_mm_def: InputDataDefinition,
    pub evap_mm_def: InputDataDefinition,
    pub seep_mm_def: InputDataDefinition,
    pub demand_def: InputDataDefinition,

    //Daily outputs
    pub rain: f64,
    pub evap: f64,
    pub seep: f64,
    pub diversion: f64,
    pub spill: f64,

    //Recorders
    recorder_idx_dsflow: Option<usize>,
    recorder_idx_usflow: Option<usize>,
    recorder_idx_storage: Option<usize>,
}

impl StorageNode {
    /*
    Constructor
    */
    pub fn new() -> Self {
        Self {
            name: "".to_string(),
            d: Table::new(4),
            area0: -1.0,
            ..Default::default()
        }
    }
}

impl Node for StorageNode {
    /*
    Initialise node before model run
    */
    fn initialise(&mut self, data_cache: &mut DataCache, node_dictionary: &HashMap<String, usize>) {
        // Reset initial state values
        // (note some state variables will get overridden each timestep and maybe dont need resetting)
        self.us_link.flow = 0_f64;
        self.ds_link_primary.flow = 0_f64;
        self.us_flow = 0_f64;
        self.ds_flow = 0_f64;
        self.storage = self.v_initial;

        //Initialize inflow series
        self.rain_mm_def.add_series_to_data_cache_if_required_and_get_idx(data_cache, true);
        self.evap_mm_def.add_series_to_data_cache_if_required_and_get_idx(data_cache, true);
        self.seep_mm_def.add_series_to_data_cache_if_required_and_get_idx(data_cache, true);
        self.demand_def.add_series_to_data_cache_if_required_and_get_idx(data_cache, true);

        // Checks
        if self.d.nrows() < 2 {
            panic!("Error in storage node. Storage dimension table must have at least 2 rows.")
        }

        // Initial values and pre-calculations
        self.area0 = self.d.interpolate(VOLU, AREA, 0_f64);

        //Initialize result recorders
        let node_name = self.name.clone();
        self.recorder_idx_dsflow = data_cache.get_series_idx(make_result_name(node_name.as_str(), "dsflow").as_str(), false);
        self.recorder_idx_usflow = data_cache.get_series_idx(make_result_name(node_name.as_str(), "usflow").as_str(), false);
        self.recorder_idx_storage = data_cache.get_series_idx(make_result_name(node_name.as_str(), "storage").as_str(), false);

        //Initialize the links by converting any named links to indexed links.
        match &self.ds_link_primary.node_identification {
            ComponentIdentification::Named {name: n } => {
                let idx = node_dictionary[n];
                self.ds_link_primary = Link::new_indexed_link(idx);
            },
            _ => {}
        }
        match &self.ds_link_secondary.node_identification {
            ComponentIdentification::Named {name: n } => {
                let idx = node_dictionary[n];
                self.ds_link_secondary = Link::new_indexed_link(idx);
            },
            _ => {}
        }
    }


    /*
    Get the name of the node
     */
    fn get_name(&self) -> String { self.name.to_string() }


    /*
    Storage node processing follows BackwardEuler with a variation that
    diversion takes precedence over other fluxes. This means we can rely on:
         * being able to extract the full start-of-day storage volume (at least)
         * plus inflow if we know it
         * plus rainfall in excess of seep and evap
         * that a large demand will leave volume = 0 at the end of the day
     */
    fn run_flow_phase(&mut self, data_cache: &mut DataCache) {

        //Get flow from the upstream link
        self.us_flow = self.us_link.remove_flow();

        // Get the driving data
        let mut rain_mm = 0_f64;
        if let Some(idx) = self.rain_mm_def.idx {
            rain_mm = data_cache.get_current_value(idx);
        }
        let mut evap_mm = 0_f64;
        if let Some(idx) = self.evap_mm_def.idx {
            evap_mm = data_cache.get_current_value(idx);
        }
        let mut seep_mm = 0_f64;
        if let Some(idx) = self.seep_mm_def.idx {
            seep_mm = data_cache.get_current_value(idx);
        }
        let mut demand = 0_f64;
        if let Some(idx) = self.demand_def.idx {
            demand = data_cache.get_current_value(idx);
        }

        // TODO: Spill is zero when the volume is zero. MAKE THIS A REQUIREMENT of the storage table for consistency with the results.
        //  Also the table must start with volume = 0.

        // We already know what the upstream flows are, and by looking at the
        // net rainfall at dead storage, we can already know what the maximum
        // diversion could be.
        self.v += self.us_flow;
        let net_rain_mm = rain_mm - evap_mm - seep_mm;
        let net_rain_at_dead_storage = 0.01 * self.area0 * net_rain_mm;
        let max_diversion = self.v + net_rain_at_dead_storage.max(0_f64);
        self.diversion = demand.min(max_diversion);
        self.v -= self.diversion;

        // Now we just need to solve backward euler on the dimension table 'd'
        // to find the actual final solution (including net rainfall and spill
        // commensurate with the final storage level).
        //
        // The solution is likely to be between the table rows. The strategy is
        // therefore to (1) find which rows bound the solution (2) interpolate
        // between those rows.
        let mut error_prev = 0_f64;
        let mut error_i = 0_f64;
        let mut istop = 0;
        for i in 0..self.d.nrows() {
            istop = i; //why cant I just remember i?

            //TODO: this is the stupid version that iterates through every i... change it to bisection.

            // The predicted volume at any row 'i' in the table is given by:
            // (remembering that self.v already accounts for inflows and diversions)
            let predicted_volume = self.v +
                net_rain_mm * self.d.get_value(i, VOLU) - self.d.get_value(i, SPIL);

            // The row declares the final volume is = self.d.get_value(i, VOLU)
            // therefore the error associated with that row is:
            error_prev = error_i;
            error_i = self.d.get_value(i, VOLU) - predicted_volume;

            // A positive error means that the solution is somewhere before row i.
            if error_i >= 0_f64 { break }
        }
        if error_i < 0_f64 {
            // All error values are negative. This means that the volume went off the top of the table.
            //TODO: add some functionality to extrapolate using the previous two rows
            panic!("Error in storage node. Modelled volume exceeds the storage dimension table.")
        }
        if istop == 0 {
            if error_i < -EPSILON {
                panic!("Error in storage node. Negative error at row 0. How can this be?");
            }

            // The solution is equal to row 0. I think we can just update the error
            // values and i, and interpolate between row 0 and 1.
            istop = 1;
            error_prev = 0_f64;
            error_i = 1_f64; //any positive value should work here
        }
        // Now interpolate between i and i-1

        // Volume
        let x = error_prev / (error_prev - error_i);
        let v_lo = self.d.get_value(istop - 1, VOLU);
        let v_hi = self.d.get_value(istop, VOLU);
        let dv = v_hi - v_lo;
        let v = v_lo + dv * x;

        // Spill
        let spill = self.d.interpolate_row(istop -1, VOLU, SPIL, v);
        self.spill = spill;
        self.v -= spill;

        // Level and Area
        self.level = self.d.interpolate_row(istop -1, VOLU, LEVL, v);
        let area = self.d.interpolate_row(istop -1, VOLU, AREA, v);

        // Rainfall
        let rain = rain_mm * area * 0.01;
        self.rain = rain;
        self.v += rain;

        // Seep and Evap
        let seep_nom = seep_mm * area * 0.01;
        let evap_nom = evap_mm * area * 0.01;
        let seep_evap_nom = seep_nom + evap_nom;
        let mut seep_evap_factor = 1_f64;
        if seep_evap_nom > 0_f64 { seep_evap_factor = seep_evap_nom.min(self.v) / seep_evap_nom; }
        self.seep = seep_nom * seep_evap_factor;
        self.evap = evap_nom * seep_evap_factor;
        self.v = self.v - self.seep - self.evap;

        // Check the answer
        if (self.v - v).abs() > EPSILON {
            println!("About to panic");
            println!("v = {}, self.v = {}", v, self.v);
            println!("self.spill = {}", self.spill);
            println!("self.seep = {}, self.evap = {}, self.rain = {}", self.seep, self.evap, self.rain);
            println!("self.us_flow = {}, self.diversion = {}", self.us_flow, self.diversion);
            panic!("Error in storage node. Mass balance was wrong. Solution should be {} but vol={}", v, self.v);
        }

        // Only spills go downstream
        self.ds_flow = self.spill;

        // Give all the ds_flow water to the downstream terminal
        self.ds_link_primary.flow = self.ds_flow;
        // self.ds_link_secondary.flow = 0_f64;

        //Record results
        if let Some(idx) = self.recorder_idx_dsflow {
            data_cache.add_value_at_index(idx, self.ds_flow)
        }
        if let Some(idx) = self.recorder_idx_usflow {
            data_cache.add_value_at_index(idx, self.us_flow)
        }
        if let Some(idx) = self.recorder_idx_storage {
            data_cache.add_value_at_index(idx, self.storage)
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
        self.ds_link_primary.remove_flow() +
            self.ds_link_secondary.remove_flow()
    }

    fn get_ds_links(&self) -> [Link; 2] {
        [self.ds_link_primary.clone(), self.ds_link_secondary.clone()]
    }
}
