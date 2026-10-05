use super::{recorder, Node};
use crate::model_inputs::DynamicInput;
use crate::data_management::data_cache::DataCache;
use crate::hydrology::accounts::account_manager::AccountManager;
use crate::hydrology::crop::Crop;
use crate::hydrology::soil::{transfer, Partition, Profile};
use crate::misc::location::Location;
use crate::numerical::fifo_buffer::FifoBuffer;

const MAX_DS_LINKS: usize = 2;

/// ds_1 carries bypass and the river's share of the runoff; ds_2 carries the farm's share, return_flow.
pub const DS_1_OUTLET: u8 = 0;
pub const DS_2_OUTLET: u8 = 1;

/// The crop slots a field can hold: `crop_1` to `crop_4`, the `ds_N` precedent
pub const MAX_CROPS: usize = 4;

/// The properties of a crop slot, after `crop_N`: the parser accepts each and
/// the linter schema lists each, for N in 1..=MAX_CROPS
pub const CROP_SLOT_PROPERTIES: [&str; 4] = ["", "_plant", "_order", "_viable_area"];

/// The results of a crop slot, after `crop_N`
pub const CROP_SLOT_OUTPUTS: [&str; 9] = ["_area", "_days", "_depletion", "_ks", "_order", "_order_due", "_orders_en_route", "_plant", "_viable_area"];

/// A crop whose stress coefficient at the start of the day is this or below dies,
/// when the slot has no viable_area rule of its own (Source's rule)
const DEATH_KS: f64 = 0.05;

/// A crop slot: the farmer's decisions for one crop, and the crop's state while
/// it is in the ground. The irrigation machinery is per slot, so each crop
/// orders for its own deficit.
#[derive(Clone)]
pub struct CropSlot {
    pub crop: Crop,
    pub plant_input: DynamicInput,        // km2 to plant today, read while the slot is empty; 0 plants nothing
    pub order_input: DynamicInput,        // the irrigation rule, in ML; omitted, rain-fed
    pub viable_area_input: Option<DynamicInput>, // the area becomes min(area, value); absent, the built-in rule

    // Internal state only
    partition: Partition,
    in_ground: bool,
    days: u32,                            // since planting: 0 on the day it is planted
    ks: f64,                              // at the start of the day
    order_value: f64,
    order_due: f64,
    order_buffer: FifoBuffer,
    plant_read: f64,                      // what the plant rule gave today; NaN when it was not read
    viable_read: f64,                     // what the viable_area rule gave today, or the built-in rule's area; NaN when not read

    // Recorders, in the order of CROP_SLOT_OUTPUTS
    recorder_idx: [Option<usize>; CROP_SLOT_OUTPUTS.len()],
}

impl CropSlot {
    pub fn new(crop: Crop) -> Self {
        Self {
            crop,
            plant_input: DynamicInput::default(),
            order_input: DynamicInput::default(),
            viable_area_input: None,
            partition: Partition::default(),
            in_ground: false,
            days: 0,
            ks: f64::NAN,
            order_value: 0.0,
            order_due: 0.0,
            order_buffer: FifoBuffer::default(),
            plant_read: f64::NAN,
            viable_read: f64::NAN,
            recorder_idx: [None; CROP_SLOT_OUTPUTS.len()],
        }
    }

    /// FAO-56 eq. 84 from the root bucket as it stands: full transpiration until p
    /// of the capacity is used, falling to none at empty
    fn stress(&self) -> f64 {
        stress(&self.partition, self.crop.p)
    }
}

fn stress(partition: &Partition, p: f64) -> f64 {
    let readily_available = (1.0 - p) * partition.root_capacity;
    ((partition.root_capacity - partition.root_depletion) / readily_available).clamp(0.0, 1.0)
}

