/// Implementations of Calibratable trait for rainfall-runoff models
///
/// This module provides Calibratable implementations for:
/// - Sacramento model
/// - GR4J model
///
/// These implementations handle both direct parameters and derived parameters
/// (e.g., sarva_on_pctim = sarva/pctim)

use crate::hydrology::rainfall_runoff::sacramento::Sacramento;
use crate::hydrology::rainfall_runoff::gr4j::Gr4j;
use super::calibratable::Calibratable;

// ============================================================================
// Sacramento Model
// ============================================================================

impl Calibratable for Sacramento {
    fn set_param(&mut self, name: &str, value: f64) -> Result<(), String> {
        match name {
            // Direct parameters
            "adimp" => self.set_params(
                value, self.lzfpm, self.lzfsm, self.lzpk,
                self.lzsk, self.lztwm, self.pctim, self.pfree,
                self.rexp, self.sarva, self.side, self.ssout,
                self.uzfwm, self.uzk, self.uztwm, self.zperc,
                self.laguh
            ),
            "lzfpm" => self.set_params(
                self.adimp, value, self.lzfsm, self.lzpk,
                self.lzsk, self.lztwm, self.pctim, self.pfree,
                self.rexp, self.sarva, self.side, self.ssout,
                self.uzfwm, self.uzk, self.uztwm, self.zperc,
                self.laguh
            ),
            "lzfsm" => self.set_params(
                self.adimp, self.lzfpm, value, self.lzpk,
                self.lzsk, self.lztwm, self.pctim, self.pfree,
                self.rexp, self.sarva, self.side, self.ssout,
                self.uzfwm, self.uzk, self.uztwm, self.zperc,
                self.laguh
            ),
            "lzpk" => self.set_params(
                self.adimp, self.lzfpm, self.lzfsm, value,
                self.lzsk, self.lztwm, self.pctim, self.pfree,
                self.rexp, self.sarva, self.side, self.ssout,
                self.uzfwm, self.uzk, self.uztwm, self.zperc,
                self.laguh
            ),
            "lzsk" => self.set_params(
                self.adimp, self.lzfpm, self.lzfsm, self.lzpk,
                value, self.lztwm, self.pctim, self.pfree,
                self.rexp, self.sarva, self.side, self.ssout,
                self.uzfwm, self.uzk, self.uztwm, self.zperc,
                self.laguh
            ),
            "lztwm" => self.set_params(
                self.adimp, self.lzfpm, self.lzfsm, self.lzpk,
                self.lzsk, value, self.pctim, self.pfree,
                self.rexp, self.sarva, self.side, self.ssout,
                self.uzfwm, self.uzk, self.uztwm, self.zperc,
                self.laguh
            ),
            "pctim" => self.set_params(
                self.adimp, self.lzfpm, self.lzfsm, self.lzpk,
                self.lzsk, self.lztwm, value, self.pfree,
                self.rexp, self.sarva, self.side, self.ssout,
                self.uzfwm, self.uzk, self.uztwm, self.zperc,
                self.laguh
            ),
            "pfree" => self.set_params(
                self.adimp, self.lzfpm, self.lzfsm, self.lzpk,
                self.lzsk, self.lztwm, self.pctim, value,
                self.rexp, self.sarva, self.side, self.ssout,
                self.uzfwm, self.uzk, self.uztwm, self.zperc,
                self.laguh
            ),
            "rexp" => self.set_params(
                self.adimp, self.lzfpm, self.lzfsm, self.lzpk,
                self.lzsk, self.lztwm, self.pctim, self.pfree,
                value, self.sarva, self.side, self.ssout,
                self.uzfwm, self.uzk, self.uztwm, self.zperc,
                self.laguh
            ),
            "sarva" => self.set_params(
                self.adimp, self.lzfpm, self.lzfsm, self.lzpk,
                self.lzsk, self.lztwm, self.pctim, self.pfree,
                self.rexp, value, self.side, self.ssout,
                self.uzfwm, self.uzk, self.uztwm, self.zperc,
                self.laguh
            ),
            "side" => self.set_params(
                self.adimp, self.lzfpm, self.lzfsm, self.lzpk,
                self.lzsk, self.lztwm, self.pctim, self.pfree,
                self.rexp, self.sarva, value, self.ssout,
                self.uzfwm, self.uzk, self.uztwm, self.zperc,
                self.laguh
            ),
            "ssout" => self.set_params(
                self.adimp, self.lzfpm, self.lzfsm, self.lzpk,
                self.lzsk, self.lztwm, self.pctim, self.pfree,
                self.rexp, self.sarva, self.side, value,
                self.uzfwm, self.uzk, self.uztwm, self.zperc,
                self.laguh
            ),
            "uzfwm" => self.set_params(
                self.adimp, self.lzfpm, self.lzfsm, self.lzpk,
                self.lzsk, self.lztwm, self.pctim, self.pfree,
                self.rexp, self.sarva, self.side, self.ssout,
                value, self.uzk, self.uztwm, self.zperc,
                self.laguh
            ),
            "uzk" => self.set_params(
                self.adimp, self.lzfpm, self.lzfsm, self.lzpk,
                self.lzsk, self.lztwm, self.pctim, self.pfree,
                self.rexp, self.sarva, self.side, self.ssout,
                self.uzfwm, value, self.uztwm, self.zperc,
                self.laguh
            ),
            "uztwm" => self.set_params(
                self.adimp, self.lzfpm, self.lzfsm, self.lzpk,
                self.lzsk, self.lztwm, self.pctim, self.pfree,
                self.rexp, self.sarva, self.side, self.ssout,
                self.uzfwm, self.uzk, value, self.zperc,
                self.laguh
            ),
            "zperc" => self.set_params(
                self.adimp, self.lzfpm, self.lzfsm, self.lzpk,
                self.lzsk, self.lztwm, self.pctim, self.pfree,
                self.rexp, self.sarva, self.side, self.ssout,
                self.uzfwm, self.uzk, self.uztwm, value,
                self.laguh
            ),
            "laguh" => self.set_params(
                self.adimp, self.lzfpm, self.lzfsm, self.lzpk,
                self.lzsk, self.lztwm, self.pctim, self.pfree,
                self.rexp, self.sarva, self.side, self.ssout,
                self.uzfwm, self.uzk, self.uztwm, self.zperc,
                value
            ),

            // Derived parameters
            "sarvaonpctim" | "sarva_on_pctim" => {
                // sarva = value * pctim
                let sarva = value * self.pctim.max(0.0001);
                self.set_params(
                    self.adimp, self.lzfpm, self.lzfsm, self.lzpk,
                    self.lzsk, self.lztwm, self.pctim, self.pfree,
                    self.rexp, sarva, self.side, self.ssout,
                    self.uzfwm, self.uzk, self.uztwm, self.zperc,
                    self.laguh
                )
            },
            "lzpkonlzsk" | "lzpk_on_lzsk" => {
                // lzpk = value * lzsk
                let lzpk = value * self.lzsk;
                self.set_params(
                    self.adimp, self.lzfpm, self.lzfsm, lzpk,
                    self.lzsk, self.lztwm, self.pctim, self.pfree,
                    self.rexp, self.sarva, self.side, self.ssout,
                    self.uzfwm, self.uzk, self.uztwm, self.zperc,
                    self.laguh
                )
            },
            "rfsum" => {
                // Special parameter for rainfall sum adjustment (if needed)
                // For now, just ignore or implement as needed
                return Ok(());
            },

            _ => return Err(format!("Unknown Sacramento parameter: {}", name)),
        };

        Ok(())
    }

