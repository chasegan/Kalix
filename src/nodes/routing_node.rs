use super::{recorder, single_outlet_node_impls, Node};
use crate::data_management::data_cache::DataCache;
use crate::hydrology::accounts::account_manager::AccountManager;
use crate::misc::location::Location;
use crate::model_inputs::DynamicInput;
use crate::numerical::mathfn::quadratic_plus;
use crate::numerical::interpolation::lerp;
use crate::numerical::opt::optimisable_component::OptimisableComponent;
use crate::numerical::table::Table;

const MAX_DS_LINKS: usize = 1;
const PWL_TT_PREFIX: &str = "pwl_tt_";
/// loss_table column index for flow
const FLOW: usize = 0;
/// loss_table column index for dead storage volume
const DSVO: usize = 1;
/// loss_table column index for area
const AREA: usize = 2;

/// One piece of a per-division area lookup: area = a_lo + (x - x_lo) * slope for x_lo <= x < next x_lo.
#[derive(Default, Clone, Copy)]
struct AreaSegment {
    x_lo: f64,
    a_lo: f64,
    slope: f64,
}

impl AreaSegment {
    /// Area at `x` on this segment's line (no range check).
    #[inline(always)]
    fn area(&self, x: f64) -> f64 {
        self.a_lo + (x - self.x_lo) * self.slope
    }
}

/// Builds a per-division area lookup from (x, reach area) points with ascending x.
/// Areas are divided by `n_divs`; where x repeats the last point wins. The final
/// segment is flat, so the area holds at the last point beyond the table.
fn build_area_segments(points: impl Iterator<Item = (f64, f64)>, n_divs: f64, segs: &mut Vec<AreaSegment>) {
    segs.clear();
    for (x, area) in points {
        let a = area / n_divs;
        match segs.last_mut() {
            Some(last) if last.x_lo == x => last.a_lo = a,
            Some(last) => {
                last.slope = (a - last.a_lo) / (x - last.x_lo);
                segs.push(AreaSegment { x_lo: x, a_lo: a, slope: 0.0 });
            }
            None => segs.push(AreaSegment { x_lo: x, a_lo: a, slope: 0.0 }),
        }
    }
}

/// Per-division area at `x`. Linear scan: loss tables are a handful of rows.
/// `segs` must be non-empty.
#[inline(always)]
fn area_at(segs: &[AreaSegment], x: f64) -> f64 {
    let mut s = &segs[0];
    for next in &segs[1..] {
        if next.x_lo > x { break; }
        s = next;
    }
    s.area(x)
}

#[derive(Default, Clone)]
pub enum StorageRoutingMethod {
    #[default]
    // None, # could this be a faster special case?
    // Lag,  # could this be a faster special case?
    LagPlusNLM,
    LagPlusPWL,
}

#[derive(Default, Clone)]
pub struct RoutingNode {
    pub name: String,
    pub location: Location,
    pub mbal: f64,

    // Internal state only
    usflow: f64,
    dsflow_primary: f64,
    storage_volume: f64,

    //Parameters
    routing_method: StorageRoutingMethod,
    lag: usize,         //number of days lag
    x: f64,             //inflow bias x
    n_divs: usize,      //number of divisions in the storage routing
    nlm_m: f64,         //nonlinear muskingum m parameter
    nlm_k: f64,         //nonlinear muskingum k parameter
    nlm_k_working_units: f64, //nlm_k converted so that storage_ML = nlm_k_working_units * flow_ML_per_day^m
    nlm_a: f64,                //precomputed: nlm_k_working_units * (1 - x)
    nlm_one_minus_x: f64,      //precomputed: 1 - x
    nlm_inv_one_minus_x: f64,  //precomputed: 1 / (1 - x); 0 when x_is_unity
    nlm_m_minus_1: f64,        //precomputed: m - 1
    pwl_segs: usize,    //number of segments defined in the seg_par_xx arrays
    pwl_qq: [f64; 32],  //pwl routing definition - index flows, supporting up to 32 points
    pwl_tt: [f64; 32],  //pwl routing definition - travel times, supporting up to 32 points

    //State vars and calculation vars for lag routing part
    //====================================================
    //The array below is for storing flow values in the lag part of the routing. 
    //The array has a fixed length, but we will only use as many elements as we need.
    //We will need lag+1 elements.
    lag_sto_array: [f64; 32], //This allows lag up to 31 days. The 32 here is a rust limitation if we want to use the automatically derived default.
    lag_sto_used: usize,      //number of elements being used, set to (self.lag+1) during initialise.
    lag_iter_index: usize,    //this index keeps track of the index where the next inflows are going.
    
