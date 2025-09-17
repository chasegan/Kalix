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

    /// Returns a new link that is not connected to any nodes, and has a flow of zero.
    /// Probably the same as a default link, but spelling it out for clarity.
    pub fn new_unconnected_link() -> Link {
        Link {
            flow: 0_f64,
            node_identification: ComponentIdentification::None,
        }
    }

    /// Constructor for indexed links
    pub fn new_indexed_link(idx_of_linked_node: usize) -> Link {
        Link {
            flow: 0_f64,
            node_identification: ComponentIdentification::Indexed {
                idx: idx_of_linked_node,
            }
        }
    }

    /// Constructor for named links
    pub fn new_named_link(name_of_linked_node: &str) -> Link {
        Link {
            flow: 0_f64,
            node_identification: ComponentIdentification::Named {
                name: name_of_linked_node.to_string(),
            }
        }
    }
}
