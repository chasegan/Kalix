use crate::data_cache::DataCache;

#[derive(Clone)]
#[derive(Default)]
pub struct InputDataDefinition {
    pub name: String,       //The name of the series in the data_cache to use for inflows
    pub idx: Option<usize>, //This is the idx of the series, which will be determined during init and used subsequently
}

impl InputDataDefinition {
    pub fn add_series_to_data_cache_if_required_and_get_idx(&mut self, data_cache: &mut DataCache, flag_as_critical: bool) {
        if !self.name.is_empty() {
            let lower_name = self.name.to_lowercase();
            self.idx = Some(data_cache.get_or_add_new_series(lower_name.as_str(), flag_as_critical));
        } else {
            self.idx = None;
        }
    }
}