use crate::data_management::data_cache::DataCache;
use crate::hydrology::accounts::account_manager::AccountManager;
use crate::model_inputs::DynamicInput;

/// When a RAS fires (kalix-allocation-components.md §3.3): either a calendar
/// keyword (fires once at the boundary) or a DynamicExpression evaluated as a
/// pseudo-bool (level-semantic: the action applies on every timestep the
/// expression is nonzero). There is no parse ambiguity — bare identifiers are
/// never valid Kalix expressions, so the closed keyword set is tried first.
#[derive(Clone)]
pub enum RasTrigger {
    EveryStep,
    StartMonth,
    StartYear,
    /// Month is resolved at load time (a literal or a const.* reference).
    StartWaterYear(u8),
    Expression(DynamicInput),
}

impl RasTrigger {
    pub fn is_triggered(&self, data_cache: &mut DataCache) -> bool {
        match self {
            Self::EveryStep => true,
            Self::StartMonth => data_cache.get_timestamp_day() == 1 && data_cache.get_timestamp_seconds() == 0,
            Self::StartYear => data_cache.get_day_of_year() == 1 && data_cache.get_timestamp_seconds() == 0,
            Self::StartWaterYear(wy_month) => {
                data_cache.get_timestamp_day() == 1
                    && data_cache.get_timestamp_month() as u8 == *wy_month
                    && data_cache.get_timestamp_seconds() == 0
            }
            Self::Expression(input) => input.get_value(data_cache) != 0.0,
        }
    }
}

/// The stencilled action vocabulary (§3.3): applied to each target account
/// independently; arguments are expression-valued, evaluated once per firing.
/// Under a level-semantic expression trigger an action compounds per step —
/// deliberate (`scale(0.95)` while spilling means ×0.95 per timestep).
/// `allocate` and `distribute` are the group-wide actions: one value is
/// evaluated for the firing and the targets share it (§3.4, ADR-0019).
#[derive(Clone)]
pub enum RasAction {
    SetFull,
    SetEmpty,
    Set(DynamicInput),
    SetFraction(DynamicInput),
    Credit(DynamicInput),
    /// Credit each account by a fraction of its own size (negative debits).
    CreditFraction(DynamicInput),
    Debit(DynamicInput),
    Scale(DynamicInput),
    ReduceTo(DynamicInput),
    /// Roll an N-period cap: bank the period's debits, credit back those from
    /// N periods ago (a Source 'Moving Water Year' rolling cap; N = 1 is an
    /// annual cap).
    RollCap(DynamicInput),

    /// Announce an allocation percentage (0–100) for the target accounts.
    /// Distributive in effect but stencilled in form: the percentage is
    /// computed once and each account's allocation is raised to that share of
    /// its own entitlement, never lowered (§3.4). The modeller supplies the
    /// percentage — typically a `table.*` lookup over assessed resources — so
    /// the action carries no assessment machinery of its own.
    Allocate(DynamicInput),

    /// Start a new allocation period: allocation to date returns to zero.
    ResetAllocation,

    /// Share one signed volume across the target accounts in proportion to
    /// the stated weight (ADR-0019 §1). A positive amount credits, a
    /// negative one debits; a balance never leaves `[0, size]` by this
    /// action, and what an account cannot hold is shared among the others
    /// by the same weights (§2). The amount is one volume for the whole
    /// pool, so like `allocate` it never takes `self`.
    Distribute(DynamicInput, DistributeBy),
}

/// The weight a `distribute` action shares by (ADR-0019 §1). Weights are
/// read once at the firing: a balance-weighted share uses the balances as
/// they stood before the action, not as they move under it.
#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub enum DistributeBy {
    Size,
    Balance,
}

/// A resource allocation system ([ras.<name>] section): one trigger, one
/// action, applied to the accounts of one or more target groups. RAS sections
/// execute in file order in the account-maintenance slot at the top of the
/// timestep, before the flow phase — so today's takes see today's policy.
#[derive(Clone)]
pub struct RasSystem {
    pub name: String,
    /// Flattened member account ids across the target groups, in
    /// group-then-row order.
    pub target_account_ids: Vec<usize>,
    pub trigger: RasTrigger,
    pub action: RasAction,

