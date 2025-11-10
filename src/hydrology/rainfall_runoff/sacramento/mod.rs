use libm::ceil;
use crate::hydrology::routing::unit_hydrograph::uh_prealloc_32::UHPrealloc32;

const PDN20: f64 = 5.08;
const PDNOR: f64 = 25.4;

#[derive(Default)]
#[derive(Clone)]
pub struct Sacramento {
    // Sacramento model parameters
    #[allow(dead_code)]
    name: String,

    // Variable stuff
    runoff: f64,
    rainfall: f64,      // Rainfall [mm]
    pet: f64,           // Evap [mm]
    baseflow: f64,      // Baseflow
    quickflow: f64,     // Quickflow

    // Unit hydrograph
    unit_hydrograph: UHPrealloc32,
    laguh: f64,         // Optional parametrisation of a unit hydrograph's lag (use set_laguh() to modify)

    // Parameters (public for optimisation)
    pub adimp: f64,
    pub lzfpm: f64,
    pub lzfsm: f64,
    pub lzpk: f64,
    pub lzsk: f64,
    pub lztwm: f64,
    pub pctim: f64,
    pub pfree: f64,
    pub rexp: f64,
    pub rserv: f64,
    pub sarva: f64,
    pub side: f64,
    pub ssout: f64,
    pub uzfwm: f64,
    pub uzk: f64,
    pub uztwm: f64,
    pub zperc: f64,

    // TODO: which of these can be moved into the functions?
    // Other internal vars
    adimc: f64,
    alzfpc: f64,
    alzfpm: f64,
    alzfsc: f64,
    alzfsm: f64,
    channelflow: f64,
    evapuzfw: f64,
    flobf: f64,
    floin: f64,
    flosf: f64,
    flwbf: f64,
    flwsf: f64,
    lzfpc: f64,
    lzfsc: f64,
    lztwc: f64,
    pbase: f64,
    perc: f64,
    uzfwc: f64,
    uztwc: f64,
}

impl Sacramento {

    pub fn new() -> Self {
        // Create a struct with preliminary values
        let mut ans = Self {
            ..Default::default()
        };
        ans.set_params_default();
        ans.initialize_state_empty();
        ans
    }


    fn set_params_default(&mut self) -> &mut Self {
        self.rserv = 0.3;
        self.set_params(
            0.01, 40.0, 23.0, 0.009,
            0.043, 130.0, 0.01, 0.063,
            1.0, 0.01, 0.0, 0.0, 40.0,
            0.245, 50.0, 40.0, 0.1)
    }


    pub fn set_params(&mut self,
                  adimp: f64, lzfpm: f64, lzfsm: f64, lzpk: f64,
                  lzsk: f64, lztwm: f64, pctim: f64, pfree: f64,
                  rexp: f64, sarva: f64, side: f64, ssout: f64,
                  uzfwm: f64, uzk: f64, uztwm: f64, zperc: f64,
                  laguh: f64) -> &mut Self {
        self.adimp = adimp;
        self.lzfpm = lzfpm;
        self.lzfsm = lzfsm;
        self.lzpk = lzpk;
        self.lzsk = lzsk;
        self.lztwm = lztwm;
        self.pctim = pctim;
        self.pfree = pfree;
        self.rserv = 0.3;
        self.rexp = rexp;
        self.sarva = sarva;
        self.side = side;
        self.ssout = ssout;
        self.uzfwm = uzfwm;
        self.uzk = uzk;
        self.uztwm = uztwm;
        self.zperc = zperc;
        self.laguh = laguh;
        self.set_uh_ordinates_using_laguh()
    }

    pub fn set_params_by_vec(&mut self, vec_params: Vec<f64>) {
        self.rserv = 0.3;
        self.adimp = vec_params[0];
        self.lzfpm = vec_params[1];
        self.lzfsm = vec_params[2];
        self.lzpk = vec_params[3];
        self.lzsk = vec_params[4];
        self.lztwm = vec_params[5];
        self.pctim = vec_params[6];
        self.pfree = vec_params[7];
        self.rexp = vec_params[8];
        self.sarva = vec_params[9];
        self.side = vec_params[10];
        self.ssout = vec_params[11];
        self.uzfwm = vec_params[12];
        self.uzk = vec_params[13];
        self.uztwm = vec_params[14];
        self.zperc = vec_params[15];
        self.set_laguh(vec_params[16]);
    }