    //State vars and calculation vars for NWM and PWL routing parts
    //=============================================================
    x_is_unity: bool,         //Flag set during init if x is APPROXIMATELY 1.
    div_sto_array: [f64; 32], //This is storage in the divisions, supporting up to 32 divisions.
    nlm_qref_array: [f64; 32], //Warm-start q_ref values per division for the NLM Newton solver.
    //The arrays below hold parameters for the PWL segments, supporting up to 32 segments.
    seg_par_q1: [f64; 32],    //PWL segment parameters - qr at the start of the segment
    seg_par_q2: [f64; 32],    //PWL segment parameters - qr at the end of the segment
    seg_par_t1: [f64; 32],    //PWL segment parameters - tt at the start of the segment
    seg_par_t2: [f64; 32],    //PWL segment parameters - tt at the end of the segment
    seg_par_v1: [f64; 32],    //PWL segment parameters - vol at the start of the segment
    seg_par_v2: [f64; 32],    //PWL segment parameters - vol at the end of the segment
    seg_par_aa: [f64; 32],    //PWL segment parameters - aa coefficient
    seg_par_bb: [f64; 32],    //PWL segment parameters - bb coefficient
    seg_par_cc: [f64; 32],    //PWL segment parameters - cc coefficient
    //Saturation point for reference flows above the PWL table: travel time is
    //flat beyond the last row, so per-division storage caps at V(q_max) and
    //the balance is released downstream. Both zero for a lag-only node (no
    //table), where the fall-through reduces to pass-through.
    pwl_q_max: f64,           //index flow at the top of the table
    pwl_v_max: f64,           //per-division storage integral at pwl_q_max

    // Properties and internal state - ordering
    pub typical_regulated_flow: f64,
    pub dsorders: [f64; MAX_DS_LINKS],

    // Dead storage & evap feature
    /// Evaporation (mm). Assumed >= 0: the still-water solve divides by 1 + E * slope.
    pub evap_mm_input: DynamicInput,
    pub loss_table: Table, 
    /// Using the evaporative losses/dead storage feature? Defined at initialisation time.
    using_reach_losses: bool,
    // Per-division lookups built from loss_table at initialise; areas and dead volumes divided by n_divs.
    div_area_by_flow: Vec<AreaSegment>,      // flowing range: flow -> area, from the last zero-flow row up
    div_area_by_dead_vol: Vec<AreaSegment>,  // zero-flow rows: dead volume -> area
    div_dead_max: f64,                       // dead storage in a full division
    loss: f64,                               // reach loss this step (ML), summed over divisions
    area: f64,                               // reach area this step (km2), summed over divisions

    //Recorders
    recorder_idx_usflow: Option<usize>,
    recorder_idx_volume: Option<usize>,
    recorder_idx_dsflow: Option<usize>,
    recorder_idx_ds_1: Option<usize>,
    recorder_idx_ds_1_order: Option<usize>,
    recorder_idx_evap: Option<usize>,
    recorder_idx_area: Option<usize>,
    recorder_idx_loss: Option<usize>
}

impl RoutingNode {

    /// Base constructor
    pub fn new() -> RoutingNode {
        RoutingNode {
            name: "".to_string(),
            routing_method: StorageRoutingMethod::LagPlusPWL,
            n_divs: 1,
            x: 0.0,
            lag: 0,
            typical_regulated_flow: 0.0,
            nlm_k: 0.0,
            nlm_m: 0.75,
            ..Default::default()
        }
    }

    pub fn set_k(&mut self, value: f64) {
        self.nlm_k = value;
    }
    pub fn get_k(&self) -> f64 { self.nlm_k }

    /// Whether this node uses the nonlinear Muskingum (NLM) routing method.
    /// This is the single source of truth for the NLM-vs-PWL discriminator: a
    /// positive `nlm_k` means NLM, otherwise PWL (or lag-only). Used both when
    /// resolving the routing method in `initialise` and when serialising back
    /// to INI, so the reader and writer always agree.
    pub fn uses_nlm(&self) -> bool { self.nlm_k > 0.0 }

    pub fn set_m(&mut self, value: f64) {
        self.nlm_m = value;
    }
    pub fn get_m(&self) -> f64 { self.nlm_m }

    pub fn set_x(&mut self, value: f64) {
        self.x = value;
    }
    pub fn get_x(&self) -> f64 { self.x }
    
    pub  fn set_divs(&mut self, value: usize) {
        self.n_divs = value;
    }
    pub fn get_divs(&self) -> usize { self.n_divs }

    pub fn set_lag(&mut self, value: usize) {
        self.lag = value;
    }
    pub fn get_lag(&self) -> usize { self.lag }

    pub fn get_routing_table_as_vec(&self) -> Vec<f64> {
        let mut answer = vec![];
        let n_rows = self.pwl_segs + 1;
        for i in 0..n_rows {
            answer.push(self.pwl_qq[i]);
            answer.push(self.pwl_tt[i]);
        }
        answer
    }

