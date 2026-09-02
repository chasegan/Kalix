use super::{Node, recorder};
use crate::model_inputs::DynamicInput;
use crate::numerical::table::Table;
use crate::data_management::data_cache::DataCache;
use crate::hydrology::accounts::account_manager::AccountManager;
use crate::misc::location::Location;
use crate::numerical::fifo_buffer::FifoBuffer;

const LEVL: usize = 0;
const VOLU: usize = 1;
const AREA: usize = 2;
const SPIL: usize = 3;
const EPSILON: f64 = 1e-6;
const MAX_DS_LINKS: usize = 4;

/// Defines outlet configuration: minimum operating level (MOL), capacity, or
/// a full rating table. Levels are in metres (the dimensions-table datum) and
/// are converted to volumes internally; capacities are ML per timestep.
///
/// All three forms are the same thing — a capacity-vs-level rating curve:
/// - `OutletWithMOL(l)`: 0 at/below level l, unlimited strictly above (a step);
/// - `OutletWithMOLAndCapacity(l, c)`: 0 at/below l, `c` strictly above;
/// - `OutletWithRatingTable(pairs)`: (level, capacity) points, capacity
///   interpolated linearly in level between points, held flat beyond the
///   ends. A repeated level is an explicit step.
#[derive(Default, Clone, Debug, PartialEq)]
pub enum OutletDefinition {
    #[default]
    None,
    OutletWithMOL(f64),                   // MOL level in metres
    OutletWithMOLAndCapacity(f64, f64),   // MOL level, capacity
    OutletWithRatingTable(Vec<(f64, f64)>), // (level, capacity) points
}

#[derive(Default, Clone)]
pub struct StorageNode {
    pub name: String,
    pub location: Location,
    pub mbal: f64,
    pub dimensions: Table,       // Level m, Volume ML, Area km2, Spill ML
    pub volume: f64,
    pub vol_initial: f64,
    pub order_through: bool,
    pub rain_mm_input: DynamicInput,
    pub evap_mm_input: DynamicInput,
    pub seep_mm_input: DynamicInput,
    pub pond_demand_input: DynamicInput,
    pub target_level: DynamicInput,
    pub exists: DynamicInput,
    pub ds_force_release_input: [DynamicInput; MAX_DS_LINKS],

    // Internal state only
    usflow: f64,
    dsflow: f64,
    ds_flows: [f64; MAX_DS_LINKS],
    ds_release_due: [f64; MAX_DS_LINKS],
    level: f64,
    rain_vol: f64,
    evap_vol: f64,
    seep_vol: f64,
    pond_diversion: f64, //pond diversion
    spill: f64,
    exists_configured: bool,
    exists_bool: bool,

    // Cached state for search optimization
    previous_istop: usize,  // Remember previous solution row for warm start

    // Orders
    pub ds_orders: [f64; MAX_DS_LINKS],
    pub ds_orders_due: [f64; MAX_DS_LINKS],
    pub us_orders: f64,
    pub has_target_level: bool,
    pub target_level_order_buffer: FifoBuffer,
    pub ds_order_buffers: [FifoBuffer; MAX_DS_LINKS],

    // Outlet definitions (MOL, capacity) - parsed from INI
    pub outlet_definition: [OutletDefinition; MAX_DS_LINKS],

    // Minimum operating volume for each outlet (converted from MOL level during init)
    // 0.0 means no MOL constraint (outlet always active)
    min_operating_volume: [f64; MAX_DS_LINKS],

    // True when any outlet has a MOL constraint (set during init). When
    // false the solver skips the MOL allocation entirely — the hot path for
    // ordinary storages is then identical to the pre-MOL-redesign solver.
    has_mol: bool,

    // Canonical capacity-vs-VOLUME curve per outlet, built at init from the
    // outlet definition (empty = unlimited). Points are sorted by volume;
    // a repeated volume is a step (left value at/below, right value above).
    // Rating-table curves also carry breakpoints at every dimension-table
    // row their level span crosses, so capacity is exactly linear in volume
    // between consecutive points.
    cap_curves: [Vec<(f64, f64)>; MAX_DS_LINKS],

    // Recorders
    recorder_idx_usflow: Option<usize>,
    recorder_idx_volume: Option<usize>,
    recorder_idx_level: Option<usize>,
    recorder_idx_target_level: Option<usize>,
    recorder_idx_area: Option<usize>,
    recorder_idx_seep_megs: Option<usize>,
    recorder_idx_evap_megs: Option<usize>,
    recorder_idx_rain_megs: Option<usize>,
    recorder_idx_seep_mm: Option<usize>,
    recorder_idx_evap_mm: Option<usize>,
    recorder_idx_rain_mm: Option<usize>,
    recorder_idx_pond_demand: Option<usize>,
    recorder_idx_pond_diversion: Option<usize>,
    recorder_idx_dsflow: Option<usize>,
    // Per-outlet recorder indices, one slot per ds link (ds_1 = index 0).
    recorder_idx_ds: [Option<usize>; MAX_DS_LINKS],
    recorder_idx_ds_order: [Option<usize>; MAX_DS_LINKS],
    recorder_idx_ds_order_due: [Option<usize>; MAX_DS_LINKS],
    recorder_idx_ds_outlet: [Option<usize>; MAX_DS_LINKS],
    recorder_idx_ds_spill: [Option<usize>; MAX_DS_LINKS],
    recorder_idx_ds_force_release: [Option<usize>; MAX_DS_LINKS],
    recorder_idx_exists: Option<usize>,
}

impl StorageNode {

    /// Base constructor
    pub fn new() -> Self {
        Self {
            name: "".to_string(),
            dimensions: Table::new(4),
            order_through: false,
            usflow: 0.0,
            ..Default::default()
        }
    }

    // -------------------------------------------------------------------------
    // Backward Euler Solver
    // -------------------------------------------------------------------------
    //
    // Terminology for ds_1 flows:
    // - "ds_1_spill": uncontrolled overflow via the spillway, counts towards ds_1 orders
    // - "ds_1_outlet": controlled outlet flow, supplements spill to meet ds_1
    //                  orders, or optionally forced by user input
    // - "ds_1" (total): ds_1_spill + ds_1_outlet
    // Note that the "order" is overridden by the "force_release" if defined.
    //
    // For ds_2, ds_3, ds_4: flow = outlet flow only (no spill component)
    //
    // Minimum operating levels (see docs/storage_mol_semantics.md): an outlet
    // may only be supplied from water stored above its own MOL. For every
    // threshold t among the outlet MOLs (and 0, the storage floor), the
    // controlled releases must satisfy
    //     sum of c_i over {m_i >= t}  <=  max(0, W - S - t)
    // where W is all water available this step and S the spill (drawn off the
    // top). Contention within a band resolves by outlet priority
    // ds_1 > ds_2 > ds_3 > ds_4. The MOL bounds which water an outlet can
    // access; it is not a gate on the end-of-step level. Releases are
    // therefore continuous in volumes and demands, no outlet is starved by a
    // sibling's MOL, and mass balance closes by construction.

    /// Determine whether the release at the outlet should be the forced release optionally
    /// supplied by the user or the order determined by the model.
    fn check_forced_release(
        data_cache: &mut DataCache,
        forced_release_input: &DynamicInput,
        order_due: f64
    ) -> f64 {
        match forced_release_input {
            DynamicInput::None { .. } => order_due,
            _ => forced_release_input.get_value(data_cache),
        }
    }

    /// Value recorded for the `ds_N_force_release` series of outlet `i`: the applied forced
    /// release when an input is configured, or NaN when the release is order-driven (so the
    /// series never masquerades as the plain order, which `ds_N_order_due` already records).
    fn force_release_output(&self, i: usize) -> f64 {
        match &self.ds_force_release_input[i] {
            DynamicInput::None { .. } => f64::NAN,
            _ => self.ds_release_due[i],
        }
    }

    /// Controlled-release demands at a given spill: ds_1's controlled outlet
    /// only tops the spill up to its release due (spill counts toward ds_1
    /// orders); the other outlets demand their full release due.
    fn demands_at_spill(&self, spill: f64) -> [f64; MAX_DS_LINKS] {
        let mut demands = self.ds_release_due;
        demands[0] = (demands[0] - spill).max(0.0);
        demands
    }