/// One partition's day: evapotranspiration at the crop's rate, stressed as the
/// bucket dries, then the rain, less what the canopy intercepts, into the root
/// bucket; what the bucket cannot hold fills the layers below, and what passes
/// the last layer is excess. Returns (ks, et, excess), all in mm.
fn soil_day(profile: &Profile, partition: &mut Partition, p: f64, kc: f64, evap_mm: f64, effective_rain_mm: f64) -> (f64, f64, f64) {
    let ks = stress(partition, p);
    let et_mm = (ks * kc * evap_mm).min(partition.root_capacity - partition.root_depletion);
    partition.root_depletion += et_mm;
    partition.root_depletion -= effective_rain_mm;
    let mut excess_mm = 0.0;
    if partition.root_depletion < 0.0 {
        excess_mm = partition.drain_overflow(profile, -partition.root_depletion);
        partition.root_depletion = 0.0;
    }
    (ks, et_mm, excess_mm)
}

/// Storm runoff off a partition's surface from a day's rain, by the USDA-NRCS curve
/// number: S = 254 (100/CN − 1) mm of retention, runoff once the rain exceeds the initial
/// abstraction 0.2 S. The day's curve sits between the dry one (CN1, at wilting point) and
/// the wet one (CN3, at field capacity) by the root bucket's wetness, as APSIM chooses it.
/// Rain only: irrigation never runs through it.
fn curve_number_runoff(cn_dry: f64, cn_wet: f64, partition: &Partition, rain_mm: f64) -> f64 {
    let wetness = (1.0 - partition.root_depletion / partition.root_capacity).clamp(0.0, 1.0);
    let cn = cn_dry + (cn_wet - cn_dry) * wetness;
    let s = 254.0 * (100.0 / cn - 1.0);
    let initial_abstraction = 0.2 * s;
    if rain_mm > initial_abstraction {
        (rain_mm - initial_abstraction).powi(2) / (rain_mm + 0.8 * s)
    } else {
        0.0
    }
}

/// An irrigated field: paddocks under crops that dry by evapotranspiration,
/// fill with rain and irrigation, and order water upstream to meet their
/// deficits. Each crop's root zone is the FAO-56 daily depletion balance with
/// the crop's coefficient and a stress coefficient that reduces
/// evapotranspiration as the soil dries; the soil below the roots is kept in
/// layers so that water left by one crop is there for the next.
///
/// The field is partitioned: a fallow, which is what is not planted, and up to
/// MAX_CROPS crop slots. A slot's crop is declared once in a `[crop.*]`
/// section; the slot's properties are the farmer's decisions: when to plant,
/// how much, how to irrigate, when to give up. Planting takes area from the
/// fallow with its water; harvest and abandonment give it back.
///
/// Working in mm over each partition's area: 1 mm × 1 km² = 1 ML.
///
/// A field's outlets are drains, not delivery paths: ds_1 carries bypass and
/// the river's share of the runoff, ds_2 the share the farm catches
/// (return_flow). So the links leaving a field are not regulated
/// (the ordering system's `zone_role`): no order travels up them, and the
/// travel time to the field is no part of the travel time to anything below
/// it. The field sends its own orders upstream and nothing else.
#[derive(Default, Clone)]
pub struct FieldNode {

    // Properties - basic
    pub name: String,
    pub location: Location,
    pub mbal: f64,

    // Properties - the field
    pub area: f64,                  // km2
    pub available_water: f64,       // mm of water per m of soil, between full and empty
    pub initial_depletion: f64,     // mm below full over the whole profile, every layer alike
    pub efficiency: f64,            // share of supply that reaches the soil at all; the rest escapes
    pub interception: f64,          // fraction of evap that rain must exceed to reach the soil (FAO-56: 0.2)
    pub return_fraction: f64,       // share of the excess that the farm catches: it goes down ds_2 as return_flow
    pub curve_number: Option<f64>,  // USDA-NRCS CN2 for storm runoff off the paddock surface; None, saturation excess only
    pub rain_input: DynamicInput,   // mm
    pub evap_input: DynamicInput,   // mm, the reference the kc values were derived for
    pub fallow: Option<Crop>,       // the crop that covers what is not planted; required
    pub slots: Vec<CropSlot>,       // crop_1.. in order, at most MAX_CROPS

    // Ordering
    pub order_travel_time: usize,
    pub order_value: f64,           // the slots' orders, summed

