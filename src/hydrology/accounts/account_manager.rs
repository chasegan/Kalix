use rustc_hash::FxHashMap;
use crate::data_management::data_cache::DataCache;
use crate::hydrology::accounts::account::Account;
use crate::hydrology::accounts::maintenance::{MaintenanceGroup, MaintenanceType};
use crate::hydrology::accounts::trigger::Trigger;

#[derive(Default, Clone)]
pub struct AccountManager {
    accounts: Vec<Account>,
    account_lookup: FxHashMap<String, usize>,
    has_accounts: bool,

    // Account maintenance tasks
    account_maintenance_groups: Vec<MaintenanceGroup>,

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
            account_maintenance_groups: Vec::new(),
            has_recorders: false,
            recorder_acc_balance: Vec::new(),
            recorder_acc_size: Vec::new(),
        }
    }


    /// Add an account
    pub fn add_account(&mut self, account: Account) -> Result<usize, String> {

        // Check the name doesn't clash
        if self.account_lookup.contains_key(&account.name) {
            return Err(format!("Tried to create account '{}' more than once.", &account.name));
        }

        // Add the account to the vec & hashmap
        let idx = self.accounts.len();
        self.account_lookup.insert(account.name.clone(), idx);
        self.accounts.push(account);

        // Success!
        Ok(idx)
    }


    /// Create a new account within this account management system
    pub fn create_account(&mut self, name: String, account_type: String, size: f64, wy_month: u8, initial_balance: f64) -> Result<usize, String> {
        self.add_account(Account::new_with_size(
            name.clone(),
            account_type,
            size,
            wy_month,
            initial_balance)
        )
    }

    /// Initialize account manager, including all accounts and recorders
    pub fn initialize(&mut self, data_cache: &mut DataCache) {

        // Initialize internal state
        for account in &mut self.accounts {
            account.initialize();
        }
        self.has_accounts = self.accounts.len() >= 1;

        // Checks
        // None

        // Initialize maintenance tasks
        // (Go through all the accounts and see what maintenance they need)
        // Group accounts by (trigger, task) using a map, then convert to MaintenanceGroups
        let mut group_map: FxHashMap<(Trigger, MaintenanceType), Vec<usize>> = FxHashMap::default();
        for (account_idx, account) in self.accounts.iter().enumerate() {
            if account.is_unreg && (account.wy_month > 0) && (account.size > 0.0) {
                let key = (Trigger::StartWaterYear(account.wy_month), MaintenanceType::SetFull);
                group_map.entry(key).or_default().push(account_idx);
            }
        }
        for ((trigger, maintenance_task), account_ids) in group_map {
            self.account_maintenance_groups.push(MaintenanceGroup { trigger, maintenance_task, account_ids });
        }

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

    /// Run maintenance:
    /// - each group involves a particular trigger, and a particular type of maintenance;
    /// - check each group to see if it "is_triggered";
    /// - if triggered, do the maintenance for all accounts in the group.
    pub fn run_maintenance(&mut self, data_cache: &DataCache) {
        for group in &self.account_maintenance_groups {
            if group.trigger.is_triggered(data_cache) {
                match group.maintenance_task {
                    MaintenanceType::SetFull => {
                        for &idx in &group.account_ids {
                            self.accounts[idx].set_balance_fraction(1.0);
                        }
                    },
                    MaintenanceType::SetEmpty => {
                        for &idx in &group.account_ids {
                            self.accounts[idx].set_balance_fraction(0.0);
                        }
                    }
                }
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

    /// Debit account
    pub fn debit_account(&mut self, account_id: usize, amount: f64) {
        self.accounts[account_id].debit_account_fast(amount);
    }
}


pub fn make_acc_result_name(node_name: &str, parameter: &str) -> String {
    format!("acc.{node_name}.{parameter}")
}