use super::Node;
use crate::misc::misc_functions::make_result_name;
use crate::model_inputs::DynamicInput;
use crate::numerical::table::Table;
use crate::data_management::data_cache::DataCache;
use crate::misc::location::Location;

// Table column indices
const LEVL: usize = 0;
const VOLU: usize = 1;
const AREA: usize = 2;
const SPIL: usize = 3;

// Other constants
const EPSILON: f64 = 1e-6;
const MAX_DS_LINKS: usize = 4;

// -----------------------------------------------------------------------------
// Threshold and solver support structures
// -----------------------------------------------------------------------------

/// Precomputed information about a release threshold (minimum volume for release).
/// Computed once during initialization to avoid repeated table lookups.
#[derive(Default, Clone)]
struct ThresholdInfo {
    volume: f64,
    #[allow(dead_code)]  // Reserved for potential optimization
    table_row: usize,
}

/// A breakpoint where the outflow function changes character.
/// Used during the backward Euler solve to handle piecewise-linear outflow.
#[derive(Clone)]
enum Breakpoint {
    /// Volume where spill equals ds_1 order (transitions from order-limited to spill-limited)
    SpillCrossover { volume: f64 },
    /// Volume threshold below which an outlet cannot release
    ReleaseThreshold { outlet: usize },
}

/// Result of the backward Euler solve, containing final state and outlet flows.
struct SolveResult {
    volume: f64,
    level: f64,
    area: f64,
    spill: f64,
    ds_flows: [f64; MAX_DS_LINKS],
}

/// Configure the node with these. The node will be initialized accordingly.
#[derive(Default, Clone)]
pub enum OutletDefinition {
    #[default]
    None,
    OutletWithMOL(f64), //MOL [m]
    OutletWithAndMOLAndCapacity(f64, f64), //MOL [m], Capacity [ML/step]
    //OutletWithRatingCurve(table) //TODO: implement this
}


// -----------------------------------------------------------------------------
// StorageNode
// -----------------------------------------------------------------------------

#[derive(Default, Clone)]
pub struct StorageNode {
    pub name: String,
    pub location: Location,
    pub mbal: f64,
    pub d: Table,       // Dimension table: Level(m), Volume(ML), Area(km²), Spill(ML/d)
    pub v: f64,         // Current volume
    pub v_initial: f64,
    pub area0_km2: f64, // Area at minimum storage (interpolated during init)

    // Outlets
    pub min_operating_volume: [f64; MAX_DS_LINKS], // Minimum operating volume for each outlet.
    pub outlet_definition: [OutletDefinition; MAX_DS_LINKS],
    pub outlet_priority: [u8; MAX_DS_LINKS], // Release priorities. Default: [0, 1, 2, 3]. lower value = higher priority.

    // Climate inputs
    pub rain_mm_input: DynamicInput,
    pub evap_mm_input: DynamicInput,
    pub seep_mm_input: DynamicInput,
    pub pond_demand_input: DynamicInput,

    // Precomputed threshold info (populated during initialization)
    threshold_info: [Option<ThresholdInfo>; MAX_DS_LINKS],

    // Internal state
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
    pond_diversion: f64,
    spill: f64,

    // Warm-start hint for table row search
    previous_row: usize,

    // Orders (set by ordering system before run_flow_phase)
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
    recorder_idx_ds_1_order: Option<usize>,
    recorder_idx_ds_2: Option<usize>,
    recorder_idx_ds_2_order: Option<usize>,
    recorder_idx_ds_3: Option<usize>,
    recorder_idx_ds_3_order: Option<usize>,
    recorder_idx_ds_4: Option<usize>,
    recorder_idx_ds_4_order: Option<usize>,
}

impl StorageNode {
    pub fn new() -> Self {
        Self {
            name: String::new(),
            d: Table::new(4),
            threshold_info: Default::default(), //[None, None, None, None],
            outlet_priority: std::array::from_fn(|i| i as u8), //[0, 1, 2, 3],
            ..Default::default()
        }
    }

    // -------------------------------------------------------------------------
    // Backward Euler solver
    // -------------------------------------------------------------------------

