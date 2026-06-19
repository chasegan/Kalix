/// Selects the model formulation. The two variants are structurally identical
/// (same stores, splits, exchange and routing); they differ only in two
/// constants that were recalibrated for sub-daily timesteps. See airGR
/// (`frun_GR4J.f90`/`frun_GR4H.f90`, `utils_D.f90`/`utils_H.f90`).
#[derive(Default, Clone, Copy, PartialEq, Debug)]
pub enum Gr4Variant {
    /// Classic daily formulation.
    #[default]
    Gr4j,
    /// Sub-daily formulation (airGR's "hourly" model; also used for other
    /// short timesteps such as 30-minute). Despite the "h", the constants are
    /// timestep-agnostic — x4 is expressed in timestep units.
    Gr4h,
}

impl Gr4Variant {
    /// Percolation reservoir factor: percolation acts as if draining a store of
    /// capacity `factor * x1`. GR4J = 9/4, GR4H = 21/4.
    fn perc_factor(self) -> f64 {
        match self {
            Gr4Variant::Gr4j => 2.25, // 9/4
            Gr4Variant::Gr4h => 5.25, // 21/4
        }
    }

    /// Unit-hydrograph S-curve exponent. GR4J = 2.5, GR4H = 1.25.
    fn uh_exponent(self) -> f64 {
        match self {
            Gr4Variant::Gr4j => 2.5,
            Gr4Variant::Gr4h => 1.25,
        }
    }
}

#[derive(Default)]
#[derive(Clone)]
pub struct Gr4j {
    //GR4J model parameters
    pub x1: f64, //350 [100, 1200]
    pub x2: f64, //0 [-5, 3]
    pub x3: f64, //90 [20, 300]
    pub x4: f64, //1.7 [1.1, 2.9]

    //Model formulation (daily GR4J vs sub-daily GR4H)
    pub variant: Gr4Variant,

    //UH kernel
    uh1_len: usize,
    uh2_len: usize,
    uh1_ordinates: Vec<f64>,
    uh2_ordinates: Vec<f64>,

    //UH storages
    uh1: Vec<f64>,
    uh2: Vec<f64>,

    // Precomputed 1.0 / (perc_factor * x1), derived from variant + x1 in
    // initialize(). Keeps the percolation step in run_step() a single multiply
    // with no per-timestep branch on the variant.
    inv_perc_x1: f64,

    //Store values
    // Public so that gr4j nodes may read them
    pub production_store: f64,
    pub routing_store: f64,
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
        //Set up the unit hydrograph kernels and stores (OBS! THESE DEPEND ON x4 AND THE VARIANT)
        let uh_exponent = self.variant.uh_exponent();
        self.uh1_len = self.x4.ceil() as usize;
        self.uh2_len = (2.0 * self.x4).ceil() as usize;
        self.uh1_ordinates = vec![0.0; self.uh1_len];
        self.uh2_ordinates = vec![0.0; self.uh2_len];
        self.uh1 = vec![0.0; self.uh1_len];
        self.uh2 = vec![0.0; self.uh2_len];
        for t in 0..(self.uh1_len as usize) {
            self.uh1_ordinates[t] = s_curves1(t + 1, self.x4, uh_exponent) - s_curves1(t, self.x4, uh_exponent);
        }
        for t in 0..(self.uh2_len as usize) {
            self.uh2_ordinates[t] = s_curves2(t + 1, self.x4, uh_exponent) - s_curves2(t, self.x4, uh_exponent);
        }

        //Precompute the percolation divisor (run-invariant: depends only on variant and x1)
        self.inv_perc_x1 = 1.0 / (self.variant.perc_factor() * self.x1);

        //Set up the production and routing stores
        self.production_store = 0.0;
        self.routing_store = 0.0;
    }

    /// Switch the model formulation. Re-initialises the UH kernels and the
    /// percolation divisor, both of which depend on the variant.
    pub fn set_variant(&mut self, variant: Gr4Variant) {
        self.variant = variant;
        self.initialize();
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

        //Percolation (inv_perc_x1 = 1/(perc_factor*x1); perc_factor is 2.25 for GR4J, 5.25 for GR4H)
        let perc = self.production_store * (1.0 - (1.0 + (self.production_store * self.inv_perc_x1).powi(4)).powf(-0.25));
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
 * `exp` is the variant-specific shape exponent (2.5 for GR4J, 1.25 for GR4H).
 */
fn s_curves1(t: usize, x4: f64, exp: f64) -> f64 {
    let t_f64 = t as f64;
    if t <= 0 {
        0.0
    } else if t_f64 < x4 {
        (t_f64 / x4).powf(exp)
    } else {
        1.0
    }
}

/**
 * Unit hydrograph ordinates for UH2 derived from S-curves.
 * `exp` is the variant-specific shape exponent (2.5 for GR4J, 1.25 for GR4H).
 */
fn s_curves2(t: usize, x4: f64, exp: f64) -> f64 {
    let t_f64 = t as f64;
    if t <= 0 {
        0.0
    } else if t_f64 < x4 {
        0.5 * (t_f64 / x4).powf(exp)
    } else if t_f64 < 2.0 * x4 {
        1.0 - 0.5 * (2.0 - t_f64 / x4).powf(exp)
    } else {
        1.0 // t >= 2*x4
    }
}