    /// Nested-cap MOL allocation. `w_net` is the water available above the
    /// storage floor, net of spill (W - S). Grants each demanding outlet, in
    /// priority order ds_1..ds_4, the largest controlled release consistent
    /// with every outlet drawing only water above its own MOL. Greedy is
    /// exact here because the access constraints are nested intervals: each
    /// grant is checked against every band it draws from, and later grants
    /// subtract earlier ones.
    fn allocate_releases(&self, w_net: f64, demands: &[f64; MAX_DS_LINKS]) -> [f64; MAX_DS_LINKS] {
        let mut granted = [0.0; MAX_DS_LINKS];
        for i in 0..MAX_DS_LINKS {
            // Skips NaN demands too (a NaN forced release grants nothing).
            if demands[i].is_nan() || demands[i] <= 0.0 {
                continue;
            }
            let m_i = self.min_operating_volume[i];
            // Tightest of the nested caps at thresholds t <= m_i. The floor
            // (t = 0) is always a threshold: nothing releases water that
            // isn't there.
            let mut cap = f64::INFINITY;
            for t_idx in 0..=MAX_DS_LINKS {
                let t = if t_idx < MAX_DS_LINKS { self.min_operating_volume[t_idx] } else { 0.0 };
                if t > m_i {
                    continue;
                }
                let mut avail = (w_net - t).max(0.0);
                for (m_j, g_j) in self.min_operating_volume.iter().zip(granted.iter()).take(i) {
                    if *m_j >= t {
                        avail -= *g_j;
                    }
                }
                cap = cap.min(avail);
            }
            granted[i] = demands[i].min(cap).max(0.0);
        }
        granted
    }

    /// Equilibrium error at a candidate point (volume, area, spill): positive
    /// means the equilibrium lies at or below this volume. The outflow folds
    /// the MOL constraints in via `allocate_releases`, so the error is a
    /// continuous function of volume — no active-set switching.
    ///
    /// When no MOL cap binds at the point, the outflow is computed with the
    /// historical unconstrained expressions (`max(spill, ds_1 due) + ds_2..4
    /// dues`) so that models whose storages never hit a MOL constraint stay
    /// bit-identical with pre-redesign results; the two branches agree to FP
    /// rounding at the boundary.
    fn equilibrium_error(&self, v: f64, area: f64, spill: f64, v_working: f64, net_rain_mm: f64) -> f64 {
        if self.has_mol {
            let w = v_working + net_rain_mm * area;
            let demands = self.demands_at_spill(spill);
            let granted = self.allocate_releases(w - spill, &demands);
            if (0..MAX_DS_LINKS).any(|i| granted[i] < demands[i]) {
                let outflow = spill + granted.iter().sum::<f64>();
                return v - (w - outflow);
            }
        }
        let ds1_flow = spill.max(self.ds_release_due[0]);
        let ds234_orders: f64 = self.ds_release_due[1..].iter().sum();
        let total_outflow = ds1_flow + ds234_orders;
        let predicted = v_working + net_rain_mm * area - total_outflow;
        v - predicted
    }

    /// True when the MOL allocation grants every demand in full at this
    /// point, i.e. no access cap binds.
    fn allocation_unbound(&self, area: f64, spill: f64, v_working: f64, net_rain_mm: f64) -> bool {
        let w = v_working + net_rain_mm * area;
        let demands = self.demands_at_spill(spill);
        let granted = self.allocate_releases(w - spill, &demands);
        (0..MAX_DS_LINKS).all(|i| granted[i] >= demands[i])
    }

    // -------------------------------------------------------------------------
    // PROTOTYPE: rating-based outlet semantics
    // -------------------------------------------------------------------------
    // Each outlet has a level-dependent release capacity, evaluated at the
    // end-of-step volume like spill: no MOL => infinite; MOL only => infinite
    // strictly above the MOL volume, zero at or below it (a step); MOL +
    // capacity => `capacity` strictly above, zero at or below. Outlets couple
    // only implicitly through the solved level. The equilibrium error is
    // monotone with an upward JUMP at each MOL threshold; when the root falls
    // inside a jump (outlet on would overshoot below its MOL, off would stay
    // above), the volume parks exactly on the threshold and the jumping
    // outlet(s) release the residual, in priority order — the generalized
    // (Filippov) solution of the step. Continuous in all inputs and mass-
    // conserving by construction.

    /// Capacity from a canonical curve at volume v, approached from BELOW:
    /// the value exactly on a step volume is the lower one ("0 at the MOL").
    /// An empty curve is an unlimited outlet. Infinity is safe here: it only
    /// ever enters arithmetic as min(demand, capacity).
    fn cap_left(curve: &[(f64, f64)], v: f64) -> f64 {
        if curve.is_empty() {
            return f64::INFINITY;
        }
        let idx = curve.partition_point(|p| p.0 < v);
        if idx == curve.len() {
            return curve[idx - 1].1; // beyond the last point: flat
        }
        let (v1, c1) = curve[idx];
        if idx == 0 || v1 == v {
            // Before the first point (flat), or exactly on a point: the
            // first point at v carries the from-below value.
            return c1;
        }
        let (v0, c0) = curve[idx - 1];
        c0 + (c1 - c0) * (v - v0) / (v1 - v0)
    }

    /// Capacity from a canonical curve at volume v, approached from ABOVE —
    /// the "flowing" side of a step, used for jump sizes, parking residuals,
    /// and drain-to-floor releases.
    fn cap_right(curve: &[(f64, f64)], v: f64) -> f64 {
        if curve.is_empty() {
            return f64::INFINITY;
        }
        let idx = curve.partition_point(|p| p.0 <= v);
        if idx == 0 {
            return curve[0].1; // below the first point: flat
        }
        let (v0, c0) = curve[idx - 1];
        if idx == curve.len() || v0 == v {
            // Beyond the last point (flat), or exactly on a point: the last
            // point at v carries the from-above value.
            return c0;
        }
        let (v1, c1) = curve[idx];
        c0 + (c1 - c0) * (v - v0) / (v1 - v0)
    }

    /// Controlled release demand for outlet i given the spill (ds_1's outlet
    /// only tops the spill up to its release due). NaN/non-positive => 0.
    fn rating_demand(&self, i: usize, spill: f64) -> f64 {
        let d = if i == 0 {
            (self.ds_release_due[0] - spill).max(0.0)
        } else {
            self.ds_release_due[i]
        };
        if d.is_nan() || d <= 0.0 { 0.0 } else { d }
    }

    /// Controlled releases at a point: min(demand, capacity(v)) per outlet,
    /// with capacities taken as the limit from below (a step's lower value
    /// exactly on its threshold, i.e. that outlet shut).
    fn rating_releases_at(&self, v: f64, spill: f64) -> [f64; MAX_DS_LINKS] {
        let mut releases = [0.0; MAX_DS_LINKS];
        for (i, r) in releases.iter_mut().enumerate() {
            let d = self.rating_demand(i, spill);
            if d > 0.0 {
                *r = d.min(Self::cap_left(&self.cap_curves[i], v));
            }
        }
        releases
    }

    /// The size of the error jump at breakpoint volume t: how much more the
    /// outlets step up to just above t than they release at/below it.
    /// Zero at a breakpoint where every curve is continuous.
    fn rating_jump_at(&self, t: f64, spill: f64) -> f64 {
        let mut jump = 0.0;
        for i in 0..MAX_DS_LINKS {
            let curve = &self.cap_curves[i];
            if curve.is_empty() {
                continue;
            }
            let d = self.rating_demand(i, spill);
            if d > 0.0 {
                jump += (d.min(Self::cap_right(curve, t)) - d.min(Self::cap_left(curve, t))).max(0.0);
            }
        }
        jump
    }

