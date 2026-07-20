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
    Debit(DynamicInput),
    Scale(DynamicInput),
    ReduceTo(DynamicInput),
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
}

impl RasSystem {
    /// Evaluate the trigger and, if it fires, apply the action to every target
    /// account. Returns whether the system fired (recorded in step 4).
    pub fn run(&self, data_cache: &mut DataCache, account_manager: &mut AccountManager) -> bool {
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
        }
        true
    }
}