    //
    pub fn get_params_as_vec(&self) -> Vec<f64> {
        let mut answer = Vec::new();
        //answer.push(self.rserv);
        answer.push(self.adimp);
        answer.push(self.lzfpm);
        answer.push(self.lzfsm);
        answer.push(self.lzpk);
        answer.push(self.lzsk);
        answer.push(self.lztwm);
        answer.push(self.pctim);
        answer.push(self.pfree);
        answer.push(self.rexp);
        answer.push(self.sarva);
        answer.push(self.side);
        answer.push(self.ssout);
        answer.push(self.uzfwm);
        answer.push(self.uzk);
        answer.push(self.uztwm);
        answer.push(self.zperc);
        answer.push(self.laguh);
        answer
    }

    pub fn set_uh_ordinates_using_laguh(&mut self) -> &mut Self {
        // How big does the kernel need to be?
        let high_ordinate_position = ceil(self.laguh) as usize;
        let kernel_len = high_ordinate_position + 1;

        // Create the unit hydrograph
        self.unit_hydrograph = UHPrealloc32::new(kernel_len);

        // Set the kernel ordinates
        let low_ordinate_value = ceil(self.laguh) - self.laguh;
        let high_ordinate_value = 1f64 - low_ordinate_value;
        self.unit_hydrograph.set_kernel(high_ordinate_position, high_ordinate_value);
        if low_ordinate_value > 0f64 {
            self.unit_hydrograph.set_kernel(high_ordinate_position - 1, low_ordinate_value);
        }
        self
    }


    /// Public setter for laguh parameter
    ///
    /// This method sets the laguh value and updates the unit hydrograph ordinates.
    /// Use this instead of directly setting laguh to ensure the unit hydrograph stays synchronized.
    pub fn set_laguh(&mut self, value: f64) -> &mut Self {
        self.laguh = value;
        self.set_uh_ordinates_using_laguh()
    }


    /// Public getter for laguh parameter
    pub fn get_laguh(&self) -> f64 {
        self.laguh
    }


    /*
    Reset the model to empty, and update other internal states accordingly.
    This is the only function you need to call if you want to reset the model.
     */
    pub fn initialize_state_empty(&mut self) -> &mut Self {

        // Set all the stores empty
        self.rainfall = 0f64;
        self.pet = 0f64;
        self.uzfwc = 0f64;
        self.uztwc = 0f64;
        self.lzfpc = 0f64;
        self.lzfsc = 0f64;
        self.lztwc = 0f64;
        self.flobf = 0f64;
        self.flosf = 0f64;
        self.floin = 0f64;
        self.flwbf = 0f64;
        self.flwsf = 0f64;
        self.evapuzfw = 0f64;

        // Set the UH empty
        self.unit_hydrograph.reset_state_to_empty();

        // Update the other state variables that depend on the storages
        self.alzfsm = self.lzfsm * (1f64 + self.side);
        self.alzfpm = self.lzfpm * (1f64 + self.side);
        self.alzfsc = self.lzfsc * (1f64 + self.side);
        self.alzfpc = self.lzfpc * (1f64 + self.side);
        self.pbase = self.alzfsm * self.lzsk + self.alzfpm * self.lzpk;
        self.adimc = self.uztwc + self.lztwc;

        // Return self
        self
    }