    /// Arena base of the six `self.*` slots (RAS_SELF_FIELDS order) — Some
    /// iff the action argument references self, which switches the argument
    /// from evaluate-once-per-firing to evaluate-per-target against that
    /// account's freshly written slots. None costs nothing: the existing
    /// once-per-firing path runs unchanged.
    pub self_slots: Option<usize>,

    // Originals as written, for round-trip re-emission.
    pub targets_original: String,
    pub trigger_original: String,
    pub action_original: String,

    /// Last percentage announced by an `allocate` action, for the `pct`
    /// recorder. Not policy state: the allocation itself lives in the accounts.
    pub last_pct: f64,

    /// What the last `distribute` firing was asked to share, and the part of
    /// it that found no room, signed like the amount so that their difference
    /// is what landed (ADR-0019 §3). For the `amount` and `undistributed`
    /// recorders; both read as zero on steps the action does not fire. Not
    /// policy state.
    pub last_amount: f64,
    pub last_undistributed: f64,

    /// Scratch for `distribute`: one weight per target, frozen at the firing.
    /// Sized at load for a Distribute action and empty otherwise, so a firing
    /// never allocates.
    pub distribute_weights: Vec<f64>,

    /// Recorder for the `ras.<name>.fired` series (0/1 per step), opt-in via
    /// [outputs] like every recorder.
    pub recorder_idx_fired: Option<usize>,

    /// Recorder for `ras.<name>.pct` — the percentage an `allocate` action
    /// announced this step (carried forward on steps where it did not fire, so
    /// the series reads as the standing announcement).
    pub recorder_idx_pct: Option<usize>,

    /// Recorders for `ras.<name>.amount` and `ras.<name>.undistributed` — what
    /// a `distribute` action was asked to share this step and the part with no
    /// room for it; zero on steps it did not fire.
    pub recorder_idx_amount: Option<usize>,
    pub recorder_idx_undistributed: Option<usize>,
}

impl RasSystem {
    /// Resolve the opt-in recorder series (registered by [outputs]), and clear
    /// the standing announcement so a rerun of the same model object does not
    /// start by reporting the previous run's last percentage. Called at the
    /// start of every run.
    pub fn initialize_recorders(&mut self, data_cache: &mut DataCache) {
        self.last_pct = 0.0;
        self.last_amount = 0.0;
        self.last_undistributed = 0.0;
        self.recorder_idx_fired = data_cache.get_series_idx(
            format!("ras.{}.fired", self.name).as_str(), false);
        self.recorder_idx_pct = data_cache.get_series_idx(
            format!("ras.{}.pct", self.name).as_str(), false);
        self.recorder_idx_amount = data_cache.get_series_idx(
            format!("ras.{}.amount", self.name).as_str(), false);
        self.recorder_idx_undistributed = data_cache.get_series_idx(
            format!("ras.{}.undistributed", self.name).as_str(), false);
    }

    /// Evaluate the trigger and, if it fires, apply the action to every target
    /// account. Records the `fired` series if requested; returns whether it fired.
    pub fn run(&mut self, data_cache: &mut DataCache, account_manager: &mut AccountManager) -> bool {
        let fired = self.run_inner(data_cache, account_manager);
        if let Some(idx) = self.recorder_idx_fired {
            data_cache.add_value_at_index(idx, if fired { 1.0 } else { 0.0 });
        }
        if let Some(idx) = self.recorder_idx_pct {
            data_cache.add_value_at_index(idx, self.last_pct);
        }
        if let Some(idx) = self.recorder_idx_amount {
            data_cache.add_value_at_index(idx, if fired { self.last_amount } else { 0.0 });
        }
        if let Some(idx) = self.recorder_idx_undistributed {
            data_cache.add_value_at_index(idx, if fired { self.last_undistributed } else { 0.0 });
        }
        fired
    }

