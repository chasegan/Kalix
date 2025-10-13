use crate::timeseries::new_vector;


#[derive(Default)]
#[derive(Clone)]
pub struct Gr4j {
    //GR4J model parameters
    pub x1: f64, //350 [100, 1200]
    pub x2: f64, //0 [-5, 3]
    pub x3: f64, //90 [20, 300]
    pub x4: f64, //1.7 [1.1, 2.9]

    //UH kernel
    uh1_ordinates: Vec<f64>,
    uh2_ordinates: Vec<f64>,

    //UH storages
    uh1: Vec<f64>,
    uh2: Vec<f64>,

    //Store values
    production_store: f64,
    routing_store: f64,
}

impl Gr4j {
    pub fn new() -> Self {
        //Create a struct with preliminary values
        let mut ans = Self {
            x1: 350.0,
            x2: 0.0,
            x3: 90.0,
            x4: 1.7,
            uh1_ordinates: Vec::new(),
            uh2_ordinates: Vec::new(),
            uh1: Vec::new(),
            uh2: Vec::new(),
            production_store: 0.0,
            routing_store: 0.0,
            ..Default::default()
        };
        ans.initialize();

        //Return        
        ans
    }
    
    
    /**
     *
     */
    pub fn initialize(&mut self) {
        //Set up the unit hydrograph kernels and stores (OBS! THESE DEPEND ON x4)
        let n_uh1 = self.x4.ceil() as i32;
        let n_uh2 = (2.0 * self.x4).ceil() as i32;
        self.uh1_ordinates = new_vector(0.0, n_uh1);
        self.uh2_ordinates = new_vector(0.0, n_uh2);
        self.uh1 = new_vector(0.0, self.uh1_ordinates.len() as i32);
        self.uh2 = new_vector(0.0, self.uh2_ordinates.len() as i32);        
        for t in 1..((n_uh1 + 1) as usize) {
            self.uh1_ordinates[t - 1] = s_curves1(t, self.x4) - s_curves1(t - 1, self.x4);
        }
        for t in 1..((n_uh2 + 1) as usize) {
            self.uh2_ordinates[t - 1] = s_curves2(t, self.x4) - s_curves2(t - 1, self.x4);
        }

        //Set up the production and routing stores
        self.production_store = 0.0;
        self.routing_store = 0.0;
    }


    /**
     *
     */
    pub fn run_step(&mut self, p: f64, e: f64) -> f64 {
        let mut reservoir_production = 0.0;
        let mut routing_pattern = 0.0;
        let mut net_evap = 0.0;

        //Precipitation and evaporation
        if p > e {
            let mut scaled_net_precip = (p - e) / self.x1;
            if scaled_net_precip > 13.0 {
                scaled_net_precip = 13.0;
            }
            let tanh_scaled_net_precip = f64::tanh(scaled_net_precip);
            reservoir_production = 
                (self.x1 * (1.0 - (self.production_store / self.x1).powi(2)) * tanh_scaled_net_precip)
                / (1.0 + self.production_store / self.x1 * tanh_scaled_net_precip);
            routing_pattern = p - e - reservoir_production;
        } else {
            let mut scaled_net_evap = (e - p) / self.x1;
            if scaled_net_evap > 13.0 {
                scaled_net_evap = 13.0;
            }
            let tanh_scaled_net_evap = f64::tanh(scaled_net_evap);
            let ps_div_x1 = (2.0 - self.production_store / self.x1) * tanh_scaled_net_evap;
            net_evap = self.production_store * ps_div_x1
                / (1.0 + (1.0 - self.production_store / self.x1) * tanh_scaled_net_evap);
        }

        //Production store
        self.production_store = self.production_store - net_evap + reservoir_production;
        let percolation = self.production_store
            / (1.0 + (self.production_store / 2.25 / self.x1).powi(4)).powf(0.25);
        routing_pattern = routing_pattern + (self.production_store - percolation);
        self.production_store = percolation;

        //Unit hydrographs
        let uh_len = self.uh1.len();
        for i in 0..uh_len - 1 {
            self.uh1[i] = self.uh1[i + 1] + self.uh1_ordinates[i] * routing_pattern;
        }
        self.uh1[uh_len - 1] = self.uh1_ordinates[uh_len - 1] * routing_pattern;
        let uh_len = self.uh2.len();
        for i in 0..uh_len - 1 {
            self.uh2[i] = self.uh2[i + 1] + self.uh2_ordinates[i] * routing_pattern;
        }
        self.uh2[uh_len - 1] = self.uh2_ordinates[uh_len - 1] * routing_pattern;

        //Groundwater and routing store
        let groundwater_exchange = self.x2 * (self.routing_store / self.x3).powf(3.5);
        self.routing_store = f64::max(
            0.0, self.routing_store + self.uh1[0] * 0.9 + groundwater_exchange);
        let r2 = self.routing_store
            / (1.0 + (self.routing_store / self.x3).powi(4)).powf(0.25);
        let qr = self.routing_store - r2;
        self.routing_store = r2;
        let qd = f64::max(0.0, self.uh2[0] * 0.1 + groundwater_exchange);
        let q = qr + qd;
        q
    }
}

/**
 * Unit hydrograph ordinates for UH1 derived from S-curves.
 */
fn s_curves1(t: usize, x4: f64) -> f64 {
    let t_f64 = t as f64;
    if t <= 0 {
        0.0
    } else if t_f64 < x4 {
        (t_f64 / x4).powf(2.5)
    } else {
        1.0
    }
}

/**
 * Unit hydrograph ordinates for UH2 derived from S-curves.
 */
fn s_curves2(t: usize, x4: f64) -> f64 {
    let t_f64 = t as f64;
    if t <= 0 {
        0.0
    } else if t_f64 < x4 {
        0.5 * (t_f64 / x4).powf(2.5)
    } else if t_f64 < 2.0 * x4 {
        1.0 - 0.5 * (2.0 - t_f64 / x4).powf(2.5)
    } else {
        1.0 // t >= x4
    }
}
