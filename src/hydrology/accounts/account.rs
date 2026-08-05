use crate::numerical::fifo_buffer::FifoBuffer;

/// A single account: pure state — name, cap, balance. Accounts carry no
/// behaviour and no calendar; everything that changes a balance is a [ras.*]
/// action or a node take (kalix-allocation-components.md §3.1).
#[derive(Default, Clone)]
pub struct Account {
    // Properties
    pub name: String,
    /// The declaring group's name (the acc. addressing/targeting unit).
    pub account_type: String,
    pub size: f64,
    pub initial_balance: f64,
    /// Paired account (the `pair` table column), resolved to its index at
    /// load. The pairing is symmetric — declared on either account's row,
    /// readable from both ends as `self.pair.<field>` in [ras.*] action
    /// arguments — and an account may be in at most one pair. The one
    /// declared relationship an account carries: accounts stay pure state
    /// otherwise — debit order remains the user node's `accounts =` list,
    /// never baked in here.
    pub pair: Option<usize>,
    /// Whether this row is the one that declared the pairing — round-trip
    /// only, so the canonical render emits the `pair` column on the side
    /// that wrote it and not both.
    pub pair_declared: bool,

    // State
    pub balance: f64,
    /// Debits from node takes so far this step. Reset at the top of every
    /// step; only `debit_account` adds to it, so policy changes made by
    /// [ras.*] actions (write-offs, resets, credits) are excluded by
    /// construction — this is "water used", not "balance moved".
    pub debits_today: f64,

    /// Debits from node takes since the last allocation reset. Together with
    /// the balance this *is* the account's allocation to date: water used does
    /// not reduce an allocation, it moves between the two terms. Reset only by
    /// the `reset_allocation` action (kalix-allocation-components.md §3.4).
    pub debits_since_reset: f64,

    /// Debits from node takes since the last `roll_cap` action.
    pub debits_this_period: f64,
    /// Completed-period debit totals not yet expired by `roll_cap` (N-1
    /// slots). Zero-capacity (passthrough) unless roll_cap targets this
    /// account with N > 1.
    pub aged_debits: FifoBuffer,
}

impl Account {

    // Constructor
    pub fn new_with_size(name: String, account_type: String, size: f64, initial_balance: f64) -> Self {
        Account {
            name,
            account_type,
            size,
            initial_balance,
            pair: None,
            pair_declared: false,
            balance: initial_balance,
            debits_today: 0.0,
            debits_since_reset: 0.0,
            debits_this_period: 0.0,
            aged_debits: FifoBuffer::default(),
        }
    }

    // Initialize account using saved initial balance
    pub fn initialize(&mut self) {
        self.balance = self.initial_balance;
        self.debits_today = 0.0;
        self.debits_since_reset = 0.0;
        self.debits_this_period = 0.0;
        self.aged_debits.reset();
    }

    /// Allocation to date: everything credited since the last reset, whether
    /// it is still held or has been used. This is the quantity an announced
    /// allocation percentage sets, and the reason a user drawing their balance
    /// down does not reduce their allocation.
    pub fn allocation(&self) -> f64 {
        self.balance + self.debits_since_reset
    }

    // Set balance but not allowing it to be less than 0 or greater
    // than the account size.
    pub fn set_balance_safely(&mut self, balance: f64) {
        self.balance = balance.max(0.0).min(self.size);
    }

    // Sets balance without checking the validity.
    pub fn set_balance_fast(&mut self, balance: f64) {
        self.balance = balance
    }

    // Add some value (positive or negative), but not allowing the
    // new value to become less than 0 or greater than the account
    // size.
    pub fn add_value_safely(&mut self, amount: f64) {
        let temp_bal = self.balance + amount;
        self.set_balance_safely(temp_bal);
    }

    // Add some value to the account balance without checking the validity
    // of the resulting account balance.
    pub fn credit_account_fast(&mut self, amount: f64) {
        self.balance = self.balance + amount;
    }

    // Subtract some value from the account balance without checking the validity
    // of the resulting account balance.
    pub fn debit_account_fast(&mut self, amount: f64) {
        self.balance = self.balance - amount;
    }

    // Sets the account balance to the given fraction of the account size
    pub fn set_balance_fraction(&mut self, balance_as_proportion_of_account_size: f64) {
        self.set_balance_fast(balance_as_proportion_of_account_size * self.size);
    }
}
