use rustc_hash::FxHashMap;
use crate::data_management::data_cache::DataCache;
use crate::hydrology::accounts::account::Account;
use crate::numerical::fifo_buffer::FifoBuffer;

/// An account group ([acc.<name>] section): the unit RAS clauses target.
/// Pure nouns — membership and per-account data columns; no behaviour
/// (kalix-allocation-components.md §3.1).
#[derive(Default, Clone)]
pub struct AccountGroup {
    pub name: String,
    /// Indices into AccountManager::accounts, in table row order
    /// (row order is meaningful: fill_in_order distributes by it).
    pub member_ids: Vec<usize>,
    /// Data columns beyond name/size/initial, aligned with member_ids.
    pub columns: Vec<(String, Vec<f64>)>,
}

#[derive(Default, Clone)]
pub struct AccountManager {
    accounts: Vec<Account>,
    account_lookup: FxHashMap<String, usize>,
    has_accounts: bool,

    // Account groups ([acc.*] sections). Group names share the flat `acc.`
    // namespace with account names, so both lookups reject either kind of
    // collision.
    groups: Vec<AccountGroup>,
    group_lookup: FxHashMap<String, usize>,

    // Recorder vectors are built in the 'initialize' method so we know what account values to
    // record during the run. Pairs are (account_idx | group_idx, series_idx).
    //
    // Two balance series with distinct timing, so expressions get a stable view
    // and outputs get the true end state (kalix-allocation-components.md §3.2):
    //   opening_balance — written once after the [ras.*] loop, before ordering
    //                     and flow, so every reader sees the same value however
    //                     the nodes are ordered;
    //   closing_balance — written at the end of the step, like every other
    //                     recorder (mid-step reads hit the standard
    //                     unwritten-value error and want a [-1, 0] offset).
    has_recorders: bool,
    has_opening_recorders: bool,
    recorder_acc_opening: Vec<(usize, usize)>,
    recorder_acc_closing: Vec<(usize, usize)>,
    recorder_acc_debits: Vec<(usize, usize)>,
    recorder_acc_allocation: Vec<(usize, usize)>,
    recorder_acc_size: Vec<(usize, usize)>,
    recorder_grp_opening: Vec<(usize, usize)>,
    recorder_grp_closing: Vec<(usize, usize)>,
    recorder_grp_debits: Vec<(usize, usize)>,
    recorder_grp_allocation: Vec<(usize, usize)>,
}

/// Series suffixes an `acc.<name>.<field>` reference may use. Closed set:
/// anything else is a load error rather than a silently unwritten series.
pub const ACCOUNT_SERIES_FIELDS: [&str; 5] = ["opening_balance", "closing_balance", "debits", "allocation", "size"];

/// Of those, the fields a *group* aggregate publishes (summed over members).
pub const GROUP_SERIES_FIELDS: [&str; 4] = ["opening_balance", "closing_balance", "debits", "allocation"];

impl AccountManager {

    /// Create new account manager with no accounts
    pub fn new() -> Self {
        Self {
            accounts: Vec::new(),
            account_lookup: FxHashMap::default(),
            has_accounts: false,
            groups: Vec::new(),
            group_lookup: FxHashMap::default(),
            has_recorders: false,
            has_opening_recorders: false,
            recorder_acc_opening: Vec::new(),
            recorder_acc_closing: Vec::new(),
            recorder_acc_debits: Vec::new(),
            recorder_acc_allocation: Vec::new(),
            recorder_acc_size: Vec::new(),
            recorder_grp_opening: Vec::new(),
            recorder_grp_closing: Vec::new(),
            recorder_grp_debits: Vec::new(),
            recorder_grp_allocation: Vec::new(),
        }
    }