    pub fn set_routing_table(&mut self, index_flows: Vec<f64>, travel_times: Vec<f64>) {
        self.pwl_segs = index_flows.len() - 1;
        for i in 0..=self.pwl_segs {
            self.pwl_qq[i] = index_flows[i];
            self.pwl_tt[i] = travel_times[i];
        }
    }

    /// Estimates the total lag at a given flow rate. This is the sum of the pure lag
    /// and the storage routing lag.
    pub fn estimate_total_lag(&self, flow_rate: f64) -> f64 {
        let answer = match self.routing_method {
            StorageRoutingMethod::LagPlusPWL => {
                let n = self.pwl_segs + 1;
                let storage_lag = 0f64.max(lerp(&self.pwl_qq[..n],
                                                &self.pwl_tt[..n], flow_rate));
                let pure_lag = self.lag as f64;
                storage_lag + pure_lag
            }
            StorageRoutingMethod::LagPlusNLM => {
                // Storage routing lag at a given flow is dS/dQ summed across divisions.
                // Per-div S = k * Q^m so per-div dS/dQ = k * m * Q^(m-1); n_divs in series
                // gives total reach lag = n_divs * k * m * Q^(m-1).
                let pure_lag = self.lag as f64;
                if flow_rate > 0.0 {
                    let storage_lag = self.n_divs as f64 * self.nlm_k_working_units
                                    * self.nlm_m * flow_rate.powf(self.nlm_m_minus_1);
                    storage_lag + pure_lag
                } else {
                    // m<1 would give infinite lag at Q=0; fall back to pure lag only.
                    pure_lag
                }
            }
        };
        answer
    }

    /// Splits loss_table into the per-division lookups. Leading zero-flow rows
    /// give area by dead volume; the last of them onward gives area by flow.
    fn build_loss_lookups(&mut self) -> Result<(), String> {
        let t = &self.loss_table;
        let nrows = t.nrows();
        if nrows == 0 {
            return Err(format!("Error in node '{}'. Loss table has no rows.", self.name));
        }
        if t.get_value(0, FLOW) != 0.0 {
            return Err(format!("Error in node '{}'. Loss table must begin with flow = 0.", self.name));
        }
        for r in 0..nrows {
            let (q, v, a) = (t.get_value(r, FLOW), t.get_value(r, DSVO), t.get_value(r, AREA));
            // `!(x >= 0)` also catches NaN.
            if !(q >= 0.0) || !(v >= 0.0) || !(a >= 0.0) {
                return Err(format!("Error in node '{}'. Loss table values must be non-negative (row {}).", self.name, r + 1));
            }
            if r > 0 && (q < t.get_value(r - 1, FLOW) || v < t.get_value(r - 1, DSVO)) {
                return Err(format!(
                    "Error in node '{}'. Loss table flow and dead storage volume must not decrease (row {}).",
                    self.name, r + 1
                ));
            }
        }

        // Validate that areas are non-decreasing (the still-water solve's segment scan relies on it)
        for r in 1..nrows {
            if t.get_value(r, AREA) < t.get_value(r - 1, AREA) {
                return Err(format!(
                    "Error in node '{}'. Loss table areas must be non-decreasing (violation at row {}).",
                    self.name, r + 1
                ));
            }
        }

        let n_zero = (0..nrows).take_while(|&r| t.get_value(r, FLOW) == 0.0).count();
        let d = self.n_divs as f64;
        build_area_segments((0..n_zero).map(|r| (t.get_value(r, DSVO) / d, t.get_value(r, AREA))), d, &mut self.div_area_by_dead_vol);
        build_area_segments((n_zero - 1..nrows).map(|r| (t.get_value(r, FLOW), t.get_value(r, AREA))), d, &mut self.div_area_by_flow);
        self.div_dead_max = t.get_value(n_zero - 1, DSVO) / d;
        Ok(())
    }

    /// Calculate the node storage by adding up all water volumes in the
    /// lag array and pwl arrays.
    fn calculate_storage(&mut self) -> f64 {
        let mut total_storage = 0.0;

        // Lag storage
        for i in 0..self.lag_sto_used {
            total_storage += self.lag_sto_array[i];
        }

        // PWL or NLM storage
        for i in 0..self.n_divs {
            total_storage += self.div_sto_array[i];
        }

        total_storage
    }
}


impl Node for RoutingNode {
    single_outlet_node_impls!();

