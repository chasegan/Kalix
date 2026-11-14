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
pub const CROP_SLOT_PROPERTIES: [&str; 5] = ["", "_plant", "_order", "_viable_area", "_kc_multiplier"];

/// The results of a crop slot, after `crop_N`
pub const CROP_SLOT_OUTPUTS: [&str; 14] = ["_area", "_days", "_depletion", "_ks", "_order", "_order_due", "_orders_en_route", "_plant", "_viable_area", "_kc_multiplier", "_kc", "_root_depth", "_p", "_ke"];

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
    pub kc_multiplier_input: DynamicInput, // scales the crop's kc in the ET step only; omitted, 1; read when planting runs

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
    opened: bool,                         // today's opening state (days, root depth, kc, p, ks) has been settled and recorded by planting
    kc_multiplier_read: f64,              // what the multiplier gave today; 1 when none is written; NaN when the crop does not stand
    kc_used: f64,                         // the coefficient used for today's ET: the curve times the multiplier; NaN when the crop does not stand
    p_today: f64,                         // the depletion fraction used today, adjusted for the day's ETc unless the crop says not; NaN when the crop does not stand
    kcb_today: f64,                       // the basal coefficient the stress applies to: the curve times the multiplier
    ke_today: f64,                        // the soil evaporation coefficient; NaN when the crop does not stand
    few_today: f64,                       // the exposed and wetted share of the ground, which the evaporation layer's balance is over
    grows: bool,                          // the crop's: it has an initial_root_depth, so the roots grow each day
    p_adjust: bool,                       // the crop's p_adjust, and its adjustment precomputed: p_base − 0.04 × ETc within p_lo..p_hi
    p_base: f64,
    p_lo: f64,
    p_hi: f64,

    // Recorders, in the order of CROP_SLOT_OUTPUTS
    recorder_idx: [Option<usize>; CROP_SLOT_OUTPUTS.len()],
}

impl CropSlot {
    pub fn new(crop: Crop) -> Self {
        Self {
            grows: crop.initial_root_depth.is_some(),
            p_adjust: crop.p_adjust,
            p_base: crop.p + 0.2,
            p_lo: crop.p.min(0.1),
            p_hi: crop.p.max(0.8),
            crop,
            plant_input: DynamicInput::default(),
            order_input: DynamicInput::default(),
            viable_area_input: None,
            kc_multiplier_input: DynamicInput::default(),
            partition: Partition::default(),
            in_ground: false,
            days: 0,
            ks: f64::NAN,
            order_value: 0.0,
            order_due: 0.0,
            order_buffer: FifoBuffer::default(),
            plant_read: f64::NAN,
            viable_read: f64::NAN,
            opened: false,
            kc_multiplier_read: f64::NAN,
            kc_used: f64::NAN,
            p_today: f64::NAN,
            kcb_today: 0.0,
            ke_today: f64::NAN,
            few_today: 1.0,
            recorder_idx: [None; CROP_SLOT_OUTPUTS.len()],
        }
    }

