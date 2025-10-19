#[derive(Default)]
#[derive(Clone)]
pub struct Gr4j {
    //GR4J model parameters
    pub x1: f64, //350 [100, 1200]
    pub x2: f64, //0 [-5, 3]
    pub x3: f64, //90 [20, 300]
    pub x4: f64, //1.7 [1.1, 2.9]

    //UH kernel
    uh1_len: usize,
    uh2_len: usize,
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
        self.uh1_len = self.x4.ceil() as usize;
        self.uh2_len = (2.0 * self.x4).ceil() as usize;
        self.uh1_ordinates = vec![0.0; self.uh1_len];
        self.uh2_ordinates = vec![0.0; self.uh2_len];
        self.uh1 = vec![0.0; self.uh1_len];
        self.uh2 = vec![0.0; self.uh2_len];
        for t in 0..(self.uh1_len as usize) {
            self.uh1_ordinates[t] = s_curves1(t + 1, self.x4) - s_curves1(t, self.x4);
        }
        for t in 0..(self.uh2_len as usize) {
            self.uh2_ordinates[t] = s_curves2(t + 1, self.x4) - s_curves2(t, self.x4);
        }

        //Set up the production and routing stores
        self.production_store = 0.0;
        self.routing_store = 0.0;
    }


    /**
     *
     */
    pub fn run_step(&mut self, p: f64, e: f64) -> f64 {
        let mut ps = 0.0;
        let mut es = 0.0;

        //Precipitation and evaporation
        let s_on_x1 = self.production_store / self.x1; //NOTE: s == production_store
        let pn:f64;
        if p > e {
            //Determine precipitation to the stores, ps
            pn = p - e;
            let pn_on_x1 = pn / self.x1; //.min(13.0); //min(13) comes from the python implementation
            let temp = f64::tanh(pn_on_x1);
            ps = (self.x1 * (1.0 - s_on_x1 * s_on_x1) * temp) / (1.0 + s_on_x1 * temp);
        } else {
            // Determine evaporation from the stores, es
            pn = 0.0;
            let en_on_x1 = (e - p) / self.x1; //.min(13.0); //min(13) comes from the python implementation
            let temp = f64::tanh(en_on_x1);
            es = self.production_store * (2.0 - s_on_x1) * temp / (1.0 + (1.0 - s_on_x1) * temp);
        }

        //Production store
        self.production_store = self.production_store - es + ps;

        //Percolation
        let perc = self.production_store * (1.0 - (1.0 + (self.production_store / 2.25 / self.x1).powi(4)).powf(-0.25));
        self.production_store -= perc;
        let pr = perc + pn - ps;

        //Unit hydrographs
        let pr90 = pr * 0.9; //90% goes through UH1 and then non-linear routing
        for i in 0..self.uh1_len - 1 {
            self.uh1[i] = self.uh1[i + 1] + self.uh1_ordinates[i] * pr90;
        }
        self.uh1[self.uh1_len - 1] = self.uh1_ordinates[self.uh1_len - 1] * pr90;
        let pr10 = pr * 0.1; //10% goes through UH2 and no routing
        for i in 0..self.uh2_len - 1 {
            self.uh2[i] = self.uh2[i + 1] + self.uh2_ordinates[i] * pr10;
        }
        self.uh2[self.uh2_len - 1] = self.uh2_ordinates[self.uh2_len - 1] * pr10;

        //Groundwater exchange rate
        let groundwater_exchange = self.x2 * (self.routing_store / self.x3).powf(3.5);

        //Routing store (applies to UH1)
        self.routing_store = f64::max(0.0, self.routing_store + self.uh1[0] + groundwater_exchange);
        let qr = self.routing_store * (1.0 - (1.0 + (self.routing_store / self.x3).powi(4)).powf(-0.25));
        self.routing_store -= qr;

        //Direct flow
        let qd = f64::max(0.0, self.uh2[0] + groundwater_exchange);

        //Return the total flow
        qr + qd
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