    /// Add an account
    pub fn add_account(&mut self, account: Account) -> Result<usize, String> {

        // Check the name doesn't clash (accounts and groups share the acc. namespace)
        if self.account_lookup.contains_key(&account.name) {
            return Err(format!("Tried to create account '{}' more than once.", &account.name));
        }
        if self.group_lookup.contains_key(&account.name) {
            return Err(format!("Account '{}' clashes with an account group of the same name.", &account.name));
        }

        // Add the account to the vec & hashmap
        let idx = self.accounts.len();
        self.account_lookup.insert(account.name.clone(), idx);
        self.accounts.push(account);

        // Success!
        Ok(idx)
    }


    /// Add an account group ([acc.<name>] section). Group names share the flat
    /// `acc.` namespace with account names: a collision either way is an error.
    pub fn add_group(&mut self, group: AccountGroup) -> Result<usize, String> {
        if self.group_lookup.contains_key(&group.name) {
            return Err(format!("Tried to create account group '{}' more than once.", &group.name));
        }
        if self.account_lookup.contains_key(&group.name) {
            return Err(format!("Account group '{}' clashes with an account of the same name.", &group.name));
        }
        let idx = self.groups.len();
        self.group_lookup.insert(group.name.clone(), idx);
        self.groups.push(group);
        Ok(idx)
    }

    /// Look up an account group index by name.
    pub fn get_group_idx(&self, name: &str) -> Option<usize> {
        self.group_lookup.get(name).copied()
    }

    /// Get a reference to an account group by index, if it exists.
    pub fn get_group(&self, group_idx: usize) -> Option<&AccountGroup> {
        self.groups.get(group_idx)
    }

    /// All account groups, in declaration order.
    pub fn groups(&self) -> &[AccountGroup] {
        &self.groups
    }

    /// Look up an account index by name.
    pub fn get_account_idx(&self, name: &str) -> Option<usize> {
        self.account_lookup.get(name).copied()
    }

    /// Initialize account manager, including all accounts and recorders
    pub fn initialize(&mut self, data_cache: &mut DataCache) {

        // Initialize internal state
        for account in &mut self.accounts {
            account.initialize();
        }
        self.has_accounts = self.accounts.len() >= 1;

        // No implicit behaviour: the old hard-coded water-year refill is gone.
        // Everything that changes a balance is a [ras.*] action (executed by
        // the model loop) or a node take.

        // Initialize result recorders. A series exists here if [outputs] asked
        // for it or an expression referenced it — same opt-in path as node
        // outputs, so referencing acc.x.opening_balance registers the writer.
        self.has_recorders = false;
        self.recorder_acc_opening.clear();
        self.recorder_acc_closing.clear();
        self.recorder_acc_debits.clear();
        self.recorder_acc_allocation.clear();
        self.recorder_acc_size.clear();
        self.recorder_grp_opening.clear();
        self.recorder_grp_closing.clear();
        self.recorder_grp_debits.clear();
        self.recorder_grp_allocation.clear();

        for account_idx in 0..self.accounts.len() {
            let name = self.accounts[account_idx].name.clone();
            let mut register = |field: &str, target: &mut Vec<(usize, usize)>, flag: &mut bool| {
                if let Some(series_idx) = data_cache.get_series_idx(
                    make_acc_result_name(&name, field).as_str(), false
                ) {
                    target.push((account_idx, series_idx));
                    *flag = true;
                }
            };
            let mut any = false;
            register("opening_balance", &mut self.recorder_acc_opening, &mut any);
            register("closing_balance", &mut self.recorder_acc_closing, &mut any);
            register("debits", &mut self.recorder_acc_debits, &mut any);
            register("allocation", &mut self.recorder_acc_allocation, &mut any);
            register("size", &mut self.recorder_acc_size, &mut any);
            self.has_recorders |= any;
        }

        // Group aggregates: the same fields summed over member accounts
        for group_idx in 0..self.groups.len() {
            let name = self.groups[group_idx].name.clone();
            let mut register = |field: &str, target: &mut Vec<(usize, usize)>, flag: &mut bool| {
                if let Some(series_idx) = data_cache.get_series_idx(
                    make_acc_result_name(&name, field).as_str(), false
                ) {
                    target.push((group_idx, series_idx));
                    *flag = true;
                }
            };
            let mut any = false;
            register("opening_balance", &mut self.recorder_grp_opening, &mut any);
            register("closing_balance", &mut self.recorder_grp_closing, &mut any);
            register("debits", &mut self.recorder_grp_debits, &mut any);
            register("allocation", &mut self.recorder_grp_allocation, &mut any);
            self.has_recorders |= any;
        }

        self.has_opening_recorders =
            !self.recorder_acc_opening.is_empty() || !self.recorder_grp_opening.is_empty();
    }

