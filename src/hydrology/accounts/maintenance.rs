use crate::hydrology::accounts::trigger::Trigger;


/// MaintenanceGroup is a helper struct for having a list of
/// accounts which need a certain type of maintenance on a
/// given trigger.
#[derive(Clone)]
pub struct MaintenanceGroup {
    pub trigger: Trigger,
    pub maintenance_task: MaintenanceType,
    pub account_ids: Vec<usize>,
}

#[derive(Clone, Copy, PartialEq, Eq, Hash)]
pub enum MaintenanceType {
    SetFull,
    SetEmpty,
}