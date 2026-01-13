use super::Node;
use crate::misc::misc_functions::make_result_name;
use crate::model_inputs::DynamicInput;
use crate::numerical::table::Table;
use crate::data_management::data_cache::DataCache;
use crate::misc::location::Location;

const LEVL: usize = 0;
const VOLU: usize = 1;
const AREA: usize = 2;
const SPIL: usize = 3;
const EPSILON: f64 = 1e-3;
const MAX_DS_LINKS: usize = 5;

#[derive(Default, Clone)]
pub struct StorageNode {
    pub name: String,
    pub location: Location,
    pub mbal: f64,
    pub d: Table,       // Level m, Volume ML, Area km2, Spill ML
    pub v: f64,
    pub v_initial: f64,
    pub area0_km2: f64, // Dead storage area interpolated from 'd' table during node initialisation
    pub rain_mm_input: DynamicInput,
    pub evap_mm_input: DynamicInput,
    pub seep_mm_input: DynamicInput,
    pub pond_demand_input: DynamicInput,

    // Internal state only
    usflow: f64,
    dsflow: f64,
    ds_1_flow: f64,
    ds_2_flow: f64,
    ds_3_flow: f64,
    ds_4_flow: f64,
    level: f64,
    rain_vol: f64,
    evap_vol: f64,
    seep_vol: f64,
    pond_diversion: f64, //pond diversion
    spill: f64,

    // Cached state for search optimization
    previous_istop: usize,  // Remember previous solution row for warm start

    // Orders
    pub dsorders: [f64; MAX_DS_LINKS],

    // Recorders
    recorder_idx_usflow: Option<usize>,
    recorder_idx_volume: Option<usize>,
    recorder_idx_level: Option<usize>,
    recorder_idx_area: Option<usize>,
    recorder_idx_seep: Option<usize>,
    recorder_idx_evap: Option<usize>,
    recorder_idx_rain: Option<usize>,
    recorder_idx_pond_diversion: Option<usize>,
    recorder_idx_dsflow: Option<usize>,
    recorder_idx_ds_1: Option<usize>,
    recorder_idx_ds_2: Option<usize>,
    recorder_idx_ds_3: Option<usize>,
    recorder_idx_ds_4: Option<usize>,
}

impl StorageNode {

    /// Base constructor
    pub fn new() -> Self {
        Self {
            name: "".to_string(),
            d: Table::new(4),
            ..Default::default()
        }
    }
}

impl Node for StorageNode {

