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
const MAX_DS_LINKS: usize = 4;

/// Defines outlet configuration including minimum operating level (MOL) and capacity.
/// MOL is specified as a level (m) and converted to volume internally.
#[derive(Default, Clone, Copy, Debug, PartialEq)]
pub enum OutletDefinition {
    #[default]
    None,
    OutletWithMOL(f64),                    // MOL level in metres
    OutletWithAndMOLAndCapacity(f64, f64), // MOL level, capacity
}

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

    // Outlet definitions (MOL, capacity) - parsed from INI
    pub outlet_definition: [OutletDefinition; MAX_DS_LINKS],

    // Minimum operating volume for each outlet (converted from MOL level during init)
    // 0.0 means no MOL constraint (outlet always active)
    min_operating_volume: [f64; MAX_DS_LINKS],

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
    recorder_idx_ds_1_order: Option<usize>,
    recorder_idx_ds_2: Option<usize>,
    recorder_idx_ds_2_order: Option<usize>,
    recorder_idx_ds_3: Option<usize>,
    recorder_idx_ds_3_order: Option<usize>,
    recorder_idx_ds_4: Option<usize>,
    recorder_idx_ds_4_order: Option<usize>,
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

    // -------------------------------------------------------------------------
    // Backward Euler Solver with MOL Support
    // -------------------------------------------------------------------------
    //
    // Terminology for ds_1 flows:
    // - "ds_1_spill": uncontrolled overflow via the spillway, counts towards ds_1 orders
    // - "ds_1_outlet": controlled outlet flow, supplements spill to meet ds_1 orders
    // - "ds_1" (total): ds_1_spill + ds_1_outlet
    //
    // For ds_2, ds_3, ds_4: flow = outlet flow only (no spill component)
    //

    /// Determines which outlets are active (able to release) at a given volume.
    /// An outlet is active if volume >= its minimum operating volume.
    /// Returns a bitmask: bit i is set if outlet i is active.
    fn active_outlets_at_volume(&self, volume: f64) -> u8 {
        let mut active = 0u8;
        for i in 0..MAX_DS_LINKS {
            if self.dsorders[i] > 0.0 && volume >= self.min_operating_volume[i] {
                active |= 1 << i;
            }
        }
        active
    }

    /// Sums the orders for outlets specified by the mask.
    /// Bit i in the mask corresponds to outlet i (ds_1=bit 0, ds_2=bit 1, etc).
    fn sum_orders(&self, outlet_mask: u8) -> f64 {
        let mut total = 0.0;
        for i in 0..MAX_DS_LINKS {
            if outlet_mask & (1 << i) != 0 {
                total += self.dsorders[i];
            }
        }
        total
    }

    /// Solves the backward Euler equation for equilibrium volume.
    ///
    /// Uses a two-pass approach for ds_1:
    /// - Pass 1: Solve spill-limited case (ds_1_outlet = 0, spill alone may meet order)
    /// - If spill >= ds_1_order: done, ds_1 flow = spill
    /// - Pass 2: Solve order-limited case (ds_1_outlet = order, subject to MOL)
    ///
    /// Returns (final_volume, ds_flows[4], spill)
    fn solve_backward_euler(
        &self,
        v_working: f64,
        net_rain_mm: f64,
    ) -> (f64, [f64; MAX_DS_LINKS], f64) {
        let nrows = self.d.nrows();
        let ds_1_order = self.dsorders[0];

        // --- Pass 1: Solve spill-limited case (no controlled release on ds_1) ---
        let (v_spill_only, spill) = self.solve_spill_limited_case(v_working, net_rain_mm, nrows);

        if spill >= ds_1_order {
            // Spill satisfies ds_1 order - no controlled release needed
            let active = self.active_outlets_at_volume(v_spill_only);
            let mut ds_flows = [0.0; MAX_DS_LINKS];
            ds_flows[0] = spill;  // ds_1 gets the full spill
            for i in 1..MAX_DS_LINKS {
                if active & (1 << i) != 0 {
                    ds_flows[i] = self.dsorders[i];
                }
            }
            return (v_spill_only, ds_flows, spill);
        }

        // --- Pass 2: Solve order-limited case (ds_1 needs controlled release) ---
        let (v_with_release, spill2) = self.solve_order_limited_case(v_working, net_rain_mm, ds_1_order, nrows);

        let active = self.active_outlets_at_volume(v_with_release);
        let ds_1_active = (active & 1) != 0;

        let mut ds_flows = [0.0; MAX_DS_LINKS];
        if ds_1_active {
            // ds_1 releases its order plus any spill
            ds_flows[0] = spill2 + ds_1_order;
        } else {
            // ds_1 below MOL - only spill flows
            ds_flows[0] = spill2;
        }
        for i in 1..MAX_DS_LINKS {
            if active & (1 << i) != 0 {
                ds_flows[i] = self.dsorders[i];
            }
        }

        (v_with_release, ds_flows, spill2)
    }

    /// Solves the spill-limited case: no required ds_1 flow, spill alone determines ds_1.
    /// Returns (equilibrium_volume, spill_at_equilibrium)
    fn solve_spill_limited_case(
        &self,
        v_working: f64,
        net_rain_mm: f64,
        nrows: usize,
    ) -> (f64, f64) {
        self.solve_with_outflows(v_working, net_rain_mm, 0.0, nrows)
    }

    /// Solves the order-limited case: ds_1 must flow at least the order amount.
    /// Returns (equilibrium_volume, spill_at_equilibrium)
    fn solve_order_limited_case(
        &self,
        v_working: f64,
        net_rain_mm: f64,
        ds_1_order: f64,
        nrows: usize,
    ) -> (f64, f64) {
        self.solve_with_outflows(v_working, net_rain_mm, ds_1_order, nrows)
    }

    /// Solves for equilibrium volume with a required minimum ds_1 flow.
    /// The actual ds_1 contribution to mass balance is max(spill, ds1_required_flow).
    /// Handles MOL thresholds for ds_2, ds_3, ds_4 via iteration.
    /// Returns (equilibrium_volume, spill_at_equilibrium)
    fn solve_with_outflows(
        &self,
        v_working: f64,
        net_rain_mm: f64,
        ds1_required_flow: f64,
        nrows: usize,
    ) -> (f64, f64) {
        // Start with outlets active based on current volume
        let mut active = self.active_outlets_at_volume(v_working);

        const MAX_ITERATIONS: usize = 8;

        for _iter in 0..MAX_ITERATIONS {
            // Sum orders for ds_2, ds_3, ds_4 based on active set
            let ds234_orders = self.sum_orders(active & 0b1110);

            // Find equilibrium volume
            let v_candidate = self.find_equilibrium_volume(
                v_working, net_rain_mm, ds1_required_flow, ds234_orders, nrows
            );

            // Check which outlets should be active at the candidate volume
            let new_active = self.active_outlets_at_volume(v_candidate);

            if new_active == active {
                // Converged
                let spill = self.d.interpolate(VOLU, SPIL, v_candidate).max(0.0);
                return (v_candidate, spill);
            }

            // Active set changed - check if equilibrium is at a threshold
            if let Some((threshold_vol, _throttled_outlet)) =
                self.find_crossed_threshold(v_candidate, active, new_active)
            {
                // Try equilibrium at threshold
                let area = self.d.interpolate(VOLU, AREA, threshold_vol);
                let spill = self.d.interpolate(VOLU, SPIL, threshold_vol).max(0.0);
                let outflow_needed = v_working + net_rain_mm * area - threshold_vol;
                let ds1_flow = spill.max(ds1_required_flow);
                let total_outflow = ds1_flow + ds234_orders;

                if outflow_needed >= 0.0 && outflow_needed <= total_outflow + EPSILON {
                    return (threshold_vol, spill);
                }
            }

            active = new_active;
        }

        // Fallback
        let spill = self.d.interpolate(VOLU, SPIL, v_working).max(0.0);
        (v_working, spill)
    }

    /// Finds equilibrium volume given required ds_1 flow and ds_2/3/4 orders.
    /// Mass balance: v = v_working + net_rain*area(v) - max(spill(v), ds1_required_flow) - ds234_orders
    /// Uses exponential expansion + bisection to find the table row,
    /// then linear interpolation within the row.
    fn find_equilibrium_volume(
        &self,
        v_working: f64,
        net_rain_mm: f64,
        ds1_required_flow: f64,
        ds234_orders: f64,
        nrows: usize,
    ) -> f64 {
        // Error function: positive means solution is at or below this row
        let compute_error = |row: usize| -> f64 {
            let table_vol = self.d.get_value(row, VOLU);
            let area = self.d.get_value(row, AREA);
            let spill = self.d.get_value(row, SPIL).max(0.0);

            let ds1_flow = spill.max(ds1_required_flow);
            let total_outflow = ds1_flow + ds234_orders;
            let predicted = v_working + net_rain_mm * area - total_outflow;
            table_vol - predicted
        };

        // Exponential expansion from previous solution
        let start = self.previous_istop.min(nrows - 1);
        let error_start = compute_error(start);

        let (mut lo, mut hi) = if error_start < 0.0 {
            // Solution is above start row - expand upward
            let mut lo = start;
            let mut step = 1;
            let mut hi = (start + step).min(nrows - 1);
            while compute_error(hi) < 0.0 && hi < nrows - 1 {
                lo = hi;
                step *= 2;
                hi = (hi + step).min(nrows - 1);
            }
            (lo, hi)
        } else {
            // Solution is at or below start row - expand downward
            let mut hi = start;
            let mut step = 1;
            let mut lo = start.saturating_sub(step);
            while compute_error(lo) >= 0.0 && lo > 0 {
                hi = lo;
                step *= 2;
                lo = lo.saturating_sub(step);
            }
            (lo, hi)
        };

        // Bisect to find exact bracket
        while hi - lo > 1 {
            let mid = lo + (hi - lo) / 2;
            if compute_error(mid) < 0.0 {
                lo = mid;
            } else {
                hi = mid;
            }
        }

        let istop = hi;
        let error_i = compute_error(istop);

        // Handle edge cases
        if istop == 0 {
            // Solution at or below row 0
            return self.d.get_value(0, VOLU);
        }
        if error_i < 0.0 {
            // Volume exceeds table - return max
            return self.d.get_value(nrows - 1, VOLU);
        }

        // Interpolate between rows
        let error_prev = compute_error(istop - 1);
        let x = error_prev / (error_prev - error_i);
        let v_lo = self.d.get_value(istop - 1, VOLU);
        let v_hi = self.d.get_value(istop, VOLU);

        v_lo + (v_hi - v_lo) * x
    }

    /// Finds which MOL threshold was crossed between old and new active sets.
    /// Returns (threshold_volume, outlet_index) for the crossed threshold.
    fn find_crossed_threshold(
        &self,
        v_candidate: f64,
        old_active: u8,
        new_active: u8,
    ) -> Option<(f64, usize)> {
        let changed = old_active ^ new_active;

        // Find the threshold closest to the candidate volume
        let mut best: Option<(f64, usize)> = None;
        let mut best_dist = f64::MAX;

        for i in 0..MAX_DS_LINKS {
            if changed & (1 << i) != 0 {
                let threshold = self.min_operating_volume[i];
                let dist = (threshold - v_candidate).abs();
                if dist < best_dist {
                    best_dist = dist;
                    best = Some((threshold, i));
                }
            }
        }

        best
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

        // Convert outlet definitions (MOL levels) to volumes
        for i in 0..MAX_DS_LINKS {
            self.min_operating_volume[i] = match self.outlet_definition[i] {
                OutletDefinition::None => 0.0,
                OutletDefinition::OutletWithMOL(level) => {
                    self.d.interpolate(LEVL, VOLU, level)
                }
                OutletDefinition::OutletWithAndMOLAndCapacity(level, _capacity) => {
                    self.d.interpolate(LEVL, VOLU, level)
                }
            };
        }

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
        self.recorder_idx_ds_1_order = data_cache.get_series_idx(
            make_result_name(&self.name, "ds_1_order").as_str(), false
        );
        self.recorder_idx_ds_2_order = data_cache.get_series_idx(
            make_result_name(&self.name, "ds_2_order").as_str(), false
        );
        self.recorder_idx_ds_3_order = data_cache.get_series_idx(
            make_result_name(&self.name, "ds_3_order").as_str(), false
        );
        self.recorder_idx_ds_4_order = data_cache.get_series_idx(
            make_result_name(&self.name, "ds_4_order").as_str(), false
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

        // Add upstream inflows
        self.v += self.usflow;

        // Net rainfall rate (will be applied to area in solver)
        let net_rain_mm = rain_mm - evap_mm - seep_mm;

        // Handle pond diversion first (highest priority)
        let net_rain_at_dead_storage = self.area0_km2 * net_rain_mm;
        let max_diversion = self.v + net_rain_at_dead_storage.max(0.0);
        self.pond_diversion = pond_demand.min(max_diversion);
        self.v -= self.pond_diversion;

        // Working volume for backward Euler (after pond diversion)
        let v_working = self.v;

        // Solve backward Euler with MOL-aware releases
        let (v_final, ds_flows, spill) = self.solve_backward_euler(v_working, net_rain_mm);

        // Update state from solution
        self.v = v_final;
        self.level = self.d.interpolate(VOLU, LEVL, v_final);
        let area_km2 = self.d.interpolate(VOLU, AREA, v_final);
        self.spill = spill;
        self.ds_1_flow = ds_flows[0];
        self.ds_2_flow = ds_flows[1];
        self.ds_3_flow = ds_flows[2];
        self.ds_4_flow = ds_flows[3];
        self.dsflow = self.ds_1_flow + self.ds_2_flow + self.ds_3_flow + self.ds_4_flow;

        // Compute climate volumes using solved area
        self.rain_vol = rain_mm * area_km2;
        self.evap_vol = evap_mm * area_km2;
        self.seep_vol = seep_mm * area_km2;

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
        if let Some(idx) = self.recorder_idx_ds_1_order {
            data_cache.add_value_at_index(idx, self.dsorders[0]);
        }
        if let Some(idx) = self.recorder_idx_ds_2_order {
            data_cache.add_value_at_index(idx, self.dsorders[1]);
        }
        if let Some(idx) = self.recorder_idx_ds_3_order {
            data_cache.add_value_at_index(idx, self.dsorders[2]);
        }
        if let Some(idx) = self.recorder_idx_ds_4_order {
            data_cache.add_value_at_index(idx, self.dsorders[3]);
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