    /// The day opens for a crop in the ground: its coefficient from the curve at its
    /// days since planting times the slot's multiplier, the depletion fraction for the
    /// day's unstressed evapotranspiration, and the stress coefficient from the bucket
    /// as it stands (FAO-56 eq. 84: full transpiration until p of the capacity is used,
    /// falling to none at empty). Recorded as soon as they are known, so a viable-area
    /// rule reads today's `this.crop_N_ks`, `_days`, `_root_depth`, `_kc` and `_p` with
    /// no offset. The multiplier may read `this.crop_N_days` likewise; the area is
    /// settled later and wants `[-1, 0]`.
    #[inline]
    fn open_day(&mut self, field_name: &str, evap_mm: f64, layer: &EvapLayer, data_cache: &mut DataCache) {
        for (j, value) in [(1, self.days as f64), (11, self.partition.root_depth)] {
            if let Some(idx) = self.recorder_idx[j] {
                data_cache.add_value_at_index(idx, value);
            }
        }
        let multiplier = match self.kc_multiplier_input {
            DynamicInput::None { .. } => 1.0,
            _ => {
                let value = self.kc_multiplier_input.get_value(data_cache);
                if !(value >= 0.0) {
                    panic!("Field '{}': crop kc_multiplier rule for '{}' gave {}; it must be a non-negative number", field_name, self.crop.name, value);
                }
                value
            }
        };
        self.kc_multiplier_read = multiplier;
        self.kcb_today = self.crop.kcb.at(self.days as f64) * multiplier;
        // The soil surface evaporates by Ke from the field's evaporation layer, and FAO-56's
        // Kc for the day is Kcb + Ke
        let (ke, few) = layer.ke(self.partition.evap_depletion, self.kcb_today);
        self.ke_today = ke;
        self.few_today = few;
        self.kc_used = self.kcb_today + ke;
        // FAO-56 Table 22's adjustment of p for the day's ETc (Crop::p_for, unrolled onto
        // the slot's own precomputed numbers for the hot path)
        self.p_today = if self.p_adjust { (self.p_base - 0.04 * self.kc_used * evap_mm).clamp(self.p_lo, self.p_hi) } else { self.crop.p };
        self.ks = stress(&self.partition, self.p_today);
        self.opened = true;
        let values = [(9, multiplier), (10, self.kc_used), (12, self.p_today), (3, self.ks), (13, self.ke_today)];
        for (j, value) in values {
            if let Some(idx) = self.recorder_idx[j] {
                data_cache.add_value_at_index(idx, value);
            }
        }
    }
}

#[inline]
fn stress(partition: &Partition, p: f64) -> f64 {
    let readily_available = (1.0 - p) * partition.root_capacity;
    ((partition.root_capacity - partition.root_depletion) / readily_available).clamp(0.0, 1.0)
}

/// The field's surface evaporation layer (FAO-56 Chapter 7): the top 100 to
/// 150 mm of soil, holding `tew` mm of
/// evaporable water of which `rew` mm evaporate at the full rate before the
/// surface dries and the rate falls.
#[derive(Clone, Copy, Default)]
struct EvapLayer {
    tew: f64,    // total evaporable water, mm
    rew: f64,    // readily evaporable water, mm
    kc_max: f64, // the most evaporation and transpiration a wet surface under a full canopy gives, as a share of evap
    fw: f64,     // the share of the ground that irrigation wets
}

impl EvapLayer {
    /// The soil evaporation coefficient Ke for a surface `de` mm below full under a
    /// crop with basal coefficient `kcb`, and the share of the ground `few` that is
    /// both exposed and wetted, which the layer's balance is over. FAO-56 eqs 71 to
    /// 76: Kr = 1 while less than REW has evaporated, then falls to 0 at TEW; Ke is
    /// Kr of the energy the canopy leaves, no more than the exposed wetted ground
    /// can give; the canopy's cover from Kcb (eq. 76 with its exponent 1: the
    /// crop-height exponent only tells under drip at full canopy).
    #[inline]
    fn ke(&self, de: f64, kcb: f64) -> (f64, f64) {
        // Eq. 72: the ceiling is never less than Kcb + 0.05, so a full canopy still leaves a
        // wet surface a little to evaporate with
        let kc_max = self.kc_max.max(kcb + 0.05);
        let kr = if de <= self.rew { 1.0 } else { ((self.tew - de) / (self.tew - self.rew)).clamp(0.0, 1.0) };
        const KC_MIN: f64 = 0.15;
        let cover = ((kcb - KC_MIN) / (kc_max - KC_MIN)).clamp(0.0, 1.0);
        let few = (1.0 - cover).min(self.fw);
        let ke = (kr * (kc_max - kcb)).min(few * kc_max).max(0.0);
        (ke, few)
    }