    /// Attribution at a solved volume v inside (or beyond) segment `row`:
    /// releases from the rating at v, v_final closing the balance exactly.
    fn rating_attribution(&self, v: f64, row: usize, v_working: f64, net_rain_mm: f64)
        -> (f64, [f64; MAX_DS_LINKS], f64, usize, f64)
    {
        let area = self.dimensions.interpolate_row(row, VOLU, AREA, v);
        let spill = self.dimensions.interpolate_row(row, VOLU, SPIL, v).max(0.0);
        let w = v_working + net_rain_mm * area;
        let mut flows = self.rating_releases_at(v, spill);
        let v_final = (w - spill - flows.iter().sum::<f64>()).max(0.0);
        flows[0] += spill;
        (v_final, flows, spill, row, area)
    }

    /// The volume parks exactly on breakpoint t: outlets take their at-t
    /// (from-below) releases, and whoever steps up at t shares the residual
    /// in priority order, each capped by its from-above capacity — the
    /// generalized solution of the jump.
    fn rating_park_at(&self, t: f64, row: usize, v_working: f64, net_rain_mm: f64)
        -> (f64, [f64; MAX_DS_LINKS], f64, usize, f64)
    {
        let area = self.dimensions.interpolate_row(row, VOLU, AREA, t);
        let spill = self.dimensions.interpolate_row(row, VOLU, SPIL, t).max(0.0);
        let w = v_working + net_rain_mm * area;
        let mut flows = self.rating_releases_at(t, spill);
        let mut residual = (w - spill - t) - flows.iter().sum::<f64>();
        for (i, flow) in flows.iter_mut().enumerate() {
            if residual <= 0.0 {
                break;
            }
            let curve = &self.cap_curves[i];
            if curve.is_empty() {
                continue;
            }
            let d = self.rating_demand(i, spill);
            if d > 0.0 {
                let headroom = (d.min(Self::cap_right(curve, t)) - *flow).max(0.0);
                let give = headroom.min(residual);
                *flow += give;
                residual -= give;
            }
        }
        let v_final = (w - spill - flows.iter().sum::<f64>()).max(0.0);
        flows[0] += spill;
        (v_final, flows, spill, row, area)
    }

    /// Demand exceeds everything available: drain to the table floor, and cap
    /// releases by the water actually there, in priority order. Each outlet's
    /// capacity is taken just above the floor (it flowed while draining down).
    fn rating_floor(&self, v_working: f64, net_rain_mm: f64)
        -> (f64, [f64; MAX_DS_LINKS], f64, usize, f64)
    {
        let floor_vol = self.dimensions.get_value(0, VOLU);
        let area = self.dimensions.get_value(0, AREA);
        let spill = self.dimensions.get_value(0, SPIL).max(0.0);
        let w = v_working + net_rain_mm * area;
        let mut remaining = (w - spill).max(0.0);
        let mut flows = [0.0; MAX_DS_LINKS];
        for (i, f) in flows.iter_mut().enumerate() {
            let cap = Self::cap_right(&self.cap_curves[i], floor_vol);
            let give = self.rating_demand(i, spill).min(cap).min(remaining);
            *f = give;
            remaining -= give;
        }
        let v_final = remaining.max(0.0);
        flows[0] += spill;
        (v_final, flows, spill, 0, area)
    }