    // Internal state only
    pub dsorders: [f64; MAX_DS_LINKS],
    profile: Profile,
    fallow_partition: Partition,
    planting_done: bool,            // this step's planting ran in the order phase
    cn_dry: f64,                    // the curve numbers at wilting point and at field capacity (CN1, CN3)
    cn_wet: f64,
    usflow: f64,
    dsflow_primary: f64,
    dsflow_return: f64,

    // Recorders
    recorder_idx_fallow_depletion: Option<usize>,
    recorder_idx_usflow: Option<usize>,
    recorder_idx_et: Option<usize>,
    recorder_idx_et_vol: Option<usize>,
    recorder_idx_rain: Option<usize>,
    recorder_idx_rain_vol: Option<usize>,
    recorder_idx_intercepted: Option<usize>,
    recorder_idx_evap: Option<usize>,
    recorder_idx_excess: Option<usize>,
    recorder_idx_supply: Option<usize>,
    recorder_idx_escape: Option<usize>,
    recorder_idx_bypass: Option<usize>,
    recorder_idx_return_flow: Option<usize>,
    recorder_idx_dsflow: Option<usize>,
    recorder_idx_ds_1: Option<usize>,
    recorder_idx_ds_2: Option<usize>,
}


impl FieldNode {

    /// Base constructor
    pub fn new() -> Self {
        Self {
            name: "".to_string(),
            efficiency: 1.0,
            interception: 0.2,
            return_fraction: 0.0,
            rain_input: DynamicInput::default(),
            evap_input: DynamicInput::default(),
            ..Default::default()
        }
    }

    /// Sizes every slot's order buffer for the travel time the ordering system found
    pub fn set_order_travel_time(&mut self, travel_time: usize) {
        self.order_travel_time = travel_time;
        for slot in &mut self.slots {
            slot.order_buffer = FifoBuffer::new(travel_time);
        }
    }

    /// Planting, in the wide sense: the day's moves of land between the fallow
    /// and the crops, so that the day's orders and fluxes use the day's areas.
    /// Harvest, then abandonment, then planting proper; each moves area with
    /// its water, layer by layer. It is the farmer's first act of the day: in
    /// the order phase where the field has one (a crop planted today orders
    /// today), and failing that at the start of the flow phase, so a field
    /// outside every regulated zone sees the same day.
    #[inline(never)]
    fn planting(&mut self, data_cache: &mut DataCache) {
        for slot in &mut self.slots {
            slot.plant_read = f64::NAN;
            slot.viable_read = f64::NAN;
        }
        for slot in &mut self.slots {
            if !slot.in_ground { continue; }
            slot.days += 1;
            // Harvest: season_len days after planting, the whole crop goes back to the fallow
            if let Some(season_len) = slot.crop.season_len {
                if slot.days >= season_len {
                    let all = slot.partition.area;
                    transfer(&self.profile, &mut slot.partition, &mut self.fallow_partition, all);
                    slot.in_ground = false;
                    continue;
                }
            }
            // Viability: the area can only fall; what leaves goes to the fallow with its water
            slot.ks = slot.stress();
            let viable_area = match &slot.viable_area_input {
                Some(input) => {
                    let value = input.get_value(data_cache);
                    if !(value >= 0.0) {
                        panic!("Field '{}': crop viable_area rule for '{}' gave {}; it must be a non-negative area in km2", self.name, slot.crop.name, value);
                    }
                    value
                }
                None => if slot.ks <= DEATH_KS { 0.0 } else { slot.partition.area },
            };
            slot.viable_read = viable_area;
            if viable_area < slot.partition.area {
                let leaving = slot.partition.area - viable_area;
                transfer(&self.profile, &mut slot.partition, &mut self.fallow_partition, leaving);
                if slot.partition.area <= 0.0 {
                    slot.in_ground = false;
                }
            }
        }
        // Planting: the rule gives the area to plant today, 0 for none. Lower slots first; a
        // slot in the ground is not read. A slot that left the ground above, by harvest or
        // abandonment, is empty here and may plant again today. A rule that gives no number,
        // or a negative one, has broken: it stops the run, as viable_area does.
        for slot in &mut self.slots {
            if slot.in_ground { continue; }
            let wanted = slot.plant_input.get_value(data_cache);
            slot.plant_read = wanted;
            if !(wanted >= 0.0) {
                panic!("Field '{}': crop plant rule for '{}' gave {}; it must be the area to plant in km2, 0 for none", self.name, slot.crop.name, wanted);
            }
            let area = wanted.min(self.fallow_partition.area);
            if area <= 0.0 { continue; }
            transfer(&self.profile, &mut self.fallow_partition, &mut slot.partition, area);
            slot.in_ground = true;
            slot.days = 0;
            slot.ks = slot.stress();
        }
    }

