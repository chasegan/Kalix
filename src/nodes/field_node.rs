use super::{recorder, single_outlet_node_impls, Node};
use crate::model_inputs::DynamicInput;
use crate::data_management::data_cache::DataCache;
use crate::hydrology::accounts::account_manager::AccountManager;
use crate::misc::location::Location;
use crate::numerical::fifo_buffer::FifoBuffer;

const MAX_DS_LINKS: usize = 1;

/// An irrigated field: a root-zone soil store that dries by evapotranspiration,
/// fills with rain and irrigation, and orders water upstream to meet its
/// deficit. The soil water balance is the FAO-56 daily root-zone depletion
/// balance, with a single crop coefficient and a stress coefficient that
/// reduces evapotranspiration as the soil dries.
///
/// Phase 1 of the field: one crop, always in the ground, over the whole
/// field, with one soil store. The properties this simplifies are marked
/// PHASE1-PLACEHOLDER; later phases make them per crop and time-varying
/// (see the field spec).
///
/// Working in mm over the field's `area`: 1 mm × 1 km² = 1 ML.
///
/// A field's outlets are drains, not delivery paths: they carry bypass and
/// excess back to the river. So the links leaving a field are not regulated
/// (the ordering system's `zone_role`): no order travels up them, and the
/// travel time to the field is no part of the travel time to anything below
/// it. The field sends its own order upstream and nothing else.
#[derive(Default, Clone)]
pub struct FieldNode {

    // Properties - basic
    pub name: String,
    pub location: Location,
    pub mbal: f64,

    // Properties - the field
    pub area: f64,                  // km2. PHASE1-PLACEHOLDER: the whole field is cropped
    pub capacity: f64,              // mm the root zone holds between full and empty
    pub initial_depletion: f64,     // mm
    pub efficiency: f64,            // share of supply that reaches the soil; the rest escapes
    pub rain_input: DynamicInput,   // mm
    pub evap_input: DynamicInput,   // mm, the reference the kc values were derived for
    pub kc_input: DynamicInput,     // crop coefficient. PHASE1-PLACEHOLDER: one crop, on the field
    pub p: f64,                     // depletion fraction: stress begins beyond p x capacity. PHASE1-PLACEHOLDER: on the field
    pub order_input: DynamicInput,  // the irrigation rule, in ML; omitted, the field is rain-fed

    // Ordering
    pub order_travel_time: usize,
    pub order_value: f64,
    pub order_buffer: FifoBuffer,

    // Internal state only
    pub dsorders: [f64; MAX_DS_LINKS],
    depletion: f64, // mm, how far the root zone is below field capacity: 0 full, capacity empty
    order_due: f64,
    usflow: f64,
    dsflow_primary: f64,

    // Recorders
    recorder_idx_depletion: Option<usize>,
    recorder_idx_orders_en_route: Option<usize>,
    recorder_idx_order: Option<usize>,
    recorder_idx_order_due: Option<usize>,
    recorder_idx_usflow: Option<usize>,
    recorder_idx_ks: Option<usize>,
    recorder_idx_kc: Option<usize>,
    recorder_idx_et: Option<usize>,
    recorder_idx_rain: Option<usize>,
    recorder_idx_excess: Option<usize>,
    recorder_idx_supply: Option<usize>,
    recorder_idx_escape: Option<usize>,
    recorder_idx_bypass: Option<usize>,
    recorder_idx_dsflow: Option<usize>,
    recorder_idx_ds_1: Option<usize>,
}


impl FieldNode {

    /// Base constructor
    pub fn new() -> Self {
        Self {
            name: "".to_string(),
            efficiency: 1.0,
            p: 0.5,
            rain_input: DynamicInput::default(),
            evap_input: DynamicInput::default(),
            kc_input: DynamicInput::default(),
            order_input: DynamicInput::default(),
            order_buffer: FifoBuffer::default(),
            ..Default::default()
        }
    }
}

impl Node for FieldNode {
    single_outlet_node_impls!();

    fn initialise(&mut self, data_cache: &mut DataCache, _account_manager: &mut AccountManager) -> Result<(), String> {
        // Checks
        if !(self.area > 0.0 && self.area.is_finite()) {
            return Err(format!("Error in node '{}'. area must be a positive number of km2, got {}.", self.name, self.area));
        }
        if !(self.capacity > 0.0 && self.capacity.is_finite()) {
            return Err(format!("Error in node '{}'. capacity must be a positive number of mm, got {}.", self.name, self.capacity));
        }
        if !(self.initial_depletion >= 0.0 && self.initial_depletion <= self.capacity) {
            return Err(format!("Error in node '{}'. initial_depletion must be between 0 and capacity ({} mm), got {}.", self.name, self.capacity, self.initial_depletion));
        }
        if !(self.efficiency > 0.0 && self.efficiency <= 1.0) {
            return Err(format!("Error in node '{}'. efficiency must be greater than 0 and at most 1, got {}.", self.name, self.efficiency));
        }
        if !(self.p >= 0.0 && self.p < 1.0) {
            return Err(format!("Error in node '{}'. p must be at least 0 and less than 1, got {}.", self.name, self.p));
        }

        // Initialize only internal state
        self.mbal = 0.0;
        self.usflow = 0.0;
        self.dsflow_primary = 0.0;
        self.depletion = self.initial_depletion;

        // Reset order state, so a rerun of the same model object starts clean.
        // The ordering system (which initialises after the nodes) sizes the
        // buffer from the travel time it finds.
        self.order_travel_time = 0;
        self.order_buffer = FifoBuffer::default();
        self.order_value = 0.0;
        self.order_due = 0.0;

        // DynamicInput is already initialized during parsing

        // Initialize result recorders
        self.recorder_idx_depletion = recorder(data_cache, &self.name, "depletion");
        self.recorder_idx_orders_en_route = recorder(data_cache, &self.name, "orders_en_route");
        self.recorder_idx_order = recorder(data_cache, &self.name, "order");
        self.recorder_idx_order_due = recorder(data_cache, &self.name, "order_due");
        self.recorder_idx_usflow = recorder(data_cache, &self.name, "usflow");
        self.recorder_idx_ks = recorder(data_cache, &self.name, "ks");
        self.recorder_idx_kc = recorder(data_cache, &self.name, "kc");
        self.recorder_idx_et = recorder(data_cache, &self.name, "et");
        self.recorder_idx_rain = recorder(data_cache, &self.name, "rain");
        self.recorder_idx_excess = recorder(data_cache, &self.name, "excess");
        self.recorder_idx_supply = recorder(data_cache, &self.name, "supply");
        self.recorder_idx_escape = recorder(data_cache, &self.name, "escape");
        self.recorder_idx_bypass = recorder(data_cache, &self.name, "bypass");
        self.recorder_idx_dsflow = recorder(data_cache, &self.name, "dsflow");
        self.recorder_idx_ds_1 = recorder(data_cache, &self.name, "ds_1");

        // Return
        Ok(())
    }

