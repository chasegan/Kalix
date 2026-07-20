use rustc_hash::FxHashMap;
use crate::data_management::data_cache::DataCache;
use crate::hydrology::accounts::account::Account;

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
    // record during the run.
    // Recorder pairs are (account_idx, series_idx) where account_idx is the index of the account
    // in the 'accounts' vector and the series_idx is the index of the series in the data cache.
    has_recorders: bool,
    recorder_acc_balance: Vec<(usize, usize)>,
    recorder_acc_size: Vec<(usize, usize)>,
}

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
            recorder_acc_balance: Vec::new(),
            recorder_acc_size: Vec::new(),
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

        // Initialize result recorders
        self.has_recorders = false;
        self.recorder_acc_balance.clear();
        self.recorder_acc_size.clear();
        for (account_idx, account) in self.accounts.iter().enumerate() {

            // Account balance recorders
            if let Some(series_idx) = data_cache.get_series_idx(
                make_acc_result_name(&account.name, "balance").as_str(), false
            ) {
                self.recorder_acc_balance.push((account_idx, series_idx));
                self.has_recorders = true;
            }

            // Account size recorders
            if let Some(series_idx) = data_cache.get_series_idx(
                make_acc_result_name(&account.name, "size").as_str(), false
            ) {
                self.recorder_acc_size.push((account_idx, series_idx));
                self.has_recorders = true;
            }
        }
    }

    /// Record results
    pub fn record_results(&self, data_cache: &mut DataCache) {
        // Early exit if there are no recorders
        if !self.has_recorders { return; }

        // Record account balances
        for &(account_idx, series_idx) in &self.recorder_acc_balance {
            data_cache.add_value_at_index(series_idx, self.accounts[account_idx].balance);
        }

        // Record account sizes
        for &(account_idx, series_idx) in &self.recorder_acc_size {
            data_cache.add_value_at_index(series_idx, self.accounts[account_idx].size);
        }
    }

    /// Accessor for account balance
    pub fn get_account_balance(&self, account_id: usize) -> f64 {
        self.accounts[account_id].balance
    }

    /// Get a reference to an account by index, if it exists.
    pub fn get_account(&self, account_id: usize) -> Option<&Account> {
        self.accounts.get(account_id)
    }

    /// Debit account
    pub fn debit_account(&mut self, account_id: usize, amount: f64) {
        self.accounts[account_id].debit_account_fast(amount);
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