    fn run_inner(&mut self, data_cache: &mut DataCache, account_manager: &mut AccountManager) -> bool {
        if !self.trigger.is_triggered(data_cache) {
            return false;
        }
        match &self.action {
            RasAction::SetFull => {
                for &idx in &self.target_account_ids {
                    account_manager.set_account_balance_fraction(idx, 1.0);
                }
            }
            RasAction::SetEmpty => {
                for &idx in &self.target_account_ids {
                    account_manager.set_account_balance_fraction(idx, 0.0);
                }
            }
            RasAction::Set(input) => {
                self.apply_valued(data_cache, account_manager, input, |am, idx, value| {
                    am.set_account_balance_safely(idx, value);
                });
            }
            RasAction::SetFraction(input) => {
                self.apply_valued(data_cache, account_manager, input, |am, idx, value| {
                    am.set_account_balance_fraction(idx, value.clamp(0.0, 1.0));
                });
            }
            RasAction::Credit(input) => {
                self.apply_valued(data_cache, account_manager, input, |am, idx, value| {
                    am.add_account_value_safely(idx, value);
                });
            }
            RasAction::CreditFraction(input) => {
                self.apply_valued(data_cache, account_manager, input, |am, idx, value| {
                    let size = am.get_account_size(idx);
                    am.add_account_value_safely(idx, value * size);
                });
            }
            RasAction::Debit(input) => {
                self.apply_valued(data_cache, account_manager, input, |am, idx, value| {
                    am.add_account_value_safely(idx, -value);
                });
            }
            RasAction::Scale(input) => {
                self.apply_valued(data_cache, account_manager, input, |am, idx, value| {
                    let balance = am.get_account_balance(idx);
                    am.set_account_balance_safely(idx, balance * value.max(0.0));
                });
            }
            RasAction::ReduceTo(input) => {
                self.apply_valued(data_cache, account_manager, input, |am, idx, cap| {
                    let balance = am.get_account_balance(idx);
                    if balance > cap {
                        am.set_account_balance_safely(idx, cap);
                    }
                });
            }
            RasAction::RollCap(input) => {
                self.apply_valued(data_cache, account_manager, input, |am, idx, value| {
                    am.roll_cap(idx, value.round().max(1.0) as usize);
                });
            }
            RasAction::Allocate(input) => {
                // Announcements are one percentage for the whole group
                // (kalix-allocation-components.md §3.4), so allocate never
                // takes self references (rejected at load) and stays on the
                // evaluate-once path that also feeds the pct recorder.
                let pct = input.get_value(data_cache);
                self.last_pct = pct;
                for &idx in &self.target_account_ids {
                    account_manager.allocate(idx, pct);
                }
            }
            RasAction::ResetAllocation => {
                for &idx in &self.target_account_ids {
                    account_manager.reset_allocation(idx);
                }
            }
            RasAction::Distribute(input, by) => {
                // One volume for the whole pool: evaluated once, like allocate.
                let amount = input.get_value(data_cache);
                let by = *by;
                self.distribute(account_manager, amount, by);
            }
        }
        true
    }