    /// Water arriving beyond the crops' orders is a forced watering the modeller
    /// intended: poured over the crops in the ground so as to level their
    /// depletion, driest first, each capped by its room. Returns the supply
    /// taken, in ML; the rest is bypass. Only runs when there is leftover water.
    #[inline(never)]
    fn level_pour(&mut self, water_ml: f64) -> f64 {
        // The crops with room, driest first: (slot index, depletion, area)
        let mut thirsty: [(usize, f64, f64); MAX_CROPS] = [(0, 0.0, 0.0); MAX_CROPS];
        let mut n = 0;
        for (i, slot) in self.slots.iter().enumerate() {
            if slot.in_ground && slot.partition.root_depletion > 0.0 {
                thirsty[n] = (i, slot.partition.root_depletion, slot.partition.area);
                n += 1;
            }
        }
        if n == 0 { return 0.0; }
        thirsty[..n].sort_by(|a, b| b.1.partial_cmp(&a.1).unwrap());

        // Lower the level from the driest crop's depletion until the water to the
        // soil is spent, taking in each crop as the level reaches it
        let mut water_mm_km2 = water_ml * self.efficiency;
        let mut level = thirsty[0].1;
        let mut k = 1;
        loop {
            let next = if k < n { thirsty[k].1 } else { 0.0 };
            let area: f64 = thirsty[..k].iter().map(|t| t.2).sum();
            let needed = area * (level - next);
            if water_mm_km2 >= needed {
                water_mm_km2 -= needed;
                level = next;
                if k == n { break; }
                k += 1;
            } else {
                level -= water_mm_km2 / area;
                break;
            }
        }
        let mut to_soil = 0.0;
        for t in &thirsty[..n] {
            let slot = &mut self.slots[t.0];
            let poured = (slot.partition.root_depletion - level).max(0.0);
            slot.partition.root_depletion -= poured;
            to_soil += poured * slot.partition.area;
        }
        to_soil / self.efficiency
    }
}

impl Node for FieldNode {
    fn add_usflow(&mut self, flow: f64, _inlet: u8) {
        self.usflow += flow;
    }