    fn initialise(&mut self, data_cache: &mut DataCache, _account_manager: &mut AccountManager) -> Result<(), String>{

        // Initialize only internal state
        self.mbal = 0.0;
        self.usflow = 0.0;
        self.dsflow_primary = 0.0;
        self.storage_volume = 0.0;
        self.loss = 0.0;
        self.area = 0.0;
        self.x_is_unity = self.x > 0.999999;

        // Validate array bounds
        if self.lag >= self.lag_sto_array.len() {
            return Err(format!(
                "Error in node '{}'. Lag value {} exceeds maximum of {}.",
                self.name, self.lag, self.lag_sto_array.len() - 1
            ));
        }
        if self.n_divs > self.div_sto_array.len() {
            return Err(format!(
                "Error in node '{}'. Number of divisions {} exceeds maximum of {}.",
                self.name, self.n_divs, self.div_sto_array.len()
            ));
        }
        if self.pwl_segs + 1 > self.pwl_qq.len() {
            return Err(format!(
                "Error in node '{}'. Routing table has {} points which exceeds maximum of {}.",
                self.name, self.pwl_segs + 1, self.pwl_qq.len()
            ));
        }

        // Validate PWL table index flows are strictly increasing
        for i in 0..self.pwl_segs {
            if self.pwl_qq[i + 1] <= self.pwl_qq[i] {
                return Err(format!(
                    "Error in node '{}'. Routing table index flows must be strictly increasing (violation at row {}).",
                    self.name, i + 2
                ));
            }
        }

        // Validate PWL travel times are non-negative. Segment storage is
        // V(q) = integral of tt(q), so a negative travel time makes V
        // non-monotonic and the reference-flow solver loses root uniqueness.
        // (`!(x >= 0)` also catches NaN.)
        for i in 0..=self.pwl_segs {
            if !(self.pwl_tt[i] >= 0.0) {
                return Err(format!(
                    "Error in node '{}'. Routing table travel times must be non-negative, got {} at row {}.",
                    self.name, self.pwl_tt[i], i + 1
                ));
            }
        }

        // Validate NLM parameters
        // k must not be negative (would silently fall through to PWL since NLM is detected by k>0).
        if self.nlm_k < 0.0 {
            return Err(format!(
                "Error in node '{}'. NLM parameter 'k' must be non-negative, got {}.",
                self.name, self.nlm_k
            ));
        }
        // m only matters when NLM is active; m <= 0 makes Q^(m-1) singular or trivial.
        // Upper bound is generous - typical hydrology uses 0.6 to ~1.0.
        if self.nlm_k > 0.0 && (self.nlm_m <= 0.0 || self.nlm_m > 5.0) {
            return Err(format!(
                "Error in node '{}'. NLM parameter 'm' must be in (0, 5], got {}.",
                self.name, self.nlm_m
            ));
        }

        // Detect and check StorageRoutingMethod
        let nlm_is_defined = self.uses_nlm();        //k > 0 means NLM
        let pwl_is_defined = self.pwl_segs > 0usize; //assume pwl_segs means PWL
        if nlm_is_defined && pwl_is_defined {
            // Error we cant have both pwl and nlm in one node.
            return Err(format!("Error in node '{}'. Cannot have NLM and PWL routing in same node.", self.name));
        } else if nlm_is_defined {
            self.routing_method = StorageRoutingMethod::LagPlusNLM;
        } else if pwl_is_defined {
            self.routing_method = StorageRoutingMethod::LagPlusPWL;
        } else {
            // Just need lag. Default to PWL because that should work anyway.
            // TODO: replace this with a lag-only variant because it might make the simulation faster.
            self.routing_method = StorageRoutingMethod::LagPlusPWL;
        }

        // Init for lag routing
        self.lag_sto_array.fill(0.0);
        self.lag_sto_used = self.lag + 1;
        self.lag_iter_index = 0;

        match self.routing_method {
            StorageRoutingMethod::LagPlusNLM => {
                // Convert step_size (seconds) to days. On the configure-time pass step_size
                // is still 0; fall back to 1 day. initialise() is called again from
                // initialize_network() once step_size is set, which overwrites these values
                // before run_flow_phase ever fires.
                let dt_days = if data_cache.step_size == 0 {
                    1.0
                } else {
                    data_cache.step_size as f64 / 86400.0
                };
                // k applies per-division (convention matching other NLM implementations).
                // Total reach storage at steady state is n_divs * k * Q^m.
                self.nlm_k_working_units = self.nlm_k * 1e-3
                                         * (1.0 / (86.4 * dt_days)).powf(self.nlm_m);
                let one_minus_x = 1.0 - self.x;
                self.nlm_one_minus_x = one_minus_x;
                self.nlm_inv_one_minus_x = if self.x_is_unity { 0.0 } else { 1.0 / one_minus_x };
                self.nlm_a = self.nlm_k_working_units * one_minus_x;
                self.nlm_m_minus_1 = self.nlm_m - 1.0;
                self.nlm_qref_array.fill(0.0);
            },
            StorageRoutingMethod::LagPlusPWL => {
                // Initialise pwl segment parameters
                let d = self.n_divs as f64;
                let mut temp_v = 0.0;
                for i in 0..self.pwl_segs {

                    //Calculate the parameters of pwl segment i
                    let q1 = self.pwl_qq[i];
                    let q2 = self.pwl_qq[i+1];
                    let t1 = self.pwl_tt[i] / d;
                    let t2 = self.pwl_tt[i+1] / d;
                    let a = 0.5 * (t2 - t1) / (q2 - q1);
                    let b = t1 - q1 * (t2 - t1) / (q2 - q1);
                    let c = temp_v - a*q1*q1 - b*q1;
                    let v1 = temp_v;
                    let v2 = a*q2*q2 + b*q2 + c;
                    temp_v = v2;

                    //Put the above into the table of segment parameters
                    self.seg_par_v1[i] = v1;
                    self.seg_par_v2[i] = v2;
                    self.seg_par_q1[i] = q1;
                    self.seg_par_q2[i] = q2;
                    self.seg_par_t1[i] = t1;
                    self.seg_par_t2[i] = t2;
                    self.seg_par_aa[i] = a;
                    self.seg_par_bb[i] = b;
                    self.seg_par_cc[i] = c;
                }

                //Saturation point for out-of-table reference flows (see run_flow_phase).
                if self.pwl_segs > 0 {
                    self.pwl_q_max = self.seg_par_q2[self.pwl_segs - 1];
                    self.pwl_v_max = self.seg_par_v2[self.pwl_segs - 1];
                } else {
                    self.pwl_q_max = 0.0;
                    self.pwl_v_max = 0.0;
                }
            }
        }

        // Init PWL and NLM storage array
        self.div_sto_array.fill(0.0);

        // Init for dead storage and loss feature
        // Verified that evap & loss table defined together so sufficient to check only one.
        self.using_reach_losses = !matches!(self.evap_mm_input, DynamicInput::None { .. });
        if self.using_reach_losses {
            self.build_loss_lookups()?;
        }

        // Initialize result recorders
        self.recorder_idx_usflow = recorder(data_cache, &self.name, "usflow");
        self.recorder_idx_volume = recorder(data_cache, &self.name, "volume");
        self.recorder_idx_dsflow = recorder(data_cache, &self.name, "dsflow");
        self.recorder_idx_ds_1 = recorder(data_cache, &self.name, "ds_1");
        self.recorder_idx_ds_1_order = recorder(data_cache, &self.name, "ds_1_order");
        self.recorder_idx_evap = recorder(data_cache, &self.name, "evap");
        self.recorder_idx_area = recorder(data_cache, &self.name, "area");
        self.recorder_idx_loss = recorder(data_cache, &self.name, "loss");

        //Return
        Ok(())
    }