    /// Start-of-step accounting, called after the [ras.*] loop and before the
    /// ordering and flow phases. Resets the per-step debit tally and publishes
    /// `opening_balance` — the post-policy, pre-take snapshot every expression
    /// reader sees, whatever order the nodes run in.
    pub fn start_of_step(&mut self, data_cache: &mut DataCache) {
        for account in &mut self.accounts {
            account.debits_today = 0.0;
        }
        if !self.has_opening_recorders { return; }
        for &(account_idx, series_idx) in &self.recorder_acc_opening {
            data_cache.add_value_at_index(series_idx, self.accounts[account_idx].balance);
        }
        for &(group_idx, series_idx) in &self.recorder_grp_opening {
            let total = self.group_sum(group_idx, |a| a.balance);
            data_cache.add_value_at_index(series_idx, total);
        }
    }

    fn group_sum(&self, group_idx: usize, f: impl Fn(&Account) -> f64) -> f64 {
        self.groups[group_idx].member_ids.iter()
            .map(|&idx| f(&self.accounts[idx]))
            .sum()
    }

    /// Record end-of-step results
    pub fn record_results(&self, data_cache: &mut DataCache) {
        // Early exit if there are no recorders
        if !self.has_recorders { return; }

        for &(account_idx, series_idx) in &self.recorder_acc_closing {
            data_cache.add_value_at_index(series_idx, self.accounts[account_idx].balance);
        }
        for &(account_idx, series_idx) in &self.recorder_acc_debits {
            data_cache.add_value_at_index(series_idx, self.accounts[account_idx].debits_today);
        }
        for &(account_idx, series_idx) in &self.recorder_acc_allocation {
            data_cache.add_value_at_index(series_idx, self.accounts[account_idx].allocation());
        }
        for &(account_idx, series_idx) in &self.recorder_acc_size {
            data_cache.add_value_at_index(series_idx, self.accounts[account_idx].size);
        }

        for &(group_idx, series_idx) in &self.recorder_grp_closing {
            let total = self.group_sum(group_idx, |a| a.balance);
            data_cache.add_value_at_index(series_idx, total);
        }
        for &(group_idx, series_idx) in &self.recorder_grp_debits {
            let total = self.group_sum(group_idx, |a| a.debits_today);
            data_cache.add_value_at_index(series_idx, total);
        }
        for &(group_idx, series_idx) in &self.recorder_grp_allocation {
            let total = self.group_sum(group_idx, |a| a.allocation());
            data_cache.add_value_at_index(series_idx, total);
        }
    }

    /// Accessor for account balance
    pub fn get_account_balance(&self, account_id: usize) -> f64 {
        self.accounts[account_id].balance
    }

    /// Accessor for account size
    pub fn get_account_size(&self, account_id: usize) -> f64 {
        self.accounts[account_id].size
    }

    /// Get a reference to an account by index, if it exists.
    pub fn get_account(&self, account_id: usize) -> Option<&Account> {
        self.accounts.get(account_id)
    }

    /// Debit an account for water taken by a node. This is the only path that
    /// feeds the `debits` series, so policy changes stay out of "water used".
    pub fn debit_account(&mut self, account_id: usize, amount: f64) {
        self.accounts[account_id].debit_account_fast(amount);
        self.accounts[account_id].debits_today += amount;
        self.accounts[account_id].debits_since_reset += amount;
        self.accounts[account_id].debits_this_period += amount;
    }

