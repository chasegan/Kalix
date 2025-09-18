use super::Node;
use crate::misc::misc_functions::make_result_name;
use crate::data_cache::DataCache;
use crate::misc::location::Location;
use super::super::numerical::mathfn::quadratic_plus;

#[derive(Default, Clone)]
pub struct RoutingNode {
    pub name: String,
    pub location: Location,

    // Internal state only
    usflow: f64,
    dsflow_primary: f64,
    storage: f64,

    //Parameters
    lag: i32,           //number of days lag
    x: f64,             //inflow bias x
    pwl_divs: usize,    //number of divisions in the pwl routing
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
    
    //State vars and calculation vars for pwl routing part
    //====================================================
    pwl_sto_array: [f64; 32], //This is storage in the divisions, supporting up to 32 divisions.
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

    //Recorders
    recorder_idx_dsflow: Option<usize>,
    recorder_idx_usflow: Option<usize>,
    recorder_idx_storage: Option<usize>,
}

impl RoutingNode {

    /// Base constructor
    pub fn new() -> RoutingNode {
        RoutingNode {
            name: "".to_string(),
            pwl_divs: 1,
            x: 0.0,
            ..Default::default()
        }
    }

    /// Base constructor with name
    pub fn new_named(name: &str) -> Self {
        Self {
            name: name.to_string(),
            pwl_divs: 1,
            x: 0.0,
            ..Default::default()
        }
    }

    pub fn set_x(&mut self, value: f64) {
        self.x = value;
    }
    
    pub  fn set_divs(&mut self, value: usize) {
        self.pwl_divs = value;
    }
    
    pub  fn set_lag(&mut self, value: i32) {
        self.lag = value;
    }

    pub  fn set_routing_table(&mut self, index_flows: Vec<f64>, travel_times: Vec<f64>) {
        self.pwl_segs = index_flows.len() - 1;
        for i in 0..=self.pwl_segs {
            self.pwl_qq[i] = index_flows[i];
            self.pwl_tt[i] = travel_times[i];
        }
    }

    /// Calculate the node storage by adding up all water volumes in the
    /// lag array and pwl arrays.
    fn calculate_storage(&mut self) -> f64 {
        let mut answer = 0f64;
        for i in 0..self.lag_sto_used {
            answer += self.lag_sto_array[i];
        }
        for i in 0..self.pwl_divs {
            answer += self.pwl_sto_array[i];
        }
        answer
    }
}


impl Node for RoutingNode {
    fn initialise(&mut self, data_cache: &mut DataCache) {

        // Initialize only internal state
        self.usflow = 0.0;
        self.dsflow_primary = 0.0;
        self.storage = 0.0;

        // Init for lag routing
        for i in 0..self.lag_sto_array.len(){
            self.lag_sto_array[i] = 0_f64;
        }
        self.lag_sto_used = (self.lag + 1) as usize;
        self.lag_iter_index = 0;

        // Here I can pre-compute all the PWL segment parameters
        let d = self.pwl_divs as f64;
        let mut temp_v = 0_f64;
        for i in 0..self.pwl_tt.len() - 1 {
            
            //Calculate the parameters of pwl segment 1
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
        for i in 0..self.pwl_sto_array.len() {
            self.pwl_sto_array[i] = 0_f64;
        }

        // Initialize result recorders
        self.recorder_idx_dsflow = data_cache.get_series_idx(
            make_result_name(&self.name, "dsflow").as_str(), false
        );
        self.recorder_idx_usflow = data_cache.get_series_idx(
            make_result_name(&self.name, "usflow").as_str(), false
        );
        self.recorder_idx_storage = data_cache.get_series_idx(
            make_result_name(&self.name, "storage").as_str(), false
        );
    }

    fn get_name(&self) -> &str {
        &self.name  // Return reference, not owned String
    }

    /// Runs the node for the current timestep and updates the node state
    fn run_flow_phase(&mut self, data_cache: &mut DataCache) {

        // Lag routing first
        // Put the new inflow into the lag array
        self.lag_sto_array[self.lag_iter_index] = self.usflow;
        // Now copy the oldest element out of the lag storage array
        let oldest_index = (self.lag_iter_index + 1) % self.lag_sto_used;
        let flow_out_of_lag_reach = self.lag_sto_array[oldest_index];
        self.lag_sto_array[oldest_index] = 0_f64; //set the element to zero
        self.lag_iter_index=oldest_index;

        // Pwl routing second
        let mut qout = flow_out_of_lag_reach; //define so it gets ingested into the first division
        for i in 0..self.pwl_divs {
            let qin = qout;                   //inflow to this division
            let vi = self.pwl_sto_array[i];   //initial storage volume for this division
            let mut vf = 0f64;                //variable to hold final storage volume
            for j in 0..self.pwl_segs {
                if self.x > 0.999999 {
                    //For inflow bias=1, reference flow "qr" equals inflow.
                    //TODO: I could move this loop outside the 'i' loop to avoid checking every div
                    let qr = qin;
                    if (qr >= self.seg_par_q1[j]) & (qr <= self.seg_par_q2[j])
                    {
                        let vf = self.seg_par_aa[j] * qr * qr + self.seg_par_bb[j] * qr + self.seg_par_cc[j];
                        qout = vi + qin - vf;
                        break
                    }
                } else {
                    //For inflow bias<1, reference flow "qr" is not known a priori.
                    let a = self.seg_par_aa[j];
                    let b = self.seg_par_bb[j] + (1.0/(1.0 - self.x));
                    let c = self.seg_par_cc[j] - vi - qin/(1.0 - self.x);
                    let qr = quadratic_plus(a, b, c);

                    //Check if qr is within the segment and if so finalise solution
                    if (!qr.is_nan()) && (qr >= self.seg_par_q1[j] && qr <= self.seg_par_q2[j]) {
                        qout = (qr - qin * self.x) / (1.0 - self.x);
                        vf = vi + qin - qout;
                        break;
                    }
                }
            }

            //Do not allow water to flow upstream.
            if qout < 0f64 {
                qout = 0f64;
                vf = vi + qin;
            }

            //The new storage volume for this division is vf.
            self.pwl_sto_array[i] = vf;
        }

        // Store outflow
        self.dsflow_primary = qout;

        // Record results
        if let Some(idx) = self.recorder_idx_dsflow {
            data_cache.add_value_at_index(idx, self.dsflow_primary);
        }
        if let Some(idx) = self.recorder_idx_usflow {
            data_cache.add_value_at_index(idx, self.usflow);
        }
        if let Some(idx) = self.recorder_idx_storage {
            self.storage = self.calculate_storage();
            data_cache.add_value_at_index(idx, self.storage);
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
}
