
#[derive(Default, Clone)]
pub struct Account {
    // Properties
    pub name: String,
    pub account_type: String,
    pub size: f64,
    pub wy_month: u8, //TODO: maybe change to u8, or option<u8>?
    pub is_unreg: bool,
    pub initial_balance: f64,

    // State
    pub balance: f64,
}

impl Account {

    // Constructor
    pub fn new_with_size(name: String, account_type: String, size: f64, wy_month: u8, initial_balance: f64, ) -> Self {
        Account {
            name,
            account_type,
            size,
            wy_month,
            is_unreg: true,
            initial_balance,
            balance: initial_balance,
        }
    }

    // Initialize account using saved initial balance
    pub fn initialize(&mut self) {
        self.balance = self.initial_balance;
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

    // Sets the account balance to zero
    pub fn set_balance_fraction(&mut self, balance_as_proportion_of_account_size: f64) {
        self.set_balance_fast(balance_as_proportion_of_account_size * self.size);
    }


    // // Carryover - maybe should work something like below
    // pub fn carryover(&mut self) {
    //     // done during initialization
    //     self.carryover_size = self.size * self.carryover_proportion;
    //
    //     // and then done here
    //     self.carryover_balance = self.balance.min(self.carryover_size);
    //     self.balance = 0.0;
    // }
}