    /// The curve number for the day between the dry curve `cn_dry` and the wet one
    /// `cn_wet`, by the surface's depletion `de`, as ASCE Manual 70 (2016) eqs 14-18 to
    /// 14-20 set it: the wet curve while less than half REW has evaporated, the dry curve
    /// once more than 0.7 REW + 0.3 TEW has, a straight line between.
    #[inline]
    fn curve_number(&self, de: f64, cn_dry: f64, cn_wet: f64) -> f64 {
        let wet_below = 0.5 * self.rew;
        let dry_above = 0.7 * self.rew + 0.3 * self.tew;
        if de <= wet_below {
            cn_wet
        } else if de >= dry_above {
            cn_dry
        } else {
            cn_wet + (cn_dry - cn_wet) * (de - wet_below) / (dry_above - wet_below)
        }
    }

    /// The layer's balance for the day: the evaporation taken from it over the
    /// exposed wetted ground, the rain that reached the soil put back, held between
    /// full and TEW (what a full layer cannot take is in the root bucket already).
    #[inline]
    fn day(&self, partition: &mut Partition, e_mm: f64, few: f64, effective_rain_mm: f64) {
        let de = partition.evap_depletion + if few > 0.0 { e_mm / few } else { 0.0 } - effective_rain_mm;
        partition.evap_depletion = de.clamp(0.0, self.tew);
    }

    /// Irrigation of `depth_mm` over the partition wets the share fw of its ground
    #[inline]
    fn irrigate(&self, partition: &mut Partition, depth_mm: f64) {
        partition.evap_depletion = (partition.evap_depletion - depth_mm / self.fw).max(0.0);
    }
}

