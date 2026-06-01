use super::Node;
use crate::misc::misc_functions::make_result_name;
use crate::data_management::data_cache::DataCache;
use crate::hydrology::accounts::account_manager::AccountManager;
use crate::misc::location::Location;
use crate::numerical::mathfn::quadratic_plus;
use crate::numerical::interpolation::lerp;

const MAX_DS_LINKS: usize = 1;

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

    // Properties and internal state - ordering
    pub typical_regulated_flow: f64,
    pub dsorders: [f64; MAX_DS_LINKS],

    //Recorders
    recorder_idx_usflow: Option<usize>,
    recorder_idx_volume: Option<usize>,
    recorder_idx_dsflow: Option<usize>,
    recorder_idx_ds_1: Option<usize>,
    recorder_idx_ds_1_order: Option<usize>,
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
                // Storage routing lag at a given flow is dS/dQ for the full reach.
                // S_full = K_full * Q^m, so dS/dQ = K_full * m * Q^(m-1).
                // Note nlm_k_working_units is per-division; full reach is *n_divs.
                let pure_lag = self.lag as f64;
                if flow_rate > 0.0 {
                    let k_full = self.nlm_k_working_units * self.n_divs as f64;
                    let storage_lag = k_full * self.nlm_m * flow_rate.powf(self.nlm_m_minus_1);
                    storage_lag + pure_lag
                } else {
                    // m<1 would give infinite lag at Q=0; fall back to pure lag only.
                    pure_lag
                }
            }
        };
        answer
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
    fn initialise(&mut self, data_cache: &mut DataCache, _account_manager: &mut AccountManager) -> Result<(), String>{

        // Initialize only internal state
        self.mbal = 0.0;
        self.usflow = 0.0;
        self.dsflow_primary = 0.0;
        self.storage_volume = 0.0;
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
        let nlm_is_defined = self.nlm_k > 0f64;      //assume k > 0 means NLM
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

        // Init for NLM routing
        if matches!(self.routing_method, StorageRoutingMethod::LagPlusNLM) {
            // Divide k by n_divs so n sub-reaches in series reproduce the full-reach
            // storage at steady state (S_full = K*Q^m == sum of n * (K/n)*Q^m).
            self.nlm_k_working_units = self.nlm_k * 1e-3 * (1f64 / 86.4).powf(self.nlm_m)
                                       / self.n_divs as f64;
            let one_minus_x = 1.0 - self.x;
            self.nlm_one_minus_x = one_minus_x;
            self.nlm_inv_one_minus_x = if self.x_is_unity { 0.0 } else { 1.0 / one_minus_x };
            self.nlm_a = self.nlm_k_working_units * one_minus_x;
            self.nlm_m_minus_1 = self.nlm_m - 1.0;
            self.nlm_qref_array.fill(0.0);
        }
        
        // Init for PWL routing
        if matches!(self.routing_method, StorageRoutingMethod::LagPlusPWL) {
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
        }

        // Init PWL and NLM storage array
        self.div_sto_array.fill(0.0);

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
        self.recorder_idx_ds_1_order = data_cache.get_series_idx(
            make_result_name(&self.name, "ds_1_order").as_str(), false
        );

        //Return
        Ok(())
    }

    fn get_name(&self) -> &str {
        &self.name  // Return reference, not owned String
    }

    fn run_order_phase(&mut self, data_cache: &mut DataCache) {

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

        // Lag routing first
        // Put the new inflow into the lag array
        self.lag_sto_array[self.lag_iter_index] = self.usflow;
        // Now copy the oldest element out of the lag storage array
        let oldest_index = (self.lag_iter_index + 1) % self.lag_sto_used;
        let flow_out_of_lag_reach = self.lag_sto_array[oldest_index];
        self.lag_sto_array[oldest_index] = 0_f64; //set the element to zero
        self.lag_iter_index=oldest_index;

        // PWL or NLM routing second
        match self.routing_method {
            StorageRoutingMethod::LagPlusNLM => {
                let mut qout = flow_out_of_lag_reach; //ingested into the first division
                let k = self.nlm_k_working_units;
                let m = self.nlm_m;

                if self.x_is_unity {
                    // x = 1: q_ref = q_in directly, no iteration.
                    // S_new = k * q_in^m;  q_out = q_in + S_old - S_new.
                    for i in 0..self.n_divs {
                        let qin = qout;
                        let vi = self.div_sto_array[i];
                        let vf_unclamped = k * qin.powf(m);
                        let (new_qout, vf) = if qin + vi - vf_unclamped < 0.0 {
                            (0.0, vi + qin)
                        } else {
                            (qin + vi - vf_unclamped, vf_unclamped)
                        };
                        self.div_sto_array[i] = vf;
                        qout = new_qout;
                    }
                } else {
                    // General x < 1: Newton solve A*y^m + y = b for y = q_ref per division.
                    let x = self.x;
                    let a = self.nlm_a;
                    let one_minus_x = self.nlm_one_minus_x;
                    let inv_one_minus_x = self.nlm_inv_one_minus_x;
                    let m_minus_1 = self.nlm_m_minus_1;
                    const NLM_TOL_ABS: f64 = 1.0e-12;
                    const NLM_TOL_REL: f64 = 1.0e-10;
                    const NLM_MAX_ITER: usize = 8;

                    for i in 0..self.n_divs {
                        let qin = qout;
                        let vi = self.div_sto_array[i];
                        let b = one_minus_x * vi + qin;

                        if b <= 0.0 {
                            // Empty division with no inflow; nothing to solve.
                            self.div_sto_array[i] = 0.0;
                            self.nlm_qref_array[i] = 0.0;
                            qout = 0.0;
                            continue;
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
                        let (new_qout, vf) = if new_qout_raw < 0.0 {
                            // No upstream flow allowed; absorb inflow into storage.
                            (0.0, vi + qin)
                        } else {
                            (new_qout_raw, vi + qin - new_qout_raw)
                        };
                        self.div_sto_array[i] = vf;
                        qout = new_qout;
                    }
                }

                // Final answer
                self.dsflow_primary = qout;
            }
            StorageRoutingMethod::LagPlusPWL => {
                let mut qout = flow_out_of_lag_reach; //ingested into the first division
                for i in 0..self.n_divs {
                    let qin = qout;                   //inflow to this division
                    let vi = self.div_sto_array[i];   //initial storage volume for this division
                    let mut vf = 0.0;                 //variable to hold final storage volume
                    if self.x_is_unity {
                        //For x=1, reference flow "qr" equals inflow.
                        let qr = qin;
                        for j in 0..self.pwl_segs {
                            if (qr >= self.seg_par_q1[j]) && (qr <= self.seg_par_q2[j]) {
                                vf = self.seg_par_aa[j] * qr * qr + self.seg_par_bb[j] * qr + self.seg_par_cc[j];
                                qout = vi + qin - vf;
                                break;
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
                                break;
                            }
                        }
                    }

                    //Do not allow water to flow upstream.
                    if qout < 0.0 {
                        qout = 0.0;
                        vf = vi + qin;
                    }

                    //The new storage volume for this division is vf.
                    self.div_sto_array[i] = vf;
                }

                // Final answer
                self.dsflow_primary = qout;
            }
        }

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
        // Reset upstream inflow for next timestep
        self.usflow = 0.0;
    }

    fn add_usflow(&mut self, flow: f64, _inlet: u8) {
        self.usflow += flow;
    }

    fn remove_dsflow(&mut self, outlet: u8) -> f64 {
        match outlet {
            0 => {
                let outflow = self.dsflow_primary;
                self.dsflow_primary = 0.0;
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
