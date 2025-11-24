use crate::io::csv_io::csv_string_to_f64_vec;
use crate::io::custom_ini_parser::IniDocument;
use crate::misc::location::Location;
use crate::model_inputs::DynamicInput;
use crate::numerical::table::Table;
use crate::model::Model;
use crate::misc::link_helper::LinkHelper;
use crate::tid::utils::{date_string_to_u64_flexible, u64_to_date_string};
use crate::misc::misc_functions::{is_valid_variable_name, split_interleaved, parse_csv_to_bool_option_u32, require_non_empty, format_vec_as_multiline_table, set_property_if_not_empty};
use crate::nodes::{NodeEnum, blackhole_node::BlackholeNode, confluence_node::ConfluenceNode, gauge_node::GaugeNode, loss_node::LossNode, splitter_node::SplitterNode, user_node::UserNode, gr4j_node::Gr4jNode, inflow_node::InflowNode, routing_node::RoutingNode, sacramento_node::SacramentoNode, storage_node::StorageNode, Node};

const INLET: u8 = 0; //always inlet 0
const DS_1_OUTLET: u8 = 0; //ds_1 is outlet 0
const DS_2_OUTLET: u8 = 1; //ds_2 is outlet 1



/// Converts INI-doc to Model struct.
/// Returns Model on success, error message on failure.
///
/// # Arguments
/// * `ini_doc` - The parsed INI document
/// * `working_directory` - Optional working directory for resolving relative paths.
///   If None, uses the current working directory.
pub fn ini_doc_to_model_0_0_1(ini_doc: IniDocument, working_directory: Option<std::path::PathBuf>) -> Result<Model, String> {

    // Create a new model
    let mut model = Model::new();

    // Set the working directory if provided (before loading any data!)
    if let Some(wd) = working_directory {
        model.working_directory = wd;
    }

    // Store a copy of the ini_doc in the model for later use
    model.ini_document = Some(ini_doc.clone());

    // For building links I need to keep a list of link details, and then create the links
    // after all the nodes are done. The function model.add_link(...) accepts node and outlet
    // indices rather than names. So I'll need to know those indices.
    let mut vec_link_defs: Vec<LinkHelper> = Vec::new();

    // Iterate over the sections of the ini_doc and construct the model as we go
    for (section_name, ini_section) in ini_doc.sections {

        if section_name == "kalix" {
            // -------------------------------------------------------------------------------------
            // Parsing kalix
            // -------------------------------------------------------------------------------------
            for (name, ini_property) in ini_section.properties {
                // Each property is a path to an input file
                let name_lower = name.to_lowercase();
                if name_lower == "start" {
                    let timestamp = date_string_to_u64_flexible(ini_property.value.as_str())?.0;
                    model.configuration.specified_sim_start_timestamp = Some(timestamp);
                } else if name_lower == "end" {
                    let timestamp = date_string_to_u64_flexible(ini_property.value.as_str())?.0;
                    model.configuration.specified_sim_end_timestamp = Some(timestamp);
                }
            }
        } else if section_name == "inputs" {
            // -------------------------------------------------------------------------------------
            // Parsing inputs
            // -------------------------------------------------------------------------------------
            for (name, ini_property) in ini_section.properties {
                // Each property is a path to an input file
                let _ = model.load_input_data(name.as_str())
                    .map_err(|e| format!("Error on line {}: {}", ini_property.line_number, e))?;
            }
        } else if section_name == "constants" {
            // -------------------------------------------------------------------------------------
            // Parsing constants
            // -------------------------------------------------------------------------------------
            for (name, ini_property) in ini_section.properties {
                // Each name defines a constant, and each value should be a number
                let const_name = name.to_lowercase();
                if !is_valid_variable_name(&name) { Err(format!("Error on line {}: Invalid constant name '{}'", ini_property.line_number, const_name))?; }
                let const_value = ini_property.value.parse::<f64>()
                    .map_err(|_| format!("Error on line {}: Value for constant '{}': must be a number", ini_property.line_number, ini_property.value))?;
                model.data_cache.constants.set_value(const_name.as_str(), const_value);
            }
        } else if section_name.starts_with("node.") {
            // -------------------------------------------------------------------------------------
            // Parsing nodes
            // -------------------------------------------------------------------------------------

            // Get the name and type
            let node_name = &section_name[5..];
            let node_type = ini_section.properties.get("type")
                .ok_or(format!("Error on line {}: Missing 'type'", ini_section.line_number))?.value.to_lowercase();

            // Now match on the type and do different stuff per type
            let node_enum= match node_type.as_str() {
                "blackhole" => {
                    let mut n = BlackholeNode::new();
                    n.name = node_name.to_string();
                    for (name, ini_property) in ini_section.properties {
                        let name_lower = name.to_lowercase();
                        let v = require_non_empty(&ini_property.value, &name, ini_property.line_number)?;
                        if name_lower == "loc" {
                            n.location = Location::from_str(v)
                                .map_err(|e| format!("Error on line {}: {}", ini_property.line_number, e))?;
                        } else if name_lower == "type" {
                            // Skipping this
                        } else if name_lower == "ds_1" {
                            vec_link_defs.push(LinkHelper::new_from_names(&n.name, v, DS_1_OUTLET, INLET))
                        } else {
                            return Err(format!("Error on line {}: Unexpected parameter '{}' for node '{}'",
                                              ini_property.line_number, name, node_name));
                        }
                    }
                    NodeEnum::BlackholeNode(n)
                }
                "confluence" => {
                    let mut n = ConfluenceNode::new();
                    n.name = node_name.to_string();
                    for (name, ini_property) in ini_section.properties {
                        let name_lower = name.to_lowercase();
                        let v = require_non_empty(&ini_property.value, &name, ini_property.line_number)?;
                        if name_lower == "loc" {
                            n.location = Location::from_str(v)
                                .map_err(|e| format!("Error on line {}: {}", ini_property.line_number, e))?;
                        } else if name_lower == "type" {
                            // Skipping this
                        } else if name_lower == "ds_1" {
                            vec_link_defs.push(LinkHelper::new_from_names(&n.name, v, DS_1_OUTLET, INLET))
                        } else {
                            return Err(format!("Error on line {}: Unexpected parameter '{}' for node '{}'", ini_property.line_number, name, node_name));
                        }
                    }
                    NodeEnum::ConfluenceNode(n)
                }
                "gauge" => {
                    let mut n = GaugeNode::new();
                    n.name = node_name.to_string();
                    for (name, ini_property) in ini_section.properties {
                        let name_lower = name.to_lowercase();
                        let v = require_non_empty(&ini_property.value, &name, ini_property.line_number)?;
                        if name_lower == "loc" {
                            n.location = Location::from_str(v)
                                .map_err(|e| format!("Error on line {}: {}", ini_property.line_number, e))?;
                        } else if name_lower == "type" {
                            // Skipping this
                        } else if name_lower == "ds_1" {
                            vec_link_defs.push(LinkHelper::new_from_names(&n.name, v, DS_1_OUTLET, INLET))
                        } else if name_lower == "observed" {
                            n.observed_flow_input = DynamicInput::from_string(v, &mut model.data_cache, false)
                                .map_err(|e| format!("Error on line {}: {}", ini_property.line_number, e))?;
                        } else {
                            return Err(format!("Error on line {}: Unexpected parameter '{}' for node '{}'",
                                              ini_property.line_number, name, node_name));
                        }
                    }
                    NodeEnum::GaugeNode(n)
                }
                "gr4j" => {
                    let mut n = Gr4jNode::new();
                    n.name = node_name.to_string();
                    for (name, ini_property) in ini_section.properties {
                        let name_lower = name.to_lowercase();
                        let v = require_non_empty(&ini_property.value, &name, ini_property.line_number)?;
                        if name_lower == "loc" {
                            n.location = Location::from_str(v)
                                .map_err(|e| format!("Error on line {}: {}", ini_property.line_number, e))?;
                        } else if name_lower == "type" {
                            // Skipping this
                        } else if name_lower == "ds_1" {
                            vec_link_defs.push(LinkHelper::new_from_names(&n.name, v, DS_1_OUTLET, INLET))
                        } else if name_lower == "evap" {
                            n.evap_mm_input = DynamicInput::from_string(v, &mut model.data_cache, true)
                                .map_err(|e| format!("Error on line {}: {}", ini_property.line_number, e))?;
                        } else if name_lower == "rain" {
                            n.rain_mm_input = DynamicInput::from_string(v, &mut model.data_cache, true)
                                .map_err(|e| format!("Error on line {}: {}", ini_property.line_number, e))?;
                        } else if name_lower == "area" {
                            n.area_km2 = v.parse::<f64>()
                                .map_err(|_| format!("Error on line {}: Invalid '{}' value for node '{}': not a valid number",
                                                     ini_property.line_number, name, node_name))?;
                        } else if name_lower == "params" {
                            let params = csv_string_to_f64_vec(v)
                                .map_err(|e| format!("Error on line {}: {}", ini_property.line_number, e))?;
                            if params.len() != 4 {
                                return Err(format!("Error on line {}: GR4J params must have 4 values, got {}",
                                                   ini_property.line_number, params.len()));
                            }
                            n.gr4j_model.x1 = params[0];
                            n.gr4j_model.x2 = params[1];
                            n.gr4j_model.x3 = params[2];
                            n.gr4j_model.x4 = params[3];
                        } else {
                            return Err(format!("Error on line {}: Unexpected parameter '{}' for node '{}'",
                                              ini_property.line_number, name, node_name));
                        }
                    }
                    NodeEnum::Gr4jNode(n)
                }
                "inflow" => {
                    let mut n = InflowNode::new();
                    n.name = node_name.to_string();
                    for (name, ini_property) in ini_section.properties {
                        let name_lower = name.to_lowercase();
                        let v = require_non_empty(&ini_property.value, &name, ini_property.line_number)?;
                        if name_lower == "loc" {
                            n.location = Location::from_str(v)
                                .map_err(|e| format!("Error on line {}: {}", ini_property.line_number, e))?;
                        } else if name_lower == "type" {
                            // Skipping this
                        } else if name_lower == "ds_1" {
                            vec_link_defs.push(LinkHelper::new_from_names(&n.name, v, DS_1_OUTLET, INLET))
                        } else if name_lower == "inflow" {
                            n.inflow_input = DynamicInput::from_string(v, &mut model.data_cache, true)
                                .map_err(|e| format!("Error on line {}: {}", ini_property.line_number, e))?;
                        } else {
                            return Err(format!("Error on line {}: Unexpected parameter '{}' for node '{}'",
                                              ini_property.line_number, name, node_name));
                        }
                    }
                    NodeEnum::InflowNode(n)
                }
                "loss" => {
                    let mut n = LossNode::new();
                    n.name = node_name.to_string();
                    for (name, ini_property) in ini_section.properties {
                        let name_lower = name.to_lowercase();
                        let v = require_non_empty(&ini_property.value, &name, ini_property.line_number)?;
                        if name_lower == "loc" {
                            n.location = Location::from_str(v)
                                .map_err(|e| format!("Error on line {}: {}", ini_property.line_number, e))?;
                        } else if name_lower == "type" {
                            // Skipping this
                        } else if name_lower == "ds_1" {
                            vec_link_defs.push(LinkHelper::new_from_names(&n.name, v, DS_1_OUTLET, INLET))
                        } else if name_lower == "table" {
                            n.loss_table = Table::from_csv_string(v, 2, false)
                                .map_err(|e| format!("Error on line {}: Could not parse loss table for node '{}': {}",
                                                     ini_property.line_number, node_name, e))?;
                        } else {
                            return Err(format!("Error on line {}: Unexpected parameter '{}' for node '{}'",
                                              ini_property.line_number, name, node_name));
                        }
                    }
                    NodeEnum::LossNode(n)
                }
                "routing" => {
                    let mut n = RoutingNode::new();
                    n.name = node_name.to_string();
                    let mut r_flows: Option<Vec<f64>> = None;
                    let mut r_times: Option<Vec<f64>> = None;
                    for (name, ini_property) in ini_section.properties {
                        let name_lower = name.to_lowercase();
                        let v = require_non_empty(&ini_property.value, &name, ini_property.line_number)?;
                        if name_lower == "loc" {
                            n.location = Location::from_str(v)
                                .map_err(|e| format!("Error on line {}: {}", ini_property.line_number, e))?;
                        } else if name_lower == "type" {
                            // Skipping this
                        } else if name_lower == "ds_1" {
                            vec_link_defs.push(LinkHelper::new_from_names(&n.name, v, DS_1_OUTLET, INLET))
                        } else if name_lower == "lag" {
                            n.set_lag(v.parse::<i32>()
                                .map_err(|_| format!("Error on line {}: Invalid '{}' value for node '{}': required integer",
                                                     ini_property.line_number, name, node_name))?);
                        } else if name_lower == "n_divs" {
                            n.set_divs(v.parse::<usize>()
                                .map_err(|_| format!("Error on line {}: Invalid '{}' value for node '{}': required non-negative integer",
                                                     ini_property.line_number, name, node_name))?);
                        } else if name_lower == "x" {
                            n.set_x(v.parse::<f64>()
                                .map_err(|_| format!("Error on line {}: Invalid '{}' value for node '{}': not a valid number",
                                                     ini_property.line_number, name, node_name))?);
                        } else if name_lower == "pwl" {
                            let all_values = csv_string_to_f64_vec(v)
                                .map_err(|e| format!("Error on line {}: {}", ini_property.line_number, e))?;
                            let nvals = all_values.len();
                            let nrows = nvals / 2;
                            if all_values.len() % 2 > 0 {
                                return Err(format!("Error on line {}: Pwl table must contain an even number of elements, but found {}",
                                                   ini_property.line_number, nvals));
                            } else if nrows > 32 {
                                return Err(format!("Error on line {}: Pwl table must contain no more than 32 rows but found {}",
                                                   ini_property.line_number, nrows));
                            } else if nrows < 1 {
                                return Err(format!("Error on line {}: Pwl table must contain at least one row",
                                                   ini_property.line_number));
                            }
                            let (index_flows, index_times) = split_interleaved(&all_values);
                            n.set_routing_table(index_flows, index_times);
                        } else if name_lower == "index_flows" {
                            let index_flows = csv_string_to_f64_vec(v)
                                .map_err(|e| format!("Error on line {}: {}", ini_property.line_number, e))?;
                            if let Some(index_times) = &r_times {
                                n.set_routing_table(index_flows, index_times.clone());
                            } else {
                                r_flows = Some(index_flows);
                            }
                        } else if name_lower == "index_times" {
                            let index_times = csv_string_to_f64_vec(v)
                                .map_err(|e| format!("Error on line {}: {}", ini_property.line_number, e))?;
                            if let Some(index_flows) = &r_flows {
                                n.set_routing_table(index_flows.clone(), index_times);
                            } else {
                                r_times = Some(index_times);
                            }
                        } else {
                            return Err(format!("Error on line {}: Unexpected parameter '{}' for node '{}'",
                                              ini_property.line_number, name, node_name));
                        }
                    }
                    NodeEnum::RoutingNode(n)
                }
                "sacramento" => {
                    let mut n = SacramentoNode::new();
                    n.name = node_name.to_string();
                    for (name, ini_property) in ini_section.properties {
                        let name_lower = name.to_lowercase();
                        let v = require_non_empty(&ini_property.value, &name, ini_property.line_number)?;
                        if name_lower == "loc" {
                            n.location = Location::from_str(v)
                                .map_err(|e| format!("Error on line {}: {}", ini_property.line_number, e))?;
                        } else if name_lower == "type" {
                            // Skipping this
                        } else if name_lower == "ds_1" {
                            vec_link_defs.push(LinkHelper::new_from_names(&n.name, v, DS_1_OUTLET, INLET))
                        } else if name_lower == "evap" {
                            n.evap_mm_input = DynamicInput::from_string(v, &mut model.data_cache, true)
                                .map_err(|e| format!("Error on line {}: {}", ini_property.line_number, e))?;
                        } else if name_lower == "rain" {
                            n.rain_mm_input = DynamicInput::from_string(v, &mut model.data_cache, true)
                                .map_err(|e| format!("Error on line {}: {}", ini_property.line_number, e))?;
                        } else if name_lower == "area" {
                            n.area_km2 = v.parse::<f64>()
                                .map_err(|_| format!("Error on line {}: Invalid '{}' value for node '{}': not a valid number",
                                                     ini_property.line_number, name, node_name))?;
                        } else if name_lower == "params" {
                            let params = csv_string_to_f64_vec(v)
                                .map_err(|e| format!("Error on line {}: {}", ini_property.line_number, e))?;
                            if params.len() < 17 {
                                return Err(format!("Error on line {}: Sacramento params must have 17 values, got {}",
                                                   ini_property.line_number, params.len()));
                            }
                            n.sacramento_model.set_params_by_vec(params);
                        } else {
                            return Err(format!("Error on line {}: Unexpected parameter '{}' for node '{}'",
                                              ini_property.line_number, name, node_name));
                        }
                    }
                    NodeEnum::SacramentoNode(n)
                }
                "splitter" => {
                    let mut n = SplitterNode::new();
                    n.name = node_name.to_string();
                    for (name, ini_property) in ini_section.properties {
                        let name_lower = name.to_lowercase();
                        let v = require_non_empty(&ini_property.value, &name, ini_property.line_number)?;
                        if name_lower == "loc" {
                            n.location = Location::from_str(v)
                                .map_err(|e| format!("Error on line {}: {}", ini_property.line_number, e))?;
                        } else if name_lower == "type" {
                            // Skipping this
                        } else if name_lower == "ds_1" {
                            vec_link_defs.push(LinkHelper::new_from_names(&n.name, v, DS_1_OUTLET, INLET))
                        } else if name_lower == "ds_2" {
                            vec_link_defs.push(LinkHelper::new_from_names(&n.name, v, DS_2_OUTLET, INLET))
                        } else if name_lower == "table" {
                            n.splitter_table = Table::from_csv_string(v, 2, false)
                                .map_err(|e| format!("Error on line {}: Could not parse splitter table for node '{}': {}",
                                                     ini_property.line_number, node_name, e))?;
                        } else {
                            return Err(format!("Error on line {}: Unexpected parameter '{}' for node '{}'",
                                              ini_property.line_number, name, node_name));
                        }
                    }
                    NodeEnum::SplitterNode(n)
                }
                "storage" => {
                    let mut n = StorageNode::new();
                    n.name = node_name.to_string();
                    for (name, ini_property) in ini_section.properties {
                        let name_lower = name.to_lowercase();
                        let v = require_non_empty(&ini_property.value, &name, ini_property.line_number)?;
                        if name_lower == "loc" {
                            n.location = Location::from_str(v)
                                .map_err(|e| format!("Error on line {}: {}", ini_property.line_number, e))?;
                        } else if name_lower == "type" {
                            // Skipping this
                        } else if name_lower == "ds_1" {
                            vec_link_defs.push(LinkHelper::new_from_names(&n.name, v, DS_1_OUTLET, INLET))
                        } else if name_lower == "ds_2" {
                            vec_link_defs.push(LinkHelper::new_from_names(&n.name, v, DS_2_OUTLET, INLET))
                        } else if name_lower == "evap" {
                            n.evap_mm_input = DynamicInput::from_string(v, &mut model.data_cache, true)
                                .map_err(|e| format!("Error on line {}: {}", ini_property.line_number, e))?;
                        } else if name_lower == "rain" {
                            n.rain_mm_input = DynamicInput::from_string(v, &mut model.data_cache, true)
                                .map_err(|e| format!("Error on line {}: {}", ini_property.line_number, e))?;
                        } else if name_lower == "seep" {
                            n.seep_mm_input = DynamicInput::from_string(v, &mut model.data_cache, true)
                                .map_err(|e| format!("Error on line {}: {}", ini_property.line_number, e))?;
                        } else if name_lower == "pond_demand" {
                            n.demand_input = DynamicInput::from_string(v, &mut model.data_cache, true)
                                .map_err(|e| format!("Error on line {}: {}", ini_property.line_number, e))?;
                        } else if name_lower == "dimensions" {
                            n.d = Table::from_csv_string(v, 4, false)
                                .map_err(|e| format!("Error on line {}: Could not parse dimensions table for node '{}': {}",
                                                     ini_property.line_number, node_name, e))?;
                        } else if name_lower == "dimensions_file" {
                            n.d = Table::from_csv_file(v);
                                // .map_err(|e| format!("Error on line {}: Could not load dimensions file for node '{}': {}",
                                //                      ini_property.line_number, node_name, e))?;
                        } else {
                            return Err(format!("Error on line {}: Unexpected parameter '{}' for node '{}'",
                                              ini_property.line_number, name, node_name));
                        }
                    }
                    NodeEnum::StorageNode(n)
                }
                "user" => {
                    let mut n = UserNode::new();
                    n.name = node_name.to_string();
                    for (name, ini_property) in ini_section.properties {
                        let name_lower = name.to_lowercase();
                        let v = require_non_empty(&ini_property.value, &name, ini_property.line_number)?;
                        if name_lower == "loc" {
                            n.location = Location::from_str(v)
                                .map_err(|e| format!("Error on line {}: {}", ini_property.line_number, e))?;
                        } else if name_lower == "type" {
                            // Skipping this
                        } else if name_lower == "ds_1" {
                            vec_link_defs.push(LinkHelper::new_from_names(&n.name, v, DS_1_OUTLET, INLET))
                        } else if name_lower == "demand" {
                            n.demand_input = DynamicInput::from_string(v, &mut model.data_cache, true)
                                .map_err(|e| format!("Error on line {}: {}", ini_property.line_number, e))?;
                        } else if name_lower == "annual_cap" {
                            let params = csv_string_to_f64_vec(v)
                                .map_err(|e| format!("Error on line {}: {}", ini_property.line_number, e))?;
                            if params.len() != 2 {
                                return Err(format!("Error on line {}: User 'annual_cap' must have 2 values, got {}",
                                                   ini_property.line_number, params.len()));
                            }
                            n.annual_cap = Some(params[0]);
                            n.annual_cap_reset_month = params[1] as u32;
                        } else if name_lower == "pump" {
                            n.pump_capacity = DynamicInput::from_string(v, &mut model.data_cache, true)
                                .map_err(|e| format!("Error on line {}: {}", ini_property.line_number, e))?;
                        } else if name_lower == "flow_threshold" {
                            n.flow_threshold = DynamicInput::from_string(v, &mut model.data_cache, true)
                                .map_err(|e| format!("Error on line {}: {}", ini_property.line_number, e))?;
                        } else if name_lower == "demand_carryover" {
                            (n.demand_carryover_allowed, n.demand_carryover_reset_month) = parse_csv_to_bool_option_u32(v)
                                .map_err(|e| format!("Error on line {}: {}", ini_property.line_number, e))?;
                        } else {
                            return Err(format!("Error on line {}: Unexpected parameter '{}' for node '{}'",
                                              ini_property.line_number, name, node_name));
                        }
                    }
                    NodeEnum::UserNode(n)
                }
                _ => {
                    let line_number = match ini_section.properties.get("type") {
                        Some(ini_property) => ini_property.line_number,
                        None => ini_section.line_number,
                    };
                    return Err(format!("Error on line {}: Unknown node type '{}'",  line_number, node_type))
                }
            };
            model.add_node(node_enum);
        } else if section_name == "outputs" {
            // -------------------------------------------------------------------------------------
            // Parsing outputs
            // -------------------------------------------------------------------------------------
            for (name, _ini_property) in ini_section.properties {
                // Each property is a model result we want to record
                model.outputs.push(name);
            }
        } else {
            // -------------------------------------------------------------------------------------
            // Unexpected section
            // -------------------------------------------------------------------------------------
            return Err(format!("Error on line {}: Unexpected section '{}'", ini_section.line_number, section_name));
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



pub fn model_to_ini_doc_0_0_1(model: &Model) -> IniDocument {

    // Start by cloning the model's ini_doc if it has one. Otherwise we make a new one.
    let mut ini_doc = match &model.ini_document {
        Some(doc) => doc.clone(),
        None => IniDocument::new(),
    };

    // Invalidate the ini_doc
    ini_doc.invalidate_all();

    // Set the ini version
    ini_doc.set_property("kalix", "version", "0.0.1");

    // List all input files
    for file_path in &model.input_file_paths {
        ini_doc.set_property("inputs", file_path.as_str(), "");
    }

    // List all constants
    for (name, value) in model.data_cache.constants.get_name_value_pairs() {
        ini_doc.set_property("constants", name.as_str(), value.to_string().as_str());
    }

    // List all nodes
    for node_enum in &model.nodes {
        match node_enum {
            NodeEnum::BlackholeNode(n) => {
                let section_name = format!("node.{}", n.name);
                ini_doc.set_property(section_name.as_str(), "loc", n.location.to_string().as_str());
                ini_doc.set_property(section_name.as_str(), "type", "blackhole");
            }
            NodeEnum::ConfluenceNode(n) => {
                let section_name = format!("node.{}", n.name);
                ini_doc.set_property(section_name.as_str(), "loc", n.location.to_string().as_str());
                ini_doc.set_property(section_name.as_str(), "type", "confluence");
            }
            NodeEnum::GaugeNode(n) => {
                let section_name = format!("node.{}", n.name);
                ini_doc.set_property(section_name.as_str(), "loc", n.location.to_string().as_str());
                ini_doc.set_property(section_name.as_str(), "type", "gauge");
                set_property_if_not_empty(&mut ini_doc, section_name.as_str(), "observed", &n.observed_flow_input.to_string());
            }
            NodeEnum::Gr4jNode(n) => {
                let section_name = format!("node.{}", n.name);
                ini_doc.set_property(section_name.as_str(), "loc", n.location.to_string().as_str());
                ini_doc.set_property(section_name.as_str(), "type", "gr4j");
                set_property_if_not_empty(&mut ini_doc, section_name.as_str(), "evap", &n.evap_mm_input.to_string());
                set_property_if_not_empty(&mut ini_doc, section_name.as_str(), "rain", &n.rain_mm_input.to_string());
                ini_doc.set_property(section_name.as_str(), "area", n.area_km2.to_string().as_str());
                let params_str = format!("{}, {}, {}, {}", n.gr4j_model.x1, n.gr4j_model.x2, n.gr4j_model.x3, n.gr4j_model.x4);
                ini_doc.set_property(section_name.as_str(), "params", params_str.as_str());
            }
            NodeEnum::InflowNode(n) => {
                let section_name = format!("node.{}", n.name);
                ini_doc.set_property(section_name.as_str(), "loc", n.location.to_string().as_str());
                ini_doc.set_property(section_name.as_str(), "type", "inflow");
                set_property_if_not_empty(&mut ini_doc, section_name.as_str(), "inflow", &n.inflow_input.to_string());
            }
            NodeEnum::LossNode(n) => {
                let section_name = format!("node.{}", n.name);
                ini_doc.set_property(section_name.as_str(), "loc", n.location.to_string().as_str());
                ini_doc.set_property(section_name.as_str(), "type", "loss");
                let loss_table_values = n.loss_table.get_values_as_vec();
                let loss_table_str = format_vec_as_multiline_table(&loss_table_values, n.loss_table.ncols(), 4);
                ini_doc.set_property(section_name.as_str(), "table", loss_table_str.as_str());
            }
            NodeEnum::RoutingNode(n) => {
                let section_name = format!("node.{}", n.name);
                ini_doc.set_property(section_name.as_str(), "loc", n.location.to_string().as_str());
                ini_doc.set_property(section_name.as_str(), "type", "routing");
                if n.get_divs() != 1 { ini_doc.set_property(section_name.as_str(), "n_divs", n.get_divs().to_string().as_str()); }
                if n.get_x() != 0.0 { ini_doc.set_property(section_name.as_str(), "x", n.get_x().to_string().as_str()); }
                if n.get_lag() != 0 { ini_doc.set_property(section_name.as_str(), "lag", n.get_lag().to_string().as_str()); }
                let pwl_values = n.get_routing_table_as_vec();
                if pwl_values.len() > 0 {
                    let pwl_values_str = format_vec_as_multiline_table(pwl_values.as_slice(), 2, 4);
                    ini_doc.set_property(section_name.as_str(), "pwl", pwl_values_str.as_str());
                }
            }
            NodeEnum::SacramentoNode(n) => {
                let section_name = format!("node.{}", n.name);
                ini_doc.set_property(section_name.as_str(), "loc", n.location.to_string().as_str());
                ini_doc.set_property(section_name.as_str(), "type", "sacramento");
                set_property_if_not_empty(&mut ini_doc, section_name.as_str(), "evap", &n.evap_mm_input.to_string());
                set_property_if_not_empty(&mut ini_doc, section_name.as_str(), "rain", &n.rain_mm_input.to_string());
                ini_doc.set_property(section_name.as_str(), "area", n.area_km2.to_string().as_str());
                let params = n.sacramento_model.get_params_as_vec();
                let params_str = format_vec_as_multiline_table(&params, 4, 4);
                ini_doc.set_property(section_name.as_str(), "params", params_str.as_str());
            }
            NodeEnum::SplitterNode(n) => {
                let section_name = format!("node.{}", n.name);
                ini_doc.set_property(section_name.as_str(), "loc", n.location.to_string().as_str());
                ini_doc.set_property(section_name.as_str(), "type", "splitter");
                let splitter_table_values = n.splitter_table.get_values_as_vec();
                let splitter_table_str = format_vec_as_multiline_table(&splitter_table_values, n.splitter_table.ncols(), 4);
                ini_doc.set_property(section_name.as_str(), "table", splitter_table_str.as_str());
            }
            NodeEnum::StorageNode(n) => {
                let section_name = format!("node.{}", n.name);
                ini_doc.set_property(section_name.as_str(), "loc", n.location.to_string().as_str());
                ini_doc.set_property(section_name.as_str(), "type", "storage");
                set_property_if_not_empty(&mut ini_doc, section_name.as_str(), "evap", &n.evap_mm_input.to_string());
                set_property_if_not_empty(&mut ini_doc, section_name.as_str(), "rain", &n.rain_mm_input.to_string());
                set_property_if_not_empty(&mut ini_doc, section_name.as_str(), "seep", &n.seep_mm_input.to_string());
                set_property_if_not_empty(&mut ini_doc, section_name.as_str(), "pond_demand", &n.demand_input.to_string());
                let dimensions_values = n.d.get_values_as_vec();
                let dimensions_str = format_vec_as_multiline_table(&dimensions_values, n.d.ncols(), 4);
                ini_doc.set_property(section_name.as_str(), "dimensions", dimensions_str.as_str());
            }
            NodeEnum::UserNode(n) => {
                let section_name = format!("node.{}", n.name);
                ini_doc.set_property(section_name.as_str(), "loc", n.location.to_string().as_str());
                ini_doc.set_property(section_name.as_str(), "type", "user");
                set_property_if_not_empty(&mut ini_doc, section_name.as_str(), "demand", &n.demand_input.to_string());
                set_property_if_not_empty(&mut ini_doc, section_name.as_str(), "pump", &n.pump_capacity.to_string());
                set_property_if_not_empty(&mut ini_doc, section_name.as_str(), "flow_threshold", &n.flow_threshold.to_string());
                match n.annual_cap {
                    Some(cap) => {
                        let value_str = format!("{},{}", cap, n.annual_cap_reset_month);
                        ini_doc.set_property(section_name.as_str(), "annual_cap", value_str.as_str()); }
                    None => {}
                }
                if n.demand_carryover_allowed {
                    let value = match n.demand_carryover_reset_month {
                        Some(month) => format!("true, {}", month),
                        None => "true".to_string()
                    };
                    ini_doc.set_property(section_name.as_str(), "demand_carryover", value.as_str());
                }
            }
        }
    }

    // Put in the links
    for link in &model.links {
        let us_node_name = model.nodes[link.from_node].get_name();
        let ds_node_name = model.nodes[link.to_node].get_name();
        let section_name = format!("node.{}", us_node_name);
        let property_name = match link.from_outlet {
            0 => "ds_1".to_string(),
            1 => "ds_2".to_string(),
            _ => { format!("ds_{}", link.from_outlet + 1) }, //plus one
        };
        ini_doc.set_property(section_name.as_str(), property_name.as_str(), ds_node_name);
    }

    // List all the recorders
    for name in &model.outputs {
        ini_doc.set_property("outputs", name.as_str(), "");
    }

    // Delete anything that remains invalidated
    ini_doc.remove_invalid_sections_and_properties();

    // Return
    ini_doc
}