    /// Main solver entry point. Finds final volume accounting for:
    /// - Net rainfall (rain - evap - seep) scaled by area
    /// - Spill from the dimension table
    /// - Ordered releases with threshold constraints
    /// - ds_1 spill credit (spill contributes to satisfying ds_1 order)
    fn solve_backward_euler(
        &self,
        v_working: f64,
        net_rain_mm: f64,
    ) -> SolveResult {
        let nrows = self.d.nrows();

        // Find the table row interval containing the solution
        let (row_lo, row_hi) = self.find_row_interval(v_working, net_rain_mm, nrows);

        // Check for breakpoints (discontinuities) within this interval
        let v_lo = self.d.get_value(row_lo, VOLU);
        let v_hi = self.d.get_value(row_hi, VOLU);
        let breakpoints = self.find_breakpoints_in_range(v_lo, v_hi);

        if breakpoints.is_empty() {
            // Fast path: no discontinuities, solve directly in this interval
            self.solve_in_interval(row_lo, v_working, net_rain_mm)
        } else {
            // Slow path: handle piecewise segments
            self.solve_with_breakpoints(row_lo, v_working, net_rain_mm, &breakpoints)
        }
    }

    /// Finds the table row interval [lo, hi] that brackets the solution.
    /// Uses exponential expansion from the previous solution, then bisection.
    fn find_row_interval(
        &self,
        v_working: f64,
        net_rain_mm: f64,
        nrows: usize,
    ) -> (usize, usize) {
        // Error function: positive means solution is at or below this row
        let error_at_row = |row: usize| -> f64 {
            let area = self.d.get_value(row, AREA);
            let table_vol = self.d.get_value(row, VOLU);
            let outflow = self.total_outflow_at_volume(table_vol);
            let predicted = v_working + net_rain_mm * area - outflow;
            table_vol - predicted
        };

        // Start from previous solution (warm start)
        let start = self.previous_row.min(nrows - 1);
        let error_start = error_at_row(start);

        let (mut lo, mut hi) = if error_start < 0.0 {
            // Solution is above start row - expand upward
            let mut lo = start;
            let mut step = 1;
            let mut hi = (start + step).min(nrows - 1);
            while error_at_row(hi) < 0.0 && hi < nrows - 1 {
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
            while error_at_row(lo) >= 0.0 && lo > 0 {
                hi = lo;
                step *= 2;
                lo = lo.saturating_sub(step);
            }
            (lo, hi)
        };

        // Bisect to find exact bracket
        while hi - lo > 1 {
            let mid = lo + (hi - lo) / 2;
            if error_at_row(mid) < 0.0 {
                lo = mid;
            } else {
                hi = mid;
            }
        }

        (lo, hi)
    }

    /// Computes total outflow at a given volume, including:
    /// - ds_1: max(order, spill) to give spill credit
    /// - ds_2, ds_3, ds_4: order if volume > threshold, else 0
    fn total_outflow_at_volume(&self, volume: f64) -> f64 {
        let spill = self.d.interpolate(VOLU, SPIL, volume).max(0.0);

        // ds_1: spill counts toward order (take the larger of spill or order)
        let ds_1_outflow = if self.dsorders[0] > 0.0 {
            spill.max(self.dsorders[0])
        } else {
            spill
        };

        // ds_2, ds_3, ds_4: release if above threshold
        let mut other_outflow = 0.0;
        for i in 1..MAX_DS_LINKS {
            if self.dsorders[i] > 0.0 {
                let threshold = self.min_operating_volume[i];
                if volume > threshold {
                    other_outflow += self.dsorders[i];
                }
            }
        }

        ds_1_outflow + other_outflow
    }

    /// Computes the total release capacity at a given volume.
    /// This is the sum of effective orders for all active outlets.
    /// Used to determine the "natural" release budget (no constraint).
    fn release_capacity_at_volume(&self, volume: f64, spill: f64) -> f64 {
        // ds_1: effective order is (order - spill), since spill satisfies part of order
        let mut capacity = (self.dsorders[0] - spill).max(0.0);

        // ds_2, ds_3, ds_4: full order if above threshold
        for i in 1..MAX_DS_LINKS {
            if volume > self.min_operating_volume[i] {
                capacity += self.dsorders[i];
            }
        }

        capacity
    }

    /// Allocates a release budget among outlets based on priority.
    /// Higher priority outlets (lower priority value) are satisfied first.
    /// ds_1 always receives spill in addition to any allocated release.
    fn allocate_releases(
        &self,
        volume: f64,
        spill: f64,
        release_budget: f64,
    ) -> [f64; MAX_DS_LINKS] {
        let mut ds_flows = [0.0; MAX_DS_LINKS];
        let mut remaining = release_budget;

        // Compute effective order for each outlet
        // ds_1: spill credit (order minus what spill already provides)
        // ds_2, ds_3, ds_4: full order if above threshold, else 0
        let effective_orders: [f64; MAX_DS_LINKS] = [
            (self.dsorders[0] - spill).max(0.0),
            if volume > self.min_operating_volume[1] { self.dsorders[1] } else { 0.0 },
            if volume > self.min_operating_volume[2] { self.dsorders[2] } else { 0.0 },
            if volume > self.min_operating_volume[3] { self.dsorders[3] } else { 0.0 },
        ];

        // Sort outlets by priority (lower value = higher priority)
        let mut outlets: [(usize, u8); MAX_DS_LINKS] = [
            (0, self.outlet_priority[0]),
            (1, self.outlet_priority[1]),
            (2, self.outlet_priority[2]),
            (3, self.outlet_priority[3]),
        ];
        outlets.sort_by_key(|&(_, p)| p);

        // Allocate budget by priority
        for (outlet, _) in outlets {
            let release = effective_orders[outlet].min(remaining);
            ds_flows[outlet] = release;
            remaining -= release;
        }

        // ds_1 always receives spill (physical outflow, not from budget)
        ds_flows[0] += spill;

        ds_flows
    }

    /// Finds breakpoints (discontinuities) within a volume range.
    /// Only includes breakpoints for outlets with non-zero orders.
    fn find_breakpoints_in_range(&self, v_lo: f64, v_hi: f64) -> Vec<Breakpoint> {
        let mut breaks = Vec::with_capacity(4);

        // Check release thresholds for ds_2, ds_3, ds_4
        for i in 1..MAX_DS_LINKS {
            if self.dsorders[i] > 0.0 {
                if let Some(ref info) = self.threshold_info[i] {
                    if info.volume > v_lo && info.volume < v_hi {
                        breaks.push(Breakpoint::ReleaseThreshold { outlet: i });
                    }
                }
            }
        }

        // Check for spill crossover (where spill = ds_1_order)
        if self.dsorders[0] > 0.0 {
            let spill_lo = self.d.interpolate(VOLU, SPIL, v_lo).max(0.0);
            let spill_hi = self.d.interpolate(VOLU, SPIL, v_hi).max(0.0);

            if spill_lo < self.dsorders[0] && self.dsorders[0] < spill_hi {
                // Crossover is within this interval
                if let Some(vol) = self.find_spill_crossover_in_range(v_lo, v_hi) {
                    breaks.push(Breakpoint::SpillCrossover { volume: vol });
                }
            }
        }

        // Sort by volume for proper segment handling
        breaks.sort_by(|a, b| {
            let va = self.breakpoint_volume(a);
            let vb = self.breakpoint_volume(b);
            va.partial_cmp(&vb).unwrap()
        });

        breaks
    }

    /// Returns the volume at which a breakpoint occurs.
    fn breakpoint_volume(&self, bp: &Breakpoint) -> f64 {
        match bp {
            Breakpoint::SpillCrossover { volume } => *volume,
            Breakpoint::ReleaseThreshold { outlet } => {
                self.threshold_info[*outlet].as_ref().unwrap().volume
            }
        }
    }

    /// Finds the volume where spill equals ds_1_order within a range.
    fn find_spill_crossover_in_range(&self, v_lo: f64, v_hi: f64) -> Option<f64> {
        let order = self.dsorders[0];
        let spill_lo = self.d.interpolate(VOLU, SPIL, v_lo).max(0.0);
        let spill_hi = self.d.interpolate(VOLU, SPIL, v_hi).max(0.0);

        if spill_lo >= order || spill_hi <= order {
            return None;
        }

        // Linear interpolation to find crossover volume
        let t = (order - spill_lo) / (spill_hi - spill_lo);
        Some(v_lo + t * (v_hi - v_lo))
    }

    /// Solves directly within a table row interval (no breakpoints).
    fn solve_in_interval(
        &self,
        row_lo: usize,
        v_working: f64,
        net_rain_mm: f64,
    ) -> SolveResult {
        let v_lo = self.d.get_value(row_lo, VOLU);
        let v_hi = self.d.get_value(row_lo + 1, VOLU);

        self.solve_in_segment(v_lo, v_hi, v_working, net_rain_mm)
    }

    /// Solves within a segment where all functions are linear.
    /// Uses direct algebraic solution of the backward Euler equation.
    fn solve_in_segment(
        &self,
        v_lo: f64,
        v_hi: f64,
        v_working: f64,
        net_rain_mm: f64,
    ) -> SolveResult {
        let dv = v_hi - v_lo;
        if dv.abs() < EPSILON {
            return self.build_result_at_volume(v_lo);
        }

        // Linear coefficients for area: area(v) = a0 + a1*v
        let area_lo = self.d.interpolate(VOLU, AREA, v_lo);
        let area_hi = self.d.interpolate(VOLU, AREA, v_hi);
        let a1 = (area_hi - area_lo) / dv;
        let a0 = area_lo - a1 * v_lo;

        // Determine outflow regime at segment midpoint
        let v_mid = (v_lo + v_hi) / 2.0;
        let spill_mid = self.d.interpolate(VOLU, SPIL, v_mid).max(0.0);
        let ds_1_is_spill_limited = spill_mid >= self.dsorders[0];

        // Constant releases from ds_2, ds_3, ds_4 (if above their thresholds)
        let mut const_releases = 0.0;
        for i in 1..MAX_DS_LINKS {
            if self.dsorders[i] > 0.0 && v_mid > self.min_operating_volume[i] {
                const_releases += self.dsorders[i];
            }
        }

        // Solve the backward Euler equation:
        //   v = v_working + net_rain*(a0 + a1*v) - outflow(v)
        let volume = if ds_1_is_spill_limited {
            // ds_1 outflow = spill(v), which is linear: s0 + s1*v
            let spill_lo = self.d.interpolate(VOLU, SPIL, v_lo).max(0.0);
            let spill_hi = self.d.interpolate(VOLU, SPIL, v_hi).max(0.0);
            let s1 = (spill_hi - spill_lo) / dv;
            let s0 = spill_lo - s1 * v_lo;

            // v*(1 - net_rain*a1 + s1) = v_working + net_rain*a0 - s0 - const_releases
            let coeff = 1.0 - net_rain_mm * a1 + s1;
            let rhs = v_working + net_rain_mm * a0 - s0 - const_releases;
            rhs / coeff
        } else {
            // ds_1 outflow = ds_1_order (constant)
            // v*(1 - net_rain*a1) = v_working + net_rain*a0 - ds_1_order - const_releases
            let coeff = 1.0 - net_rain_mm * a1;
            let rhs = v_working + net_rain_mm * a0 - self.dsorders[0] - const_releases;
            rhs / coeff
        };

        // Clamp to segment bounds (numerical safety)
        let volume = volume.clamp(v_lo, v_hi);
        self.build_result_at_volume(volume)
    }

    /// Solves when there are breakpoints within the table row interval.
    fn solve_with_breakpoints(
        &self,
        row_lo: usize,
        v_working: f64,
        net_rain_mm: f64,
        breakpoints: &[Breakpoint],
    ) -> SolveResult {
        // Build segment boundaries: [table_lo, bp1, bp2, ..., table_hi]
        let v_table_lo = self.d.get_value(row_lo, VOLU);
        let v_table_hi = self.d.get_value(row_lo + 1, VOLU);

        let mut bounds = Vec::with_capacity(breakpoints.len() + 2);
        bounds.push(v_table_lo);
        for bp in breakpoints {
            bounds.push(self.breakpoint_volume(bp));
        }
        bounds.push(v_table_hi);

        // Try each segment from low to high
        for i in 0..bounds.len() - 1 {
            let seg_lo = bounds[i];
            let seg_hi = bounds[i + 1];

            let result = self.solve_in_segment(seg_lo, seg_hi, v_working, net_rain_mm);

            // Check if solution is within this segment
            if result.volume >= seg_lo - EPSILON && result.volume <= seg_hi + EPSILON {
                return result;
            }

            // Check for threshold discontinuity at segment boundary
            if result.volume > seg_hi && i < bounds.len() - 2 {
                if let Some(threshold_result) = self.check_threshold_at_boundary(
                    seg_hi, v_working, net_rain_mm, breakpoints
                ) {
                    return threshold_result;
                }
            }
        }

        // Fallback: solution at table boundary (shouldn't normally reach here)
        self.build_result_at_volume(v_table_hi)
    }

    /// Checks if the solution lies exactly at a threshold boundary.
    /// At boundaries, the release budget is constrained by mass balance,
    /// and allocation among outlets follows the priority ordering.
    fn check_threshold_at_boundary(
        &self,
        boundary_volume: f64,
        v_working: f64,
        net_rain_mm: f64,
        breakpoints: &[Breakpoint],
    ) -> Option<SolveResult> {
        // Find which breakpoint matches this boundary
        for bp in breakpoints {
            if let Breakpoint::ReleaseThreshold { outlet: _ } = bp {
                let bp_vol = self.breakpoint_volume(bp);
                if (bp_vol - boundary_volume).abs() < EPSILON {
                    // Solution is at this threshold boundary
                    let area = self.d.interpolate(VOLU, AREA, boundary_volume);
                    let spill = self.d.interpolate(VOLU, SPIL, boundary_volume).max(0.0);

                    // Compute release budget from mass balance constraint
                    let outflow_needed = v_working + net_rain_mm * area - boundary_volume;
                    let release_budget = (outflow_needed - spill).max(0.0);

                    // Allocate constrained budget by priority
                    return Some(self.build_result_at_boundary(boundary_volume, release_budget));
                }
            }
        }
        None
    }

    /// Builds a SolveResult for a given final volume (unconstrained case).
    /// All active outlets receive their full orders.
    fn build_result_at_volume(&self, volume: f64) -> SolveResult {
        let level = self.d.interpolate(VOLU, LEVL, volume);
        let area = self.d.interpolate(VOLU, AREA, volume);
        let spill = self.d.interpolate(VOLU, SPIL, volume).max(0.0);

        // In the unconstrained case, budget equals full capacity
        let release_budget = self.release_capacity_at_volume(volume, spill);
        let ds_flows = self.allocate_releases(volume, spill, release_budget);

        SolveResult { volume, level, area, spill, ds_flows }
    }

    /// Builds a SolveResult for a threshold boundary (constrained case).
    /// Release budget is determined by mass balance; allocation follows priority.
    fn build_result_at_boundary(
        &self,
        volume: f64,
        release_budget: f64,
    ) -> SolveResult {
        let level = self.d.interpolate(VOLU, LEVL, volume);
        let area = self.d.interpolate(VOLU, AREA, volume);
        let spill = self.d.interpolate(VOLU, SPIL, volume).max(0.0);
        let ds_flows = self.allocate_releases(volume, spill, release_budget);

        SolveResult { volume, level, area, spill, ds_flows }
    }
}

// -----------------------------------------------------------------------------
// Node trait implementation
// -----------------------------------------------------------------------------

impl Node for StorageNode {
    fn initialise(&mut self, data_cache: &mut DataCache) -> Result<(), String> {
        // Reset internal state
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
        self.previous_row = 0;

        // Validate dimension table
        if self.d.nrows() < 2 {
            return Err(format!(
                "Storage node '{}': dimension table must have at least 2 rows",
                self.name
            ));
        }

        // Precompute area at minimum storage
        self.area0_km2 = self.d.interpolate(VOLU, AREA, 0.0);

        // Precompute the minimum operating volume
        for i in 0..MAX_DS_LINKS {
            self.min_operating_volume[i] = match self.outlet_definition[i] {
                OutletDefinition::None => 0.0,
                OutletDefinition::OutletWithMOL(mol) | OutletDefinition::OutletWithAndMOLAndCapacity(mol, _) =>
                    self.d.interpolate(LEVL, VOLU, mol),
            };
            println!("MOV {} set to {}", i, self.min_operating_volume[i]);
        }

        // Precompute threshold info for each outlet
        let v_min = self.d.get_value(0, VOLU);
        let v_max = self.d.get_value(self.d.nrows() - 1, VOLU);
        for i in 0..MAX_DS_LINKS {
            let threshold = self.min_operating_volume[i];
            if threshold > 0.0 && threshold >= v_min && threshold <= v_max {
                if let Some(row) = self.d.find_row(VOLU, threshold) {
                    self.threshold_info[i] = Some(ThresholdInfo {
                        volume: threshold,
                        table_row: row,
                    });
                }
            } else {
                self.threshold_info[i] = None;
            }
        }

        // Initialize recorders
        self.recorder_idx_usflow = data_cache.get_series_idx(
            &make_result_name(&self.name, "usflow"), false);
        self.recorder_idx_volume = data_cache.get_series_idx(
            &make_result_name(&self.name, "volume"), false);
        self.recorder_idx_level = data_cache.get_series_idx(
            &make_result_name(&self.name, "level"), false);
        self.recorder_idx_area = data_cache.get_series_idx(
            &make_result_name(&self.name, "area"), false);
        self.recorder_idx_seep = data_cache.get_series_idx(
            &make_result_name(&self.name, "seep"), false);
        self.recorder_idx_rain = data_cache.get_series_idx(
            &make_result_name(&self.name, "rain"), false);
        self.recorder_idx_evap = data_cache.get_series_idx(
            &make_result_name(&self.name, "evap"), false);
        self.recorder_idx_pond_diversion = data_cache.get_series_idx(
            &make_result_name(&self.name, "pond_diversion"), false);
        self.recorder_idx_dsflow = data_cache.get_series_idx(
            &make_result_name(&self.name, "dsflow"), false);
        self.recorder_idx_ds_1 = data_cache.get_series_idx(
            &make_result_name(&self.name, "ds_1"), false);
        self.recorder_idx_ds_2 = data_cache.get_series_idx(
            &make_result_name(&self.name, "ds_2"), false);
        self.recorder_idx_ds_3 = data_cache.get_series_idx(
            &make_result_name(&self.name, "ds_3"), false);
        self.recorder_idx_ds_4 = data_cache.get_series_idx(
            &make_result_name(&self.name, "ds_4"), false);
        self.recorder_idx_ds_1_order = data_cache.get_series_idx(
            &make_result_name(&self.name, "ds_1_order"), false);
        self.recorder_idx_ds_2_order = data_cache.get_series_idx(
            &make_result_name(&self.name, "ds_2_order"), false);
        self.recorder_idx_ds_3_order = data_cache.get_series_idx(
            &make_result_name(&self.name, "ds_3_order"), false);
        self.recorder_idx_ds_4_order = data_cache.get_series_idx(
            &make_result_name(&self.name, "ds_4_order"), false);

        Ok(())
    }