    fn get_name(&self) -> &str {
        &self.name  // Return reference, not owned String
    }

    fn run_order_phase(&mut self, data_cache: &mut DataCache, _account_manager: &mut AccountManager) {

        // Record downstream orders
        if let Some(idx) = self.recorder_idx_ds_1_order {
            data_cache.add_value_at_index(idx, self.dsorders[0]);
        }
    }

    /// Runs the node for the current timestep and updates the node state
    fn run_flow_phase(&mut self, data_cache: &mut DataCache, _account_manager: &mut AccountManager) {

        // Record results
        if let Some(idx) = self.recorder_idx_usflow {
            data_cache.add_value_at_index(idx, self.usflow);
        }
        
        let evap_mm = self.evap_mm_input.get_value(data_cache);
        if let Some(idx) = self.recorder_idx_evap {
            data_cache.add_value_at_index(idx, evap_mm);
        }

        // Lag routing first
        // Put the new inflow into the lag array
        self.lag_sto_array[self.lag_iter_index] = self.usflow;
        // Now copy the oldest element out of the lag storage array
        let oldest_index = (self.lag_iter_index + 1) % self.lag_sto_used;
        let flow_out_of_lag_reach = self.lag_sto_array[oldest_index];
        self.lag_sto_array[oldest_index] = 0_f64; //set the element to zero
        self.lag_iter_index=oldest_index;

        // Core logic of this node
        self.route_divisions(flow_out_of_lag_reach, evap_mm);

        // Update mass balance
        self.mbal += self.dsflow_primary - self.usflow;

        // Record results
        if let Some(idx) = self.recorder_idx_volume {
            self.storage_volume = self.calculate_storage();
            data_cache.add_value_at_index(idx, self.storage_volume);
        }
        if let Some(idx) = self.recorder_idx_dsflow {
            data_cache.add_value_at_index(idx, self.dsflow_primary);
        }
        if let Some(idx) = self.recorder_idx_ds_1 {
            data_cache.add_value_at_index(idx, self.dsflow_primary);
        }
        if let Some(idx) = self.recorder_idx_area {
            data_cache.add_value_at_index(idx, self.area);
        }
        if let Some(idx) = self.recorder_idx_loss {
            data_cache.add_value_at_index(idx, self.loss);
        }
        // Reset upstream inflow for next timestep
        self.usflow = 0.0;
    }
}