    /// The rating-semantics equilibrium solve: bracket the table segment with
    /// the (monotone, jumpy) rating error, then walk the segment's threshold
    /// pieces in order — bisecting a continuous piece that crosses zero, or
    /// parking on a threshold whose jump swallows the root.
    fn solve_rating(&self, v_working: f64, net_rain_mm: f64, nrows: usize, start_row: usize)
        -> (f64, [f64; MAX_DS_LINKS], f64, usize, f64)
    {
        // Hot-path precompute: sanitized demands, step capacities, and
        // effective thresholds (-inf for outlets with no MOL, so `v > m`
        // is unconditionally true for them). The fused outflow closure is
        // then a handful of compares and mins — no enum matching, no array
        // building, per error evaluation.
        let mut d = [0.0f64; MAX_DS_LINKS];
        for (di, due) in d.iter_mut().zip(self.ds_release_due.iter()) {
            *di = if due.is_nan() || *due <= 0.0 { 0.0 } else { *due };
        }
        let curves = &self.cap_curves;
        let outflow_at = |v: f64, spill: f64| -> f64 {
            let d1 = (d[0] - spill).max(0.0);
            let mut o = spill;
            o += d1.min(Self::cap_left(&curves[0], v));
            o += d[1].min(Self::cap_left(&curves[1], v));
            o += d[2].min(Self::cap_left(&curves[2], v));
            o += d[3].min(Self::cap_left(&curves[3], v));
            o
        };
        let compute_error = |row: usize| -> f64 {
            let v = self.dimensions.get_value(row, VOLU);
            let area = self.dimensions.get_value(row, AREA);
            let spill = self.dimensions.get_value(row, SPIL).max(0.0);
            v - (v_working + net_rain_mm * area - outflow_at(v, spill))
        };

        // Exponential expansion + bisection over rows (same pattern as the
        // main solver).
        let start = start_row.min(nrows - 1);
        let error_start = compute_error(start);
        let (mut lo, mut hi, mut error_lo, mut error_hi) = if error_start < 0.0 {
            let mut lo = start;
            let mut error_lo = error_start;
            let mut step = 1;
            let mut hi = (start + step).min(nrows - 1);
            let mut error_hi = compute_error(hi);
            while error_hi < 0.0 && hi < nrows - 1 {
                lo = hi;
                error_lo = error_hi;
                step *= 2;
                hi = (hi + step).min(nrows - 1);
                error_hi = compute_error(hi);
            }
            (lo, hi, error_lo, error_hi)
        } else {
            let mut hi = start;
            let mut error_hi = error_start;
            let mut step = 1;
            let mut lo = start.saturating_sub(step);
            let mut error_lo = compute_error(lo);
            while error_lo >= 0.0 && lo > 0 {
                hi = lo;
                error_hi = error_lo;
                step *= 2;
                lo = lo.saturating_sub(step);
                error_lo = compute_error(lo);
            }
            (lo, hi, error_lo, error_hi)
        };
        while hi - lo > 1 {
            let mid = lo + (hi - lo) / 2;
            let error_mid = compute_error(mid);
            if error_mid < 0.0 {
                lo = mid;
                error_lo = error_mid;
            } else {
                hi = mid;
                error_hi = error_mid;
            }
        }
        let istop = hi;
        if istop == 0 {
            return self.rating_floor(v_working, net_rain_mm);
        }
        let row = istop - 1;
        let error_prev = if row == lo { error_lo } else { compute_error(row) };
        if error_prev >= 0.0 {
            return self.rating_floor(v_working, net_rain_mm);
        }

        let v_lo = self.dimensions.get_value(row, VOLU);
        let v_hi = self.dimensions.get_value(istop, VOLU);
        let area_lo = self.dimensions.get_value(row, AREA);
        let area_hi = self.dimensions.get_value(istop, AREA);
        let spill_lo = self.dimensions.get_value(row, SPIL).max(0.0);
        let spill_hi = self.dimensions.get_value(istop, SPIL).max(0.0);
        let lerp = |v: f64| -> (f64, f64) {
            let x = (v - v_lo) / (v_hi - v_lo);
            (area_lo + (area_hi - area_lo) * x, (spill_lo + (spill_hi - spill_lo) * x).max(0.0))
        };
        let err_at = |v: f64| -> f64 {
            let (area, spill) = lerp(v);
            v - (v_working + net_rain_mm * area - outflow_at(v, spill))
        };
        let refine = |mut a: f64, mut b: f64, mut e_a: f64, mut e_b: f64| -> f64 {
            // Illinois false position. Preconditions: e_a < 0 <= e_b, error
            // continuous on (a, b). Within a piece the error is linear except
            // for ds_1's spill-topping kinks, so this lands exactly in one
            // step on the common path and converges superlinearly otherwise.
            let mut side = 0i8;
            for _ in 0..32 {
                let denom = e_b - e_a;
                let mut m = if denom > 0.0 { a + (b - a) * (-e_a / denom) } else { 0.5 * (a + b) };
                if !(m > a && m < b) {
                    m = 0.5 * (a + b);
                }
                let e_m = err_at(m);
                // The first secant is exact (to FP) on a linear piece; accept
                // any residual at rounding scale rather than creeping on it.
                if e_m.abs() <= 1e-12 * (1.0 + b.abs()) || b - a <= 1e-12 * (1.0 + b.abs()) {
                    return m;
                }
                if e_m < 0.0 {
                    a = m;
                    e_a = e_m;
                    if side == -1 { e_b *= 0.5; }
                    side = -1;
                } else {
                    b = m;
                    e_b = e_m;
                    if side == 1 { e_a *= 0.5; }
                    side = 1;
                }
            }
            0.5 * (a + b)
        };

        // Exact solve on one piece (no curve breakpoints inside, so every
        // capacity is linear across it). With all capacities CONSTANT on the
        // piece — the MOL and MOL+capacity forms — the outflow has at most
        // three linear branches in v (spill covers ds_1's due / the outlet
        // tops up to the due / the outlet is capacity-bound): solve each
        // exactly and keep the self-consistent one. This is what keeps steep
        // spill segments (1e9 ML/ML kinks) closed-form instead of iterated.
        // With a capacity RAMP on the piece (rating tables), the outflow is
        // still linear unless a min() switches branch inside: solve from the
        // endpoint outflows and verify, falling back to Illinois otherwise.
        let solve_piece = |a: f64, b: f64, e_a: f64, e_b: f64| -> f64 {
            let (area_a, spill_a) = lerp(a);
            let (area_b, spill_b) = lerp(b);
            let solve_lin = |o_a: f64, o_b: f64| -> Option<f64> {
                let e1 = a - (v_working + net_rain_mm * area_a - o_a);
                let e2 = b - (v_working + net_rain_mm * area_b - o_b);
                let dd = e1 - e2;
                if e1 >= 0.0 || dd >= 0.0 {
                    return None;
                }
                Some(a + (b - a) * (e1 / dd))
            };
            // Per-outlet capacity endpoints from the interior side of the
            // piece (from above at a, from below at b).
            let mut cap_a = [0.0f64; MAX_DS_LINKS];
            let mut cap_b = [0.0f64; MAX_DS_LINKS];
            let mut caps_constant = true;
            for i in 0..MAX_DS_LINKS {
                cap_a[i] = Self::cap_right(&curves[i], a);
                cap_b[i] = Self::cap_left(&curves[i], b);
                if cap_a[i] != cap_b[i] {
                    caps_constant = false;
                }
            }
            if !caps_constant {
                // A capacity ramps across the piece. Outflow from endpoint
                // evaluations is exact unless a min() crosses inside; verify.
                let o_a = spill_a
                    + (d[0] - spill_a).max(0.0).min(cap_a[0])
                    + d[1].min(cap_a[1]) + d[2].min(cap_a[2]) + d[3].min(cap_a[3]);
                let o_b = spill_b
                    + (d[0] - spill_b).max(0.0).min(cap_b[0])
                    + d[1].min(cap_b[1]) + d[2].min(cap_b[2]) + d[3].min(cap_b[3]);
                if let Some(v) = solve_lin(o_a, o_b) {
                    if err_at(v).abs() <= 1e-9 * (1.0 + v.abs()) {
                        return v;
                    }
                }
                return refine(a, b, e_a, e_b);
            }
            let mut k = 0.0;
            for i in 1..MAX_DS_LINKS {
                k += d[i].min(cap_a[i]);
            }
            let c0 = cap_a[0];
            let d1 = d[0];
            let spill_at_v = |v: f64| spill_a + (spill_b - spill_a) * (v - a) / (b - a);
            // Branch 1: spill covers ds_1's due (outlet closed): o = s + k.
            // With c0 == 0 the outlet contributes nothing regardless, so the
            // branch condition is unconditional.
            if let Some(v) = solve_lin(spill_a + k, spill_b + k) {
                if c0 == 0.0 || spill_at_v(v) >= d1 {
                    return v;
                }
            }
            // Branch 2: the outlet tops spill up to the due: o = d1 + k.
            if let Some(v) = solve_lin(d1 + k, d1 + k) {
                let s = spill_at_v(v);
                if s <= d1 && d1 - s <= c0 {
                    return v;
                }
            }
            // Branch 3: the outlet is capacity-bound: o = s + c0 + k.
            if c0.is_finite() && c0 > 0.0 {
                if let Some(v) = solve_lin(spill_a + c0 + k, spill_b + c0 + k) {
                    if d1 - spill_at_v(v) >= c0 {
                        return v;
                    }
                }
            }
            refine(a, b, e_a, e_b)
        };

        // Curve breakpoints inside [v_lo, v_hi], ascending: piece boundaries
        // for the walk (steps carry jumps; ramps just kink). Capped at a
        // fixed count as a runaway guard — a curve dense enough to overflow
        // it inside ONE dimension segment degrades to the verify-and-refine
        // path, which stays mass-conserving.
        const MAX_BOUNDS: usize = 16;
        let mut thresholds = [f64::INFINITY; MAX_BOUNDS];
        let mut n_thr = 0;
        for curve in curves.iter() {
            for p in curve.iter() {
                if p.0 >= v_lo && p.0 <= v_hi && n_thr < MAX_BOUNDS {
                    thresholds[n_thr] = p.0;
                    n_thr += 1;
                }
            }
        }
        if n_thr > 1 {
            thresholds[..n_thr].sort_by(|a, b| a.partial_cmp(b).unwrap());
        }

        // Walk the pieces: from each position with a (right-limit) error still
        // negative, either the next continuous piece crosses zero (bisect it),
        // or the next threshold's jump swallows the root (park on it).
        let mut pos = v_lo;
        let mut e_pos = error_prev;
        let mut last_thr = f64::NAN;
        for &t in thresholds[..n_thr].iter() {
            if t == last_thr {
                continue; // duplicate threshold: its jump was summed already
            }
            last_thr = t;
            if t > pos {
                let e_left = err_at(t);
                if e_pos < 0.0 && e_left >= 0.0 {
                    return self.rating_attribution(solve_piece(pos, t, e_pos, e_left), row, v_working, net_rain_mm);
                }
                pos = t;
                e_pos = e_left;
            }
            if e_pos < 0.0 {
                let (_, spill_t) = lerp(t);
                let jump = self.rating_jump_at(t, spill_t);
                if e_pos + jump >= 0.0 {
                    return self.rating_park_at(t, row, v_working, net_rain_mm);
                }
                e_pos += jump;
            }
        }
        if pos < v_hi && e_pos < 0.0 && error_hi >= 0.0 {
            return self.rating_attribution(solve_piece(pos, v_hi, e_pos, error_hi), row, v_working, net_rain_mm);
        }

        // Ceiling case (error still negative at the table top): the region
        // above is linear with all thresholds' outlets flowing — extrapolate,
        // guarding against divergence like the main solver.
        let v_probe = v_hi + (v_hi - v_lo).max(1.0);
        let e_probe = err_at(v_probe);
        let start_v = pos.max(v_hi);
        let e_start = if pos >= v_hi { e_pos } else { error_hi };
        if e_start < 0.0 && e_probe > e_start {
            let x = -e_start / (e_probe - e_start);
            let v = start_v + (v_probe - start_v) * x;
            return self.rating_attribution(v, row, v_working, net_rain_mm);
        }
        self.rating_attribution(v_hi, row, v_working, net_rain_mm)
    }

