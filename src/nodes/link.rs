use crate::misc::componenet_identification::ComponentIdentification;

/// Nodes have primary and secondary downstream links. The purpose of links is to store the
/// volume of water to be transported downstream, and the id of the downstream node where the
/// water is going.
#[derive(Clone, Default)]
pub struct Link {
    pub flow: f64,
    pub node_identification: ComponentIdentification,
}

impl Link {
    /// Returns the volume of water in a link, and resets the volume of water on the link to zero.
    pub fn remove_flow(&mut self) -> f64{
        let answer = self.flow;
        self.flow = 0_f64;
        answer
    }
}
