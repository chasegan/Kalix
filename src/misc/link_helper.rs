#[derive(Clone, Default, Debug)]
pub struct LinkHelper {
    pub from_node_name: String,
    pub to_node_name: String,
    pub from_node_idx: usize,
    pub to_node_idx: usize,
    pub from_outlet: u8,  // 0 = primary, 1 = secondary, etc. u8::MAX=255
    pub to_inlet: u8,     // 0 = primary, 1 = secondary, etc. u8::MAX=255
}

impl LinkHelper {
    pub fn new() -> Self {
        LinkHelper {
            ..Default::default()
        }
    }

    pub fn new_from_names(from_node_name: &str, to_node_name: &str, from_outlet: u8, to_inlet: u8) -> Self {
        LinkHelper {
            from_node_name: from_node_name.to_string(),
            to_node_name: to_node_name.to_string(),
            from_outlet,
            to_inlet,
            ..Default::default()
        }
    }
}