    fn get_param(&self, name: &str) -> Result<f64, String> {
        match name {
            "adimp" => Ok(self.adimp),
            "lzfpm" => Ok(self.lzfpm),
            "lzfsm" => Ok(self.lzfsm),
            "lzpk" => Ok(self.lzpk),
            "lzsk" => Ok(self.lzsk),
            "lztwm" => Ok(self.lztwm),
            "pctim" => Ok(self.pctim),
            "pfree" => Ok(self.pfree),
            "rexp" => Ok(self.rexp),
            "sarva" => Ok(self.sarva),
            "side" => Ok(self.side),
            "ssout" => Ok(self.ssout),
            "uzfwm" => Ok(self.uzfwm),
            "uzk" => Ok(self.uzk),
            "uztwm" => Ok(self.uztwm),
            "zperc" => Ok(self.zperc),
            "laguh" => Ok(self.laguh),

            // Derived parameters
            "sarvaonpctim" | "sarva_on_pctim" => {
                Ok(self.sarva / self.pctim.max(0.0001))
            },
            "lzpkonlzsk" | "lzpk_on_lzsk" => {
                Ok(self.lzpk / self.lzsk.max(0.0001))
            },
            "rfsum" => Ok(1.0),  // Default value

            _ => Err(format!("Unknown Sacramento parameter: {}", name)),
        }
    }

    fn list_params(&self) -> Vec<String> {
        vec![
            "adimp", "lzfpm", "lzfsm", "lzpk", "lzsk", "lztwm",
            "pctim", "pfree", "rexp", "sarva", "side", "ssout",
            "uzfwm", "uzk", "uztwm", "zperc", "laguh",
            // Derived parameters
            "sarvaonpctim", "lzpkonlzsk", "rfsum",
        ]
        .iter()
        .map(|s| s.to_string())
        .collect()
    }
}

// ============================================================================
// GR4J Model
// ============================================================================

impl Calibratable for Gr4j {
    fn set_param(&mut self, name: &str, value: f64) -> Result<(), String> {
        match name {
            "x1" => {
                self.x1 = value;
                self.initialize();
                Ok(())
            },
            "x2" => {
                self.x2 = value;
                self.initialize();
                Ok(())
            },
            "x3" => {
                self.x3 = value;
                self.initialize();
                Ok(())
            },
            "x4" => {
                self.x4 = value;
                self.initialize();  // Must reinitialize UH when x4 changes
                Ok(())
            },
            _ => Err(format!("Unknown GR4J parameter: {}", name)),
        }
    }

    fn get_param(&self, name: &str) -> Result<f64, String> {
        match name {
            "x1" => Ok(self.x1),
            "x2" => Ok(self.x2),
            "x3" => Ok(self.x3),
            "x4" => Ok(self.x4),
            _ => Err(format!("Unknown GR4J parameter: {}", name)),
        }
    }

    fn list_params(&self) -> Vec<String> {
        vec!["x1", "x2", "x3", "x4"]
            .iter()
            .map(|s| s.to_string())
            .collect()
    }
}
