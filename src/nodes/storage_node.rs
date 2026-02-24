use super::Node;
use crate::misc::misc_functions::make_result_name;
use crate::model_inputs::DynamicInput;
use crate::numerical::table::Table;
use crate::data_management::data_cache::DataCache;
use crate::misc::location::Location;
use crate::numerical::fifo_buffer::FifoBuffer;

const LEVL: usize = 0;
const VOLU: usize = 1;
const AREA: usize = 2;
const SPIL: usize = 3;
const EPSILON: f64 = 1e-6;
const MAX_DS_LINKS: usize = 4;

/// Defines outlet configuration including minimum operating level (MOL) and capacity.
/// MOL is specified as a level (m) and converted to volume internally.
#[derive(Default, Clone, Copy, Debug, PartialEq)]
pub enum OutletDefinition {
    #[default]
    None,
    OutletWithMOL(f64),                   // MOL level in metres
    OutletWithMOLAndCapacity(f64, f64),   // MOL level, capacity
}

#[derive(Default, Clone)]
pub struct StorageNode {
    pub name: String,
    pub location: Location,
    pub mbal: f64,
    pub d: Table,       // Level m, Volume ML, Area km2, Spill ML
    pub v: f64,
    pub v_initial: f64,
    pub order_through: bool,
    pub rain_mm_input: DynamicInput,
    pub evap_mm_input: DynamicInput,
    pub seep_mm_input: DynamicInput,
    pub pond_demand_input: DynamicInput,
    pub target_level: DynamicInput,

    // Internal state only
    usflow: f64,
    dsflow: f64,
    ds_flows: [f64; MAX_DS_LINKS],
    level: f64,
    rain_vol: f64,
    evap_vol: f64,
    seep_vol: f64,
    pond_diversion: f64, //pond diversion
    spill: f64,

    // Cached state for search optimization
    previous_istop: usize,  // Remember previous solution row for warm start

    // Orders
    pub ds_orders: [f64; MAX_DS_LINKS],
    pub ds_orders_due: [f64; MAX_DS_LINKS],
    pub us_orders: f64,
    pub has_target_level: bool,
    pub target_level_order_buffer: FifoBuffer,
    pub ds_1_order_buffer: FifoBuffer,
    pub ds_2_order_buffer: FifoBuffer,
    pub ds_3_order_buffer: FifoBuffer,
    pub ds_4_order_buffer: FifoBuffer,

    // Outlet definitions (MOL, capacity) - parsed from INI
    pub outlet_definition: [OutletDefinition; MAX_DS_LINKS],

    // Minimum operating volume for each outlet (converted from MOL level during init)
    // 0.0 means no MOL constraint (outlet always active)
    min_operating_volume: [f64; MAX_DS_LINKS],

    // Recorders
    recorder_idx_usflow: Option<usize>,
    recorder_idx_volume: Option<usize>,
    recorder_idx_level: Option<usize>,
    recorder_idx_target_level: Option<usize>,
    recorder_idx_area: Option<usize>,
    recorder_idx_seep: Option<usize>,
    recorder_idx_evap: Option<usize>,
    recorder_idx_rain: Option<usize>,
    recorder_idx_pond_diversion: Option<usize>,
    recorder_idx_dsflow: Option<usize>,
    recorder_idx_ds_1: Option<usize>,
    recorder_idx_ds_1_order: Option<usize>,
    recorder_idx_ds_1_order_due: Option<usize>,
    recorder_idx_ds_1_outlet: Option<usize>,
    recorder_idx_ds_1_spill: Option<usize>,
    recorder_idx_ds_2: Option<usize>,
    recorder_idx_ds_2_order: Option<usize>,
    recorder_idx_ds_2_order_due: Option<usize>,
    recorder_idx_ds_2_outlet: Option<usize>,
    recorder_idx_ds_2_spill: Option<usize>,
    recorder_idx_ds_3: Option<usize>,
    recorder_idx_ds_3_order: Option<usize>,
    recorder_idx_ds_3_order_due: Option<usize>,
    recorder_idx_ds_3_outlet: Option<usize>,
    recorder_idx_ds_3_spill: Option<usize>,
    recorder_idx_ds_4: Option<usize>,
    recorder_idx_ds_4_order: Option<usize>,
    recorder_idx_ds_4_order_due: Option<usize>,
    recorder_idx_ds_4_outlet: Option<usize>,
    recorder_idx_ds_4_spill: Option<usize>,
}