// ============================================================================
// OptimisableComponent Implementation
// ============================================================================

impl RoutingNode {
    /// Whether this node's optimisable parameters are the PWL travel times
    /// (as opposed to the NLM pair). Keys off `pwl_segs` rather than
    /// `uses_nlm()`: the routing table is structural (the optimiser never
    /// touches it), whereas a candidate `nlm_k` of 0 would flip `uses_nlm()`
    /// mid-optimisation and make later `set_param` calls spuriously fail.
    /// A lag-only node (no table) lands in the NLM bucket: calibrating k onto
    /// it is how a modeller would give it NLM routing.
    fn optimises_pwl(&self) -> bool {
        self.pwl_segs > 0
    }

    /// Parse and bounds-check the index of a `pwl_tt_<i>` parameter name.
    /// Valid indices are 0 to `pwl_segs` inclusive (the table has
    /// `pwl_segs + 1` points).
    fn parse_pwl_tt_index(&self, name: &str) -> Result<usize, String> {
        let idx_str = &name[PWL_TT_PREFIX.len()..];
        let idx = idx_str.parse::<usize>().map_err(|_| {
            format!("Node '{}': invalid index in parameter '{}'", self.name, name)
        })?;
        if idx > self.pwl_segs {
            return Err(format!(
                "Node '{}': parameter '{}' out of range - routing table has {} points ({}0 to {}{})",
                self.name, name, self.pwl_segs + 1, PWL_TT_PREFIX, PWL_TT_PREFIX, self.pwl_segs
            ));
        }
        Ok(idx)
    }

    /// Core node logic - run once per time step.
    fn route_divisions(&mut self, flow_out_of_lag_reach: f64, evap_mm: f64) {
        // Checked once per step; each branch runs one instantiation.
        if self.using_reach_losses {
            self.route_divisions_with::<true>(flow_out_of_lag_reach, evap_mm)
        } else {
            self.route_divisions_with::<false>(flow_out_of_lag_reach, evap_mm)
        }
    }

    /// Routes the flow out of the lag reach through every division.
    fn route_divisions_with<const USING_REACH_LOSSES: bool>(&mut self, flow_out_of_lag_reach: f64, evap_mm: f64) {
        // PWL or NLM routing second
        let mut qout = flow_out_of_lag_reach; //ingested into the first division
        if USING_REACH_LOSSES {
            self.loss = 0.0;
            self.area = 0.0;
        }
        match self.routing_method {
            StorageRoutingMethod::LagPlusNLM => {
                if self.x_is_unity {
                    for i in 0..self.n_divs {
                        qout = self.route_division_nlm_unity::<USING_REACH_LOSSES>(i, qout, evap_mm);
                    }
                } else {
                    for i in 0..self.n_divs {
                        qout = self.route_division_nlm_general::<USING_REACH_LOSSES>(i, qout, evap_mm);
                    }
                }
            }
            StorageRoutingMethod::LagPlusPWL => {
                for i in 0..self.n_divs {
                    qout = self.route_division_pwl::<USING_REACH_LOSSES>(i, qout, evap_mm);
                }
            }
        }

        // Final answer
        self.dsflow_primary = qout;
    }

    /// NLM, x = 1: q_ref = q_in directly, no iteration.
    /// S_new = k * q_in^m;  q_out = q_in + S_old - S_new.
    /// Routes division `i` given inflow `qin`; updates its storage and returns its outflow.
    ///
    /// Feature: Reach losses
    /// Optional feature that adds dead storage, and reach losses via evap (mm input).
    /// The dead pool is part of the balance: flowing, S_new = D_d + k * q_in^m and
    ///     q_out = q_in + S_old - S_new - E * A(q_in),
    /// so there is outflow only once the pool is full. Otherwise q_out = 0 and the
    /// storage held is solved by backward Euler with area taken from the storage.
    #[inline(always)]
    fn route_division_nlm_unity<const USING_REACH_LOSSES: bool>(&mut self, i: usize, qin: f64, evap_mm: f64) -> f64 {
        let vi = self.div_sto_array[i];
        let vf_unclamped = self.nlm_k_working_units * qin.powf(self.nlm_m);

        if USING_REACH_LOSSES {
            // div_sto_array holds the division's total storage, dead pool included.
            // Flowing, the pool is full: vf = D_d + V(qin).
            let vf_flowing = self.div_dead_max + vf_unclamped;
            // In the flowing regime, the area at end of timestep is dependent
            // only on the reference flow.
            let area_flowing = area_at(&self.div_area_by_flow, qin);
            let loss_flowing = evap_mm * area_flowing;

            let qf_unclamped = qin + vi - vf_flowing - loss_flowing;
            let (qout, vf) = if qf_unclamped < 0.0 {
                // --- Non-flowing regime ---
                // No outflow from this branch. Solve via Backward Euler on dead storage and area
                //     vf = (vi+qin) - E * A(vf)
                // A is linear on each segment, so 
                //     vf = ((vi+qin) - E * (a_lo - slope * x_lo)) / (1 + E * slope)
                // on the segment holding the root. 
                // g(v) = v + E * A(v) - vpqin is monotone increasing in v subject to (E >= 0, A non-decreasing), 
                // so the root's segment is the last whose start has g <= 0.
                let vpqin = vi + qin;
                let segs = &self.div_area_by_dead_vol;
                let mut s = &segs[0];
                for next in &segs[1..] {
                    if next.x_lo + evap_mm * next.a_lo > vpqin { break; }
                    s = next;
                }
                let vf_solved = ((vpqin - evap_mm * (s.a_lo - s.slope * s.x_lo)) / (1.0 + evap_mm * s.slope)).max(0.0);
                self.loss += vpqin - vf_solved;
                self.area += s.area(vf_solved);
                (0.0, vf_solved)
            } else {
                // --- Flowing regime --- 
                self.loss += loss_flowing;
                self.area += area_flowing;
                (qf_unclamped, vf_flowing)
            };
            self.div_sto_array[i] = vf;
            qout
        } else {
            let qf_unclamped = qin + vi - vf_unclamped;
            let (qout, vf) = if qf_unclamped < 0.0 {
                (0.0, vi + qin)
            } else {
                (qf_unclamped, vf_unclamped)
            };
            self.div_sto_array[i] = vf;
            qout
        }
    }