    fn get_name(&self) -> &str {
        &self.name
    }

    fn run_flow_phase(&mut self, data_cache: &mut DataCache) {
        // Get climate inputs
        let rain_mm = self.rain_mm_input.get_value(data_cache);
        let evap_mm = self.evap_mm_input.get_value(data_cache);
        let seep_mm = self.seep_mm_input.get_value(data_cache);
        let pond_demand = self.pond_demand_input.get_value(data_cache);
        let net_rain_mm = rain_mm - evap_mm - seep_mm;

        // Add upstream inflow to storage
        self.v += self.usflow;

        // Handle pond diversion (takes priority over all other releases)
        let max_pond = self.v + (net_rain_mm * self.area0_km2).max(0.0);
        self.pond_diversion = pond_demand.min(max_pond).max(0.0);
        self.v -= self.pond_diversion;

        // Working volume for backward Euler (after pond diversion)
        let v_working = self.v;

        // Solve for final volume and outlet flows
        let result = self.solve_backward_euler(v_working, net_rain_mm);

        // Update state from solution
        self.v = result.volume;
        self.level = result.level;
        self.spill = result.spill;
        self.ds_1_flow = result.ds_flows[0];
        self.ds_2_flow = result.ds_flows[1];
        self.ds_3_flow = result.ds_flows[2];
        self.ds_4_flow = result.ds_flows[3];
        self.dsflow = self.ds_1_flow + self.ds_2_flow + self.ds_3_flow + self.ds_4_flow;

        // Compute climate volumes using solved area
        self.rain_vol = rain_mm * result.area;
        self.evap_vol = evap_mm * result.area;
        self.seep_vol = seep_mm * result.area;

        // Update mass balance (downstream outflows - upstream inflows)
        self.mbal += self.dsflow - self.usflow;

        // Update warm-start hint for next timestep
        if let Some(row) = self.d.find_row(VOLU, result.volume) {
            self.previous_row = row;
        }

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
            data_cache.add_value_at_index(idx, result.area);
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

    fn remove_dsflow(&mut self, outlet: u8) -> f64 {
        match outlet {
            0 => {
                let flow = self.ds_1_flow;
                self.ds_1_flow = 0.0;
                flow
            }
            1 => {
                let flow = self.ds_2_flow;
                self.ds_2_flow = 0.0;
                flow
            }
            2 => {
                let flow = self.ds_3_flow;
                self.ds_3_flow = 0.0;
                flow
            }
            3 => {
                let flow = self.ds_4_flow;
                self.ds_4_flow = 0.0;
                flow
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