    fn initialise(&mut self, data_cache: &mut DataCache) -> Result<(),String> {
        // Initialize only internal state
        self.mbal = 0.0;
        self.usflow = 0.0;
        self.dsflow = 0.0;
        self.ds_1_flow = 0.0;
        self.ds_2_flow = 0.0;
        self.ds_3_flow = 0.0;
        self.ds_4_flow = 0.0;
        self.v = self.v_initial;
        self.level = 0.0;
        self.rain_vol = 0.0;
        self.evap_vol = 0.0;
        self.seep_vol = 0.0;
        self.pond_diversion = 0.0;
        self.spill = 0.0;
        self.previous_istop = 0;  // Will be updated after first timestep

        //Initialize inflow series
        // All DynamicInput fields are already initialized during parsing

        // Checks
        if self.d.nrows() < 2 {
            let message = format!("Error in node '{}'. Storage dimension table must have at least 2 rows.", self.name);
            return Err(message);
        }

        // Initial values and pre-calculations
        self.area0_km2 = self.d.interpolate(VOLU, AREA, 0_f64); // Area at dead storage

        // Initialize result recorders
        self.recorder_idx_usflow = data_cache.get_series_idx(
            make_result_name(&self.name, "usflow").as_str(), false
        );
        self.recorder_idx_volume = data_cache.get_series_idx(
            make_result_name(&self.name, "volume").as_str(), false
        );
        self.recorder_idx_level = data_cache.get_series_idx(
            make_result_name(&self.name, "level").as_str(), false
        );
        self.recorder_idx_area = data_cache.get_series_idx(
            make_result_name(&self.name, "area").as_str(), false
        );
        self.recorder_idx_seep = data_cache.get_series_idx(
            make_result_name(&self.name, "seep").as_str(), false
        );
        self.recorder_idx_rain = data_cache.get_series_idx(
            make_result_name(&self.name, "rain").as_str(), false
        );
        self.recorder_idx_evap = data_cache.get_series_idx(
            make_result_name(&self.name, "evap").as_str(), false
        );
        self.recorder_idx_pond_diversion = data_cache.get_series_idx(
            make_result_name(&self.name, "pond_diversion").as_str(), false
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
        self.recorder_idx_ds_3 = data_cache.get_series_idx(
            make_result_name(&self.name, "ds_3").as_str(), false
        );
        self.recorder_idx_ds_4 = data_cache.get_series_idx(
            make_result_name(&self.name, "ds_4").as_str(), false
        );

        Ok(())
    }

    fn get_name(&self) -> &str { &self.name }

    fn run_flow_phase(&mut self, data_cache: &mut DataCache) {

        // Get the driving data
        let rain_mm = self.rain_mm_input.get_value(data_cache);
        let evap_mm = self.evap_mm_input.get_value(data_cache);
        let seep_mm = self.seep_mm_input.get_value(data_cache);
        let pond_demand = self.pond_demand_input.get_value(data_cache);

        // TODO: Spill is zero when the volume is zero. MAKE THIS A REQUIREMENT of the storage
        //  table for consistency with the results. Also the table must start with volume = 0.

        // We already know what the upstream flows are, and by looking at the
        // net rainfall at dead storage, we can already know what the maximum
        // diversion could be.
        self.v += self.usflow;
        let net_rain_mm = rain_mm - evap_mm - seep_mm;
        let net_rain_at_dead_storage = self.area0_km2 * net_rain_mm;
        let mut max_diversion = self.v + net_rain_at_dead_storage.max(0_f64);

        // Pond demands
        if pond_demand < max_diversion {
            self.pond_diversion = pond_demand;
            max_diversion -= self.pond_diversion;
        } else {
            self.pond_diversion = max_diversion;
            max_diversion = 0f64;
        }
        self.v -= self.pond_diversion; //TODO: potentially negative here, but I guess comes good later. Is there anything wrong this?

        // ds_1 ds_2 ds_3 ds_4 demands
        // TODO: for ds_1, demands may be met by spills, but the current logic does not account for that
        for i in 0..MAX_DS_LINKS {
            let mut release = 0f64;
            if self.dsorders[i] == 0f64 {
                // nothing
            } else if self.dsorders[i] < max_diversion {
                release = self.dsorders[i];
                max_diversion -= release;
                self.v -= release; //TODO: potentially negative here, but I guess comes good later. Is there anything wrong this?
            } else {
                release = max_diversion;
                max_diversion = 0f64;
                self.v -= release; //TODO: potentially negative here, but I guess comes good later. Is there anything wrong this?
            }
            match i {
                0 => { self.ds_1_flow = release; }
                1 => { self.ds_2_flow = release; }
                2 => { self.ds_3_flow = release; }
                3 => { self.ds_4_flow = release; }
                _ => { }
            }
        }

        // Now we just need to solve backward euler on the dimension table 'd'
        // to find the actual final solution (including net rainfall and spill
        // commensurate with the final storage level).
        //
        // The solution is likely to be between the table rows. The strategy is
        // therefore to (1) find which rows bound the solution (2) interpolate
        // between those rows.
        //
        // We use exponential expansion from the previous timestep's solution,
        // followed by bisection. This gives O(log k) complexity where k is the
        // number of rows moved since last timestep (typically 1-3).

        // Helper to compute error at a given row
        // Error = table_volume - predicted_volume
        // Positive error means solution is at or below this row
        // Negative error means solution is above this row
        let compute_error = |i: usize| -> f64 {
            let predicted_volume = self.v +
                net_rain_mm * self.d.get_value(i, AREA) - self.d.get_value(i, SPIL);
            self.d.get_value(i, VOLU) - predicted_volume
        };

        let nrows = self.d.nrows();
        let mut lo: usize;
        let mut hi: usize;

        // Start from previous solution (clamped to valid range)
        let start = self.previous_istop.min(nrows - 1);
        let error_start = compute_error(start);

        if error_start < 0.0 {
            // Solution is ABOVE start - expand upward exponentially
            lo = start;
            let mut step = 1;
            hi = (start + step).min(nrows - 1);

            while compute_error(hi) < 0.0 && hi < nrows - 1 {
                lo = hi;
                step *= 2;
                hi = (hi + step).min(nrows - 1);
            }
        } else {
            // Solution is AT or BELOW start - expand downward exponentially
            hi = start;
            let mut step = 1;
            lo = start.saturating_sub(step);

            while compute_error(lo) >= 0.0 && lo > 0 {
                hi = lo;
                step *= 2;
                lo = lo.saturating_sub(step);
            }
        }

        // Bisect between lo and hi to find exact bracket
        while hi - lo > 1 {
            let mid = lo + (hi - lo) / 2;
            if compute_error(mid) < 0.0 {
                lo = mid;
            } else {
                hi = mid;
            }
        }

        // hi is now istop (first row where error >= 0)
        // lo is istop - 1 (last row where error < 0), unless solution is at row 0
        let istop = hi;
        let error_i = compute_error(istop);
        let error_prev = if istop > 0 { compute_error(istop - 1) } else { 0.0 };

        // Remember for next timestep
        self.previous_istop = istop;

        // Check for off-the-table condition
        if error_i < 0.0 {
            // All error values are negative. Volume exceeds the table.
            panic!("Error in storage node '{}'. Modelled volume exceeds the storage dimension table.", self.name);
        }

        // Handle solution at row 0
        let (istop, error_prev, error_i) = if istop == 0 {
            if error_i < -EPSILON {
                panic!("Error in storage node '{}'. Negative error at row 0.", self.name);
            }
            // Solution is at or below row 0 - interpolate between row 0 and 1
            (1, 0.0_f64, 1.0_f64)
        } else {
            (istop, error_prev, error_i)
        };
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

        // // Check the answer
        // // TODO: Can we get rid of this?
        // if (self.v - v).abs() > EPSILON {
        //     println!("About to panic");
        //     println!("v = {}, self.v = {}", v, self.v);
        //     println!("self.spill = {}", self.spill);
        //     println!("self.seep = {}, self.evap = {}, self.rain = {}", self.seep_vol, self.evap_vol, self.rain_vol);
        //     println!("self.upstream_inflow = {}, self.diversion = {}", self.usflow, self.diversion);
        //     panic!("Error in {}. Mass balance was wrong. Solution should be {} but vol={}", self.name, v, self.v);
        // }

        // Only spills go downstream via primary outlet
        // self.ds_1_flow = self.spill;
        self.ds_1_flow += self.spill;
        // self.ds_2_flow = 0.0;  // Not implemented yet
        // self.ds_3_flow = 0.0;  // Not implemented yet
        // self.ds_4_flow = 0.0;  // Not implemented yet
        self.dsflow = self.ds_1_flow + (self.ds_2_flow + self.ds_3_flow + self.ds_4_flow);

        // Update mass balance
        self.mbal += self.dsflow - self.usflow;

        // Record results
        if let Some(idx) = self.recorder_idx_usflow {
            data_cache.add_value_at_index(idx, self.usflow);
        }
        if let Some(idx) = self.recorder_idx_volume {
            data_cache.add_value_at_index(idx, self.v);
        }
        if let Some(idx) = self.recorder_idx_level {
            data_cache.add_value_at_index(idx, self.level);
        }
        if let Some(idx) = self.recorder_idx_area {
            data_cache.add_value_at_index(idx, area_km2);
        }
        if let Some(idx) = self.recorder_idx_seep {
            data_cache.add_value_at_index(idx, self.seep_vol);
        }
        if let Some(idx) = self.recorder_idx_rain {
            data_cache.add_value_at_index(idx, self.rain_vol);
        }
        if let Some(idx) = self.recorder_idx_evap {
            data_cache.add_value_at_index(idx, self.evap_vol);
        }
        if let Some(idx) = self.recorder_idx_pond_diversion {
            data_cache.add_value_at_index(idx, self.pond_diversion);
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
        if let Some(idx) = self.recorder_idx_ds_3 {
            data_cache.add_value_at_index(idx, self.ds_3_flow);
        }
        if let Some(idx) = self.recorder_idx_ds_4 {
            data_cache.add_value_at_index(idx, self.ds_4_flow);
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
            2 => {
                let outflow = self.ds_3_flow;
                self.ds_3_flow = 0.0;
                outflow
            }
            3 => {
                let outflow = self.ds_4_flow;
                self.ds_4_flow = 0.0;
                outflow
            }
            _ => 0.0,
        }
    }

    fn get_mass_balance(&self) -> f64 {
        self.mbal
    }

    fn dsorders_mut(&mut self) -> &mut [f64] {
        &mut self.dsorders
    }
}