    /// Solves the backward Euler equation for equilibrium volume in flow phase.
    ///
    /// One monotone solve: the MOL access constraints are folded into the
    /// outflow function, so the equilibrium error is continuous in volume and
    /// a single bracket-and-refine pass finds the solution — no active outlet
    /// sets, no threshold clamping. The final volume is recomputed from the
    /// allocated flows so mass balance closes by construction.
    ///
    /// PROTOTYPE: storages with MOL outlets route to the rating-semantics
    /// solver above; storages without stay on the (bit-identical) legacy path.
    ///
    /// Returns (final_volume, ds_flows[4], spill, table_row, area)
    fn solve_backward_euler(
        &mut self,
        v_initial: f64,
        net_rain_mm: f64,
        data_cache: &mut DataCache,
    ) -> (f64, [f64; MAX_DS_LINKS], f64, usize, f64) {
        let nrows = self.dimensions.nrows();

        // Compute all release demands once (orders or forced releases)
        for i in 0..MAX_DS_LINKS {
            self.ds_release_due[i] = Self::check_forced_release(
                data_cache,
                &self.ds_force_release_input[i],
                self.ds_orders_due[i]
            );
        }

        if self.has_mol {
            return self.solve_rating(v_initial, net_rain_mm, nrows, self.previous_istop);
        }

        let (v_solved, row, legacy) = self.solve_equilibrium(v_initial, net_rain_mm, nrows, self.previous_istop);

        // Evaluate the solution point and allocate.
        let area = self.dimensions.interpolate_row(row, VOLU, AREA, v_solved);
        let spill = self.dimensions.interpolate_row(row, VOLU, SPIL, v_solved).max(0.0);

        let mut ds_flows = [0.0; MAX_DS_LINKS];
        let v_final;
        if legacy {
            // No MOL cap binds: attribute flows with the historical
            // unconstrained expressions so these days stay bit-identical with
            // pre-redesign results (the values equal the granted allocation
            // to FP rounding). v_final is the solved equilibrium volume.
            v_final = v_solved;
            if spill >= self.ds_release_due[0] {
                // Spill satisfies ds_1's release due: attribute from mass
                // balance — the interpolated spill can carry large FP error
                // when the volume sits on a steep spill curve, whereas
                // v_initial - v_final stays accurate.
                let mut remaining = (v_initial + net_rain_mm * area - v_final).max(0.0);
                let ds1_flow = spill.min(remaining);
                ds_flows[0] = ds1_flow;
                remaining -= ds1_flow;
                for (flow, due) in ds_flows.iter_mut().zip(self.ds_release_due.iter()).skip(1) {
                    if *due > 0.0 && remaining > EPSILON {
                        *flow = due.min(remaining);
                        remaining -= *flow;
                    }
                }
            } else {
                // Order-limited: release the dues exactly (no FP noise from
                // mass balance).
                ds_flows[0] = self.ds_release_due[0].max(spill);
                for (flow, due) in ds_flows.iter_mut().zip(self.ds_release_due.iter()).skip(1) {
                    if *due > 0.0 {
                        *flow = *due;
                    }
                }
            }
        } else {
            // A MOL cap binds (or the solution was clamped to the table
            // floor/ceiling): the allocation is authoritative, and v_final is
            // defined as W - S - sum(granted) so volume + flows reconcile
            // exactly with the recorded rain/evap/seep volumes (which use
            // this same area) — mass balance closes by construction.
            let w = v_initial + net_rain_mm * area;
            let demands = self.demands_at_spill(spill);
            let granted = self.allocate_releases(w - spill, &demands);
            ds_flows = granted;
            ds_flows[0] += spill;
            v_final = (w - spill - granted.iter().sum::<f64>()).max(0.0);
        }

        (v_final, ds_flows, spill, row, area)
    }

    /// Finds the equilibrium volume: v = W(v) - O(v), with W the available
    /// water (v_working + net_rain*area(v)) and O the outflow (spill plus the
    /// MOL-capped allocation of the release demands).
    /// Uses exponential expansion + bisection to find the table row, then
    /// refines within the segment: exactly (linear solve, including the
    /// max(spill, order) kink on ds_1) when no MOL cap binds there, by
    /// in-segment bisection of the continuous error otherwise.
    /// Returns (volume, row, legacy): `row` is the lower row of the solution
    /// segment; `legacy` is true when the volume is the historical
    /// unconstrained solution (no MOL cap binding), telling the caller to
    /// attribute flows with the historical expressions, and false when the
    /// MOL allocation is authoritative (including the floor and clamped
    /// fallbacks, whose flows must be capped by available water).
    fn solve_equilibrium(
        &self,
        v_working: f64,
        net_rain_mm: f64,
        nrows: usize,
        start_row: usize,
    ) -> (f64, usize, bool) {
        // Error function: positive means solution is at or below this row
        let compute_error = |row: usize| -> f64 {
            self.equilibrium_error(
                self.dimensions.get_value(row, VOLU),
                self.dimensions.get_value(row, AREA),
                self.dimensions.get_value(row, SPIL).max(0.0),
                v_working,
                net_rain_mm,
            )
        };

        // Exponential expansion from start_row hint
        let start = start_row.min(nrows - 1);
        let error_start = compute_error(start);

        let (mut lo, mut hi, mut error_lo, mut error_hi) = if error_start < 0.0 {
            // Solution is above start row - expand upward
            let mut lo = start;
            let mut error_lo = error_start;
            let mut step = 1;
            let mut hi = (start + step).min(nrows - 1);
            let mut error_hi = compute_error(hi);
            while error_hi < 0.0 && hi < nrows - 1 {
                lo = hi;
                error_lo = error_hi;
                step *= 2;
                hi = (hi + step).min(nrows - 1);
                error_hi = compute_error(hi);
            }
            (lo, hi, error_lo, error_hi)
        } else {
            // Solution is at or below start row - expand downward
            let mut hi = start;
            let mut error_hi = error_start;
            let mut step = 1;
            let mut lo = start.saturating_sub(step);
            let mut error_lo = compute_error(lo);
            while error_lo >= 0.0 && lo > 0 {
                hi = lo;
                error_hi = error_lo;
                step *= 2;
                lo = lo.saturating_sub(step);
                error_lo = compute_error(lo);
            }
            (lo, hi, error_lo, error_hi)
        };

        // Bisect to find exact bracket, caching error values
        while hi - lo > 1 {
            let mid = lo + (hi - lo) / 2;
            let error_mid = compute_error(mid);
            if error_mid < 0.0 {
                lo = mid;
                error_lo = error_mid;
            } else {
                hi = mid;
                error_hi = error_mid;
            }
        }

        let istop = hi;

        // Handle floor case (solution at or below row 0)
        if istop == 0 {
            return (self.dimensions.get_value(0, VOLU), 0, false);
        }
        // Ceiling case (error_hi < 0): allow extrapolation beyond table max
        // by falling through to normal interpolation - x > 1.0 extrapolates

        // Interpolate between rows using cached errors where possible.
        // When lo == hi (ceiling case: solution beyond table max), row != lo
        // so the cached error_lo is at the wrong position — recompute it.
        let row = istop - 1;
        let error_prev = if row == lo { error_lo } else { compute_error(row) };

        // Solution at or below the table floor. Because the allocation caps
        // outflow at available water, error(row 0) <= 0 always: a demanded
        // outflow exceeding everything the storage holds lands here with
        // error_prev == 0 exactly, and drains to the floor. Kept as >= 0 as
        // defence in depth against degenerate tables where a row pair
        // satisfies dVol ≈ net_rain·dArea (Talgai Weir rows 0-1 with net rain
        // 0.1 mm), which makes the interpolation below divide by a difference
        // of two near-equal errors (see test_storage_floor_blowup).
        if error_prev >= 0.0 {
            return (self.dimensions.get_value(0, VOLU), 0, false);
        }

        let v_lo = self.dimensions.get_value(row, VOLU);
        let v_hi = self.dimensions.get_value(istop, VOLU);
        let area_lo = self.dimensions.get_value(row, AREA);
        let area_hi = self.dimensions.get_value(istop, AREA);
        let spill_lo = self.dimensions.get_value(row, SPIL).max(0.0);
        let spill_hi = self.dimensions.get_value(istop, SPIL).max(0.0);

        // First attempt the historical unconstrained solve, ignoring MOL
        // caps: outflow = max(spill, ds_1 release due) + ds_2..ds_4 release
        // dues. Its candidate is accepted only when no cap binds at the
        // candidate point, which keeps every day a MOL never touches
        // bit-identical with pre-redesign results (expressions below are kept
        // verbatim from the old solver on purpose). Checking caps at the
        // candidate — not at the segment endpoints — matters: a row endpoint
        // deep in a steep spill curve can look capped even though the
        // solution point is not.
        if let Some(v) = self.solve_segment_unconstrained(
            v_working, net_rain_mm, v_lo, v_hi, area_lo, area_hi, spill_lo, spill_hi,
        ) {
            if !self.has_mol {
                return (v, row, true);
            }
            let area = self.dimensions.interpolate_row(row, VOLU, AREA, v);
            let spill = self.dimensions.interpolate_row(row, VOLU, SPIL, v).max(0.0);
            if self.allocation_unbound(area, spill, v_working, net_rain_mm) {
                return (v, row, true);
            }
        }

        // A MOL access cap binds (or the unconstrained solve is degenerate):
        // bisect the continuous capped error inside the segment — error_prev
        // < 0 and error_hi >= 0 guarantee a root.
        if error_hi >= 0.0 {
            let (mut a, mut b) = (v_lo, v_hi);
            for _ in 0..64 {
                let mid = 0.5 * (a + b);
                let x = (mid - v_lo) / (v_hi - v_lo);
                let area = area_lo + (area_hi - area_lo) * x;
                let spill = (spill_lo + (spill_hi - spill_lo) * x).max(0.0);
                if self.equilibrium_error(mid, area, spill, v_working, net_rain_mm) < 0.0 {
                    a = mid;
                } else {
                    b = mid;
                }
                if b - a <= 1e-12 * (1.0 + b.abs()) {
                    break;
                }
            }
            return (0.5 * (a + b), row, false);
        }

        // Ceiling case (error_hi < 0, no bracket) with a cap binding at the
        // extrapolated candidate — pathological (extrapolation means a water
        // surplus). Hand the candidate, or failing that the top row, to the
        // allocation so mass still cannot leak.
        if let Some(v) = self.solve_segment_unconstrained(
            v_working, net_rain_mm, v_lo, v_hi, area_lo, area_hi, spill_lo, spill_hi,
        ) {
            return (v, row, false);
        }
        (self.dimensions.get_value(istop, VOLU), row, false)
    }

