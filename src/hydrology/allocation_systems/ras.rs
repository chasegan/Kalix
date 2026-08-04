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
/// Distributive actions (allocate, apportion_loss, …) are phase 2/3.
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

    /// Set each target's paired carryover account (its `co_acc` column) to
    /// x × the target's own balance, clamped to the pair's size. Stencilled:
    /// x is evaluated once per firing, but each target contributes its own
    /// balance. x = 0 is a denial year — the pool is set to zero (a
    /// write-off), not left alone. Every target must declare a co_acc
    /// (validated at load).
    Carryover(DynamicInput),

    /// Announce an allocation percentage (0–100) for the target accounts.
    /// Distributive in effect but stencilled in form: the percentage is
    /// computed once and each account's allocation is raised to that share of
    /// its own entitlement, never lowered (§3.4). The modeller supplies the
    /// percentage — typically a `table.*` lookup over assessed resources — so
    /// the action carries no assessment machinery of its own.
    Allocate(DynamicInput),

    /// Start a new allocation period: allocation to date returns to zero.
    ResetAllocation,
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

    // Originals as written, for round-trip re-emission.
    pub targets_original: String,
    pub trigger_original: String,
    pub action_original: String,

    /// Last percentage announced by an `allocate` action, for the `pct`
    /// recorder. Not policy state: the allocation itself lives in the accounts.
    pub last_pct: f64,

    /// Recorder for the `ras.<name>.fired` series (0/1 per step), opt-in via
    /// [outputs] like every recorder.
    pub recorder_idx_fired: Option<usize>,

    /// Recorder for `ras.<name>.pct` — the percentage an `allocate` action
    /// announced this step (carried forward on steps where it did not fire, so
    /// the series reads as the standing announcement).
    pub recorder_idx_pct: Option<usize>,
}

impl RasSystem {
    /// Resolve the opt-in recorder series (registered by [outputs]).
    pub fn initialize_recorders(&mut self, data_cache: &mut DataCache) {
        self.recorder_idx_fired = data_cache.get_series_idx(
            format!("ras.{}.fired", self.name).as_str(), false);
        self.recorder_idx_pct = data_cache.get_series_idx(
            format!("ras.{}.pct", self.name).as_str(), false);
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
                let value = input.get_value(data_cache);
                for &idx in &self.target_account_ids {
                    account_manager.set_account_balance_safely(idx, value);
                }
            }
            RasAction::SetFraction(input) => {
                let fraction = input.get_value(data_cache).clamp(0.0, 1.0);
                for &idx in &self.target_account_ids {
                    account_manager.set_account_balance_fraction(idx, fraction);
                }
            }
            RasAction::Credit(input) => {
                let value = input.get_value(data_cache);
                for &idx in &self.target_account_ids {
                    account_manager.add_account_value_safely(idx, value);
                }
            }
            RasAction::CreditFraction(input) => {
                let fraction = input.get_value(data_cache);
                for &idx in &self.target_account_ids {
                    let size = account_manager.get_account_size(idx);
                    account_manager.add_account_value_safely(idx, fraction * size);
                }
            }
            RasAction::Debit(input) => {
                let value = input.get_value(data_cache);
                for &idx in &self.target_account_ids {
                    account_manager.add_account_value_safely(idx, -value);
                }
            }
            RasAction::Scale(input) => {
                let factor = input.get_value(data_cache).max(0.0);
                for &idx in &self.target_account_ids {
                    let balance = account_manager.get_account_balance(idx);
                    account_manager.set_account_balance_safely(idx, balance * factor);
                }
            }
            RasAction::ReduceTo(input) => {
                let cap = input.get_value(data_cache);
                for &idx in &self.target_account_ids {
                    let balance = account_manager.get_account_balance(idx);
                    if balance > cap {
                        account_manager.set_account_balance_safely(idx, cap);
                    }
                }
            }
            RasAction::RollCap(input) => {
                let n = input.get_value(data_cache).round().max(1.0) as usize;
                for &idx in &self.target_account_ids {
                    account_manager.roll_cap(idx, n);
                }
            }
            RasAction::Carryover(input) => {
                let x = input.get_value(data_cache);
                for &idx in &self.target_account_ids {
                    account_manager.carryover(idx, x);
                }
            }
            RasAction::Allocate(input) => {
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
        }
        true
    }
}