    /// Share `amount` across the targets by weight, capped and redistributed
    /// (ADR-0019 §1–§2). A positive amount fills headroom, a negative one
    /// draws balances down; neither moves a balance past `[0, size]`.
    ///
    /// Each pass hands every account that still has room its weighted share
    /// of what remains, capped at that room; an account that reaches its room
    /// leaves the pool and the next pass shares the rest among those left, by
    /// the same frozen weights. A pass that fills nobody has placed the whole
    /// remainder, so the loop ends in at most one filling pass per target and
    /// one more, with no dependence on target order. What is left when no account has room is
    /// `last_undistributed`, for the recorder — never silently dropped.
    ///
    /// Balances are written through the unclamped setter: an account already
    /// above its size (an announcement over 100%) has no room to credit and
    /// is debited by its share like any other, which the clamped mutators
    /// would wrongly cut back to size.
    fn distribute(&mut self, account_manager: &mut AccountManager, amount: f64, by: DistributeBy) {
        self.last_amount = amount;
        let crediting = amount > 0.0;
        let mut remaining = amount.abs();

        for (k, &idx) in self.target_account_ids.iter().enumerate() {
            self.distribute_weights[k] = match by {
                DistributeBy::Size => account_manager.get_account_size(idx),
                DistributeBy::Balance => account_manager.get_account_balance(idx),
            };
        }
        let room = |am: &AccountManager, idx: usize| -> f64 {
            if crediting {
                am.get_account_size(idx) - am.get_account_balance(idx)
            } else {
                am.get_account_balance(idx)
            }
        };

        while remaining > 0.0 {
            let mut weight_sum = 0.0;
            for (k, &idx) in self.target_account_ids.iter().enumerate() {
                if room(account_manager, idx) > 0.0 && self.distribute_weights[k] > 0.0 {
                    weight_sum += self.distribute_weights[k];
                }
            }
            if weight_sum <= 0.0 {
                break;
            }
            let per_weight = remaining / weight_sum;
            let mut placed = 0.0;
            let mut filled_any = false;
            for (k, &idx) in self.target_account_ids.iter().enumerate() {
                let room_k = room(account_manager, idx);
                let weight = self.distribute_weights[k];
                // The same test as the weight sum, so a NaN room or weight is
                // outside the pool in both loops and never handed a share.
                if !(room_k > 0.0 && weight > 0.0) {
                    continue;
                }
                let want = per_weight * weight;
                let balance = account_manager.get_account_balance(idx);
                if want >= room_k {
                    // Fill exactly, so the room reads as zero next pass.
                    let full = if crediting { account_manager.get_account_size(idx) } else { 0.0 };
                    account_manager.set_account_balance_fast(idx, full);
                    placed += room_k;
                    filled_any = true;
                } else {
                    let new_balance = if crediting { balance + want } else { balance - want };
                    account_manager.set_account_balance_fast(idx, new_balance);
                    placed += want;
                }
            }
            if !filled_any {
                // Every share landed in full: the remainder is rounding.
                remaining = 0.0;
                break;
            }
            remaining -= placed;
        }
        // Signed like the amount, so amount − undistributed is what landed.
        let undistributed = if remaining.is_nan() { remaining } else { remaining.max(0.0) };
        self.last_undistributed = if crediting { undistributed } else { -undistributed };
    }

    /// Evaluate the action argument and apply the verb to every target.
    /// Without self references the argument is evaluated once per firing —
    /// the stencilled convention, and byte-for-byte the previous behaviour.
    /// With them, each target's live state is written into the self slots and
    /// the argument re-evaluated, so per-account policy reads per-account
    /// facts. Either way this runs only at firings — never on the hot path.
    fn apply_valued(
        &self,
        data_cache: &mut DataCache,
        account_manager: &mut AccountManager,
        input: &DynamicInput,
        apply: impl Fn(&mut AccountManager, usize, f64),
    ) {
        match self.self_slots {
            None => {
                let value = input.get_value(data_cache);
                for &idx in &self.target_account_ids {
                    apply(account_manager, idx, value);
                }
            }
            Some(base) => {
                for &idx in &self.target_account_ids {
                    write_self_slots(data_cache, account_manager, base, idx);
                    let value = input.get_value(data_cache);
                    apply(account_manager, idx, value);
                }
            }
        }
    }
}

/// Write one target account's live state into the six self slots
/// (RAS_SELF_FIELDS order). Unpaired accounts get NaN pair fields — never
/// read in practice, because any argument using self.pair.* requires every
/// target to be paired (validated at load); if that guarantee is ever
/// bypassed, NaN poisons the result visibly rather than reading as zero.
fn write_self_slots(
    data_cache: &mut DataCache,
    account_manager: &AccountManager,
    base: usize,
    account_id: usize,
) {
    let account = account_manager.get_account(account_id).expect("RAS targets resolve at load");
    let f = &mut data_cache.expr_state.f;
    f[base] = account.balance;
    f[base + 1] = account.size;
    f[base + 2] = account.allocation();
    match account.pair.and_then(|pair| account_manager.get_account(pair)) {
        Some(pair) => {
            f[base + 3] = pair.balance;
            f[base + 4] = pair.size;
            f[base + 5] = pair.allocation();
        }
        None => {
            f[base + 3] = f64::NAN;
            f[base + 4] = f64::NAN;
            f[base + 5] = f64::NAN;
        }
    }
}