    /// The historical unconstrained within-segment solve, kept expression-for-
    /// expression compatible with the pre-MOL-redesign solver: straight linear
    /// interpolation of the error, with exact handling of the max(spill, ds_1
    /// release due) kink. Returns None when the segment is degenerate (errors
    /// do not converge downhill), mirroring the old floor/ceiling guards.
    #[allow(clippy::too_many_arguments)]
    fn solve_segment_unconstrained(
        &self,
        v_working: f64,
        net_rain_mm: f64,
        v_lo: f64,
        v_hi: f64,
        area_lo: f64,
        area_hi: f64,
        spill_lo: f64,
        spill_hi: f64,
    ) -> Option<f64> {
        let ds1_required_flow = self.ds_release_due[0];
        let ds234_orders: f64 = self.ds_release_due[1..].iter().sum();

        // The outflow term max(spill(v), ds1_required_flow) kinks at the volume
        // where the interpolated spill crosses the required flow. If that
        // crossing lies on this segment, the error is piecewise linear and
        // interpolating straight across finds a volume inconsistent with the
        // outflow actually released — the gap leaks out of the mass balance
        // (order-limited days just above FSL at Proserpine's Peter Faust Dam,
        // ~45 ML/d). Solve each linear branch exactly and keep the
        // self-consistent one; both are exact because area and spill are linear
        // on the segment (and on its extrapolation beyond the top row).
        if ds1_required_flow > spill_lo && spill_hi > spill_lo {
            let spill_at = |v: f64| spill_lo + (spill_hi - spill_lo) * (v - v_lo) / (v_hi - v_lo);
            // Solve v = v_working + net_rain*area(v) - outflow(v) - ds234 with
            // outflow linear between the given endpoint values. None when the
            // branch has no downhill crossing (mirrors the guards above).
            let solve_branch = |outflow_lo: f64, outflow_hi: f64| -> Option<f64> {
                let e_lo = v_lo - (v_working + net_rain_mm * area_lo - outflow_lo - ds234_orders);
                let e_hi = v_hi - (v_working + net_rain_mm * area_hi - outflow_hi - ds234_orders);
                let d = e_lo - e_hi;
                if e_lo >= 0.0 || d >= 0.0 {
                    return None;
                }
                Some(v_lo + (v_hi - v_lo) * (e_lo / d))
            };
            // Below the crossing the required flow governs: outflow is constant.
            if let Some(v) = solve_branch(ds1_required_flow, ds1_required_flow) {
                if spill_at(v) <= ds1_required_flow {
                    return Some(v);
                }
            }
            // Above the crossing the spill governs: outflow follows the spill
            // line. Computed with the historical spill-limited-pass
            // expressions (spill + orders, straight interpolation);
            // algebraically this equals solve_branch(spill_lo, spill_hi),
            // differing only in FP association.
            {
                let total_lo = spill_lo + ds234_orders;
                let total_hi = spill_hi + ds234_orders;
                let e_lo = v_lo - (v_working + net_rain_mm * area_lo - total_lo);
                let e_hi = v_hi - (v_working + net_rain_mm * area_hi - total_hi);
                let d = e_lo - e_hi;
                if e_lo < 0.0 && d < 0.0 {
                    let v = v_lo + (v_hi - v_lo) * (e_lo / d);
                    if spill_at(v) >= ds1_required_flow {
                        return Some(v);
                    }
                }
            }
            // Neither branch self-consistent (degenerate segment): fall through
            // to the straight interpolation rather than inventing a solution.
        }

        // Straight interpolation with the historical error expression: the
        // outflow is the same on both endpoints' side of any kink, so the
        // error is linear across the segment.
        let compute_error_old = |v: f64, area: f64, spill: f64| -> f64 {
            let ds1_flow = spill.max(ds1_required_flow);
            let total_outflow = ds1_flow + ds234_orders;
            let predicted = v_working + net_rain_mm * area - total_outflow;
            v - predicted
        };
        let error_prev = compute_error_old(v_lo, area_lo, spill_lo);
        let error_hi = compute_error_old(v_hi, area_hi, spill_hi);
        // A genuine bracket (error_hi >= 0) gives x in (0, 1]. In the ceiling
        // case (error_hi < 0) x > 1 extrapolates beyond the table, which is
        // legitimate only while the errors still converge (error_hi >
        // error_prev). Anything else is degenerate here: the caller falls
        // back to the capped bisection or the top row.
        let denom = error_prev - error_hi;
        if error_prev >= 0.0 || denom >= 0.0 {
            return None;
        }
        let x = error_prev / denom;
        Some(v_lo + (v_hi - v_lo) * x)
    }

    /// Sets `self.exists_bool` for this timestep, and records the driving value.
    ///
    /// Called once per timestep, from the flow phase. `exists` deliberately does not
    /// reach the order phase: `order_through` is baked into the ordering solution at
    /// setup (it sizes the order buffers), so a storage cannot switch between
    /// supplying and ordering-through part-way through a run. A storage that does not
    /// exist still orders exactly as it would otherwise; it simply cannot operate in
    /// any controlled way, because the flow phase passes everything straight through.
    fn check_if_exists(&mut self, data_cache: &mut DataCache) {
        // Not configured => the storage always exists.
        let exists_val = if self.exists_configured { self.exists.get_value(data_cache) } else { 1.0 };
        self.exists_bool = !(exists_val.is_nan() || exists_val == 0.0);
        if let Some(idx) = self.recorder_idx_exists {
            data_cache.add_value_at_index(idx, exists_val);
        }
    }
}

impl Node for StorageNode {