    /// NLM, general x < 1: Newton solve A*y^m + y = b for y = q_ref.
    /// Routes division `i` given inflow `qin`; updates its storage and returns its outflow.
    #[inline(always)]
    fn route_division_nlm_general<const USING_REACH_LOSSES: bool>(&mut self, i: usize, qin: f64, evap_mm: f64) -> f64 {
        let x = self.x;
        let m = self.nlm_m;
        let a = self.nlm_a;
        let one_minus_x = self.nlm_one_minus_x;
        let inv_one_minus_x = self.nlm_inv_one_minus_x;
        let m_minus_1 = self.nlm_m_minus_1;
        const NLM_TOL_ABS: f64 = 1.0e-12;
        const NLM_TOL_REL: f64 = 1.0e-10;
        const NLM_MAX_ITER: usize = 8;

        let vi = self.div_sto_array[i];
        let b = one_minus_x * vi + qin;

        if b <= 0.0 {
            // Empty division with no inflow; nothing to solve.
            self.div_sto_array[i] = 0.0;
            self.nlm_qref_array[i] = 0.0;
            return 0.0;
        }

        // Warm-start from previous timestep's q_ref for this division;
        // fall back to qin (steady-state guess) on the first step.
        let qref_prev = self.nlm_qref_array[i];
        let mut y = if qref_prev > 0.0 { qref_prev } else { qin.max(1.0e-9) };

        // Newton iteration. f is strictly monotonic on y > 0, so
        // convergence is robust from any positive start; warm-start
        // typically gets us within ~1% of the root in 2-3 iterations.
        for _ in 0..NLM_MAX_ITER {
            let ym1 = y.powf(m_minus_1);   // y^(m-1)  -- the one powf in the loop
            let ym = y * ym1;              // y^m  via one extra multiply
            let f = a * ym + y - b;
            let fp = a * m * ym1 + 1.0;
            let dy = f / fp;
            let y_new = y - dy;
            // Safeguarded update: never let y go non-positive (would NaN the next powf for m<1).
            y = if y_new > 0.0 { y_new } else { 0.5 * y };
            if dy.abs() < NLM_TOL_ABS + NLM_TOL_REL * y { break; }
        }

        self.nlm_qref_array[i] = y;
        let new_qout_raw = (y - x * qin) * inv_one_minus_x;
        let (qout, vf) = if new_qout_raw < 0.0 {
            // No upstream flow allowed; absorb inflow into storage.
            (0.0, vi + qin)
        } else {
            (new_qout_raw, vi + qin - new_qout_raw)
        };
        self.div_sto_array[i] = vf;
        qout
    }