    fn remove_dsflow(&mut self, outlet: u8) -> f64 {
        match outlet {
            DS_1_OUTLET => {
                let outflow = self.dsflow_primary;
                self.dsflow_primary = 0.0;
                outflow
            }
            DS_2_OUTLET => {
                let outflow = self.dsflow_return;
                self.dsflow_return = 0.0;
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

    fn initialise(&mut self, data_cache: &mut DataCache, _account_manager: &mut AccountManager) -> Result<(), String> {
        // Checks
        if !(self.area > 0.0 && self.area.is_finite()) {
            return Err(format!("Error in node '{}'. area must be a positive number of km2, got {}.", self.name, self.area));
        }
        if !(self.available_water > 0.0 && self.available_water.is_finite()) {
            return Err(format!("Error in node '{}'. available_water must be a positive number of mm per m of soil, got {}.", self.name, self.available_water));
        }
        if !(self.efficiency > 0.0 && self.efficiency <= 1.0) {
            return Err(format!("Error in node '{}'. efficiency must be greater than 0 and at most 1, got {}.", self.name, self.efficiency));
        }
        if !(self.interception >= 0.0 && self.interception.is_finite()) {
            return Err(format!("Error in node '{}'. interception must be a non-negative number, got {}.", self.name, self.interception));
        }
        if !(self.return_fraction >= 0.0 && self.return_fraction <= 1.0) {
            return Err(format!("Error in node '{}'. return_fraction must be between 0 and 1, got {}.", self.name, self.return_fraction));
        }
        if let Some(cn) = self.curve_number {
            if !(cn > 0.0 && cn <= 100.0) {
                return Err(format!("Error in node '{}'. curve_number must be greater than 0 and at most 100, got {}.", self.name, cn));
            }
            // The curve number method is defined on daily rain totals. step_size is 0 on the
            // configure-time pass and set when the network is initialised.
            if data_cache.step_size != 0 && data_cache.step_size != 86400 {
                return Err(format!("Error in node '{}'. curve_number is defined for daily rain totals; the model's step is {} seconds.", self.name, data_cache.step_size));
            }
            // The dry and wet curves from the tabled average one, as APSIM derives them
            self.cn_dry = cn / (2.334 - 0.01334 * cn);
            self.cn_wet = cn / (0.4036 + 0.005964 * cn);
        }
        let Some(fallow) = &self.fallow else {
            return Err(format!("Error in node '{}'. A field needs a fallow: the crop that covers what is not planted (fallow = <crop>).", self.name));
        };
        // The fallow is never planted, so days since planting mean nothing to it
        if matches!(fallow.kc, crate::hydrology::crop::KcCurve::ByDay(_)) {
            return Err(format!("Error in node '{}'. The fallow's kc must be a number, not a table by days since planting: a fallow is never planted (crop '{}').", self.name, fallow.name));
        }
        if self.slots.len() > MAX_CROPS {
            return Err(format!("Error in node '{}'. A field holds at most {} crop slots.", self.name, MAX_CROPS));
        }
        for (i, slot) in self.slots.iter().enumerate() {
            let n = i + 1;
            if matches!(slot.plant_input, DynamicInput::None { .. }) {
                return Err(format!("Error in node '{}'. crop_{n} needs crop_{n}_plant: the area to plant each day, in km2, 0 for none.", self.name));
            }
        }

        // The soil's layers are bounded by the field's distinct root depths
        let mut root_depths: Vec<f64> = self.slots.iter().map(|s| s.crop.root_depth).collect();
        root_depths.push(fallow.root_depth);
        self.profile = Profile::new(self.available_water, &root_depths);
        let total_capacity = self.profile.total_capacity();
        if !(self.initial_depletion >= 0.0 && self.initial_depletion <= total_capacity) {
            return Err(format!("Error in node '{}'. initial_depletion must be between 0 and what the soil holds to the deepest roots ({} mm), got {}.", self.name, total_capacity, self.initial_depletion));
        }

        // Initialize only internal state: all fallow to begin with
        self.mbal = 0.0;
        self.usflow = 0.0;
        self.dsflow_primary = 0.0;
        self.dsflow_return = 0.0;
        self.fallow_partition = Partition::new(&self.profile, fallow.root_depth, self.area, self.initial_depletion);
        self.planting_done = false;
        for slot in &mut self.slots {
            slot.partition = Partition::new(&self.profile, slot.crop.root_depth, 0.0, self.initial_depletion);
            slot.in_ground = false;
            slot.days = 0;
            slot.ks = f64::NAN;
            slot.order_value = 0.0;
            slot.order_due = 0.0;
            slot.order_buffer = FifoBuffer::default();
            slot.plant_read = f64::NAN;
            slot.viable_read = f64::NAN;
        }

        // Reset order state, so a rerun of the same model object starts clean.
        // The ordering system (which initialises after the nodes) sizes the
        // buffers from the travel time it finds.
        self.order_travel_time = 0;
        self.order_value = 0.0;

        // DynamicInput is already initialized during parsing

        // Initialize result recorders
        self.recorder_idx_fallow_depletion = recorder(data_cache, &self.name, "fallow_depletion");
        self.recorder_idx_usflow = recorder(data_cache, &self.name, "usflow");
        self.recorder_idx_et = recorder(data_cache, &self.name, "et");
        self.recorder_idx_et_vol = recorder(data_cache, &self.name, "et_vol");
        self.recorder_idx_rain = recorder(data_cache, &self.name, "rain");
        self.recorder_idx_rain_vol = recorder(data_cache, &self.name, "rain_vol");
        self.recorder_idx_intercepted = recorder(data_cache, &self.name, "intercepted");
        self.recorder_idx_evap = recorder(data_cache, &self.name, "evap");
        self.recorder_idx_excess = recorder(data_cache, &self.name, "excess");
        self.recorder_idx_supply = recorder(data_cache, &self.name, "supply");
        self.recorder_idx_escape = recorder(data_cache, &self.name, "escape");
        self.recorder_idx_bypass = recorder(data_cache, &self.name, "bypass");
        self.recorder_idx_return_flow = recorder(data_cache, &self.name, "return_flow");
        self.recorder_idx_dsflow = recorder(data_cache, &self.name, "dsflow");
        self.recorder_idx_ds_1 = recorder(data_cache, &self.name, "ds_1");
        self.recorder_idx_ds_2 = recorder(data_cache, &self.name, "ds_2");
        // Per slot: crop_N followed by each of CROP_SLOT_OUTPUTS
        for (i, slot) in self.slots.iter_mut().enumerate() {
            for (j, suffix) in CROP_SLOT_OUTPUTS.iter().enumerate() {
                slot.recorder_idx[j] = recorder(data_cache, &self.name, &format!("crop_{}{suffix}", i + 1));
            }
        }

        // Return
        Ok(())
    }

    fn get_name(&self) -> &str { &self.name }

    fn run_order_phase(&mut self, data_cache: &mut DataCache, _account_manager: &mut AccountManager) {

        // The day begins with the planting
        if !self.slots.is_empty() {
            self.planting(data_cache);
            self.planting_done = true;
        }

        // Each crop in the ground orders by its own rule, which reads the slot's states as
        // they stood at the end of yesterday: `this.crop_N_depletion[-1, 0]`, the soil at the
        // start of today, and `this.crop_N_orders_en_route[-1, 0]`, everything ordered before
        // today that has not arrived before today - which includes what arrives today. A slot
        // not in the ground is skipped, not evaluated. The field places the sum.
        self.order_value = 0.0;
        for slot in &mut self.slots {
            slot.order_value = if slot.in_ground { slot.order_input.get_value(data_cache).max(0.0) } else { 0.0 };
            self.order_value += slot.order_value;

            // The order placed order_travel_time steps ago is due to arrive today
            slot.order_due = slot.order_buffer.push(slot.order_value);

            // Order phase recorders. orders_en_route is what is on its way at the end of
            // today's ordering: today's order included, the order arriving today not.
            if let Some(idx) = slot.recorder_idx[4] {
                data_cache.add_value_at_index(idx, slot.order_value);
            }
            if let Some(idx) = slot.recorder_idx[5] {
                data_cache.add_value_at_index(idx, slot.order_due);
            }
            if let Some(idx) = slot.recorder_idx[6] {
                data_cache.add_value_at_index(idx, slot.order_buffer.sum());
            }
        }
    }

    fn run_flow_phase(&mut self, data_cache: &mut DataCache, _account_manager: &mut AccountManager) {

        // Record results
        if let Some(idx) = self.recorder_idx_usflow {
            data_cache.add_value_at_index(idx, self.usflow);
        }

        // 1. Planting first, so the day's fluxes use the day's areas: already done if the
        //    field had an order phase today. A field outside every regulated zone has none:
        //    it places no order, and its order results are written here as zero, so that
        //    the series exist and are as long as every other.
        let had_order_phase = self.planting_done;
        if had_order_phase {
            self.planting_done = false;
        } else {
            if !self.slots.is_empty() {
                self.planting(data_cache);
            }
            for slot in &self.slots {
                if let Some(idx) = slot.recorder_idx[4] {
                    data_cache.add_value_at_index(idx, 0.0);
                }
                if let Some(idx) = slot.recorder_idx[5] {
                    data_cache.add_value_at_index(idx, 0.0);
                }
                if let Some(idx) = slot.recorder_idx[6] {
                    data_cache.add_value_at_index(idx, 0.0);
                }
            }
        }

        // Get the driving data
        let rain_mm = self.rain_input.get_value(data_cache).max(0.0);
        let evap_mm = self.evap_input.get_value(data_cache).max(0.0);

        // 2. Each partition runs its day on its own bucket. With a curve number, the storm
        //    runoff off the surface comes first, by the partition's wetness. Then rain goes on
        //    the soil less what the canopy intercepts and evaporates (FAO-56: a share of the
        //    day's reference evapotranspiration, 0.2 by default).
        let curve = self.curve_number.map(|_| (self.cn_dry, self.cn_wet));
        let mut intercepted_ml = 0.0;
        let mut et_ml = 0.0;
        let mut excess = 0.0;
        {
            let fallow = self.fallow.as_ref().expect("initialise checked the fallow");
            let kc = fallow.kc.at(0.0); // a number: initialise refused a table
            let runoff_mm = curve.map_or(0.0, |(dry, wet)| curve_number_runoff(dry, wet, &self.fallow_partition, rain_mm));
            let intercepted_mm = (rain_mm - runoff_mm).min(self.interception * evap_mm);
            let (_, et_mm, excess_mm) = soil_day(&self.profile, &mut self.fallow_partition, fallow.p, kc, evap_mm, rain_mm - runoff_mm - intercepted_mm);
            intercepted_ml += intercepted_mm * self.fallow_partition.area;
            et_ml += et_mm * self.fallow_partition.area;
            excess += (runoff_mm + excess_mm) * self.fallow_partition.area;
        }
        for slot in &mut self.slots {
            if !slot.in_ground { continue; }
            let kc = slot.crop.kc.at(slot.days as f64);
            let runoff_mm = curve.map_or(0.0, |(dry, wet)| curve_number_runoff(dry, wet, &slot.partition, rain_mm));
            let intercepted_mm = (rain_mm - runoff_mm).min(self.interception * evap_mm);
            let (ks, et_mm, excess_mm) = soil_day(&self.profile, &mut slot.partition, slot.crop.p, kc, evap_mm, rain_mm - runoff_mm - intercepted_mm);
            slot.ks = ks;
            intercepted_ml += intercepted_mm * slot.partition.area;
            et_ml += et_mm * slot.partition.area;
            excess += (runoff_mm + excess_mm) * slot.partition.area;
        }
        let intercepted_mm = intercepted_ml / self.area;

        // 3. Irrigation. Each crop in the ground takes up to its own order due, no more than
        //    its root zone has room for after the rain, allowing for the share that escapes
        //    on the way. Water beyond the orders is poured over the crops, driest first.
        //    What no crop can take is bypass.
        let mut remaining = self.usflow;
        for slot in &mut self.slots {
            if !slot.in_ground { continue; }
            let room_ml = slot.partition.root_depletion * slot.partition.area;
            let take = slot.order_due.min(room_ml / self.efficiency).min(remaining);
            slot.partition.root_depletion -= take * self.efficiency / slot.partition.area;
            remaining -= take;
        }
        if remaining > 0.0 {
            remaining -= self.level_pour(remaining);
        }
        let bypass = remaining.max(0.0); // the pour's division can overshoot by an ulp
        let supply = self.usflow - bypass;
        let escape = supply * (1.0 - self.efficiency);

        // excess is all the rain the paddocks shed: storm runoff off the surface, and the
        // overflow of a full profile.
        // Water leaves on two links and nothing is lost here. ds_1: the bypass, which the
        // irrigator did not take, and the river's share of the runoff. ds_2: the share the farm
        // catches, return_flow, for the modeller to drain, or to feed back to the farm storage
        // through an inflow node above it (`inflow = node.<field>.return_flow[-1, 0]`). mbal is
        // emitted minus received, as on a storage: rain, evapotranspiration, escape and the
        // change in water held all show through it.
        let return_flow = excess * self.return_fraction;
        self.dsflow_primary = bypass + excess - return_flow;
        self.dsflow_return = return_flow;
        self.mbal += self.dsflow_primary + self.dsflow_return - self.usflow;

        // Record results. States are as they stand at the end of the step, as a storage's
        // volume is. A slot not in the ground has no area and no state.
        for slot in &self.slots {
            let (area, days, depletion, ks) = if slot.in_ground {
                (slot.partition.area, slot.days as f64, slot.partition.root_depletion, slot.ks)
            } else {
                (0.0, f64::NAN, f64::NAN, f64::NAN)
            };
            if let Some(idx) = slot.recorder_idx[0] {
                data_cache.add_value_at_index(idx, area);
            }
            if let Some(idx) = slot.recorder_idx[1] {
                data_cache.add_value_at_index(idx, days);
            }
            if let Some(idx) = slot.recorder_idx[2] {
                data_cache.add_value_at_index(idx, depletion);
            }
            if let Some(idx) = slot.recorder_idx[3] {
                data_cache.add_value_at_index(idx, ks);
            }
            // The rules as read today: not a number on a day a rule was not read
            if let Some(idx) = slot.recorder_idx[7] {
                data_cache.add_value_at_index(idx, slot.plant_read);
            }
            if let Some(idx) = slot.recorder_idx[8] {
                data_cache.add_value_at_index(idx, slot.viable_read);
            }
        }
        if let Some(idx) = self.recorder_idx_fallow_depletion {
            data_cache.add_value_at_index(idx, self.fallow_partition.root_depletion);
        }
        // The soil's terms in mm over the whole field, and their volumes as _vol, as on a storage
        if let Some(idx) = self.recorder_idx_et {
            data_cache.add_value_at_index(idx, et_ml / self.area);
        }
        if let Some(idx) = self.recorder_idx_et_vol {
            data_cache.add_value_at_index(idx, et_ml);
        }
        // A result named for a property reports the property's value: rain and evap are
        // the inputs in mm, as on a storage, and rain_vol is the volume
        if let Some(idx) = self.recorder_idx_rain {
            data_cache.add_value_at_index(idx, rain_mm);
        }
        if let Some(idx) = self.recorder_idx_rain_vol {
            data_cache.add_value_at_index(idx, rain_mm * self.area);
        }
        if let Some(idx) = self.recorder_idx_evap {
            data_cache.add_value_at_index(idx, evap_mm);
        }
        if let Some(idx) = self.recorder_idx_intercepted {
            data_cache.add_value_at_index(idx, intercepted_mm);
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
        if let Some(idx) = self.recorder_idx_return_flow {
            data_cache.add_value_at_index(idx, return_flow);
        }
        if let Some(idx) = self.recorder_idx_dsflow {
            data_cache.add_value_at_index(idx, self.dsflow_primary + self.dsflow_return); //Total dsflow, both outlets
        }
        if let Some(idx) = self.recorder_idx_ds_1 {
            data_cache.add_value_at_index(idx, self.dsflow_primary);
        }
        if let Some(idx) = self.recorder_idx_ds_2 {
            data_cache.add_value_at_index(idx, self.dsflow_return);
        }

        // Reset upstream inflow for next timestep
        self.usflow = 0.0;
    }
}

/// Reads a crop slot property name, `crop_<N><suffix>` with the suffix one of
/// CROP_SLOT_PROPERTIES, as (N, suffix). None for any other name.
pub fn crop_slot_property(name_lower: &str) -> Option<(usize, &'static str)> {
    let rest = name_lower.strip_prefix("crop_")?;
    let digits = rest.chars().take_while(|c| c.is_ascii_digit()).count();
    if digits == 0 { return None; }
    let n: usize = rest[..digits].parse().ok()?;
    if n.to_string() != rest[..digits] { return None; } // crop_01 is not a slot
    let suffix = CROP_SLOT_PROPERTIES.iter().find(|s| **s == &rest[digits..])?;
    Some((n, suffix))
}