    fn initialise(&mut self, data_cache: &mut DataCache, _account_manager: &mut AccountManager) -> Result<(),String> {
        // Initialize only internal state
        self.mbal = 0.0;
        self.usflow = 0.0;
        self.dsflow = 0.0;
        self.ds_flows = [0.0; MAX_DS_LINKS];
        self.volume = self.vol_initial;
        self.level = 0.0;
        self.rain_vol = 0.0;
        self.evap_vol = 0.0;
        self.seep_vol = 0.0;
        self.pond_diversion = 0.0;
        self.spill = 0.0;
        self.previous_istop = 0;
        self.exists_bool = true;

        // Checks
        if self.dimensions.nrows() < 2 {
            let message = format!("Error in node '{}'. Storage dimension table must have at least 2 rows.", self.name);
            return Err(message);
        }
        if self.dimensions.get_value(0, VOLU) != 0_f64 {
            let message = format!("Error in node '{}'. Storage dimension table must begin with volume=0.", self.name);
            return Err(message);
        }
        if self.dimensions.get_value(0, AREA) != 0_f64 {
            let message = format!("Error in node '{}'. Storage dimension table must begin with area=0.", self.name);
            return Err(message);
        }

        // Validate that volumes are strictly increasing (required for solver interpolation)
        for i in 1..self.dimensions.nrows() {
            if self.dimensions.get_value(i, VOLU) <= self.dimensions.get_value(i - 1, VOLU) {
                let message = format!(
                    "Error in node '{}'. Storage dimension table volumes must be strictly increasing (violation at row {}).",
                    self.name, i + 1
                );
                return Err(message);
            }
        }

        // Build each outlet's canonical capacity-vs-volume curve from its
        // definition (levels converted through the dimensions table). The
        // MOL forms become two-point steps; a MOL at the table floor with
        // unlimited capacity is no constraint at all and stays empty, so
        // such storages keep the legacy solver path.
        //
        // Every outlet level must lie inside the dimensions table's level
        // range: an out-of-range MOL previously interpolated to NaN and the
        // constraint silently vanished — a loud config error is the honest
        // behaviour.
        let lev_range = (
            self.dimensions.get_value(0, LEVL),
            self.dimensions.get_value(self.dimensions.nrows() - 1, LEVL),
        );
        let node_name = self.name.clone();
        let check_level = move |lev: f64, i: usize| -> Result<(), String> {
            if lev.is_nan() || lev < lev_range.0 || lev > lev_range.1 {
                return Err(format!(
                    "Error in node '{}'. ds_{}_outlet level {} lies outside the \
                     dimensions table's level range [{}, {}].",
                    node_name, i + 1, lev, lev_range.0, lev_range.1
                ));
            }
            Ok(())
        };
        for i in 0..MAX_DS_LINKS {
            let curve: Vec<(f64, f64)> = match &self.outlet_definition[i] {
                OutletDefinition::None => Vec::new(),
                OutletDefinition::OutletWithMOL(level) => {
                    check_level(*level, i)?;
                    let m = self.dimensions.interpolate(LEVL, VOLU, *level);
                    self.min_operating_volume[i] = m;
                    if m > 0.0 { vec![(m, 0.0), (m, f64::INFINITY)] } else { Vec::new() }
                }
                OutletDefinition::OutletWithMOLAndCapacity(level, capacity) => {
                    check_level(*level, i)?;
                    let m = self.dimensions.interpolate(LEVL, VOLU, *level);
                    self.min_operating_volume[i] = m;
                    vec![(m.max(0.0), 0.0), (m.max(0.0), *capacity)]
                }
                OutletDefinition::OutletWithRatingTable(points) => {
                    let nrows = self.dimensions.nrows();
                    let lev_min = self.dimensions.get_value(0, LEVL);
                    let lev_max = self.dimensions.get_value(nrows - 1, LEVL);
                    let mut curve = Vec::with_capacity(points.len());
                    for (idx, &(lev, cap)) in points.iter().enumerate() {
                        if lev < lev_min || lev > lev_max {
                            return Err(format!(
                                "Error in node '{}'. ds_{}_outlet rating level {} lies outside the \
                                 dimensions table's level range [{}, {}].",
                                self.name, i + 1, lev, lev_min, lev_max
                            ));
                        }
                        // Capacity is linear in LEVEL between rating points;
                        // insert a breakpoint at every dimension row the
                        // segment crosses so it is exactly linear in VOLUME
                        // between consecutive curve points.
                        if idx > 0 {
                            let (lev0, cap0) = points[idx - 1];
                            if lev > lev0 {
                                for r in 0..nrows {
                                    let lr = self.dimensions.get_value(r, LEVL);
                                    if lr > lev0 && lr < lev {
                                        let c = cap0 + (cap - cap0) * (lr - lev0) / (lev - lev0);
                                        curve.push((self.dimensions.get_value(r, VOLU), c));
                                    }
                                }
                            }
                        }
                        curve.push((self.dimensions.interpolate(LEVL, VOLU, lev), cap));
                    }
                    curve
                }
            };
            self.cap_curves[i] = curve;
        }
        self.has_mol = self.cap_curves.iter().any(|c| !c.is_empty());

        // Check if the storage is targeting a level
        self.has_target_level = !matches!(&self.target_level, DynamicInput::None { .. });

        // Check once whether an "exists" input was configured (default: storage always exists)
        self.exists_configured = !matches!(&self.exists, DynamicInput::None { .. });

        // Initialize result recorders
        self.recorder_idx_usflow = recorder(data_cache, &self.name, "usflow");
        self.recorder_idx_volume = recorder(data_cache, &self.name, "volume");
        self.recorder_idx_level = recorder(data_cache, &self.name, "level");
        self.recorder_idx_target_level = recorder(data_cache, &self.name, "target_level");
        self.recorder_idx_area = recorder(data_cache, &self.name, "area");
        self.recorder_idx_seep_megs = recorder(data_cache, &self.name, "seep_vol");
        self.recorder_idx_rain_megs = recorder(data_cache, &self.name, "rain_vol");
        self.recorder_idx_evap_megs = recorder(data_cache, &self.name, "evap_vol");
        self.recorder_idx_rain_mm = recorder(data_cache, &self.name, "rain");
        self.recorder_idx_evap_mm = recorder(data_cache, &self.name, "evap");
        self.recorder_idx_seep_mm = recorder(data_cache, &self.name, "seep");
        self.recorder_idx_pond_diversion = recorder(data_cache, &self.name, "pond_diversion");
        self.recorder_idx_pond_demand = recorder(data_cache, &self.name, "pond_demand");
        self.recorder_idx_dsflow = recorder(data_cache, &self.name, "dsflow");
        for i in 0..MAX_DS_LINKS {
            let n = i + 1;
            self.recorder_idx_ds[i] = recorder(data_cache, &self.name, &format!("ds_{n}"));
            self.recorder_idx_ds_order[i] = recorder(data_cache, &self.name, &format!("ds_{n}_order"));
            self.recorder_idx_ds_order_due[i] = recorder(data_cache, &self.name, &format!("ds_{n}_order_due"));
            self.recorder_idx_ds_outlet[i] = recorder(data_cache, &self.name, &format!("ds_{n}_outlet"));
            self.recorder_idx_ds_spill[i] = recorder(data_cache, &self.name, &format!("ds_{n}_spill"));
            self.recorder_idx_ds_force_release[i] = recorder(data_cache, &self.name, &format!("ds_{n}_force_release"));
        }

        self.recorder_idx_exists = recorder(data_cache, &self.name, "exists");
        Ok(())
    }

    fn get_name(&self) -> &str { &self.name }