    /// Announce an allocation: raise each account's allocation to `pct` of its
    /// entitlement, never lower it (§3.4). Allocation is `balance +
    /// debits_since_reset`, so the credit accounts for water already used and
    /// an unchanged announcement is a no-op however much has been taken.
    /// Percentages above 100 are allowed — some schemes announce them — so the
    /// balance is not clamped to the account size here.
    pub fn allocate(&mut self, account_id: usize, pct: f64) {
        let account = &mut self.accounts[account_id];
        let target = pct / 100.0 * account.size;
        let current = account.allocation();
        if target > current {
            account.balance += target - current;
        }
    }

    /// Start a new allocation period: allocation to date returns to zero, so
    /// the next announcement credits from scratch. Zeroes the balance and the
    /// use tally together — the two terms of the allocation.
    pub fn reset_allocation(&mut self, account_id: usize) {
        let account = &mut self.accounts[account_id];
        account.balance = 0.0;
        account.debits_since_reset = 0.0;
    }

    /// Declare `pair_name` as the pair of `account_id` (the `pair` table
    /// column). The pairing is symmetric — it is written onto both accounts,
    /// so `self.pair.<field>` resolves from either end — with the declaring
    /// side marked for round-trip re-emission. Validation: the pair must be
    /// an existing account (not a group), not the account itself, and
    /// neither account may already be in a pair.
    pub fn set_pair(&mut self, account_id: usize, pair_name: &str) -> Result<(), String> {
        let declarer = &self.accounts[account_id];
        let pair_idx = self.account_lookup.get(pair_name).copied().ok_or_else(|| {
            if self.group_lookup.contains_key(pair_name) {
                format!("'{}' is an account group; 'pair' takes an account name", pair_name)
            } else {
                format!("Unknown pair account '{}' for account '{}'. Accounts are declared in [acc.*] sections.",
                    pair_name, declarer.name)
            }
        })?;
        if pair_idx == account_id {
            return Err(format!("Account '{}' cannot be paired with itself", declarer.name));
        }
        for &idx in &[account_id, pair_idx] {
            if let Some(existing) = self.accounts[idx].pair {
                return Err(format!("Account '{}' is already paired with '{}'; an account can be in at most one pair",
                    self.accounts[idx].name, self.accounts[existing].name));
            }
        }
        self.accounts[account_id].pair = Some(pair_idx);
        self.accounts[account_id].pair_declared = true;
        self.accounts[pair_idx].pair = Some(account_id);
        Ok(())
    }

    /// Roll an N-period cap: bank the closing period's debits and credit back
    /// the debits expiring out of the window (those from N periods ago).
    /// The buffer is sized on first use; N is expected to be constant.
    pub fn roll_cap(&mut self, account_id: usize, n: usize) {
        let account = &mut self.accounts[account_id];
        let slots = n.saturating_sub(1);
        if account.aged_debits.len() != slots {
            account.aged_debits = FifoBuffer::new(slots);
        }
        let expired = account.aged_debits.push(account.debits_this_period);
        account.debits_this_period = 0.0;
        account.add_value_safely(expired);
    }

    /// Allocation to date for an account.
    pub fn get_account_allocation(&self, account_id: usize) -> f64 {
        self.accounts[account_id].allocation()
    }

    // Balance mutators used by [ras.*] actions (clamped to [0, size] where safe)

    pub fn set_account_balance_safely(&mut self, account_id: usize, balance: f64) {
        self.accounts[account_id].set_balance_safely(balance);
    }

    pub fn set_account_balance_fraction(&mut self, account_id: usize, fraction: f64) {
        self.accounts[account_id].set_balance_fraction(fraction);
    }

    pub fn add_account_value_safely(&mut self, account_id: usize, amount: f64) {
        self.accounts[account_id].add_value_safely(amount);
    }
}


pub fn make_acc_result_name(node_name: &str, parameter: &str) -> String {
    format!("acc.{node_name}.{parameter}")
}