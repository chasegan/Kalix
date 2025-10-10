use super::Node;
use crate::misc::misc_functions::make_result_name;
use crate::model_inputs::DynamicInput;
use crate::numerical::table::Table;
use crate::data_cache::DataCache;
use crate::misc::location::Location;

const LEVL: usize = 0;
const VOLU: usize = 1;
const AREA: usize = 2;
const SPIL: usize = 3;
const EPSILON: f64 = 1e-3;


#[derive(Default, Clone)]
pub struct StorageNode {
    pub name: String,
    pub location: Location,
    pub d: Table,       // Level m, Volume ML, Area ha, Spill ML
    pub v: f64,
    pub v_initial: f64,
    pub area0: f64,
    pub rain_mm_input: DynamicInput,
    pub evap_mm_input: DynamicInput,
    pub seep_mm_input: DynamicInput,
    pub demand_input: DynamicInput,

    // Internal state only
    usflow: f64,
    dsflow: f64,
    ds_1_flow: f64,
    ds_2_flow: f64,
    level: f64,
    rain_vol: f64,
    evap_vol: f64,
    seep_vol: f64,
    diversion: f64,
    spill: f64,

    // Recorders
    recorder_idx_usflow: Option<usize>,
    recorder_idx_volume: Option<usize>,
    recorder_idx_dsflow: Option<usize>,
    recorder_idx_ds_1: Option<usize>,
    recorder_idx_ds_2: Option<usize>,
}

impl StorageNode {

    /// Base constructor
    pub fn new() -> Self {
        Self {
            name: "".to_string(),
            d: Table::new(4),
            area0: -1.0,
            ..Default::default()
        }
    }

    /// Base constructor with node name
    pub fn new_named(name: &str) -> Self {
        Self {
            name: name.to_string(),
            d: Table::new(4),
            area0: -1.0,
            ..Default::default()
        }
    }
}

impl Node for StorageNode {

    fn initialise(&mut self, data_cache: &mut DataCache) -> Result<(),String> {
        // Initialize only internal state
        self.usflow = 0.0;
        self.dsflow = 0.0;
        self.ds_1_flow = 0.0;
        self.ds_2_flow = 0.0;
        self.v = self.v_initial;
        self.level = 0.0;
        self.rain_vol = 0.0;
        self.evap_vol = 0.0;
        self.seep_vol = 0.0;
        self.diversion = 0.0;
        self.spill = 0.0;

        //Initialize inflow series
        // All DynamicInput fields are already initialized during parsing

        // Checks
        if self.d.nrows() < 2 {
            let message = format!("Error in node '{}'. Storage dimension table must have at least 2 rows.", self.name);
            return Err(message);
        }

        // Initial values and pre-calculations
        self.area0 = self.d.interpolate(VOLU, AREA, 0_f64);

        // Initialize result recorders
        self.recorder_idx_usflow = data_cache.get_series_idx(
            make_result_name(&self.name, "usflow").as_str(), false
        );
        self.recorder_idx_volume = data_cache.get_series_idx(
            make_result_name(&self.name, "volume").as_str(), false
        );
        self.recorder_idx_dsflow = data_cache.get_series_idx(
            make_result_name(&self.name, "dsflow").as_str(), false
        );
        self.recorder_idx_ds_1 = data_cache.get_series_idx(
            make_result_name(&self.name, "ds_1").as_str(), false
        );
        self.recorder_idx_ds_2 = data_cache.get_series_idx(
            make_result_name(&self.name, "ds_2").as_str(), false
        );

        Ok(())
    }

    fn get_name(&self) -> &str { &self.name }