    fn run_order_phase(&mut self, data_cache: &mut DataCache, _account_manager: &mut AccountManager) {

        // Record downstream orders, roll each outlet's order buffer, record
        // what is due today.
        for i in 0..MAX_DS_LINKS {
            if let Some(idx) = self.recorder_idx_ds_order[i] {
                data_cache.add_value_at_index(idx, self.ds_orders[i]);
            }
            self.ds_orders_due[i] = self.ds_order_buffers[i].push(self.ds_orders[i]);
            if let Some(idx) = self.recorder_idx_ds_order_due[i] {
                data_cache.add_value_at_index(idx, self.ds_orders_due[i]);
            }
        }

        // Calculate orders. Note `exists` plays no part here: see `check_if_exists`.
        if self.order_through {
            //
            // 'Order through' means (1) the ordering system does not consider this storage
            // to be a supply, (2) total orders are propagated upstream without adjustment.
            self.us_orders = self.ds_orders.iter().sum();
            //
        } else if self.has_target_level {
            //
            // 'Target level' works like this:
            // 1) calculate the target volume
            // 2) forecast our future volume assuming:
            //    - all previous orders will arrive. (Previous orders are stored in the
            //        target_level_order_buffer so we can work out what is en route. A buffer of
            //        zero length means there is no travel time. The order we place today will
            //        arrive today and nothing is ever en route.)
            //    - no rainfall, evap, or seepage
            //    - no additional inflows will arrive
            //    - today's downstream orders will be released
            //    - no subsequent releases will be made
            // 3) order what is required to reach our target volume
            let target_level = self.target_level.get_value(data_cache);
            if let Some(idx) = self.recorder_idx_target_level {
                data_cache.add_value_at_index(idx, target_level);
            }
            // The level is below the target level. We need convert this to a volume and
            // compare it with our forecast volume.
            let target_volume = self.dimensions.interpolate_or_extrapolate(LEVL, VOLU, target_level);
            //TODO: it could be possible to keep a running forecast inflow here, add new orders
            // to it and subtract orders as they pop out of the buffer (rather than summing the
            // order buffer every time). It may be noticeable for long travel times.
            let inflows = self.target_level_order_buffer.sum();
            let known_usage: f64 = self.ds_orders_due.iter().sum();
            let forecast_volume = self.volume + inflows - known_usage;
            self.us_orders = (target_volume - forecast_volume).max(0.0);
            self.target_level_order_buffer.push(self.us_orders);
        } else {
            // Storage does not order upstream
            // self.usorders = 0.0
        }
    }

    fn run_flow_phase(&mut self, data_cache: &mut DataCache, _account_manager: &mut AccountManager) {

        // Record results
        if let Some(idx) = self.recorder_idx_usflow {
            data_cache.add_value_at_index(idx, self.usflow);
        }

        // Get the driving data
        let rain_mm = self.rain_mm_input.get_value(data_cache);
        let evap_mm = self.evap_mm_input.get_value(data_cache);
        let seep_mm = self.seep_mm_input.get_value(data_cache);
        let pond_demand = self.pond_demand_input.get_value(data_cache);

        let mut area_km2 = 0.0; // will be computed by solver if storage exists

        self.check_if_exists(data_cache);
        if !self.exists_bool {
            // Storage does not exist this timestep - skip calculations and empty storage,
            // draining everything through ds_1. Reset all other derived state so it doesn't
            // carry over stale values from the last timestep the storage existed.
            self.ds_flows = [0.0; MAX_DS_LINKS];
            self.ds_flows[0] = self.volume + self.usflow;
            self.volume = 0.0;
            self.level = self.dimensions.get_value(0, LEVL);
            // The storage is empty, so the solver's next warm start belongs at the
            // bottom of the table; a stale hint only costs it search iterations.
            self.previous_istop = 0;
            self.spill = 0.0;
            self.pond_diversion = 0.0;
            self.rain_vol = 0.0;
            self.evap_vol = 0.0;
            self.seep_vol = 0.0;
            // Only solve_backward_euler writes ds_release_due, and it does not run this
            // step; without this the ds_N_force_release recorders would keep reporting
            // the last step on which the storage existed.
            self.ds_release_due = [0.0; MAX_DS_LINKS];
            self.dsflow = self.ds_flows.iter().sum();
        } else {
            // Add upstream inflows
            self.volume += self.usflow;

            // Handle pond diversion first (highest priority)
            // If we empty the storage, there is no rainfall accessible this timestep since AREA=0.
            self.pond_diversion = pond_demand.min(self.volume);
            self.volume -= self.pond_diversion;
            
            // Net rainfall rate
            let net_rain_mm = rain_mm - evap_mm - seep_mm;
            
            // Solve backward Euler
            let (v_final, ds_flows, spill, row, solver_area_km2) = self.solve_backward_euler(self.volume, net_rain_mm, data_cache);
            area_km2 = solver_area_km2;
            
            // Update warm-start cache for next timestep (expects upper bracket)
            self.previous_istop = row + 1;
            
            // Update state from solution (area already computed by solver)
            self.volume = v_final;
            self.level = self.dimensions.interpolate_row(row, VOLU, LEVL, v_final);
            self.spill = spill;
            self.ds_flows = ds_flows;
            self.dsflow = self.ds_flows.iter().sum();
            
            // Compute climate volumes using solved area
            self.rain_vol = rain_mm * area_km2;
            self.evap_vol = evap_mm * area_km2;
            self.seep_vol = seep_mm * area_km2;
        }

        // Update mass balance
        self.mbal += self.dsflow - self.usflow;

        // Record results
        if let Some(idx) = self.recorder_idx_volume {
            data_cache.add_value_at_index(idx, self.volume);
        }
        if let Some(idx) = self.recorder_idx_level {
            data_cache.add_value_at_index(idx, self.level);
        }
        if let Some(idx) = self.recorder_idx_area {
            data_cache.add_value_at_index(idx, area_km2);
        }
        if let Some(idx) = self.recorder_idx_seep_megs {
            data_cache.add_value_at_index(idx, self.seep_vol);
        }
        if let Some(idx) = self.recorder_idx_rain_megs {
            data_cache.add_value_at_index(idx, self.rain_vol);
        }
        if let Some(idx) = self.recorder_idx_evap_megs {
            data_cache.add_value_at_index(idx, self.evap_vol);
        }
        if let Some(idx) = self.recorder_idx_seep_mm {
            data_cache.add_value_at_index(idx, seep_mm);
        }
        if let Some(idx) = self.recorder_idx_rain_mm {
            data_cache.add_value_at_index(idx, rain_mm);
        }
        if let Some(idx) = self.recorder_idx_evap_mm {
            data_cache.add_value_at_index(idx, evap_mm);
        }
        if let Some(idx) = self.recorder_idx_pond_diversion {
            data_cache.add_value_at_index(idx, self.pond_diversion);
        }
        if let Some(idx) = self.recorder_idx_pond_demand {
            data_cache.add_value_at_index(idx, pond_demand);
        }
        if let Some(idx) = self.recorder_idx_dsflow {
            data_cache.add_value_at_index(idx, self.dsflow);
        }
        // Per-outlet records. Outlet 0 (ds_1) carries the spill: its outlet
        // component is flow minus spill; the other outlets never spill.
        for i in 0..MAX_DS_LINKS {
            if let Some(idx) = self.recorder_idx_ds[i] {
                data_cache.add_value_at_index(idx, self.ds_flows[i]);
            }
            if let Some(idx) = self.recorder_idx_ds_outlet[i] {
                let outlet_flow = if i == 0 {
                    (self.ds_flows[0] - self.spill).max(0.0)
                } else {
                    self.ds_flows[i]
                };
                data_cache.add_value_at_index(idx, outlet_flow);
            }
            if let Some(idx) = self.recorder_idx_ds_spill[i] {
                data_cache.add_value_at_index(idx, if i == 0 { self.spill } else { 0.0 });
            }
            if let Some(idx) = self.recorder_idx_ds_force_release[i] {
                data_cache.add_value_at_index(idx, self.force_release_output(i));
            }
        }

        // Reset upstream inflow for next timestep
        self.usflow = 0.0;
    }

    fn add_usflow(&mut self, flow: f64, _inlet: u8) {
        self.usflow += flow;
    }

    /// Storage node processing follows BackwardEuler with a variation that
    /// diversion takes precedence over other fluxes. This means we can rely on:
    ///      * being able to extract the full start-of-day storage volume (at least)
    ///      * plus inflow if we know it
    ///      * plus rainfall in excess of seep and evap
    ///      * that a large demand will leave volume = 0 at the end of the day
    fn remove_dsflow(&mut self, outlet: u8) -> f64 {
        let idx = outlet as usize;
        if idx < MAX_DS_LINKS {
            let outflow = self.ds_flows[idx];
            self.ds_flows[idx] = 0.0;
            outflow
        } else {
            0.0
        }
    }

    fn get_mass_balance(&self) -> f64 {
        self.mbal
    }

    fn dsorders_mut(&mut self) -> &mut [f64] {
        &mut self.ds_orders
    }
}