    fn get_name(&self) -> &str { &self.name }

    fn run_order_phase(&mut self, data_cache: &mut DataCache, _account_manager: &mut AccountManager) {

        // Publish what has been ordered and has not yet arrived, before the irrigation
        // rule is evaluated, so that `this.orders_en_route` reads cleanly in the same
        // step. (`depletion` is a state, recorded at the end of the step like a storage's
        // volume; the rule reads yesterday's closing value as `this.depletion[-1, 0]`.)
        if let Some(idx) = self.recorder_idx_orders_en_route {
            data_cache.add_value_at_index(idx, self.order_buffer.sum());
        }

        // Ensure non-negativity of orders
        self.order_value = self.order_input.get_value(data_cache).max(0.0);

        // The order placed order_travel_time steps ago is due to arrive today
        self.order_due = self.order_buffer.push(self.order_value);

        // Order phase recorders
        if let Some(idx) = self.recorder_idx_order {
            data_cache.add_value_at_index(idx, self.order_value);
        }
        if let Some(idx) = self.recorder_idx_order_due {
            data_cache.add_value_at_index(idx, self.order_due);
        }
    }

    fn run_flow_phase(&mut self, data_cache: &mut DataCache, _account_manager: &mut AccountManager) {

        // Record results
        if let Some(idx) = self.recorder_idx_usflow {
            data_cache.add_value_at_index(idx, self.usflow);
        }

        // Get the driving data
        let rain_mm = self.rain_input.get_value(data_cache).max(0.0);
        let evap_mm = self.evap_input.get_value(data_cache).max(0.0);
        let kc = self.kc_input.get_value(data_cache).max(0.0);

        // 1. Stress, from the depletion at the start of the step (FAO-56 eq. 84):
        //    full transpiration until p of the capacity is used, falling to none at empty
        let readily_available = (1.0 - self.p) * self.capacity;
        let ks = ((self.capacity - self.depletion) / readily_available).clamp(0.0, 1.0);

        // 2. Evapotranspiration, no more than the water held
        let et_mm = (ks * kc * evap_mm).min(self.capacity - self.depletion);
        self.depletion += et_mm;

        // 3. Rain goes on the soil; what would take depletion below zero leaves as excess
        self.depletion -= rain_mm;
        let mut excess_mm = 0.0;
        if self.depletion < 0.0 {
            excess_mm = -self.depletion;
            self.depletion = 0.0;
        }

        // 4. Irrigation: take from what arrives no more than the soil has room for after
        //    the rain, allowing for the share that escapes on the way; bypass the rest
        let room_ml = self.depletion * self.area;
        let supply = self.usflow.min(room_ml / self.efficiency);
        let escape = supply * (1.0 - self.efficiency);
        self.depletion -= (supply - escape) / self.area;
        let bypass = self.usflow - supply;

        // Water leaves down ds_1. mbal is emitted minus received, as on a storage:
        // rain, evapotranspiration, escape and the change in water held all show through it.
        let excess = excess_mm * self.area;
        self.dsflow_primary = bypass + excess;
        self.mbal += self.dsflow_primary - self.usflow;

        // Record results. depletion is the state at the end of the step, as a storage's
        // volume is.
        if let Some(idx) = self.recorder_idx_depletion {
            data_cache.add_value_at_index(idx, self.depletion);
        }
        if let Some(idx) = self.recorder_idx_ks {
            data_cache.add_value_at_index(idx, ks);
        }
        if let Some(idx) = self.recorder_idx_kc {
            data_cache.add_value_at_index(idx, kc);
        }
        if let Some(idx) = self.recorder_idx_et {
            data_cache.add_value_at_index(idx, et_mm * self.area);
        }
        if let Some(idx) = self.recorder_idx_rain {
            data_cache.add_value_at_index(idx, rain_mm * self.area);
        }
        if let Some(idx) = self.recorder_idx_excess {
            data_cache.add_value_at_index(idx, excess);
        }
        if let Some(idx) = self.recorder_idx_supply {
            data_cache.add_value_at_index(idx, supply);
        }
        if let Some(idx) = self.recorder_idx_escape {
            data_cache.add_value_at_index(idx, escape);
        }
        if let Some(idx) = self.recorder_idx_bypass {
            data_cache.add_value_at_index(idx, bypass);
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
}