    /**
     *
     */
    pub fn run_step(&mut self, pliq: f64, evapt: f64) -> f64 {

        // Rainfall and evap
        self.rainfall = pliq;
        self.pet = evapt;

        // Evaporation from upper zone tension water
        let mut evapuztw = 0f64;
        if self.uztwm > 0f64 {
            evapuztw = evapt * self.uztwc / self.uztwm;
        }

        // Evaporation from upper zone free water
        if self.uztwc < evapuztw {
            evapuztw = self.uztwc;
            self.uztwc = 0f64;
            self.evapuzfw = (evapt - self.evapuzfw).min(self.uzfwc);
            self.uzfwc -= self.evapuzfw;
        } else {
            self.uztwc -= evapuztw;
            self.evapuzfw = 0f64;
        }

        // If upper zone free water ratio exceeds the upper tension zone ratio,
        // transfer free water into tension water to make the ratios equal
        let mut ratiouztw = 1f64;
        if self.uztwm > 0f64 {
            ratiouztw = self.uztwc / self.uztwm;
        }
        let mut ratiouzfw = 1f64;
        if self.uzfwm > 0f64 {
            ratiouzfw = self.uzfwc / self.uzfwm;
        }
        if ratiouzfw > ratiouztw {
            ratiouztw = (self.uztwc + self.uzfwc) / (self.uztwm + self.uzfwm);
            self.uztwc = self.uztwm * ratiouztw;
            self.uzfwc = self.uzfwm * ratiouztw;
        }

        // Evaporation from adimp and lower zone tension water
        let mut e3 = 0f64;
        let mut e5 = 0f64;
        if self.uztwm + self.lztwm > 0f64 {
            e3 = self.lztwc.min((evapt - evapuztw - self.evapuzfw) *
                self.lztwc / (self.uztwm + self.lztwm));
            e5 = self.adimc.min(evapuztw + ((evapt - evapuztw - self.evapuzfw) *
                (self.adimc - evapuztw - self.uztwc) / (self.uztwm + self.lztwm)));
        }

        // Transpiration from lower zone tension water
        self.lztwc -= e3;

        // Adjust impervious area store
        self.adimc -= e5;
        self.evapuzfw = self.evapuzfw * (1f64 - self.adimp - self.pctim);

        // Resupply lower zone tension with water from the lower zone free, if more water is available there.
        let mut ratiolztw = 1f64;
        if self.lztwm > 0f64 {
            ratiolztw = self.lztwc / self.lztwm;
        }

        let reserved_lower_zone = self.rserv * ( self.lzfpm + self.lzfsm );
        let mut ratiolzfw = 1f64;
        if (self.alzfpm + self.alzfsm - reserved_lower_zone + self.lztwm) > 0f64 {
            ratiolzfw = (self.alzfpc + self.alzfsc - reserved_lower_zone + self.lztwc) /
                (self.alzfpm + self.alzfsm - reserved_lower_zone + self.lztwm);
        }

        if ratiolztw < ratiolzfw {
            let transferred = (ratiolzfw - ratiolztw) * self.lztwm;
            self.lztwc += transferred;
            self.alzfsc -= transferred;
            if self.alzfsc < 0f64 {
                self.alzfpc += self.alzfsc;
                self.alzfsc = 0f64;
            }
        }

        // Runoff from the impervious or water covered area
        let mut roimp = pliq * self.pctim;

        // Reduce the rain by the amount of upper zone tension water deficiency
        let mut pav = pliq + self.uztwc - self.uztwm;
        if pav < 0f64 {
            // Fill the upper zone tension water as much as rain permits
            self.adimc += pliq;
            self.uztwc += pliq;
            pav = 0f64;
        } else {
            self.adimc += self.uztwm - self.uztwc;
            self.uztwc = self.uztwm;
        }

        let mut adj = 1f64;
        let mut itime= 1usize;
        if pav <= PDN20 {
            itime = 2;
        } else {
            if pav < PDNOR {
                adj = 0.5 * (pav / PDNOR).sqrt();
            } else {
                adj = 1f64 - 0.5 * PDNOR / pav;
            }
        }

        // We reset these reporting vars to zero so they can be calculated in the below loop.
        self.flobf = 0f64;
        self.flosf = 0f64;
        self.floin = 0f64;

        let hpl = self.alzfpm / (self.alzfpm + self.alzfsm);
        for _ in itime..=2 {
            let ninc = 1 + ((self.uzfwc * adj + pav ) * 0.2).floor() as usize;
            let mut dinc = 1f64 / (ninc as f64);
            let pinc = pav * dinc;
            dinc = dinc * adj;

            let duz:f64;
            let dlzp:f64;
            let dlzs:f64;
            if (ninc == 1) && (adj >= 1f64) {
                duz = self.uzk;
                dlzp = self.lzpk;
                dlzs = self.lzsk;
            } else {
                if self.uzk < 1f64 {
                    duz = 1f64 - (1f64 - self.uzk).powf(dinc);
                } else {
                    duz = 1f64;
                }
                if self.lzpk < 1f64 {
                    dlzp = 1f64 - (1f64 - self.lzpk).powf(dinc);
                } else {
                    dlzp = 1f64;
                }
                if self.lzsk < 1f64 {
                    dlzs = 1f64 - (1f64 - self.lzsk).powf(dinc);
                } else {
                    dlzs = 1f64;
                }
            }

            // Drainage and percolation
            for _ in 1..=ninc {
                let ratio = (self.adimc - self.uztwc) / self.lztwm;
                let mut addro = pinc * ratio * ratio;

                // Baseflow from the lower zone primary
                if self.alzfpc > 0f64 {
                    let bf = self.alzfpc * dlzp;
                    self.alzfpc -= bf;
                    self.flobf += bf;
                } else {
                    self.alzfpc = 0f64;
                }

                // Baseflow from the lower zone supplemental
                if self.alzfsc > 0f64 {
                    let bf = self.alzfsc * dlzs;
                    self.alzfsc -= bf;
                    self.flobf += bf;
                } else {
                    self.alzfsc = 0f64;
                }

                // Adjust the upper zone for percolation and interflow
                if self.uzfwc > 0f64 {
                    // Determine percolation from the upper zone free water
                    // limited to available water and lower zone airspace
                    let mut lzair = self.lztwm - self.lztwc + self.alzfsm - self.alzfsc + self.alzfpm - self.alzfpc;
                    if lzair > 0f64 {
                        self.perc = (self.pbase * dinc * self.uzfwc) / self.uzfwm;
                        self.perc = self.uzfwc.min(self.perc * (1f64 + (self.zperc *
                            (1f64 - (self.alzfpc + self.alzfsc + self.lztwc)
                                / (self.alzfpm + self.alzfsm + self.lztwm)).powf(self.rexp))));
                        self.perc = self.perc.min(lzair);
                        self.uzfwc -= self.perc;
                    } else {
                        self.perc = 0f64;
                    }

                    // Compute the interflow
                    let transferred = duz * self.uzfwc;
                    self.floin += transferred;
                    self.uzfwc -= transferred;

                    // Distribute water to lower zone tension and free water stores
                    let mut perctw = (self.perc * (1f64 - self.pfree)).min(self.lztwm - self.lztwc);
                    let mut percfw = self.perc - perctw;

                    // Shift any excess lower zone free water percolation to the lower zone tension water store
                    lzair = self.alzfsm - self.alzfsc + self.alzfpm - self.alzfpc;
                    if percfw > lzair {
                        perctw = perctw + percfw - lzair;
                        percfw = lzair;
                    }
                    self.lztwc += perctw;

                    // Distribute water between LZ free water supplemental and primary
                    if percfw > 0f64 {
                        let ratlp = 1f64 - self.alzfpc / self.alzfpm;
                        let ratls = 1f64 - self.alzfsc / self.alzfsm;
                        let mut percs = (self.alzfsm - self.alzfsc).min(percfw * (1f64 - hpl * (2.0 * ratlp) / (ratlp + ratls)));
                        self.alzfsc += percs;

                        // Spill from supplemental to primary
                        if self.alzfsc > self.alzfsm {
                            percs += self.alzfsm - self.alzfsc;
                            self.alzfsc = self.alzfsm;
                        }
                        self.alzfpc += percfw - percs;

                        // Spill from primary to supplemental
                        if self.alzfpc > self.alzfpm {
                            self.alzfsc += self.alzfpc - self.alzfpm;
                            self.alzfpc = self.alzfpm;
                        }
                    }
                }

                // Fill upper zone free water with tension water spill
                if pinc > 0f64 {
                    pav = pinc;
                    if pav - self.uzfwm + self.uzfwc <= 0f64 {
                        self.uzfwc += pav;
                    } else {
                        pav += self.uzfwc - self.uzfwm;
                        self.uzfwc = self.uzfwm;
                        self.flosf += pav;
                        addro = addro + pav * (1f64 - addro / pinc);
                    }
                }
                self.adimc += pinc - addro;
                roimp += addro * self.adimp;
            }
            adj = 1f64 - adj;
            pav = 0f64;
        }

        // Compute the storage volumes, runoff components and evaporation
        let temp = 1f64 - self.pctim - self.adimp;
        self.flosf = self.flosf * temp;
        self.floin = self.floin * temp;
        self.flobf = self.flobf * temp;

        // Take side out of the lower zone primary and supplemental stores
        let temp = 1f64 / (1f64 + self.side);
        self.lzfsc = self.alzfsc * temp;
        self.lzfpc = self.alzfpc * temp;

        // Adjust flow for unit hydrograph
        self.flwsf = self.unit_hydrograph.run_step(self.floin + self.flosf + roimp);

        // Baseflow loss
        self.flwbf = self.flobf / (1f64 + self.side);
        if self.flwbf < 0f64 {
            self.flwbf = 0f64;
        }

        // Calculate the BFI prior to losses, in order to keep this ratio in the final runoff and baseflow components.
        let mut ratio_baseflow = 0f64;
        let total_before_channel_losses = self.flwbf + self.flwsf;
        if total_before_channel_losses > 0f64 {
            ratio_baseflow = self.flwbf / total_before_channel_losses;
        }

        // Subtract losses from the total channel flow (going to the subsurface discharge)
        self.channelflow = 0f64.max(self.flwbf + self.flwsf - self.ssout);
        let evap_channel_water = self.channelflow.min(evapt * self.sarva);

        // Total runoff
        self.runoff = self.channelflow - evap_channel_water;

        // Components of total runoff
        self.baseflow = self.runoff * ratio_baseflow;
        self.quickflow = self.runoff - self.baseflow;

        //Return the results
        self.runoff
    }
}