    /// PWL routing for division `i` given inflow `qin`; updates its storage and returns its outflow.
    #[inline(always)]
    fn route_division_pwl<const USING_REACH_LOSSES: bool>(&mut self, i: usize, qin: f64, evap_mm: f64) -> f64 {
        let vi = self.div_sto_array[i];   //initial storage volume for this division
        let mut qout = 0.0;               //variable to hold outflow
        let mut vf = 0.0;                 //variable to hold final storage volume
        'segments: {
            if self.x_is_unity {
                //For x=1, reference flow "qr" equals inflow.
                let qr = qin;
                for j in 0..self.pwl_segs {
                    if (qr >= self.seg_par_q1[j]) && (qr <= self.seg_par_q2[j]) {
                        vf = self.seg_par_aa[j] * qr * qr + self.seg_par_bb[j] * qr + self.seg_par_cc[j];
                        qout = vi + qin - vf;
                        break 'segments;
                    }
                }
            } else {
                //For x<1, reference flow "qr" is not known a priori.
                let inv_one_minus_x = 1.0 / (1.0 - self.x);
                for j in 0..self.pwl_segs {
                    let a = self.seg_par_aa[j];
                    let b = self.seg_par_bb[j] + inv_one_minus_x;
                    let c = self.seg_par_cc[j] - vi - qin * inv_one_minus_x;
                    let qr = quadratic_plus(a, b, c);

                    //Check if qr is within the segment and if so finalise solution
                    if (!qr.is_nan()) && (qr >= self.seg_par_q1[j] && qr <= self.seg_par_q2[j]) {
                        qout = (qr - qin * self.x) * inv_one_minus_x;
                        vf = vi + qin - qout;
                        break 'segments;
                    }
                }
            }

            //No segment matched: the reference flow is above the top of the
            //table (travel time is flat beyond the last row), so storage
            //saturates at V(q_max) and the balance goes downstream. For a
            //lag-only node (no table) this reduces to pass-through.
            debug_assert!(
                self.pwl_segs == 0
                    || self.x * qin + (1.0 - self.x) * (vi + qin - self.pwl_v_max) >= self.pwl_q_max,
                "Node '{}': PWL segment fall-through below the top of the table (qin = {}, vi = {}).",
                self.name, qin, vi
            );
            vf = self.pwl_v_max;
            qout = vi + qin - vf;
        }

        //Do not allow water to flow upstream.
        if qout < 0.0 {
            qout = 0.0;
            vf = vi + qin;
        }

        //The new storage volume for this division is vf.
        self.div_sto_array[i] = vf;
        qout
    }
}

impl OptimisableComponent for RoutingNode {
    fn set_param(&mut self, name: &str, value: f64) -> Result<(), String> {
        // Only the active mode's parameters are accepted, mirroring
        // list_params. Value validation (tt >= 0, k >= 0, m in (0, 5]) stays
        // in initialise(), which reruns before every evaluation.
        if self.optimises_pwl() {
            if name.starts_with(PWL_TT_PREFIX) {
                let idx = self.parse_pwl_tt_index(name)?;
                self.pwl_tt[idx] = value;
                Ok(())
            } else if name == "nlm_k" || name == "nlm_m" {
                Err(format!(
                    "Node '{}' uses PWL routing; '{}' is not optimisable here (available: {}0 to {}{})",
                    self.name, name, PWL_TT_PREFIX, PWL_TT_PREFIX, self.pwl_segs
                ))
            } else {
                Err(format!("Unknown routing parameter: {}", name))
            }
        } else {
            match name {
                "nlm_k" => {
                    self.nlm_k = value;
                    Ok(())
                }
                "nlm_m" => {
                    self.nlm_m = value;
                    Ok(())
                }
                _ if name.starts_with(PWL_TT_PREFIX) => Err(format!(
                    "Node '{}' has no PWL routing table; '{}' is not optimisable here (available: nlm_k, nlm_m)",
                    self.name, name
                )),
                _ => Err(format!("Unknown routing parameter: {}", name)),
            }
        }
    }

    fn get_param(&self, name: &str) -> Result<f64, String> {
        if self.optimises_pwl() {
            if name.starts_with(PWL_TT_PREFIX) {
                let idx = self.parse_pwl_tt_index(name)?;
                Ok(self.pwl_tt[idx])
            } else if name == "nlm_k" || name == "nlm_m" {
                Err(format!(
                    "Node '{}' uses PWL routing; '{}' is not optimisable here (available: {}0 to {}{})",
                    self.name, name, PWL_TT_PREFIX, PWL_TT_PREFIX, self.pwl_segs
                ))
            } else {
                Err(format!("Unknown routing parameter: {}", name))
            }
        } else {
            match name {
                "nlm_k" => Ok(self.nlm_k),
                "nlm_m" => Ok(self.nlm_m),
                _ if name.starts_with(PWL_TT_PREFIX) => Err(format!(
                    "Node '{}' has no PWL routing table; '{}' is not optimisable here (available: nlm_k, nlm_m)",
                    self.name, name
                )),
                _ => Err(format!("Unknown routing parameter: {}", name)),
            }
        }
    }

    fn list_params(&self) -> Vec<String> {
        if self.optimises_pwl() {
            (0..=self.pwl_segs)
                .map(|i| format!("{}{}", PWL_TT_PREFIX, i))
                .collect()
        } else {
            vec!["nlm_k".to_string(), "nlm_m".to_string()]
        }
    }
}
