use std::collections::HashMap;
use crate::io::csv_io::csv_string_to_f64_vec;
use crate::misc::location::Location;
use crate::model_inputs::DynamicInput;
use crate::numerical::table::Table;
use crate::model::Model;
use crate::misc::link_helper::LinkHelper;
use crate::misc::misc_functions::{is_valid_variable_name, split_interleaved, true_or_false, parse_csv_to_bool_option_u32};
use crate::nodes::{NodeEnum, blackhole_node::BlackholeNode, confluence_node::ConfluenceNode, gauge_node::GaugeNode, loss_node::LossNode, splitter_node::SplitterNode, user_node::UserNode,
                   gr4j_node::Gr4jNode, inflow_node::InflowNode, routing_node::RoutingNode,
                   sacramento_node::SacramentoNode, storage_node::StorageNode};

const INLET: u8 = 0; //always inlet 0
const DS_1_OUTLET: u8 = 0; //ds_1 is outlet 0
const DS_2_OUTLET: u8 = 1; //ds_2 is outlet 1



/// Converts INI-parsed HashMap to Model struct.
/// Returns Model on success, error message on failure.
pub fn result_map_to_model_0_0_1(map: HashMap<String, HashMap<String, Option<String>>>) -> Result<Model, String> {

    // Create a new model
    let mut model = Model::new();

    // For building links I need to keep a list of link details, and then create the links
    // after all the nodes are done. The function model.add_link(...) accepts node and outlet
    // indices rather than names. So I'll need to know those indices.
    let mut vec_link_defs: Vec<LinkHelper> = Vec::new();

    //Iterate through the items in the ini_file
    for (k, v) in map {

        if k == "attributes" {
            // -------------------------------------------------------------------------------------
            // Loading attributes section
            // -------------------------------------------------------------------------------------
            for (vp, _vv) in &v {
                if vp == "ini_version" {
                    // Already read this
                } else {
                    return Err(format!("Unexpected attribute: {}", vp));
                }
            }
        } else if k == "inputs" {
            // -------------------------------------------------------------------------------------
            // Loading inputs section
            // -------------------------------------------------------------------------------------
            for (vp, _vv) in &v {
                // Each vp is a path to an input file
                let _ = model.load_input_data(vp.as_str())?;
            }
        } else if k == "constants" {
            // -------------------------------------------------------------------------------------
            // Loading constants section
            // -------------------------------------------------------------------------------------
            for (vp, vv) in &v {
                // Each vp is a variable name, and vv is the assigned value which should be a number
                let name = vp.to_lowercase();
                if !is_valid_variable_name(&name) { Err(format!("Invalid variable name: {}", vp))?; }
                let vvc = vv.as_ref().ok_or(format!("Missing assignment for constant '{}'", vp))?;
                let value = vvc.parse::<f64>().map_err(|_| format!("Invalid '{}' value for constant '{}': must be a number", vvc, vp))?;
                model.data_cache.constants.set_value(vp, value);
            }
        } else if k.starts_with("node.") {
            // -------------------------------------------------------------------------------------
            // Loading a node
            // -------------------------------------------------------------------------------------
            let node_name = &k[5..];
            let node_type = v.get("type")
                .ok_or("Missing 'type' field")?
                .as_ref()
                .ok_or("Empty 'type' field")?;

            let node_enum= match node_type.as_str() {
                "confluence" => {
                    let mut n = ConfluenceNode::new();
                    n.name = node_name.to_string();
                    for (vp, vv) in &v {
                        let vvc = vv.as_ref()
                            .ok_or(format!("Missing '{}' value for node '{}'", vp, node_name))?;
                        if vp == "loc" {
                            n.location = Location::from_str(vvc)?;
                        } else if vp == "ds_1" {
                            let ds_node_name= vv.as_ref()
                                .ok_or(format!("Missing '{}' value for node '{}'", vp, node_name))?;
                            vec_link_defs.push(LinkHelper::new_from_names(&n.name, &ds_node_name, DS_1_OUTLET, INLET))
                        } else if vp == "type" {
                            // skipping this
                        } else {
                            return Err(format!("Unexpected parameter '{}' for node '{}'", vp, node_name));
                        }
                    }
                    NodeEnum::ConfluenceNode(n)
                },
                "gauge" => {
                    let mut n = GaugeNode::new();
                    n.name = node_name.to_string();
                    for (vp, vv) in &v {
                        let vvc = vv.as_ref()
                            .ok_or(format!("Missing '{}' value for node '{}'", vp, node_name))?;
                        if vp == "loc" {
                            n.location = Location::from_str(vvc)?;
                        } else if vp == "observed" {
                            n.observed_flow_input = DynamicInput::from_string(vvc, &mut model.data_cache, false)?;
                        } else if vp == "ds_1" {
                            let ds_node_name= vv.as_ref()
                                .ok_or(format!("Missing '{}' value for node '{}'", vp, node_name))?;
                            vec_link_defs.push(LinkHelper::new_from_names(&n.name, &ds_node_name, DS_1_OUTLET, INLET))
                        } else if vp == "type" {
                            // skipping this
                        } else {
                            return Err(format!("Unexpected parameter '{}' for node '{}'", vp, node_name));
                        }
                    }
                    NodeEnum::GaugeNode(n)
                },
                "loss" => {
                    let mut n = LossNode::new();
                    n.name = node_name.to_string();
                    for (vp, vv) in &v {
                        let vvc = vv.as_ref()
                            .ok_or(format!("Missing '{}' value for node '{}'", vp, node_name))?;
                        if vp == "loc" {
                            n.location = Location::from_str(vvc)?;
                        } else if vp == "table" {
                            n.loss_table = Table::from_csv_string(vvc.as_str(), 2, false)
                                .expect(format!("Could not parse loss table {}", n.name).as_str());
                        } else if vp == "ds_1" {
                            let ds_node_name= vv.as_ref()
                                .ok_or(format!("Missing '{}' value for node '{}'", vp, node_name))?;
                            vec_link_defs.push(LinkHelper::new_from_names(&n.name, &ds_node_name, DS_1_OUTLET, INLET))
                        } else if vp == "type" {
                            // skipping this
                        } else {
                            return Err(format!("Unexpected parameter '{}' for node '{}'", vp, node_name));
                        }
                    }
                    NodeEnum::LossNode(n)
                },
                "splitter" => {
                    let mut n = SplitterNode::new();
                    n.name = node_name.to_string();
                    for (vp, vv) in &v {
                        let vvc = vv.as_ref()
                            .ok_or(format!("Missing '{}' value for node '{}'", vp, node_name))?;
                        if vp == "loc" {
                            n.location = Location::from_str(vvc)?;
                        } else if vp == "table" {
                            n.splitter_table = Table::from_csv_string(vvc.as_str(), 2, false)
                                .expect(format!("Could not parse splitter table {}", n.name).as_str());
                        } else if vp == "ds_1" {
                            let ds_node_name= vv.as_ref()
                                .ok_or(format!("Missing '{}' value for node '{}'", vp, node_name))?;
                            vec_link_defs.push(LinkHelper::new_from_names(&n.name, &ds_node_name, DS_1_OUTLET, INLET))
                        } else if vp == "ds_2" {
                            let ds_node_name= vv.as_ref()
                                .ok_or(format!("Missing '{}' value for node '{}'", vp, node_name))?;
                            vec_link_defs.push(LinkHelper::new_from_names(&n.name, &ds_node_name, DS_2_OUTLET, INLET))
                        } else if vp == "type" {
                            // skipping this
                        } else {
                            return Err(format!("Unexpected parameter '{}' for node '{}'", vp, node_name));
                        }
                    }
                    NodeEnum::SplitterNode(n)
                },
                "user" => {
                    let mut n = UserNode::new();
                    n.name = node_name.to_string();
                    for (vp, vv) in &v {
                        let vvc = vv.as_ref()
                            .ok_or(format!("Missing '{}' value for node '{}'", vp, node_name))?;
                        if vp == "loc" {
                            n.location = Location::from_str(vvc)?;
                        } else if vp == "demand" {
                            n.demand_input = DynamicInput::from_string(vvc, &mut model.data_cache, true)?;
                        } else if vp == "annual_cap" {
                            let params = csv_string_to_f64_vec(vvc.as_str())?;
                            if params.len() != 2 {
                                return Err(format!("User 'annual_cap' must have 2 values, got {}", params.len()));
                            }
                            n.annual_cap = Some(params[0]);
                            n.annual_cap_reset_month = params[1] as u32;
                        } else if vp == "pump" {
                            n.pump_capacity = DynamicInput::from_string(vvc, &mut model.data_cache, true)?;
                        } else if vp == "flow_threshold" {
                            n.flow_threshold = DynamicInput::from_string(vvc, &mut model.data_cache, true)?;
                        } else if vp == "demand_carryover" {
                            let vvc = vv.as_ref()
                                .ok_or(format!("Missing '{}' value for node '{}'", vp, node_name))?;
                            (n.demand_carryover_allowed, n.demand_carryover_reset_month) = parse_csv_to_bool_option_u32(vvc)?;
                        } else if vp == "ds_1" {
                            let ds_node_name= vv.as_ref()
                                .ok_or(format!("Missing '{}' value for node '{}'", vp, node_name))?;
                            vec_link_defs.push(LinkHelper::new_from_names(&n.name, &ds_node_name, DS_1_OUTLET, INLET))
                        } else if vp == "type" {
                            // skipping this
                        } else {
                            return Err(format!("Unexpected parameter '{}' for node '{}'", vp, node_name));
                        }
                    }
                    NodeEnum::UserNode(n)
                },
                "gr4j" => {
                    let mut n = Gr4jNode::new();
                    n.name = node_name.to_string();
                    for (vp, vv) in &v {
                        let vvc = vv.as_ref()
                            .ok_or(format!("Missing '{}' value for node '{}'", vp, node_name))?;
                        if vp == "loc" {
                            n.location = Location::from_str(vvc)?;
                        } else if vp == "evap" {
                            n.evap_mm_input = DynamicInput::from_string(vvc, &mut model.data_cache, true)?;
                        } else if vp == "rain" {
                            n.rain_mm_input = DynamicInput::from_string(vvc, &mut model.data_cache, true)?;
                        } else if vp == "area" {
                            n.area_km2 = vvc.parse::<f64>()
                                .map_err(|_| format!("Invalid '{}' value for node '{}': not a valid number", vp, node_name))?;
                        } else if vp == "params" {
                            let params = csv_string_to_f64_vec(vvc.as_str())?;
                            if params.len() != 4 {
                                return Err(format!("GR4J params must have 4 values, got {}", params.len()));
                            }
                            n.gr4j_model.x1 = params[0];
                            n.gr4j_model.x2 = params[1];
                            n.gr4j_model.x3 = params[2];
                            n.gr4j_model.x4 = params[3];
                        } else if vp == "ds_1" {
                            let ds_node_name= vv.as_ref()
                                .ok_or(format!("Missing '{}' value for node '{}'", vp, node_name))?;
                            vec_link_defs.push(LinkHelper::new_from_names(&n.name, &ds_node_name, DS_1_OUTLET, INLET))
                        } else if vp == "type" {
                            // skipping this
                        } else {
                            return Err(format!("Unexpected parameter '{}' for node '{}'", vp, node_name));
                        }
                    }
                    NodeEnum::Gr4jNode(n)
                },
                "blackhole" => {
                    let mut n = BlackholeNode::new();
                    n.name = node_name.to_string();
                    for (vp, vv) in &v {
                        let vvc = vv.as_ref()
                            .ok_or(format!("Missing '{}' value for node '{}'", vp, node_name))?;
                        if vp == "loc" {
                            n.location = Location::from_str(vvc)?;
                        } else if vp == "ds_1" {
                            let ds_node_name= vv.as_ref()
                                .ok_or(format!("Missing '{}' value for node '{}'", vp, node_name))?;
                            vec_link_defs.push(LinkHelper::new_from_names(&n.name, &ds_node_name, DS_1_OUTLET, INLET))
                        } else if vp == "type" {
                            // skipping this
                        } else {
                            return Err(format!("Unexpected parameter '{}' for node '{}'", vp, node_name));
                        }
                    }
                    NodeEnum::BlackholeNode(n)
                },
                "inflow" => {
                    let mut n = InflowNode::new();
                    n.name = node_name.to_string();
                    for (vp, vv) in &v {
                        let vvc = vv.as_ref()
                            .ok_or(format!("Missing '{}' value for node '{}'", vp, node_name))?;
                        if vp == "loc" {
                            n.location = Location::from_str(vvc)?;
                        } else if vp == "inflow" {
                            n.inflow_input = DynamicInput::from_string(vvc, &mut model.data_cache, true)?;
                        } else if vp == "ds_1" {
                            let ds_node_name= vv.as_ref()
                                .ok_or(format!("Missing '{}' value for node '{}'", vp, node_name))?;
                            vec_link_defs.push(LinkHelper::new_from_names(&n.name, &ds_node_name, DS_1_OUTLET, INLET))
                        } else if vp == "type" {
                            // skipping this
                        } else {
                            return Err(format!("Unexpected parameter '{}' for node '{}'", vp, node_name));
                        }
                    }
                    NodeEnum::InflowNode(n)
                },
                "routing" => {
                    let mut n = RoutingNode::new();
                    let mut r_flows: Option<Vec<f64>> = None;
                    let mut r_times: Option<Vec<f64>> = None;
                    n.name = node_name.to_string();
                    for (vp, vv) in &v {
                        let vvc = vv.as_ref()
                            .ok_or(format!("Missing '{}' value for node '{}'", vp, node_name))?;
                        if vp == "loc" {
                            n.location = Location::from_str(vvc)?;
                        } else if vp == "lag" {
                            n.set_lag(vvc.parse::<i32>()
                                .map_err(|_| format!("Invalid '{}' value for node '{}': required integer", vp, node_name))?);
                        } else if vp == "n_divs" {
                            n.set_divs(vvc.parse::<usize>()
                                .map_err(|_| format!("Invalid '{}' value for node '{}': required non-negative integer", vp, node_name))?);
                        } else if vp == "x" {
                            n.set_x(vvc.parse::<f64>()
                                .map_err(|_| format!("Invalid '{}' value for node '{}': not a valid number", vp, node_name))?);
                        } else if vp == "pwl" {
                            let all_values = csv_string_to_f64_vec(vvc.as_str())?;
                            let nvals = all_values.len();
                            let nrows = nvals / 2;
                            if all_values.len() % 2 > 0 {
                                return Err(format!("Pwl table must contain an even number of elements, but found {}", nvals))
                            } else if nrows > 32 {
                                return Err(format!("Pwl table must contain no more than 32 rows but found {}", nrows))
                            } else if nrows < 1 {
                                return Err(format!("Pwl table must contain at least one row"))
                            }
                            let (index_flows, index_times) = split_interleaved(&all_values);
                            n.set_routing_table(index_flows, index_times);
                        } else if vp == "index_flows" {
                            let index_flows = csv_string_to_f64_vec(vvc.as_str())?;
                            if let Some(index_times) = &r_times {
                                n.set_routing_table(index_flows, index_times.clone());
                            } else {
                                r_flows = Some(index_flows);
                            }
                        } else if vp == "index_times" {
                            let index_times = csv_string_to_f64_vec(vvc.as_str())?;
                            if let Some(index_flows) = &r_flows {
                                n.set_routing_table(index_flows.clone(), index_times);
                            } else {
                                r_times = Some(index_times);
                            }
                        } else if vp == "ds_1" {
                            let ds_node_name= vv.as_ref()
                                .ok_or(format!("Missing '{}' value for node '{}'", vp, node_name))?;
                            vec_link_defs.push(LinkHelper::new_from_names(&n.name, &ds_node_name, DS_1_OUTLET, INLET))
                        } else if vp == "type" {
                            // skipping this
                        } else {
                            return Err(format!("Unexpected parameter '{}' for node '{}'", vp, node_name));
                        }
                    }
                    NodeEnum::RoutingNode(n)
                },
                "sacramento" => {
                    let mut n = SacramentoNode::new();
                    n.name = node_name.to_string();
                    for (vp, vv) in &v {
                        let vvc = vv.as_ref()
                            .ok_or(format!("Missing '{}' value for node '{}'", vp, node_name))?;
                        if vp == "loc" {
                            n.location = Location::from_str(vvc)?;
                        } else if vp == "evap" {
                            n.evap_mm_input = DynamicInput::from_string(vvc, &mut model.data_cache, true)?;
                        } else if vp == "rain" {
                            n.rain_mm_input = DynamicInput::from_string(vvc, &mut model.data_cache, true)?;
                        } else if vp == "area" {
                            n.area_km2 = vvc.parse::<f64>()
                                .map_err(|_| format!("Invalid '{}' value for node '{}': not a valid number", vp, node_name))?;
                        } else if vp == "params" {
                            let params = csv_string_to_f64_vec(vvc.as_str())?;
                            if params.len() < 17 {
                                return Err(format!("Sacramento params must have 17 values, got {}", params.len()));
                            }
                            n.sacramento_model.set_params_by_vec(params);
                        } else if vp == "ds_1" {
                            let ds_node_name= vv.as_ref()
                                .ok_or(format!("Missing '{}' value for node '{}'", vp, node_name))?;
                            vec_link_defs.push(LinkHelper::new_from_names(&n.name, &ds_node_name, DS_1_OUTLET, INLET))
                        } else if vp == "type" {
                            // skipping this
                        } else {
                            return Err(format!("Unexpected parameter '{}' for node '{}'", vp, node_name));
                        }
                    }
                    NodeEnum::SacramentoNode(n)
                },
                "storage" => {
                    let mut n = StorageNode::new();
                    n.name = node_name.to_string();
                    for (vp, vv) in &v {
                        let vvc = vv.as_ref()
                            .ok_or(format!("Missing '{}' value for node '{}'", vp, node_name))?;
                        if vp == "loc" {
                            n.location = Location::from_str(vvc)?;
                        } else if vp == "evap" {
                            n.evap_mm_input = DynamicInput::from_string(vvc, &mut model.data_cache, true)?;
                        } else if vp == "rain" {
                            n.rain_mm_input = DynamicInput::from_string(vvc, &mut model.data_cache, true)?;
                        } else if vp == "seep" {
                            n.seep_mm_input = DynamicInput::from_string(vvc, &mut model.data_cache, true)?;
                        } else if vp == "pond_demand" {
                            n.demand_input = DynamicInput::from_string(vvc, &mut model.data_cache, true)?;
                        } else if vp == "dimensions" {
                            n.d = Table::from_csv_string(vvc.as_str(), 4, false)
                                .expect(format!("Could not parse dimensions table {}", n.name).as_str());
                        } else if vp == "dimensions_file" {
                            n.d = Table::from_csv_file(vvc.as_str());
                        } else if vp == "ds_1" {
                            let ds_node_name= vv.as_ref()
                                .ok_or(format!("Missing '{}' value for node '{}'", vp, node_name))?;
                            vec_link_defs.push(LinkHelper::new_from_names(&n.name, &ds_node_name, DS_1_OUTLET, INLET))
                        } else if vp == "ds_2" {
                            let ds_node_name= vv.as_ref()
                                .ok_or(format!("Missing '{}' value for node '{}'", vp, node_name))?;
                            vec_link_defs.push(LinkHelper::new_from_names(&n.name, &ds_node_name, DS_2_OUTLET, INLET))
                        } else if vp == "type" {
                            // skipping this
                        } else {
                            return Err(format!("Unexpected parameter '{}' for node '{}'", vp, node_name));
                        }
                    }
                    NodeEnum::StorageNode(n)
                },
                _ => {
                    return Err(format!("Unexpected node type: {}", node_type))
                },
            };
            model.add_node(node_enum);

        } else if k == "outputs" {
            // -------------------------------------------------------------------------------------
            // Loading outputs section
            // -------------------------------------------------------------------------------------
            for (vp, _vv) in &v {
                if vp == "file" {
                    // TODO: specify the filename to write file to disk? Or should this not be part of the model?
                } else {
                    // Each vp is something we want to record
                    model.outputs.push(vp.to_string());
                }
            }
        } else {
            Err(format!("Unexpected section: {}", k))?
        }
    }

    // -------------------------------------------------------------------------------------
    // Create all the links
    // -------------------------------------------------------------------------------------
    for link_helper in vec_link_defs {
        let from_node_idx = model.node_lookup.get(&link_helper.from_node_name)
            .ok_or(format!("Node '{}' not found", link_helper.from_node_name))?;
        let to_node_idx = model.node_lookup.get(&link_helper.to_node_name)
            .ok_or(format!("Node '{}' not found", link_helper.to_node_name))?;
        model.add_link(*from_node_idx, *to_node_idx, link_helper.from_outlet, link_helper.to_inlet);
    }

    // -------------------------------------------------------------------------------------
    // Return the model
    // -------------------------------------------------------------------------------------
    Ok(model)
}