impl StorageNode {

    /// Base constructor
    pub fn new() -> Self {
        Self {
            name: "".to_string(),
            d: Table::new(4),
            order_through: false,
            usflow: 0.0,
            ..Default::default()
        }
    }

    // -------------------------------------------------------------------------
    // Backward Euler Solver
    // -------------------------------------------------------------------------
    //
    // Terminology for ds_1 flows:
    // - "ds_1_spill": uncontrolled overflow via the spillway, counts towards ds_1 orders
    // - "ds_1_outlet": controlled outlet flow, supplements spill to meet ds_1 orders
    // - "ds_1" (total): ds_1_spill + ds_1_outlet
    //
    // For ds_2, ds_3, ds_4: flow = outlet flow only (no spill component)

    /// Determines which outlets are active (able to release) at a given volume.
    /// An outlet is active if volume >= its minimum operating volume.
    /// Returns a bitmask: bit i is set if outlet i is active.
    fn active_outlets_at_volume(&self, volume: f64) -> u8 {
        let mut active = 0u8;
        for i in 0..MAX_DS_LINKS {
            if self.ds_orders_due[i] > 0.0 && volume >= self.min_operating_volume[i] {
                active |= 1 << i;
            }
        }
        active
    }

    /// Sums the orders_due for outlets specified by the mask.
    /// Bit i in the mask corresponds to outlet i (ds_1=bit 0, ds_2=bit 1, etc).
    fn sum_ds_orders_due(&self, outlet_mask: u8) -> f64 {
        let mut total = 0.0;
        for i in 0..MAX_DS_LINKS {
            if outlet_mask & (1 << i) != 0 {
                total += self.ds_orders_due[i];
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
    /// Returns (final_volume, ds_flows[4], spill, table_row, area)
    fn solve_backward_euler(
        &self,
        v_initial: f64,
        net_rain_mm: f64,
    ) -> (f64, [f64; MAX_DS_LINKS], f64, usize, f64) {
        let nrows = self.d.nrows();
        let ds_1_order_due = self.ds_orders_due[0];

        // --- Pass 1: Solve spill-limited case (no controlled release on ds_1) ---
        let (v_spill_only, spill, active_pass1, row_pass1, _unc_pass1) =
            self.solve_spill_limited_case(v_initial, net_rain_mm, nrows, self.previous_istop);

        // Select which pass result to use
        let (v_final, final_spill, active, row, unconstrained) = if spill >= ds_1_order_due {
            // Spill satisfies ds_1 order - no controlled release needed.
            // Always use mass balance here (unconstrained=false): the interpolated spill
            // can have large FP error when the volume is near a steep spill curve, whereas
            // mass balance (v_initial - v_final) stays accurate.
            (v_spill_only, spill, active_pass1, row_pass1, false)
        } else {
            // --- Pass 2: Solve order-limited case (ds_1 needs controlled release) ---
            // Warm start from pass 1 row since solutions are nearby
            self.solve_order_limited_case(v_initial, net_rain_mm, ds_1_order_due, nrows, row_pass1 + 1)
        };

        // Compute area once (used by both allocation logic and caller)
        let area = self.d.interpolate_row(row, VOLU, AREA, v_final);

        // Allocate outflows to each downstream link.
        // When unconstrained, use orders directly to avoid floating point noise from mass balance.
        // When constrained (threshold clamp or non-convergence), compute available from mass balance.
        let mut ds_flows = [0.0; MAX_DS_LINKS];
        let mut remaining = if unconstrained {
            f64::INFINITY
        } else {
            (v_initial + net_rain_mm * area - v_final).max(0.0)
        };

        // Priority: ds_1 first (includes uncontrollable spill), then ds_2, ds_3, ds_4.
        // ds_1: spill is uncontrollable, controlled release supplements up to order
        let ds_1_active = (active & 1) != 0;
        let ds1_flow = if ds_1_active {
            ds_1_order_due.max(final_spill).min(remaining)
        } else {
            final_spill.min(remaining)
        };
        ds_flows[0] = ds1_flow;
        remaining -= ds1_flow;

        // ds_2, ds_3, ds_4: each gets min(order_due, remaining budget)
        for i in 1..MAX_DS_LINKS {
            if active & (1 << i) != 0 && remaining > EPSILON {
                let flow = self.ds_orders_due[i].min(remaining);
                ds_flows[i] = flow;
                remaining -= flow;
            }
        }

        (v_final, ds_flows, final_spill, row, area)
    }

    /// Solves the spill-limited case: no required ds_1 flow, spill alone determines ds_1.
    /// Returns (equilibrium_volume, spill_at_equilibrium, active_mask, table_row, unconstrained)
    #[inline(always)]
    fn solve_spill_limited_case(
        &self,
        v_working: f64,
        net_rain_mm: f64,
        nrows: usize,
        start_row: usize,
    ) -> (f64, f64, u8, usize, bool) {
        self.solve_with_outflows(v_working, net_rain_mm, 0.0, nrows, start_row)
    }

    /// Solves the order-limited case: ds_1 must flow at least the order amount.
    /// Returns (equilibrium_volume, spill_at_equilibrium, active_mask, table_row, unconstrained)
    #[inline(always)]
    fn solve_order_limited_case(
        &self,
        v_initial: f64,
        net_rain_mm: f64,
        ds_1_order_due: f64,
        nrows: usize,
        start_row: usize,
    ) -> (f64, f64, u8, usize, bool) {
        self.solve_with_outflows(v_initial, net_rain_mm, ds_1_order_due, nrows, start_row)
    }

    /// Solves for equilibrium volume with a required minimum ds_1 flow.
    /// The actual ds_1 contribution to mass balance is max(spill, ds1_required_flow).
    /// Handles MOL thresholds for ds_2, ds_3, ds_4 via iteration.
    /// Returns (equilibrium_volume, spill_at_equilibrium, active_outlet_mask, table_row, unconstrained)
    /// `unconstrained` is true when the solver converged naturally (stable active set) — all active
    /// outlets can release their full orders. False when the solution was clamped to a threshold
    /// or the solver did not converge, meaning available outflow may be less than total orders.
    fn solve_with_outflows(
        &self,
        v_initial: f64,
        net_rain_mm: f64,
        ds1_required_flow: f64,
        nrows: usize,
        start_row: usize,
    ) -> (f64, f64, u8, usize, bool) {
        // Start with outlets active based on current volume
        let mut active = self.active_outlets_at_volume(v_initial);
        let mut hint = start_row;

        const MAX_ITERATIONS: usize = 8;

        for _iter in 0..MAX_ITERATIONS {
            // Sum orders for ds_2, ds_3, ds_4 based on active set
            let ds234_orders_due = self.sum_ds_orders_due(active & 0b1110);

            // ds_1 required flow is zero when ds_1 is below its MOL
            let effective_ds1 = if active & 1 != 0 { ds1_required_flow } else { 0.0 };

            // Find equilibrium volume
            let (v_candidate, row) = self.find_equilibrium_volume(
                v_initial, net_rain_mm, effective_ds1, ds234_orders_due, nrows, hint
            );

            // Check which outlets should be active at the candidate volume
            let new_active = self.active_outlets_at_volume(v_candidate);

            if new_active == active {
                // Converged - use row from bisection for spill lookup
                let spill = self.d.interpolate_row(row, VOLU, SPIL, v_candidate).max(0.0);
                return (v_candidate, spill, active, row, true);
            }

            // Only check threshold clamping on reactivation (oscillation).
            // If outlets are only being deactivated, just update and re-solve.
            let reactivating = new_active & !active;
            if reactivating != 0 {
                if let Some(threshold_vol) =
                    self.find_crossed_threshold(v_candidate, active, new_active)
                {
                    if let Some(thr_row) = self.d.find_row_for_interpolation(VOLU, threshold_vol) {
                        let area = self.d.interpolate_row(thr_row, VOLU, AREA, threshold_vol);
                        let spill = self.d.interpolate_row(thr_row, VOLU, SPIL, threshold_vol).max(0.0);
                        let outflow_needed = v_initial + net_rain_mm * area - threshold_vol;
                        let ds1_flow = spill.max(effective_ds1);
                        let total_outflow = ds1_flow + ds234_orders_due;

                        if outflow_needed >= 0.0 && outflow_needed <= total_outflow + EPSILON {
                            // Smaller active set can sustain the threshold volume
                            return (threshold_vol, spill, active, thr_row, false);
                        } else if outflow_needed >= 0.0 {
                            // Smaller set can't sustain threshold - clamp anyway with
                            // larger set. Mass balance allocation will cap actual flows.
                            return (threshold_vol, spill, new_active, thr_row, false);
                        }
                    }
                }
            }

            // Warm start next iteration from current solution
            hint = row + 1;
            active = new_active;
        }

        // Fallback (rare path) - do one find_row for v_working
        if let Some(fb_row) = self.d.find_row_for_interpolation(VOLU, v_initial) {
            let spill = self.d.interpolate_row(fb_row, VOLU, SPIL, v_initial).max(0.0);
            (v_initial, spill, active, fb_row, false)
        } else {
            (v_initial, 0.0, active, 0, false)
        }
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
        start_row: usize,
    ) -> (f64, usize) {
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

        // Exponential expansion from start_row hint
        let start = start_row.min(nrows - 1);
        let error_start = compute_error(start);

        let (mut lo, mut hi, mut error_lo, mut error_hi) = if error_start < 0.0 {
            // Solution is above start row - expand upward
            let mut lo = start;
            let mut error_lo = error_start;
            let mut step = 1;
            let mut hi = (start + step).min(nrows - 1);
            let mut error_hi = compute_error(hi);
            while error_hi < 0.0 && hi < nrows - 1 {
                lo = hi;
                error_lo = error_hi;
                step *= 2;
                hi = (hi + step).min(nrows - 1);
                error_hi = compute_error(hi);
            }
            (lo, hi, error_lo, error_hi)
        } else {
            // Solution is at or below start row - expand downward
            let mut hi = start;
            let mut error_hi = error_start;
            let mut step = 1;
            let mut lo = start.saturating_sub(step);
            let mut error_lo = compute_error(lo);
            while error_lo >= 0.0 && lo > 0 {
                hi = lo;
                error_hi = error_lo;
                step *= 2;
                lo = lo.saturating_sub(step);
                error_lo = compute_error(lo);
            }
            (lo, hi, error_lo, error_hi)
        };

        // Bisect to find exact bracket, caching error values
        while hi - lo > 1 {
            let mid = lo + (hi - lo) / 2;
            let error_mid = compute_error(mid);
            if error_mid < 0.0 {
                lo = mid;
                error_lo = error_mid;
            } else {
                hi = mid;
                error_hi = error_mid;
            }
        }

        let istop = hi;

        // Handle floor case (solution at or below row 0)
        if istop == 0 {
            return (self.d.get_value(0, VOLU), 0);
        }
        // Ceiling case (error_hi < 0): allow extrapolation beyond table max
        // by falling through to normal interpolation - x > 1.0 extrapolates

        // Interpolate between rows using cached errors (no recomputation)
        let row = istop - 1;
        let x = error_lo / (error_lo - error_hi);
        let v_lo = self.d.get_value(row, VOLU);
        let v_hi = self.d.get_value(istop, VOLU);

        (v_lo + (v_hi - v_lo) * x, row)
    }

    /// Finds which MOL threshold was crossed between old and new active sets.
    /// Returns the threshold volume closest to the candidate volume.
    fn find_crossed_threshold(
        &self,
        v_candidate: f64,
        old_active: u8,
        new_active: u8,
    ) -> Option<f64> {
        let changed = old_active ^ new_active;

        // Find the threshold closest to the candidate volume
        let mut best: Option<f64> = None;
        let mut best_dist = f64::MAX;

        for i in 0..MAX_DS_LINKS {
            if changed & (1 << i) != 0 {
                let threshold = self.min_operating_volume[i];
                let dist = (threshold - v_candidate).abs();
                if dist < best_dist {
                    best_dist = dist;
                    best = Some(threshold);
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
        self.ds_flows = [0.0; MAX_DS_LINKS];
        self.v = self.v_initial;
        self.level = 0.0;
        self.rain_vol = 0.0;
        self.evap_vol = 0.0;
        self.seep_vol = 0.0;
        self.pond_diversion = 0.0;
        self.spill = 0.0;
        self.previous_istop = 0;

        // Checks
        if self.d.nrows() < 2 {
            let message = format!("Error in node '{}'. Storage dimension table must have at least 2 rows.", self.name);
            return Err(message);
        }
        if self.d.get_value(0, VOLU) != 0_f64 {
            let message = format!("Error in node '{}'. Storage dimension table must begin with volume=0.", self.name);
            return Err(message);
        }
        if self.d.get_value(0, AREA) != 0_f64 {
            let message = format!("Error in node '{}'. Storage dimension table must begin with area=0.", self.name);
            return Err(message);
        }

        // Validate that volumes are strictly increasing (required for solver interpolation)
        for i in 1..self.d.nrows() {
            if self.d.get_value(i, VOLU) <= self.d.get_value(i - 1, VOLU) {
                let message = format!(
                    "Error in node '{}'. Storage dimension table volumes must be strictly increasing (violation at row {}).",
                    self.name, i + 1
                );
                return Err(message);
            }
        }

        // Convert outlet definitions (MOL levels) to volumes
        for i in 0..MAX_DS_LINKS {
            self.min_operating_volume[i] = match self.outlet_definition[i] {
                OutletDefinition::None => 0.0,
                OutletDefinition::OutletWithMOL(level) => {
                    self.d.interpolate(LEVL, VOLU, level)
                }
                OutletDefinition::OutletWithMOLAndCapacity(level, _capacity) => {
                    self.d.interpolate(LEVL, VOLU, level)
                }
            };
        }

        // Check if the storage is targeting a level
        self.has_target_level = !matches!(&self.target_level, DynamicInput::None { .. });

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
        self.recorder_idx_target_level = data_cache.get_series_idx(
            make_result_name(&self.name, "target_level").as_str(), false
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
        self.recorder_idx_ds_1_outlet = data_cache.get_series_idx(
            make_result_name(&self.name, "ds_1_outlet").as_str(), false
        );
        self.recorder_idx_ds_1_spill = data_cache.get_series_idx(
            make_result_name(&self.name, "ds_1_spill").as_str(), false
        );
        self.recorder_idx_ds_2 = data_cache.get_series_idx(
            make_result_name(&self.name, "ds_2").as_str(), false
        );
        self.recorder_idx_ds_2_outlet = data_cache.get_series_idx(
            make_result_name(&self.name, "ds_2_outlet").as_str(), false
        );
        self.recorder_idx_ds_2_spill = data_cache.get_series_idx(
            make_result_name(&self.name, "ds_2_spill").as_str(), false
        );
        self.recorder_idx_ds_3 = data_cache.get_series_idx(
            make_result_name(&self.name, "ds_3").as_str(), false
        );
        self.recorder_idx_ds_3_spill = data_cache.get_series_idx(
            make_result_name(&self.name, "ds_3_spill").as_str(), false
        );
        self.recorder_idx_ds_3_outlet = data_cache.get_series_idx(
            make_result_name(&self.name, "ds_3_outlet").as_str(), false
        );
        self.recorder_idx_ds_4 = data_cache.get_series_idx(
            make_result_name(&self.name, "ds_4").as_str(), false
        );
        self.recorder_idx_ds_4_outlet = data_cache.get_series_idx(
            make_result_name(&self.name, "ds_4_outlet").as_str(), false
        );
        self.recorder_idx_ds_4_spill = data_cache.get_series_idx(
            make_result_name(&self.name, "ds_4_spill").as_str(), false
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
        self.recorder_idx_ds_1_order_due = data_cache.get_series_idx(
            make_result_name(&self.name, "ds_1_order_due").as_str(), false
        );
        self.recorder_idx_ds_2_order_due = data_cache.get_series_idx(
            make_result_name(&self.name, "ds_2_order_due").as_str(), false
        );
        self.recorder_idx_ds_3_order_due = data_cache.get_series_idx(
            make_result_name(&self.name, "ds_3_order_due").as_str(), false
        );
        self.recorder_idx_ds_4_order_due = data_cache.get_series_idx(
            make_result_name(&self.name, "ds_4_order_due").as_str(), false
        );

        Ok(())
    }

    fn get_name(&self) -> &str { &self.name }

    fn run_order_phase(&mut self, data_cache: &mut DataCache) {

        // Record new downstream orders
        if let Some(idx) = self.recorder_idx_ds_1_order {
            data_cache.add_value_at_index(idx, self.ds_orders[0]);
        }
        if let Some(idx) = self.recorder_idx_ds_2_order {
            data_cache.add_value_at_index(idx, self.ds_orders[1]);
        }
        if let Some(idx) = self.recorder_idx_ds_3_order {
            data_cache.add_value_at_index(idx, self.ds_orders[2]);
        }
        if let Some(idx) = self.recorder_idx_ds_4_order {
            data_cache.add_value_at_index(idx, self.ds_orders[3]);
        }

        // Update orders due
        self.ds_orders_due[0] = self.ds_1_order_buffer.push(self.ds_orders[0]);
        self.ds_orders_due[1] = self.ds_2_order_buffer.push(self.ds_orders[1]);
        self.ds_orders_due[2] = self.ds_3_order_buffer.push(self.ds_orders[2]);
        self.ds_orders_due[3] = self.ds_4_order_buffer.push(self.ds_orders[3]);

        // Record orders due
        if let Some(idx) = self.recorder_idx_ds_1_order_due {
            data_cache.add_value_at_index(idx, self.ds_orders_due[0]);
        }
        if let Some(idx) = self.recorder_idx_ds_2_order_due {
            data_cache.add_value_at_index(idx, self.ds_orders_due[1]);
        }
        if let Some(idx) = self.recorder_idx_ds_3_order_due {
            data_cache.add_value_at_index(idx, self.ds_orders_due[2]);
        }
        if let Some(idx) = self.recorder_idx_ds_4_order_due {
            data_cache.add_value_at_index(idx, self.ds_orders_due[3]);
        }

        // Calculate orders
        if self.order_through {
            //
            // 'Order through' means (1) the ordering system does not consider this storage
            // to be a supply, (2) total orders are propagated upstream without adjustment.
            self.us_orders = self.ds_orders.iter().sum();
            //
        } else if self.has_target_level {
            //
            // 'Target level' works like this:
            // 1) calculate the target volume
            // 2) forecast our future volume assuming:
            //    - all previous orders will arrive. (Previous orders are stored in the
            //        target_level_order_buffer so we can work out what is en route. A buffer of
            //        zero length means there is no travel time. The order we place today will
            //        arrive today and nothing is ever en route.)
            //    - no rainfall, evap, or seepage
            //    - no additional inflows will arrive
            //    - today's downstream orders will be released
            //    - no subsequent releases will be made
            // 3) order what is required to reach our target volume
            let target_level = self.target_level.get_value(data_cache);
            if let Some(idx) = self.recorder_idx_target_level {
                data_cache.add_value_at_index(idx, target_level);
            }
            // The level is below the target level. We need convert this to a volume and
            // compare it with our forecast volume.
            let target_volume = self.d.interpolate_or_extrapolate(LEVL, VOLU, target_level);
            //TODO: it could be possible to keep a running forecast inflow here, add new orders
            // to it and subtract orders as they pop out of the buffer (rather than summing the
            // order buffer every time). It may be noticeable for long travel times.
            let inflows = self.target_level_order_buffer.sum();
            let known_usage: f64 = self.ds_orders_due.iter().sum();
            let forecast_volume = self.v + inflows - known_usage;
            self.us_orders = (target_volume - forecast_volume).max(0.0);
            self.target_level_order_buffer.push(self.us_orders);
        } else {
            // Storage does not order upstream
            // self.usorders = 0.0
        }
    }

    fn run_flow_phase(&mut self, data_cache: &mut DataCache) {

        // Record results
        if let Some(idx) = self.recorder_idx_usflow {
            data_cache.add_value_at_index(idx, self.usflow);
        }

        // Get the driving data
        let rain_mm = self.rain_mm_input.get_value(data_cache);
        let evap_mm = self.evap_mm_input.get_value(data_cache);
        let seep_mm = self.seep_mm_input.get_value(data_cache);
        let pond_demand = self.pond_demand_input.get_value(data_cache);

        // Add upstream inflows
        self.v += self.usflow;

        // Handle pond diversion first (highest priority)
        // If we empty the storage, there is no rainfall accessible this timestep since AREA=0.
        self.pond_diversion = pond_demand.min(self.v);
        self.v -= self.pond_diversion;

        // Net rainfall rate
        let net_rain_mm = rain_mm - evap_mm - seep_mm;

        // Solve backward Euler
        let (v_final, ds_flows, spill, row, area_km2) = self.solve_backward_euler(self.v, net_rain_mm);

        // Update warm-start cache for next timestep (expects upper bracket)
        self.previous_istop = row + 1;

        // Update state from solution (area already computed by solver)
        self.v = v_final;
        self.level = self.d.interpolate_row(row, VOLU, LEVL, v_final);
        self.spill = spill;
        self.ds_flows = ds_flows;
        self.dsflow = self.ds_flows.iter().sum();

        // Compute climate volumes using solved area
        self.rain_vol = rain_mm * area_km2;
        self.evap_vol = evap_mm * area_km2;
        self.seep_vol = seep_mm * area_km2;

        // Update mass balance
        self.mbal += self.dsflow - self.usflow;

        // Record results
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
            data_cache.add_value_at_index(idx, self.ds_flows[0]);
        }
        if let Some(idx) = self.recorder_idx_ds_1_outlet {
            let ds_1_outlet_flow = (self.ds_flows[0] - self.spill).max(0.0);
            data_cache.add_value_at_index(idx, ds_1_outlet_flow);
        }
        if let Some(idx) = self.recorder_idx_ds_1_spill {
            data_cache.add_value_at_index(idx, self.spill);
        }
        if let Some(idx) = self.recorder_idx_ds_2 {
            data_cache.add_value_at_index(idx, self.ds_flows[1]);
        }
        if let Some(idx) = self.recorder_idx_ds_2_outlet {
            data_cache.add_value_at_index(idx, self.ds_flows[1]);
        }
        if let Some(idx) = self.recorder_idx_ds_2_spill {
            data_cache.add_value_at_index(idx, 0.0);
        }
        if let Some(idx) = self.recorder_idx_ds_3 {
            data_cache.add_value_at_index(idx, self.ds_flows[2]);
        }
        if let Some(idx) = self.recorder_idx_ds_3_outlet {
            data_cache.add_value_at_index(idx, self.ds_flows[2]);
        }
        if let Some(idx) = self.recorder_idx_ds_3_spill {
            data_cache.add_value_at_index(idx, 0.0);
        }
        if let Some(idx) = self.recorder_idx_ds_4 {
            data_cache.add_value_at_index(idx, self.ds_flows[3]);
        }
        if let Some(idx) = self.recorder_idx_ds_4_outlet {
            data_cache.add_value_at_index(idx, self.ds_flows[3]);
        }
        if let Some(idx) = self.recorder_idx_ds_4_spill {
            data_cache.add_value_at_index(idx, 0.0);
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
        let idx = outlet as usize;
        if idx < MAX_DS_LINKS {
            let outflow = self.ds_flows[idx];
            self.ds_flows[idx] = 0.0;
            outflow
        } else {
            0.0
        }
    }

    fn get_mass_balance(&self) -> f64 {
        self.mbal
    }

    fn dsorders_mut(&mut self) -> &mut [f64] {
        &mut self.ds_orders
    }
}