    fn run_flow_phase(&mut self, data_cache: &mut DataCache) {

        // Get the driving data
        let rain_mm = self.rain_mm_input.get_value(data_cache);
        let evap_mm = self.evap_mm_input.get_value(data_cache);
        let seep_mm = self.seep_mm_input.get_value(data_cache);
        let demand = self.demand_input.get_value(data_cache);

        // TODO: Spill is zero when the volume is zero. MAKE THIS A REQUIREMENT of the storage
        //  table for consistency with the results. Also the table must start with volume = 0.

        // We already know what the upstream flows are, and by looking at the
        // net rainfall at dead storage, we can already know what the maximum
        // diversion could be.
        self.v += self.usflow;
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
            // 1mm * 1km2 = 1ML
            let predicted_volume = self.v +
                net_rain_mm * self.d.get_value(i, AREA) - self.d.get_value(i, SPIL);

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
        // Now interpolate between row i and i-1

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
        let area_km2 = self.d.interpolate_row(istop -1, VOLU, AREA, v);

        // Rainfall
        let rain_vol = rain_mm * area_km2;
        self.rain_vol = rain_vol;
        self.v += rain_vol;

        // Seep and Evap
        let seep_vol_nominal = seep_mm * area_km2;
        let evap_vol_nominal = evap_mm * area_km2;
        let seep_evap_vol_nominal = seep_vol_nominal + evap_vol_nominal;
        if seep_evap_vol_nominal > 0_f64 {
            // Seepage+evap may need to be scaled down if self.v does not cover nominal seep+evap value
            let seep_evap_factor = seep_evap_vol_nominal.min(self.v) / seep_evap_vol_nominal;
            self.seep_vol = seep_vol_nominal * seep_evap_factor;
            self.evap_vol = evap_vol_nominal * seep_evap_factor;
            self.v -= seep_evap_vol_nominal * seep_evap_factor;
        } else {
            self.seep_vol = seep_vol_nominal;
            self.evap_vol = evap_vol_nominal;
            self.v -= seep_vol_nominal;
        }

        // Check the answer
        // TODO: Can we get rid of this?
        if (self.v - v).abs() > EPSILON {
            println!("About to panic");
            println!("v = {}, self.v = {}", v, self.v);
            println!("self.spill = {}", self.spill);
            println!("self.seep = {}, self.evap = {}, self.rain = {}", self.seep_vol, self.evap_vol, self.rain_vol);
            println!("self.upstream_inflow = {}, self.diversion = {}", self.usflow, self.diversion);
            panic!("Error in {}. Mass balance was wrong. Solution should be {} but vol={}", self.name, v, self.v);
        }

        // Only spills go downstream via primary outlet
        self.ds_1_flow = self.spill;
        self.ds_2_flow = 0.0;  // Not implemented yet
        self.dsflow = self.ds_1_flow + self.ds_2_flow;

        // Record results
        if let Some(idx) = self.recorder_idx_usflow {
            data_cache.add_value_at_index(idx, self.usflow);
        }
        if let Some(idx) = self.recorder_idx_volume {
            data_cache.add_value_at_index(idx, self.v);
        }
        if let Some(idx) = self.recorder_idx_dsflow {
            data_cache.add_value_at_index(idx, self.dsflow);
        }
        if let Some(idx) = self.recorder_idx_ds_1 {
            data_cache.add_value_at_index(idx, self.ds_1_flow);
        }
        if let Some(idx) = self.recorder_idx_ds_2 {
            data_cache.add_value_at_index(idx, self.ds_2_flow);
        }

        // Reset upstream inflow for next timestep
        self.usflow = 0.0;
    }

    fn add_usflow(&mut self, flow: f64, _inlet: u8) {
        self.usflow += flow;
    }

    /// Storage node processing follows BackwardEuler with a variation that
    /// diversion takes precedence over other fluxes. This means we can rely on:
    ///      * being able to extract the full start-of-day storage volume (at least)
    ///      * plus inflow if we know it
    ///      * plus rainfall in excess of seep and evap
    ///      * that a large demand will leave volume = 0 at the end of the day
    fn remove_dsflow(&mut self, outlet: u8) -> f64 {
        match outlet {
            0 => {
                let outflow = self.ds_1_flow;
                self.ds_1_flow = 0.0;
                outflow
            }
            1 => {
                let outflow = self.ds_2_flow;
                self.ds_2_flow = 0.0;
                outflow
            }
            _ => 0.0,
        }
    }
}
