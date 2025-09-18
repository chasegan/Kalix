#[derive(Clone, Default, Debug)]
pub struct Link {
    pub flow: f64,
    pub from_node: usize,
    pub to_node: usize,
    pub from_outlet: u8,  // 0 = primary, 1 = secondary, etc. u8::MAX=255
    pub to_inlet: u8,     // 0 = primary, 1 = secondary, etc. u8::MAX=255
}

impl Link {
    pub fn new(from_node: usize, to_node: usize, from_outlet: u8, to_inlet: u8) -> Self {
        Self {
            flow: 0.0,
            from_node,
            to_node,
            from_outlet,
            to_inlet
        }
    }

    pub fn remove_flow(&mut self) -> f64 {
        let flow = self.flow;
        self.flow = 0.0;
        flow
    }

    pub fn add_flow(&mut self, flow: f64) {
        self.flow += flow;
    }

    pub fn has_flow(&self) -> bool { self.flow > 0.0 }
}
