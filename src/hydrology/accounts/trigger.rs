use crate::data_management::data_cache::DataCache;

#[derive(Clone, Copy, PartialEq, Eq, Hash)]
pub enum Trigger {
    EveryTimestep(),
    StartMonth(),
    StartCalendarYear(),
    StartWaterYear(u8), //Value is wy_month with Jan=1
}

impl Trigger {
    pub fn is_triggered(&self, data_cache: &DataCache) -> bool {
        match self {
            Self::EveryTimestep() => true,
            Self::StartMonth() => data_cache.get_timestamp_day() == 1,
            Self::StartCalendarYear() => data_cache.get_day_of_year() == 1,
            Self::StartWaterYear(wy_month) => {
                let d = data_cache.get_timestamp_day();
                if d == 1 {
                    let m = data_cache.get_timestamp_month() as u8;
                    if m == *wy_month {
                        let s = data_cache.get_timestamp_seconds();
                        if s == 0 {
                            return true;
                        }
                    }
                }
                false
            },
        }
    }
}