/// One partition's day: transpiration at the crop's basal rate `kc`, stressed as
/// the bucket dries, plus the surface's evaporation `ke`, then the rain, less what
/// the canopy intercepts, into the root bucket; what the bucket cannot hold
/// fills the layers below, and what passes the last layer is excess. Returns
/// (ks, et, excess), all in mm.
#[inline]
fn soil_day(profile: &Profile, partition: &mut Partition, p: f64, kc: f64, ke: f64, evap_mm: f64, effective_rain_mm: f64) -> (f64, f64, f64) {
    let ks = stress(partition, p);
    let et_mm = ((ks * kc + ke) * evap_mm).min(partition.root_capacity - partition.root_depletion);
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
/// number: S = 254 (100/CN − 1) mm of retention (the handbook's 1000/CN − 10 inches),
/// runoff once the rain exceeds the initial abstraction 0.2 S. The day's curve sits between
/// the dry one (CN1) and the wet one (CN3) by how far the surface evaporation layer has
/// dried, as ASCE Manual 70 and pyfao56 choose it. Rain only: irrigation never runs
/// through it.
#[inline]
fn curve_number_runoff(cn_dry: f64, cn_wet: f64, layer: &EvapLayer, partition: &Partition, rain_mm: f64) -> f64 {
    let cn = layer.curve_number(partition.evap_depletion, cn_dry, cn_wet);
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
    pub interception: f64,          // fraction of evap that rain must exceed to reach the soil; 0 by default, the evaporation layer handles small rains (Source Irrigator deducts 0.2)
    pub return_fraction: f64,       // share of the excess that the farm catches: it goes down ds_2 as return_flow
    pub curve_number: Option<f64>,  // USDA-NRCS CN2 for storm runoff off the paddock surface, the day's curve set by the evaporation layer (ASCE Manual 70); None, saturation excess only
    pub tew: Option<f64>,           // the surface evaporation layer's total evaporable water, mm (FAO-56 Table 19); required
    pub rew: Option<f64>,           // its readily evaporable water, mm; required
    pub kc_max: f64,                // the ceiling on Kcb + Ke, 1.2 unless the climate says otherwise (FAO-56 eq. 72)
    pub wetted_fraction: f64,       // the share of the ground irrigation wets (FAO-56 Table 20); 1 for rain, flood and furrow
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
    evap_today: f64,                // today's reference evapotranspiration, read when planting runs
    evap_layer: EvapLayer,          // tew, rew, kc_max and wetted_fraction together
    fallow_kc: f64,                 // the fallow's basal coefficient, a number (0 for bare soil)
    fallow_p: (bool, f64, f64, f64), // the fallow's p adjustment: on, p_base, p_lo, p_hi; off: p in p_base
    cn_dry: f64,                    // the dry and wet curves (CN1, CN3) derived from curve_number
    cn_wet: f64,
    usflow: f64,
    dsflow_primary: f64,
    dsflow_return: f64,

    // Recorders
    recorder_idx_fallow_depletion: Option<usize>,
    recorder_idx_soil_moisture: Option<usize>,
    recorder_idx_volume: Option<usize>,
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
            interception: 0.0,
            return_fraction: 0.0,
            kc_max: 1.2,
            wetted_fraction: 1.0,
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
    ///
    /// It runs exactly once a step, so the results it settles are written
    /// here, whichever phase that is: area, days, and the two rules as read.
    /// Written this early, `this.crop_N_area` with no offset reads today's
    /// area in an order rule, as Kalix records values as soon as they are
    /// known.
    #[inline(never)]
    fn planting(&mut self, data_cache: &mut DataCache) {
        for slot in &mut self.slots {
            slot.plant_read = f64::NAN;
            slot.viable_read = f64::NAN;
            slot.opened = false;
        }
        // Today's reference evapotranspiration sets the day's depletion fraction: a data
        // series, known in the order phase. Kept for the flow phase's fluxes.
        let evap_mm = self.evap_input.get_value(data_cache).max(0.0);
        self.evap_today = evap_mm;
        for slot in &mut self.slots {
            if !slot.in_ground { continue; }
            slot.days += 1;
            // Root growth: the roots reach today's depth, taking in the soil below with its
            // water, so today's capacity and stress are the day's (FAO-56 Ch. 8, as pyfao56
            // grows the roots with the coefficient). A crop with no initial_root_depth is at
            // full depth already and this does nothing.
            if slot.grows {
                let depth = slot.crop.root_depth_at(slot.days as f64);
                slot.partition.grow(&self.profile, depth);
            }
            // Harvest: season_len days after planting, the whole crop goes back to the fallow
            if let Some(season_len) = slot.crop.season_len {
                if slot.days >= season_len {
                    let all = slot.partition.area;
                    transfer(&self.profile, &mut slot.partition, &mut self.fallow_partition, all);
                    slot.in_ground = false;
                    continue;
                }
            }
            // Viability: the area can only fall; what leaves goes to the fallow with its water.
            // The day opens first, so a rule reads today's `this.crop_N_ks`, the same number
            // the built-in rule uses.
            slot.open_day(&self.name, evap_mm, &self.evap_layer, data_cache);
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
            // The new crop's roots start at their planting depth; the land arrives with its water
            slot.partition = Partition::new(&self.profile, slot.crop.root_depth_at(0.0), 0.0, 0.0);
            transfer(&self.profile, &mut self.fallow_partition, &mut slot.partition, area);
            slot.in_ground = true;
            slot.days = 0;
            slot.open_day(&self.name, evap_mm, &self.evap_layer, data_cache);
        }
        // The day's areas are settled: record them now. A slot whose day did not open has
        // nothing in the ground: no area, and not a number for its opening state.
        for slot in &mut self.slots {
            let area = if slot.in_ground { slot.partition.area } else { 0.0 };
            if let Some(idx) = slot.recorder_idx[0] {
                data_cache.add_value_at_index(idx, area);
            }
            if !slot.opened {
                slot.kc_multiplier_read = f64::NAN;
                slot.kc_used = f64::NAN;
                slot.p_today = f64::NAN;
                slot.ke_today = f64::NAN;
                for j in [1, 3, 9, 10, 11, 12, 13] {
                    if let Some(idx) = slot.recorder_idx[j] {
                        data_cache.add_value_at_index(idx, f64::NAN);
                    }
                }
            }
            // The rules as read today: not a number on a day a rule was not read
            if let Some(idx) = slot.recorder_idx[7] {
                data_cache.add_value_at_index(idx, slot.plant_read);
            }
            if let Some(idx) = slot.recorder_idx[8] {
                data_cache.add_value_at_index(idx, slot.viable_read);
            }
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
            self.evap_layer.irrigate(&mut slot.partition, poured);
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
            // The dry and wet curves from the tabled average one: ASCE Manual 70 (2016)
            // eqs 14-14 and 14-15
            self.cn_dry = cn / (2.281 - 0.01281 * cn);
            self.cn_wet = cn / (0.427 + 0.00573 * cn);
        }
        let Some(fallow) = &self.fallow else {
            return Err(format!("Error in node '{}'. A field needs a fallow: the crop that covers what is not planted (fallow = <crop>).", self.name));
        };
        // The surface evaporation layer: every crop's Ke, and the curve number's antecedent
        let (Some(tew), Some(rew)) = (self.tew, self.rew) else {
            return Err(format!("Error in node '{}'. A field needs its surface evaporation layer: tew and rew, the total and readily evaporable water in mm (FAO-56 Table 19: a clay about 28 and 8).", self.name));
        };
        if !(rew > 0.0 && tew > rew && tew.is_finite()) {
            return Err(format!("Error in node '{}'. rew must be greater than 0 and tew greater than rew, in mm of the evaporation layer's water, got tew {} and rew {}.", self.name, tew, rew));
        }
        if !(self.kc_max > 0.0 && self.kc_max.is_finite()) {
            return Err(format!("Error in node '{}'. kc_max must be a positive number, got {}.", self.name, self.kc_max));
        }
        if !(self.wetted_fraction > 0.0 && self.wetted_fraction <= 1.0) {
            return Err(format!("Error in node '{}'. wetted_fraction must be greater than 0 and at most 1, got {}.", self.name, self.wetted_fraction));
        }
        self.evap_layer = EvapLayer { tew, rew, kc_max: self.kc_max, fw: self.wetted_fraction };
        self.fallow_kc = fallow.kcb.at(0.0);
        self.fallow_p = if fallow.p_adjust { (true, fallow.p + 0.2, fallow.p.min(0.1), fallow.p.max(0.8)) } else { (false, fallow.p, 0.0, 0.0) };
        // The fallow is never planted, so days since planting mean nothing to it
        if matches!(fallow.kcb, crate::hydrology::crop::KcCurve::ByDay(_)) {
            return Err(format!("Error in node '{}'. The fallow's kcb must be a number, not a table by days since planting: a fallow is never planted (crop '{}').", self.name, fallow.name));
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
        let fraction = (self.initial_depletion / self.profile.total_capacity()).clamp(0.0, 1.0);
        self.fallow_partition.evap_depletion = fraction * self.evap_layer.tew;
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
            slot.opened = false;
            slot.kc_multiplier_read = f64::NAN;
            slot.kc_used = f64::NAN;
            slot.p_today = f64::NAN;
        }

        // Reset order state, so a rerun of the same model object starts clean.
        // The ordering system (which initialises after the nodes) sizes the
        // buffers from the travel time it finds.
        self.order_travel_time = 0;
        self.order_value = 0.0;

        // DynamicInput is already initialized during parsing

        // Initialize result recorders
        self.recorder_idx_fallow_depletion = recorder(data_cache, &self.name, "fallow_depletion");
        self.recorder_idx_soil_moisture = recorder(data_cache, &self.name, "soil_moisture");
        self.recorder_idx_volume = recorder(data_cache, &self.name, "volume");
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
        // Planting read today's evap when it opened the slots' day; a field with no slots has
        // no planting and reads it here
        let evap_mm = if self.slots.is_empty() { self.evap_input.get_value(data_cache).max(0.0) } else { self.evap_today };

        // 2. Each partition runs its day on its own bucket. With a curve number, the storm
        //    runoff off the surface comes first, by how dry the partition's surface layer is.
        //    Then rain goes on the soil less what the canopy intercepts and evaporates
        //    (a share of the day's reference evapotranspiration; 0 unless written).
        let curve = self.curve_number.map(|_| (self.cn_dry, self.cn_wet));
        let layer = self.evap_layer;
        let mut intercepted_ml = 0.0;
        let mut et_ml = 0.0;
        let mut excess = 0.0;
        {
            let kcb = self.fallow_kc; // a number: initialise refused a table
            // The fallow (kcb usually 0) is bare soil evaporating from the layer
            let (ke, few) = layer.ke(self.fallow_partition.evap_depletion, kcb);
            let runoff_mm = curve.map_or(0.0, |(dry, wet)| curve_number_runoff(dry, wet, &layer, &self.fallow_partition, rain_mm));
            let intercepted_mm = (rain_mm - runoff_mm).min(self.interception * evap_mm);
            let effective_rain_mm = rain_mm - runoff_mm - intercepted_mm;
            let (adjust, p_base, p_lo, p_hi) = self.fallow_p;
            let p = if adjust { (p_base - 0.04 * (kcb + ke) * evap_mm).clamp(p_lo, p_hi) } else { p_base };
            let (_, et_mm, excess_mm) = soil_day(&self.profile, &mut self.fallow_partition, p, kcb, ke, evap_mm, effective_rain_mm);
            layer.day(&mut self.fallow_partition, ke * evap_mm, few, effective_rain_mm);
            intercepted_ml += intercepted_mm * self.fallow_partition.area;
            et_ml += et_mm * self.fallow_partition.area;
            excess += (runoff_mm + excess_mm) * self.fallow_partition.area;
        }
        for slot in &mut self.slots {
            if !slot.in_ground { continue; }
            // The coefficient and the depletion fraction were settled when the day opened:
            // the curve times the slot's multiplier (a skip-row planting, a climate
            // adjustment, a stress the node does not model), which touches the ET step
            // only, and p for the day's ETc. Interception reads evap as it is.
            let runoff_mm = curve.map_or(0.0, |(dry, wet)| curve_number_runoff(dry, wet, &layer, &slot.partition, rain_mm));
            let intercepted_mm = (rain_mm - runoff_mm).min(self.interception * evap_mm);
            let effective_rain_mm = rain_mm - runoff_mm - intercepted_mm;
            let (ks, et_mm, excess_mm) = soil_day(&self.profile, &mut slot.partition, slot.p_today, slot.kcb_today, slot.ke_today, evap_mm, effective_rain_mm);
            slot.ks = ks;
            layer.day(&mut slot.partition, slot.ke_today * evap_mm, slot.few_today, effective_rain_mm);
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
            let depth_mm = take * self.efficiency / slot.partition.area;
            slot.partition.root_depletion -= depth_mm;
            layer.irrigate(&mut slot.partition, depth_mm);
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

        // Record results. Depletion is as it stands at the end of the step, as a storage's
        // volume is (area, days, ks and the rules were written by `planting`). A slot not in
        // the ground has no state.
        for slot in &self.slots {
            let depletion = if slot.in_ground { slot.partition.root_depletion } else { f64::NAN };
            if let Some(idx) = slot.recorder_idx[2] {
                data_cache.add_value_at_index(idx, depletion);
            }
        }
        if let Some(idx) = self.recorder_idx_fallow_depletion {
            data_cache.add_value_at_index(idx, self.fallow_partition.root_depletion);
        }
        // The water the whole profile holds over the whole field, to the deepest roots: every
        // partition's bucket and the layers below it, by area. The one state whose change is
        // the field's water balance:
        // volume[t] - volume[t-1] = rain_vol - intercepted x area + supply
        //                                                - escape - et_vol - excess
        if self.recorder_idx_soil_moisture.is_some() || self.recorder_idx_volume.is_some() {
            let mut water_vol = self.fallow_partition.water(&self.profile) * self.fallow_partition.area;
            for slot in &self.slots {
                if slot.in_ground {
                    water_vol += slot.partition.water(&self.profile) * slot.partition.area;
                }
            }
            if let Some(idx) = self.recorder_idx_soil_moisture {
                data_cache.add_value_at_index(idx, water_vol / self.area);
            }
            if let Some(idx) = self.recorder_idx_volume {
                data_cache.add_value_at_index(idx, water_vol);
            }
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
