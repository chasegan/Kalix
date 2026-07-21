use crate::hydrology::accounts::account::Account;
use crate::hydrology::accounts::account_manager::{AccountGroup, ACCOUNT_SERIES_FIELDS, GROUP_SERIES_FIELDS};
use crate::hydrology::allocation_systems::ras::RasSystem;
use crate::io::csv_io::{csv_string_to_f64_vec, csv_to_string_vec};
use crate::io::error::KalixIoError;
use crate::io::custom_ini_parser::{IniDocument, IniSection};
use crate::misc::configuration::SaveMethod;
use crate::misc::location::Location;
use crate::model_inputs::DynamicInput;
use crate::numerical::lookup_table::LookupTable;
use crate::numerical::table::Table;
use crate::model::Model;
use crate::misc::link_helper::LinkHelper;
use crate::tid::utils::{date_string_to_u64_flexible, u64_to_date_string_for_step_size};
use crate::misc::misc_functions::{is_valid_variable_name, is_valid_bare_name, split_interleaved, parse_csv_to_bool_option_u8, require_non_empty, format_vec_as_multiline_table, set_property_if_not_empty, set_property_unless_default, format_f64};
use crate::nodes::{NodeEnum, blackhole_node::BlackholeNode, confluence_node::ConfluenceNode, gauge_node::GaugeNode, loss_node::LossNode, splitter_node::SplitterNode, regulated_user_node::RegulatedUserNode, unregulated_user_node::UnregulatedUserNode, gr4j_node::Gr4jNode, inflow_node::InflowNode, routing_node::RoutingNode, sacramento_node::SacramentoNode, storage_node::StorageNode, order_control_node::OrderControlNode, Node};
use crate::hydrology::rainfall_runoff::gr4j::Gr4Variant;
use crate::nodes::storage_node::OutletDefinition;
use crate::nodes::storage_node::OutletDefinition::{OutletWithMOLAndCapacity, OutletWithMOL};

const INLET: u8 = 0; //always inlet 0
const DS_1_OUTLET: u8 = 0; //ds_1 is outlet 0
const DS_2_OUTLET: u8 = 1; //ds_2 is outlet 1
const DS_3_OUTLET: u8 = 2; //ds_3 is outlet 2
const DS_4_OUTLET: u8 = 3; //ds_4 is outlet 3



/// Converts INI-doc to Model struct.
/// Returns Model on success, error message on failure.
///
/// # Arguments
/// * `ini_doc` - The parsed INI document
/// * `working_directory` - Optional working directory for resolving relative paths.
///   If None, uses the current working directory.
pub fn ini_doc_to_model_0_0_1(ini_doc: IniDocument, working_directory: Option<std::path::PathBuf>) -> Result<Model, KalixIoError> {

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

    // -------------------------------------------------------------------------------------
    // Parsing lookup tables (pre-pass)
    // -------------------------------------------------------------------------------------
    // Tables are parsed before everything else so that node expressions can resolve
    // table.* references regardless of where the [table.*] section sits in the file.
    for (section_name, ini_section) in &ini_doc.sections {
        if let Some(table_name) = section_name.strip_prefix("table.") {
            // Table names follow the same rules as constants, and additionally must not
            // contain '.' so that table.<name>(...) call syntax stays unambiguous.
            if !is_valid_variable_name(table_name) || table_name.contains('.') {
                return Err(format!("Error on line {}: Invalid table name '{}'", ini_section.line_number, table_name).into());
            }

            let mut ncols: usize = 2;
            let mut values: Option<&str> = None;
            for (key, ini_property) in &ini_section.properties {
                match key.to_lowercase().as_str() {
                    "n_cols" => {
                        ncols = ini_property.value.trim().parse::<usize>()
                            .map_err(|_| format!("Error on line {}: n_cols for table '{}' must be an integer, got '{}'",
                                                 ini_property.line_number, table_name, ini_property.value))?;
                    }
                    "values" => values = Some(ini_property.value.as_str()),
                    other => {
                        return Err(format!("Error on line {}: Unexpected property '{}' in section '[{}]'",
                                           ini_property.line_number, other, section_name).into());
                    }
                }
            }
            let values = values.ok_or(format!("Error on line {}: Table '{}' has no 'values' property",
                                              ini_section.line_number, table_name))?;

            let table = LookupTable::from_ini_data(table_name, values, ncols)
                .map_err(|e| format!("Error on line {}: {}", ini_section.line_number, e))?;
            model.data_cache.tables.insert(table)
                .map_err(|e| format!("Error on line {}: {}", ini_section.line_number, e))?;
        }
    }

    // -------------------------------------------------------------------------------------
    // Parsing user-defined functions (pre-pass)
    // -------------------------------------------------------------------------------------
    // Functions are passive — they have no execution time of their own — and may be
    // defined anywhere in the file, even after the nodes that call them. So the [fn]
    // section is parsed before node construction (node expressions are lowered during
    // construction and need the registry populated), mirroring the [table.*] pre-pass
    // above. Placed after tables because fn bodies may call table.*; that resolves at
    // lowering, not at fn parse, so either order works.
    //
    // IniSection.properties is an IndexMap, so this preserves the file's definition
    // order (nice for round-trip). Correctness does not depend on it — functions are
    // order-independent, resolved by name at lowering.
    if let Some(ini_section) = ini_doc.sections.get("fn") {
        for (key, ini_property) in &ini_section.properties {
            model.data_cache.fns.parse_and_insert(key, &ini_property.value)
                .map_err(|e| format!("Error on line {}: {}", ini_property.line_number, e))?;
        }
        // Verify the fn call graph is a DAG (no direct or mutual recursion), once at
        // load — so even an UNUSED cyclic definition is rejected.
        model.data_cache.fns.check_dag()
            .map_err(|e| format!("Error on line {}: {}", ini_section.line_number, e))?;
    }

    // -------------------------------------------------------------------------------------
    // Parsing account groups (pre-pass)
    // -------------------------------------------------------------------------------------
    // [acc.<group>] sections declare accounts as a headed table — pure nouns, no
    // behaviour (kalix-allocation-components.md §3.1). Parsed pre-pass so nodes
    // (accounts = references) and [ras.*] sections can target accounts and groups
    // regardless of file order. IndexMap iteration preserves file order, so account
    // indices follow declaration order (fill_in_order relies on row order per group).
    for (section_name, ini_section) in &ini_doc.sections {
        if let Some(group_name) = section_name.strip_prefix("acc.") {
            if !is_valid_bare_name(group_name) {
                return Err(format!("Error on line {}: Invalid account group name '{}'", ini_section.line_number, group_name).into());
            }
            let mut accounts_prop = None;
            for (key, ini_property) in &ini_section.properties {
                match key.as_str() {
                    // `accounts` is the only key an [acc.*] section may contain (§3.1):
                    // columns are data; anything behavioural belongs in [ras.*].
                    "accounts" => accounts_prop = Some(ini_property),
                    other => {
                        return Err(format!("Error on line {}: Unexpected property '{}' in section '[{}]'. \
                            [acc.*] sections hold only the 'accounts' table; policy belongs in [ras.*] sections.",
                            ini_property.line_number, other, section_name).into());
                    }
                }
            }
            let accounts_prop = accounts_prop.ok_or_else(|| format!(
                "Error on line {}: Section '[{}]' is missing its 'accounts' table", ini_section.line_number, section_name))?;

            let table = parse_account_table(&accounts_prop.value)
                .map_err(|e| format!("Error on line {}: {}", accounts_prop.line_number, e))?;

            let mut member_ids = Vec::with_capacity(table.names.len());
            for (row, name) in table.names.iter().enumerate() {
                let account = Account::new_with_size(
                    name.clone(),
                    group_name.to_string(), // account_type carries the group name
                    table.sizes[row],
                    table.initials[row],
                );
                let account_idx = model.account_manager.add_account(account)
                    .map_err(|e| format!("Error on line {}: {}", accounts_prop.line_number, e))?;
                member_ids.push(account_idx);
            }
            model.account_manager.add_group(AccountGroup {
                name: group_name.to_string(),
                member_ids,
                columns: Vec::new(), // engine-known columns only for now; data columns arrive with the actions that read them
            }).map_err(|e| format!("Error on line {}: {}", ini_section.line_number, e))?;
        }
    }

    // -------------------------------------------------------------------------------------
    // Collecting [ras.*] sections (parsed in a post-pass)
    // -------------------------------------------------------------------------------------
    // A RAS is one trigger + one action applied to the accounts of one or more
    // target groups (kalix-allocation-components.md §3.3). The sections are
    // collected here (preserving file order — execution order) but *built* after
    // the main loop, because triggers and action arguments may reference
    // constants, which parse below.
    let mut ras_defs: Vec<(String, usize, String, String, String)> = Vec::new();
    for (section_name, ini_section) in &ini_doc.sections {
        if let Some(ras_name) = section_name.strip_prefix("ras.") {
            if !is_valid_bare_name(ras_name) {
                return Err(format!("Error on line {}: Invalid RAS name '{}'", ini_section.line_number, ras_name).into());
            }
            let mut targets = None;
            let mut trigger = None;
            let mut action = None;
            for (key, ini_property) in &ini_section.properties {
                let v = require_non_empty(&ini_property.value, key, ini_property.line_number)?;
                match key.to_lowercase().as_str() {
                    "targets" => targets = Some(v.to_string()),
                    "trigger" => trigger = Some(v.to_string()),
                    "action" => action = Some(v.to_string()),
                    other => {
                        return Err(format!("Error on line {}: Unexpected property '{}' in section '[{}]'. \
                            A RAS has exactly three properties: targets, trigger, action.",
                            ini_property.line_number, other, section_name).into());
                    }
                }
            }
            let line = ini_section.line_number;
            let missing = |what: &str| format!("Error on line {}: Section '[{}]' is missing '{}'", line, section_name, what);
            ras_defs.push((
                ras_name.to_string(),
                line,
                targets.ok_or_else(|| missing("targets"))?,
                trigger.ok_or_else(|| missing("trigger"))?,
                action.ok_or_else(|| missing("action"))?,
            ));
        }
    }

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
                // Input files can be specified in two formats:
                // 1. Direct file path: ./path/to/file.csv (value is empty, key is the path)
                // 2. Aliased file path: alias = ./path/to/file.csv (value is the path, key is the alias)
                if ini_property.value.is_empty() {
                    // Direct file path (no alias)
                    model.load_input_data(name.as_str(), None)
                        .map_err(|e| e.with_context(&format!("Error on line {}: ", ini_property.line_number)))?;
                } else {
                    // Aliased file path: name is the alias, value is the file path
                    model.load_input_data(ini_property.value.as_str(), Some(name.as_str()))
                        .map_err(|e| e.with_context(&format!("Error on line {}: ", ini_property.line_number)))?;
                }
            }
        } else if section_name == "const" {
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
            let self_context = format!("node.{}", node_name);
            let self_ctx = Some(self_context.as_str());
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
                                              ini_property.line_number, name, node_name).into());
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
                        } else if name_lower == "harmony_fraction" {
                            n.harmony_fraction = DynamicInput::from_string(v, &mut model.data_cache, true, self_ctx)
                                .map_err(|e| format!("Error on line {}: {}", ini_property.line_number, e))?;
                        } else {
                            return Err(format!("Error on line {}: Unexpected parameter '{}' for node '{}'", ini_property.line_number, name, node_name).into());
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
                        } else if name_lower == "force_flow" {
                            n.force_flow_input = DynamicInput::from_string(v, &mut model.data_cache, false, self_ctx)
                                .map_err(|e| format!("Error on line {}: {}", ini_property.line_number, e))?;
                        } else if name_lower == "reference_flow" {
                            n.reference_flow_input = DynamicInput::from_string(v, &mut model.data_cache, false, self_ctx)
                                .map_err(|e| format!("Error on line {}: {}", ini_property.line_number, e))?;
                        } else {
                            return Err(format!("Error on line {}: Unexpected parameter '{}' for node '{}'",
                                              ini_property.line_number, name, node_name).into());
                        }
                    }
                    NodeEnum::GaugeNode(n)
                }
                "order_control" => {
                    let mut n = OrderControlNode::new();
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
                        } else if name_lower == "delay_order_steps" {
                            n.delay_order_steps = v.parse::<usize>().map_err(|_|
                                format!("Error on line {}: Invalid '{}' value for node '{}': required non-negative integer",
                                        ini_property.line_number, name, node_name))?;
                        } else if name_lower == "min_order" {
                            n.min_order_input = DynamicInput::from_string(v, &mut model.data_cache, false, self_ctx)
                                .map_err(|e| format!("Error on line {}: {}", ini_property.line_number, e))?;
                        } else if name_lower == "max_order" {
                            n.max_order_input = DynamicInput::from_string(v, &mut model.data_cache, false, self_ctx)
                                .map_err(|e| format!("Error on line {}: {}", ini_property.line_number, e))?;
                        } else if name_lower == "set_order" {
                            n.set_order_input = DynamicInput::from_string(v, &mut model.data_cache, false, self_ctx)
                                .map_err(|e| format!("Error on line {}: {}", ini_property.line_number, e))?;
                        } else {
                            return Err(format!("Error on line {}: Unexpected parameter '{}' for node '{}'",
                                               ini_property.line_number, name, node_name).into());
                        }
                    }
                    NodeEnum::OrderControlNode(n)
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
                            n.evap_mm_input = DynamicInput::from_string(v, &mut model.data_cache, true, self_ctx)
                                .map_err(|e| format!("Error on line {}: {}", ini_property.line_number, e))?;
                        } else if name_lower == "rain" {
                            n.rain_mm_input = DynamicInput::from_string(v, &mut model.data_cache, true, self_ctx)
                                .map_err(|e| format!("Error on line {}: {}", ini_property.line_number, e))?;
                        } else if name_lower == "area" {
                            n.area_km2 = v.parse::<f64>()
                                .map_err(|_| format!("Error on line {}: Invalid '{}' value for node '{}': not a valid number",
                                                     ini_property.line_number, name, node_name))?;
                        } else if name_lower == "variant" {
                            // Model formulation. Absent/"gr4j" => classic daily; "gr4h" => sub-daily.
                            // Set the field directly; gr4j_model.initialize() (called during model
                            // init) derives the variant-specific constants from it.
                            n.gr4j_model.variant = match v.to_lowercase().as_str() {
                                "gr4j" => Gr4Variant::Gr4j,
                                "gr4h" => Gr4Variant::Gr4h,
                                _ => return Err(format!("Error on line {}: Unknown gr4j variant '{}' for node '{}' (expected 'gr4j' or 'gr4h')",
                                                        ini_property.line_number, v, node_name).into()),
                            };
                        } else if name_lower == "params" {
                            let params = csv_string_to_f64_vec(v)
                                .map_err(|e| format!("Error on line {}: {}", ini_property.line_number, e))?;
                            if params.len() != 4 {
                                return Err(format!("Error on line {}: GR4J params must have 4 values, got {}",
                                                   ini_property.line_number, params.len()).into());
                            }
                            n.gr4j_model.x1 = params[0];
                            n.gr4j_model.x2 = params[1];
                            n.gr4j_model.x3 = params[2];
                            n.gr4j_model.x4 = params[3];
                        } else {
                            return Err(format!("Error on line {}: Unexpected parameter '{}' for node '{}'",
                                              ini_property.line_number, name, node_name).into());
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
                            n.inflow_input = DynamicInput::from_string(v, &mut model.data_cache, true, self_ctx)
                                .map_err(|e| format!("Error on line {}: {}", ini_property.line_number, e))?;
                        } else if name_lower == "expected_inflow" {
                            n.expected_inflow_input = DynamicInput::from_string(v, &mut model.data_cache, true, self_ctx)
                                .map_err(|e| format!("Error on line {}: {}", ini_property.line_number, e))?;
                        } else {
                            return Err(format!("Error on line {}: Unexpected parameter '{}' for node '{}'",
                                              ini_property.line_number, name, node_name).into());
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
                                              ini_property.line_number, name, node_name).into());
                        }
                    }
                    NodeEnum::LossNode(n)
                }
                "routing" => {
                    let mut n = RoutingNode::new();
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
                        } else if name_lower == "lag" {
                            n.set_lag(v.parse::<usize>()
                                .map_err(|_| format!("Error on line {}: Invalid '{}' value for node '{}': required non-negative integer",
                                                     ini_property.line_number, name, node_name))?);
                        } else if name_lower == "n_divs" {
                            n.set_divs(v.parse::<usize>()
                                .map_err(|_| format!("Error on line {}: Invalid '{}' value for node '{}': required non-negative integer",
                                                     ini_property.line_number, name, node_name))?);
                        } else if name_lower == "x" {
                            n.set_x(v.parse::<f64>()
                                .map_err(|_| format!("Error on line {}: Invalid '{}' value for node '{}': not a valid number",
                                                     ini_property.line_number, name, node_name))?);
                        } else if name_lower == "nlm" {
                            let all_values = csv_string_to_f64_vec(v)
                                .map_err(|e| format!("Error on line {}: {}", ini_property.line_number, e))?;
                            if all_values.len() < 2 {
                                return Err(format!("Error on line {}: Expected k and m values.", ini_property.line_number).into());
                            }
                            n.set_k(all_values[0]);
                            n.set_m(all_values[1]);
                        } else if name_lower == "pwl" {
                            let all_values = csv_string_to_f64_vec(v)
                                .map_err(|e| format!("Error on line {}: {}", ini_property.line_number, e))?;
                            let nvals = all_values.len();
                            let nrows = nvals / 2;
                            if all_values.len() % 2 > 0 {
                                return Err(format!("Error on line {}: Pwl table must contain an even number of elements, but found {}",
                                                   ini_property.line_number, nvals).into());
                            } else if nrows > 32 {
                                return Err(format!("Error on line {}: Pwl table must contain no more than 32 rows but found {}",
                                                   ini_property.line_number, nrows).into());
                            } else if nrows < 1 {
                                return Err(format!("Error on line {}: Pwl table must contain at least one row",
                                                   ini_property.line_number).into());
                            }
                            let (index_flows, index_times) = split_interleaved(&all_values);
                            n.set_routing_table(index_flows, index_times);
                        } else if name_lower == "typical_regulated_flow" {
                            n.typical_regulated_flow = v.parse::<f64>()
                                .map_err(|_| format!("Error on line {}: Invalid '{}' value for node '{}': not a valid number",
                                                     ini_property.line_number, name, node_name))?;
                        } else {
                            return Err(format!("Error on line {}: Unexpected parameter '{}' for node '{}'",
                                              ini_property.line_number, name, node_name).into());
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
                            n.evap_mm_input = DynamicInput::from_string(v, &mut model.data_cache, true, self_ctx)
                                .map_err(|e| format!("Error on line {}: {}", ini_property.line_number, e))?;
                        } else if name_lower == "rain" {
                            n.rain_mm_input = DynamicInput::from_string(v, &mut model.data_cache, true, self_ctx)
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
                                                   ini_property.line_number, params.len()).into());
                            }
                            n.sacramento_model.set_params_by_vec(params);
                        } else {
                            return Err(format!("Error on line {}: Unexpected parameter '{}' for node '{}'",
                                              ini_property.line_number, name, node_name).into());
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
                                              ini_property.line_number, name, node_name).into());
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
                        } else if name_lower == "ds_3" {
                            vec_link_defs.push(LinkHelper::new_from_names(&n.name, v, DS_3_OUTLET, INLET))
                        } else if name_lower == "ds_4" {
                            vec_link_defs.push(LinkHelper::new_from_names(&n.name, v, DS_4_OUTLET, INLET))
                        } else if let Some(ds_num) = name_lower.strip_prefix("ds_")
                            .and_then(|s| s.strip_suffix("_outlet"))
                            .and_then(|s| s.parse::<i32>().ok()) {
                            let params = csv_string_to_f64_vec(v)?;
                            let i_outlet = (ds_num - 1) as usize;
                            match params.len() {
                                0 => n.outlet_definition[i_outlet] = OutletDefinition::None,
                                1 => n.outlet_definition[i_outlet] = OutletWithMOL(params[0]),
                                2 => n.outlet_definition[i_outlet] = OutletWithMOLAndCapacity(params[0], params[1]),
                                _ => return Err(format!("Error on line {}: Tabulated outlet not supported yet.", ini_property.line_number).into()),
                            }
                        } else if let Some(ds_num) = name_lower.strip_prefix("ds_")
                            .and_then(|s| s.strip_suffix("_force_release"))
                            .and_then(|s| s.parse::<usize>().ok()) {
                            if ds_num < 1 || ds_num > n.ds_force_release_input.len() {
                                return Err(format!(
                                    "Error on line {}: outlet index in '{}' must be between 1 and {}",
                                    ini_property.line_number, name, n.ds_force_release_input.len()
                                ).into());
                            }
                            let i_outlet = ds_num - 1;
                            n.ds_force_release_input[i_outlet] = DynamicInput::from_string(v, &mut model.data_cache, true, self_ctx)
                                .map_err(|e| format!("Error on line {}: {}", ini_property.line_number, e))?;
                        } else if name_lower == "evap" {
                            n.evap_mm_input = DynamicInput::from_string(v, &mut model.data_cache, true, self_ctx)
                                .map_err(|e| format!("Error on line {}: {}", ini_property.line_number, e))?;
                        } else if name_lower == "rain" {
                            n.rain_mm_input = DynamicInput::from_string(v, &mut model.data_cache, true, self_ctx)
                                .map_err(|e| format!("Error on line {}: {}", ini_property.line_number, e))?;
                        } else if name_lower == "seep" {
                            n.seep_mm_input = DynamicInput::from_string(v, &mut model.data_cache, true, self_ctx)
                                .map_err(|e| format!("Error on line {}: {}", ini_property.line_number, e))?;
                        } else if name_lower == "pond_demand" {
                            n.pond_demand_input = DynamicInput::from_string(v, &mut model.data_cache, true, self_ctx)
                                .map_err(|e| format!("Error on line {}: {}", ini_property.line_number, e))?;
                        } else if name_lower == "target_level" {
                            n.target_level = DynamicInput::from_string(v, &mut model.data_cache, true, self_ctx)
                                .map_err(|e| format!("Error on line {}: {}", ini_property.line_number, e))?;
                        } else if name_lower == "dimensions" {
                            n.dimensions = Table::from_csv_string(v, 4, false)
                                .map_err(|e| format!("Error on line {}: Could not parse dimensions table for node '{}': {}",
                                                     ini_property.line_number, node_name, e))?;
                        } else if name_lower == "initial_volume" {
                            n.vol_initial = v.parse::<f64>()
                                .map_err(|_| format!("Error on line {}: Invalid '{}' value for node '{}': not a valid number",
                                                     ini_property.line_number, name, node_name))?;
                        } else if name_lower == "order_through" {
                            (n.order_through, _) = parse_csv_to_bool_option_u8(v)
                                .map_err(|e| format!("Error on line {}: {}", ini_property.line_number, e))?;
                        } else if name_lower == "exists" {
                            n.exists = DynamicInput::from_string(v, &mut model.data_cache, false, self_ctx)
                                .map_err(|e| format!("Error on line {}: {}", ini_property.line_number, e))?;
                        }
                        else {
                            return Err(format!("Error on line {}: Unexpected parameter '{}' for node '{}'",
                                              ini_property.line_number, name, node_name).into());
                        }
                    }
                    NodeEnum::StorageNode(n)
                }
                "unregulated_user" => {
                    let mut n = UnregulatedUserNode::new();
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
                            n.demand_input = DynamicInput::from_string(v, &mut model.data_cache, true, self_ctx)
                                .map_err(|e| format!("Error on line {}: {}", ini_property.line_number, e))?;
                        } else if name_lower == "accounts" {
                            let account_idxs = resolve_account_references(v, &model.account_manager)
                                .map_err(|e| format!("Error on line {}: {}", ini_property.line_number, e))?;
                            n.register_accounts(account_idxs);
                        } else if name_lower == "annual_cap" {
                            let params = csv_string_to_f64_vec(v)
                                .map_err(|e| format!("Error on line {}: {}", ini_property.line_number, e))?;
                            if params.len() != 2 {
                                return Err(format!("Error on line {}: User 'annual_cap' must have 2 values, got {}",
                                                   ini_property.line_number, params.len()).into());
                            }
                            n.annual_cap = Some(params[0]);
                            n.annual_cap_reset_month = params[1] as u8;
                        } else if name_lower == "pump" {
                            n.pump_capacity = DynamicInput::from_string(v, &mut model.data_cache, true, self_ctx)
                                .map_err(|e| format!("Error on line {}: {}", ini_property.line_number, e))?;
                        } else if name_lower == "flow_threshold" {
                            n.flow_threshold = DynamicInput::from_string(v, &mut model.data_cache, true, self_ctx)
                                .map_err(|e| format!("Error on line {}: {}", ini_property.line_number, e))?;
                        } else if name_lower == "demand_carryover" {
                            (n.demand_carryover_allowed, n.demand_carryover_reset_month) = parse_csv_to_bool_option_u8(v)
                                .map_err(|e| format!("Error on line {}: {}", ini_property.line_number, e))?;
                        } else {
                            return Err(format!("Error on line {}: Unexpected parameter '{}' for node '{}'",
                                              ini_property.line_number, name, node_name).into());
                        }
                    }
                    NodeEnum::UnregulatedUserNode(n)
                }
                "regulated_user" => {
                    let mut n = RegulatedUserNode::new();
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
                        } else if name_lower == "order" {
                            n.order_input = DynamicInput::from_string(v, &mut model.data_cache, true, self_ctx)
                                .map_err(|e| format!("Error on line {}: {}", ini_property.line_number, e))?;
                        } else if name_lower == "pump" {
                            n.pump_capacity = DynamicInput::from_string(v, &mut model.data_cache, true, self_ctx)
                                .map_err(|e| format!("Error on line {}: {}", ini_property.line_number, e))?;
                        } else if name_lower == "accounts" {
                            let account_idxs = resolve_account_references(v, &model.account_manager)
                                .map_err(|e| format!("Error on line {}: {}", ini_property.line_number, e))?;
                            n.register_accounts(account_idxs);
                        } else {
                            return Err(format!("Error on line {}: Unexpected parameter '{}' for node '{}'",
                                               ini_property.line_number, name, node_name).into());
                        }
                    }
                    NodeEnum::RegulatedUserNode(n)
                }
                _ => {
                    let line_number = match ini_section.properties.get("type") {
                        Some(ini_property) => ini_property.line_number,
                        None => ini_section.line_number,
                    };
                    return Err(format!("Error on line {}: Unknown node type '{}'",  line_number, node_type).into())
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
        } else if section_name.starts_with("var.") {
            // -------------------------------------------------------------------------------------
            // Var blocks: published calculations, ACTIVE at this file position
            // (structured_expressions_design.md §9). Unlike tables and fns,
            // their position among the node sections is part of their meaning:
            // they execute here, reading anything computed above.
            // -------------------------------------------------------------------------------------
            let block_name = &section_name[4..];
            if !is_valid_bare_name(block_name) {
                return Err(format!("Error on line {}: Invalid var block name '{}'",
                                   ini_section.line_number, block_name).into());
            }

            // Phase: 'flow' (default) runs at file position in the flow pass.
            // 'order' is designed (order phase walks bottom-up) but its
            // interleave with the ordering system is not yet implemented —
            // rejected rather than approximated (owner decision, July 2026).
            let mut phase_explicit: Option<String> = None;
            if let Some(p) = ini_section.properties.get("phase") {
                match p.value.trim().to_lowercase().as_str() {
                    "flow" => phase_explicit = Some(p.value.trim().to_string()),
                    "order" => {
                        return Err(format!(
                            "Error on line {}: phase = order is not yet implemented for \
                             [var.*] blocks (only phase = flow is supported)",
                            p.line_number).into());
                    }
                    other => {
                        return Err(format!(
                            "Error on line {}: invalid phase '{}' for [{}] (expected 'flow' or 'order')",
                            p.line_number, other, section_name).into());
                    }
                }
            }

            let mut defs: Vec<crate::model::VarDef> = Vec::new();
            for (key, ini_property) in &ini_section.properties {
                if key.as_str() == "phase" {
                    continue;
                }
                if !is_valid_bare_name(key) {
                    return Err(format!("Error on line {}: Invalid var name '{}' in [{}]",
                                       ini_property.line_number, key, section_name).into());
                }

                let series_name = format!("var.{}.{}", block_name, key).to_lowercase();
                if model.data_cache.get_existing_series_idx(&series_name).is_some() {
                    return Err(format!(
                        "Error on line {}: series '{}' already exists (duplicate var definition?)",
                        ini_property.line_number, series_name).into());
                }
                // The block's own output series: registered before lowering
                // the expression, so a self-reference with a [-1, default]
                // offset resolves to the same series.
                let series_idx = model.data_cache.get_or_add_new_series(&series_name, false);

                let input = DynamicInput::from_string(
                    &ini_property.value, &mut model.data_cache, true, None)
                    .map_err(|e| format!("Error on line {}: in '{}': {}",
                                         ini_property.line_number, key, e))?;

                defs.push(crate::model::VarDef {
                    key: key.clone(),
                    series_idx,
                    input,
                    original: ini_property.value.clone(),
                });
            }

            model.add_var_block(crate::model::VarBlock {
                name: block_name.to_string(),
                defs,
                phase_explicit,
            });
        } else if section_name.starts_with("table.") {
            // -------------------------------------------------------------------------------------
            // Lookup tables — already parsed in the pre-pass above
            // -------------------------------------------------------------------------------------
        } else if section_name.starts_with("acc.") {
            // -------------------------------------------------------------------------------------
            // Account groups — already parsed in the pre-pass above
            // -------------------------------------------------------------------------------------
        } else if section_name.starts_with("ras.") {
            // -------------------------------------------------------------------------------------
            // Resource allocation systems — collected above, built in the post-pass below
            // -------------------------------------------------------------------------------------
        } else if section_name == "fn" {
            // -------------------------------------------------------------------------------------
            // User-defined functions — already parsed in the pre-pass above
            // -------------------------------------------------------------------------------------
        } else {
            // -------------------------------------------------------------------------------------
            // Unexpected section
            // -------------------------------------------------------------------------------------
            return Err(format!("Error on line {}: Unexpected section '{}'", ini_section.line_number, section_name).into());
        }
    }

    // -------------------------------------------------------------------------------------
    // Create all the links
    // -------------------------------------------------------------------------------------
    for link_helper in vec_link_defs {
        let from_node_idx = model.get_node_idx(&link_helper.from_node_name)
            .ok_or(format!("Node '{}' not found", link_helper.from_node_name))?;
        let to_node_idx = model.get_node_idx(&link_helper.to_node_name)
            .ok_or(format!("Node '{}' not found", link_helper.to_node_name))?;
        model.add_link(from_node_idx, to_node_idx, link_helper.from_outlet, link_helper.to_inlet);
    }

    // -------------------------------------------------------------------------------------
    // Building [ras.*] systems (post-pass: constants and groups are now loaded)
    // -------------------------------------------------------------------------------------
    for (ras_name, line, targets_str, trigger_str, action_str) in ras_defs {
        if model.ras_systems.iter().any(|r| r.name == ras_name) {
            return Err(format!("Error on line {}: RAS '{}' declared more than once", line, ras_name).into());
        }
        // Resolve targets: one or more acc.<group> references, flattened to
        // member accounts in group-then-row order
        let mut target_account_ids = Vec::new();
        for t in csv_to_string_vec(&targets_str) {
            let lower = t.to_lowercase();
            let group_name = lower.strip_prefix("acc.").ok_or_else(|| format!(
                "Error on line {}: RAS target '{}' must be an account group reference like 'acc.<group>'", line, t))?;
            let group_idx = model.account_manager.get_group_idx(group_name).ok_or_else(|| format!(
                "Error on line {}: Unknown account group '{}' in RAS targets", line, t))?;
            target_account_ids.extend(model.account_manager.get_group(group_idx).unwrap().member_ids.iter().copied());
        }
        let trigger = parse_ras_trigger(&trigger_str, &mut model, line)?;
        let action = parse_ras_action(&action_str, &mut model, line)?;
        model.ras_systems.push(RasSystem {
            name: ras_name,
            target_account_ids,
            trigger,
            action,
            targets_original: targets_str,
            trigger_original: trigger_str,
            action_original: action_str,
            recorder_idx_fired: None,
        });
    }

    // -------------------------------------------------------------------------------------
    // Validate acc.* series references
    // -------------------------------------------------------------------------------------
    // Every acc.* series in the data cache came from an [outputs] entry or an
    // expression reference. Each must name a real account (or group) and a real
    // field — otherwise a typo would sit there as a series nothing ever writes,
    // reading as a silent NaN instead of an error.
    for name in model.data_cache.series_name.clone() {
        let Some(rest) = name.strip_prefix("acc.") else { continue };
        let (target, field) = rest.rsplit_once('.').ok_or_else(|| format!(
            "Invalid account reference 'acc.{}': expected 'acc.<account or group>.<field>'", rest))?;
        let is_account = model.account_manager.get_account_idx(target).is_some();
        let is_group = model.account_manager.get_group_idx(target).is_some();
        if !is_account && !is_group {
            return Err(format!("Unknown account or account group '{}' in reference '{}'. \
                Accounts are declared in [acc.*] sections.", target, name).into());
        }
        let allowed: &[&str] = if is_account { &ACCOUNT_SERIES_FIELDS } else { &GROUP_SERIES_FIELDS };
        if !allowed.contains(&field) {
            return Err(format!("Unknown field '{}' in reference '{}'. Available for {}: {}",
                field, name,
                if is_account { "an account" } else { "an account group" },
                allowed.join(", ")).into());
        }
    }

    // -------------------------------------------------------------------------------------
    // Capture the canonical baseline of the model exactly as loaded
    // -------------------------------------------------------------------------------------
    // Render the pristine model canonically and stash it. At save time we render
    // the (possibly mutated) model again and diff against this baseline to find
    // which sections actually changed — see the formatting-preserving saver.
    model.baseline_canonical = Some(render_canonical_0_0_1(&model));

    // -------------------------------------------------------------------------------------
    // Return the model
    // -------------------------------------------------------------------------------------
    Ok(model)
}



/// Render a model to an INI document canonically — every property formatted
/// from the model's typed fields. Starts from the model's `ini_document` (when
/// present) so leading comments, blank lines and section order survive, but all
/// values are re-emitted in canonical form. This is the single canonicalizer
/// used both to capture the load-time baseline and to compute the current state
/// at save time.
pub fn render_canonical_0_0_1(model: &Model) -> IniDocument {

    // Start by cloning the model's ini_doc if it has one. Otherwise we make a new one.
    let mut ini_doc = match &model.ini_document {
        Some(doc) => doc.clone(),
        None => IniDocument::new(),
    };

    // Invalidate the ini_doc
    ini_doc.invalidate_all();

    // Validate the kalix section and version property if present (preserves original formatting)
    ini_doc.validate_section("kalix");
    ini_doc.validate_property("kalix", "version");

    // Set start and end dates if specified. Format matches the simulation step_size — daily
    // models stay as YYYY-MM-DD, sub-daily models use ISO datetime so the time-of-day isn't lost.
    let sim_stepsize = model.configuration.sim_stepsize;
    if let Some(start_timestamp) = model.configuration.specified_sim_start_timestamp {
        ini_doc.set_property("kalix", "start", &u64_to_date_string_for_step_size(start_timestamp, sim_stepsize));
    }
    if let Some(end_timestamp) = model.configuration.specified_sim_end_timestamp {
        ini_doc.set_property("kalix", "end", &u64_to_date_string_for_step_size(end_timestamp, sim_stepsize));
    }

    // List all input files
    for file_path in &model.input_file_paths {
        let alias = model.alias_map.get(file_path);
        let (k, v) = match alias {
            Some(alias_string) => (alias_string.as_str(), file_path.as_str()),
            None => (file_path.as_str(), ""),
        };
        ini_doc.set_property("inputs", k, v);
    }

    // List all constants
    for (name, value) in model.data_cache.constants.get_name_value_pairs() {
        ini_doc.set_property("const", name.as_str(), value.to_string().as_str());
    }

    // List all account groups ([acc.*] sections), each as its headed accounts
    // table. Emitted before nodes so saved files read declaration-first, though
    // load order is a pre-pass and does not depend on it.
    for group in model.account_manager.groups() {
        let section_name = format!("acc.{}", group.name);
        let table_str = format_account_group_table(&model.account_manager, group);
        ini_doc.set_property(section_name.as_str(), "accounts", table_str.as_str());
    }

    // List all RAS systems ([ras.*] sections) in execution (file) order,
    // re-emitting targets/trigger/action as written
    for ras in &model.ras_systems {
        let section_name = format!("ras.{}", ras.name);
        ini_doc.set_property(section_name.as_str(), "targets", ras.targets_original.as_str());
        ini_doc.set_property(section_name.as_str(), "trigger", ras.trigger_original.as_str());
        ini_doc.set_property(section_name.as_str(), "action", ras.action_original.as_str());
    }

    // List all nodes and var blocks, interleaved in execution (file) order —
    // a var block's position is part of its meaning, so a round-trip must
    // keep it where the modeller put it.
    for exec_item in &model.exec_items {
        let node_enum = match exec_item {
            crate::model::ExecItem::VarBlock(i) => {
                let vb = &model.var_blocks[*i];
                let section_name = format!("var.{}", vb.name);
                if let Some(p) = &vb.phase_explicit {
                    ini_doc.set_property(section_name.as_str(), "phase", p.as_str());
                }
                for def in &vb.defs {
                    ini_doc.set_property(section_name.as_str(), def.key.as_str(), def.original.as_str());
                }
                continue;
            }
            crate::model::ExecItem::Node(idx) => &model.nodes[*idx],
        };
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
                set_property_if_not_empty(&mut ini_doc, section_name.as_str(), "harmony_fraction", &n.harmony_fraction.to_string());
            }
            NodeEnum::GaugeNode(n) => {
                let section_name = format!("node.{}", n.name);
                ini_doc.set_property(section_name.as_str(), "loc", n.location.to_string().as_str());
                ini_doc.set_property(section_name.as_str(), "type", "gauge");
                set_property_if_not_empty(&mut ini_doc, section_name.as_str(), "force_flow", &n.force_flow_input.to_string());
                set_property_if_not_empty(&mut ini_doc, section_name.as_str(), "reference_flow", &n.reference_flow_input.to_string());
            }
            NodeEnum::OrderControlNode(n) => {
                let section_name = format!("node.{}", n.name);
                ini_doc.set_property(section_name.as_str(), "loc", n.location.to_string().as_str());
                ini_doc.set_property(section_name.as_str(), "type", "order_control");
                set_property_if_not_empty(&mut ini_doc, section_name.as_str(), "min_order", &n.min_order_input.to_string());
                set_property_if_not_empty(&mut ini_doc, section_name.as_str(), "max_order", &n.max_order_input.to_string());
                set_property_if_not_empty(&mut ini_doc, section_name.as_str(), "set_order", &n.set_order_input.to_string());
                set_property_unless_default(&mut ini_doc, section_name.as_str(), "delay_order_steps", &n.delay_order_steps.to_string(), "0");
            }
            NodeEnum::Gr4jNode(n) => {
                let section_name = format!("node.{}", n.name);
                ini_doc.set_property(section_name.as_str(), "loc", n.location.to_string().as_str());
                ini_doc.set_property(section_name.as_str(), "type", "gr4j");
                // Only emit the variant line when non-default, to keep classic GR4J models diff-clean.
                if let Gr4Variant::Gr4h = n.gr4j_model.variant {
                    ini_doc.set_property(section_name.as_str(), "variant", "gr4h");
                }
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
                set_property_if_not_empty(&mut ini_doc, section_name.as_str(), "expected_inflow", &n.expected_inflow_input.to_string());
            }
            NodeEnum::LossNode(n) => {
                let section_name = format!("node.{}", n.name);
                ini_doc.set_property(section_name.as_str(), "loc", n.location.to_string().as_str());
                ini_doc.set_property(section_name.as_str(), "type", "loss");
                let loss_table_values = n.loss_table.get_values_as_vec();
                let loss_table_str = format_vec_as_multiline_table(&loss_table_values, n.loss_table.ncols(), 4);
                //ini_doc.set_property (section_name.as_str(), "table", loss_table_str.as_str());
                set_property_if_not_empty(&mut ini_doc, section_name.as_str(), "table", loss_table_str.as_str());
            }
            NodeEnum::RoutingNode(n) => {
                let section_name = format!("node.{}", n.name);
                ini_doc.set_property(section_name.as_str(), "loc", n.location.to_string().as_str());
                ini_doc.set_property(section_name.as_str(), "type", "routing");
                if n.get_divs() != 1 { ini_doc.set_property(section_name.as_str(), "n_divs", n.get_divs().to_string().as_str()); }
                if n.get_x() != 0.0 { ini_doc.set_property(section_name.as_str(), "x", n.get_x().to_string().as_str()); }
                if n.get_lag() != 0 { ini_doc.set_property(section_name.as_str(), "lag", n.get_lag().to_string().as_str()); }
                // NLM and PWL are mutually exclusive (see RoutingNode::initialise,
                // which errors if both are set). Emit whichever this node uses, keyed
                // off the same discriminator the node uses, so we never write both.
                if n.uses_nlm() {
                    let m = n.get_m();
                    let k = n.get_k();
                    set_property_if_not_empty(&mut ini_doc, section_name.as_str(), "nlm", format!("{}, {}", k, m).as_str());
                } else {
                    let pwl_values = n.get_routing_table_as_vec();
                    if pwl_values.len() > 0 {
                        let pwl_values_str = format_vec_as_multiline_table(pwl_values.as_slice(), 2, 4);
                        ini_doc.set_property(section_name.as_str(), "pwl", pwl_values_str.as_str());
                    }
                }
                set_property_unless_default(&mut ini_doc, section_name.as_str(), "typical_regulated_flow", &n.typical_regulated_flow.to_string(), "0");
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
                //ini_doc.set_property(section_name.as_str(), "table", splitter_table_str.as_str());
                set_property_if_not_empty(&mut ini_doc, section_name.as_str(), "table", splitter_table_str.as_str());
            }
            NodeEnum::StorageNode(n) => {
                let section_name = format!("node.{}", n.name);
                ini_doc.set_property(section_name.as_str(), "loc", n.location.to_string().as_str());
                ini_doc.set_property(section_name.as_str(), "type", "storage");
                set_property_if_not_empty(&mut ini_doc, section_name.as_str(), "evap", &n.evap_mm_input.to_string());
                set_property_if_not_empty(&mut ini_doc, section_name.as_str(), "rain", &n.rain_mm_input.to_string());
                set_property_if_not_empty(&mut ini_doc, section_name.as_str(), "seep", &n.seep_mm_input.to_string());
                set_property_if_not_empty(&mut ini_doc, section_name.as_str(), "pond_demand", &n.pond_demand_input.to_string());
                set_property_if_not_empty(&mut ini_doc, section_name.as_str(), "target_level", &n.target_level.to_string());
                set_property_if_not_empty(&mut ini_doc, section_name.as_str(), "exists", &n.exists.to_string());
                for (i, ds_force_release) in n.ds_force_release_input.iter().enumerate() {
                    let property_name = format!("ds_{}_force_release", i + 1);
                    set_property_if_not_empty(&mut ini_doc, section_name.as_str(), &property_name, &ds_force_release.to_string());
                }
                set_property_unless_default(&mut ini_doc, section_name.as_str(), "initial_volume", &n.vol_initial.to_string(), "0");
                // order_through defaults to false; emit only when enabled.
                if n.order_through {
                    ini_doc.set_property(section_name.as_str(), "order_through", "true");
                }
                let dimensions_values = n.dimensions.get_values_as_vec();
                let dimensions_str = format_vec_as_multiline_table(&dimensions_values, n.dimensions.ncols(), 4);
                ini_doc.set_property(section_name.as_str(), "dimensions", dimensions_str.as_str());
                for (i, outlet_def) in n.outlet_definition.iter().enumerate() {
                    let property_name = format!("ds_{}_outlet", i + 1);
                    let value = match outlet_def {
                        OutletDefinition::None => String::new(),
                        OutletWithMOL(mol) => format_f64(*mol),
                        OutletWithMOLAndCapacity(mol, cap) => format!("{}, {}", format_f64(*mol), format_f64(*cap)),
                    };
                    set_property_if_not_empty(&mut ini_doc, section_name.as_str(), &property_name, &value);
                }
            }
            NodeEnum::UnregulatedUserNode(n) => {
                let section_name = format!("node.{}", n.name);
                ini_doc.set_property(section_name.as_str(), "loc", n.location.to_string().as_str());
                ini_doc.set_property(section_name.as_str(), "type", "unregulated_user");
                set_property_if_not_empty(&mut ini_doc, section_name.as_str(), "demand", &n.demand_input.to_string());
                set_property_if_not_empty(&mut ini_doc, section_name.as_str(), "pump", &n.pump_capacity.to_string());
                set_property_if_not_empty(&mut ini_doc, section_name.as_str(), "flow_threshold", &n.flow_threshold.to_string());
                // Re-emit the ordered account references by name (order is
                // meaning: deemed order-of-use)
                if !n.account_idxs.is_empty() {
                    let names: Vec<&str> = n.account_idxs.iter()
                        .filter_map(|&idx| model.account_manager.get_account(idx).map(|a| a.name.as_str()))
                        .collect();
                    ini_doc.set_property(section_name.as_str(), "accounts", names.join(", ").as_str());
                }
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
            NodeEnum::RegulatedUserNode(n) => {
                let section_name = format!("node.{}", n.name);
                ini_doc.set_property(section_name.as_str(), "loc", n.location.to_string().as_str());
                ini_doc.set_property(section_name.as_str(), "type", "regulated_user");
                set_property_if_not_empty(&mut ini_doc, section_name.as_str(), "order", &n.order_input.to_string());
                set_property_if_not_empty(&mut ini_doc, section_name.as_str(), "pump", &n.pump_capacity.to_string());
                if !n.account_idxs.is_empty() {
                    let names: Vec<&str> = n.account_idxs.iter()
                        .filter_map(|&idx| model.account_manager.get_account(idx).map(|a| a.name.as_str()))
                        .collect();
                    ini_doc.set_property(section_name.as_str(), "accounts", names.join(", ").as_str());
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

    // List all lookup tables and user-defined functions. Both are passive and
    // position-free (load-time pre-passes resolve them wherever they sit), so
    // canonically they live at the bottom of the file, just above [outputs].
    // Tables are sorted by name so the canonical render is deterministic.
    for (name, table) in model.data_cache.tables.iter_sorted() {
        let section_name = format!("table.{}", name);
        if table.ncols() > 2 {
            ini_doc.set_property(section_name.as_str(), "n_cols", table.ncols().to_string().as_str());
        }
        ini_doc.set_property(section_name.as_str(), "values", table.format_data(4).as_str());
    }

    // [fn] definitions re-emit from their original signature key and body text,
    // in file order (iter_in_order), so a round-trip preserves the definitions.
    if !model.data_cache.fns.is_empty() {
        for def in model.data_cache.fns.iter_in_order() {
            ini_doc.set_property("fn", def.original_key.as_str(), def.original_body.as_str());
        }
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


/// Serialise a model back to an INI document, preserving the original formatting
/// of everything that didn't change (the formatting-preserving "state-diff" saver).
///
/// Render the model canonically now (`current`) and compare it, section by
/// section, against the canonical baseline captured at load. A section whose
/// canonical form is unchanged is emitted verbatim from the original
/// `ini_document` (raw lines, comments and spacing intact); a section that
/// changed (or is new) is emitted canonically; a section that vanished is
/// dropped. Comparison is section-level on purpose: it sidesteps representation
/// mismatches (e.g. an input written as an alias `name = path` but canonicalised
/// to its bare path, or a key in non-canonical casing), since unchanged sections
/// are never key-matched — they are kept as-is.
///
/// Falls back to a full canonical render when there is no baseline or original
/// document to preserve (e.g. a model built programmatically).
pub fn model_to_ini_doc_0_0_1(model: &Model) -> IniDocument {
    let current = render_canonical_0_0_1(model);
    // Exhaustive match is a compiler-enforced guarantee that all save methods are handled.
    match model.configuration.save_method {
        SaveMethod::Standard => {
            // Standard save method
            let (baseline, original) = match (&model.baseline_canonical, &model.ini_document) {
            (Some(baseline), Some(original)) => (baseline, original),
            _ => return current, // nothing to preserve
            };

            // Rebuild the original (formatting-preserving) document by mark-and-sweep:
            // validate the sections we keep verbatim, set the ones that changed, and let
            // the sweep drop everything left invalid (deleted sections/properties).
            let mut out = original.clone();
            out.invalidate_all();

            for (section_name, current_section) in &current.sections {
                let unchanged = baseline.sections.get(section_name)
                    .map_or(false, |base| sections_canonically_equal(base, current_section));

                if unchanged && out.sections.contains_key(section_name) {
                    // Keep the original section verbatim (preserves raw_lines and comments).
                    out.validate_section(section_name);
                    let keys: Vec<String> = out.sections[section_name].properties.keys().cloned().collect();
                    for key in keys {
                        out.validate_property(section_name, &key);
                    }
                } else {
                    // Changed, new, or not present in the original: emit canonically.
                    {
                        for (key, prop) in &current_section.properties {
                            out.set_property(section_name, key, &prop.value);
                        }
                    };
                }
            }
            // A section the source declared but that carries no properties (an empty
            // `[inputs]`, say) has no counterpart in the canonical render, so the loop
            // above never validated it and the sweep would drop it — taking any comments
            // attached to it with it. It holds no model-meaningful content, so there is
            // nothing in it that *could* have changed: keep it verbatim.
            let empty_sections: Vec<String> = out.sections.iter()
                .filter(|(_, section)| section.properties.is_empty())
                .map(|(name, _)| name.clone())
                .collect();
            for section_name in empty_sections {
                out.validate_section(&section_name);
            }

            out.remove_invalid_sections_and_properties();
            out
        }
        SaveMethod::Canonical => {
            // Canonical save method
            current
        }
        // Note: never use a wildcard match here, to ensure compiler enforces exhaustive handling.
    }
}

/// Allowed column vocabulary for the [acc.*] accounts table. A closed set by
/// design (kalix-allocation-components.md §3.1): the header is detected as the
/// leading run of allowed column names in the flattened cell stream — the core
/// ini parser stays row-agnostic — and strictness on unrecognised columns is
/// what catches typos ('initail') at load. The set grows only when an engine
/// feature consumes a new column.
const ACCOUNT_TABLE_COLUMNS: [&str; 3] = ["name", "size", "initial"];

struct AccountTableData {
    names: Vec<String>,
    sizes: Vec<f64>,
    initials: Vec<f64>,
}

/// Parse the `accounts` property of an [acc.*] section: a headed table that
/// reaches us as one comma-flattened cell stream (multi-line values are joined
/// by the ini parser). Header length = leading run of allowed column names;
/// everything after wraps into rows of that width.
fn parse_account_table(flat: &str) -> Result<AccountTableData, String> {
    let trimmed = flat.trim_end_matches(|c: char| c == ',' || c.is_whitespace());
    if trimmed.is_empty() {
        return Err("Empty 'accounts' table".to_string());
    }
    let cells: Vec<&str> = trimmed.split(',').map(|x| x.trim()).collect();

    // Header = leading run of allowed column names
    let mut header: Vec<String> = Vec::new();
    for cell in &cells {
        let lower = cell.to_lowercase();
        if ACCOUNT_TABLE_COLUMNS.contains(&lower.as_str()) {
            if header.contains(&lower) {
                return Err(format!("Duplicate column '{}' in accounts table header", lower));
            }
            header.push(lower);
        } else {
            break;
        }
    }
    if header.is_empty() {
        return Err(format!("Accounts table must start with a header row of column names (allowed: {})",
            ACCOUNT_TABLE_COLUMNS.join(", ")));
    }
    if header[0] != "name" {
        return Err("First column of the accounts table must be 'name'".to_string());
    }
    if !header.iter().any(|h| h == "size") {
        return Err("Accounts table requires a 'size' column".to_string());
    }

    let n_cols = header.len();
    let data = &cells[n_cols..];
    if data.is_empty() {
        return Err("Accounts table has a header but no account rows".to_string());
    }
    if data.len() % n_cols != 0 {
        return Err(format!(
            "Accounts table is malformed: {} data cells is not a whole number of {}-column rows \
            (an unrecognised column name in the header reads as data — allowed columns: {})",
            data.len(), n_cols, ACCOUNT_TABLE_COLUMNS.join(", ")));
    }

    let mut table = AccountTableData { names: Vec::new(), sizes: Vec::new(), initials: Vec::new() };
    for row in data.chunks(n_cols) {
        let mut size = f64::NAN;
        let mut initial = 0.0;
        for (col_name, cell) in header.iter().zip(row.iter()) {
            match col_name.as_str() {
                "name" => {
                    let name = cell.to_lowercase();
                    if !is_valid_bare_name(&name) {
                        return Err(format!("Invalid account name '{}'", cell));
                    }
                    // A keyword-named account would be swallowed by header
                    // detection when the saved file is re-read.
                    if ACCOUNT_TABLE_COLUMNS.contains(&name.as_str()) {
                        return Err(format!("Account name '{}' clashes with an accounts-table column name", cell));
                    }
                    table.names.push(name);
                }
                "size" => {
                    size = cell.parse::<f64>()
                        .map_err(|_| format!("Invalid size '{}' for account '{}': must be a number",
                            cell, table.names.last().map(String::as_str).unwrap_or("?")))?;
                    if !(size >= 0.0) {
                        return Err(format!("Account '{}' has negative size {}",
                            table.names.last().map(String::as_str).unwrap_or("?"), size));
                    }
                }
                "initial" => {
                    initial = cell.parse::<f64>()
                        .map_err(|_| format!("Invalid initial balance '{}' for account '{}': must be a number",
                            cell, table.names.last().map(String::as_str).unwrap_or("?")))?;
                }
                _ => unreachable!("header is drawn from ACCOUNT_TABLE_COLUMNS"),
            }
        }
        if initial < 0.0 || initial > size {
            return Err(format!("Account '{}' has initial balance {} outside [0, size={}]",
                table.names.last().map(String::as_str).unwrap_or("?"), initial, size));
        }
        table.sizes.push(size);
        table.initials.push(initial);
    }
    Ok(table)
}

/// Parse a RAS trigger: a calendar keyword or a DynamicExpression evaluated as
/// a pseudo-bool (level-semantic). The closed keyword set is tried first —
/// bare identifiers are never valid Kalix expressions, so there is no
/// ambiguity. `start_water_year(m)` takes its month explicitly: a literal or a
/// const.* reference (shared months live in [const]).
fn parse_ras_trigger(s: &str, model: &mut Model, line: usize) -> Result<crate::hydrology::allocation_systems::ras::RasTrigger, String> {
    use crate::hydrology::allocation_systems::ras::RasTrigger;
    let trimmed = s.trim();
    match trimmed {
        "every_step" => return Ok(RasTrigger::EveryStep),
        "start_month" => return Ok(RasTrigger::StartMonth),
        "start_year" => return Ok(RasTrigger::StartYear),
        "start_water_year" => {
            return Err(format!("Error on line {}: 'start_water_year' needs its month: \
                start_water_year(<1-12>) or start_water_year(const.<name>)", line));
        }
        _ => {}
    }
    if let Some(inner) = trimmed.strip_prefix("start_water_year(").and_then(|r| r.strip_suffix(')')) {
        let arg = inner.trim();
        let month_value = if let Ok(m) = arg.parse::<f64>() {
            m
        } else if arg.starts_with("const.") {
            model.data_cache.constants.get_value_by_name(&arg.to_lowercase())
                .map_err(|e| format!("Error on line {}: {}", line, e))?
        } else {
            return Err(format!("Error on line {}: Invalid start_water_year month '{}': \
                must be a number 1-12 or a const.* reference", line, arg));
        };
        let month = month_value as u8;
        if month_value.fract() != 0.0 || !(1..=12).contains(&month) {
            return Err(format!("Error on line {}: start_water_year month must be a whole number 1-12, got {}", line, month_value));
        }
        return Ok(RasTrigger::StartWaterYear(month));
    }
    // Anything else lowers as an expression
    let input = DynamicInput::from_string(trimmed, &mut model.data_cache, true, None)
        .map_err(|e| format!("Error on line {}: Invalid RAS trigger: {}", line, e))?;
    Ok(RasTrigger::Expression(input))
}

/// Parse a RAS action: a stencilled action name with an optional
/// expression-valued argument. Distributive actions arrive in phase 2/3.
fn parse_ras_action(s: &str, model: &mut Model, line: usize) -> Result<crate::hydrology::allocation_systems::ras::RasAction, String> {
    use crate::hydrology::allocation_systems::ras::RasAction;
    let trimmed = s.trim();
    match trimmed {
        "set_full" => return Ok(RasAction::SetFull),
        "set_empty" => return Ok(RasAction::SetEmpty),
        _ => {}
    }
    let open = trimmed.find('(');
    let (name, arg) = match (open, trimmed.ends_with(')')) {
        (Some(p), true) => (trimmed[..p].trim(), trimmed[p + 1..trimmed.len() - 1].trim()),
        _ => {
            return Err(format!("Error on line {}: Invalid RAS action '{}'. Expected one of: set_full, \
                set_empty, set(x), set_fraction(x), credit(x), debit(x), scale(x), reduce_to(x)", line, trimmed));
        }
    };
    let input = DynamicInput::from_string(arg, &mut model.data_cache, true, None)
        .map_err(|e| format!("Error on line {}: Invalid argument for RAS action '{}': {}", line, name, e))?;
    match name {
        "set" => Ok(RasAction::Set(input)),
        "set_fraction" => Ok(RasAction::SetFraction(input)),
        "credit" => Ok(RasAction::Credit(input)),
        "debit" => Ok(RasAction::Debit(input)),
        "scale" => Ok(RasAction::Scale(input)),
        "reduce_to" => Ok(RasAction::ReduceTo(input)),
        other => Err(format!("Error on line {}: Unknown RAS action '{}'. Expected one of: set_full, \
            set_empty, set(x), set_fraction(x), credit(x), debit(x), scale(x), reduce_to(x)", line, other)),
    }
}

/// Resolve a node's `accounts` property — a comma-separated, *ordered* list of
/// account names (order-of-use) — to account indices. Reference-only: names
/// must already be declared in [acc.*] sections.
fn resolve_account_references(v: &str, manager: &crate::hydrology::accounts::account_manager::AccountManager) -> Result<Vec<usize>, String> {
    let names = csv_to_string_vec(v);
    if names.is_empty() {
        return Err("'accounts' list is empty".to_string());
    }
    let mut idxs = Vec::with_capacity(names.len());
    for name in &names {
        let lower = name.to_lowercase();
        let idx = manager.get_account_idx(&lower).ok_or_else(|| {
            if manager.get_group_idx(&lower).is_some() {
                format!("'{}' is an account group; a node's 'accounts' list takes account names \
                    (group targeting belongs in [ras.*] sections)", name)
            } else {
                format!("Unknown account '{}'. Accounts are declared in [acc.*] sections.", name)
            }
        })?;
        if idxs.contains(&idx) {
            return Err(format!("Account '{}' is listed more than once", name));
        }
        idxs.push(idx);
    }
    Ok(idxs)
}

/// Render an account group's headed table for saving, in the same multi-line
/// continuation style as the loss node's table (one row per line, trailing
/// commas, 4-space continuation indent).
fn format_account_group_table(manager: &crate::hydrology::accounts::account_manager::AccountManager, group: &AccountGroup) -> String {
    let indent = " ".repeat(4);
    let mut out = String::from("name, size, initial, ");
    for &account_idx in &group.member_ids {
        if let Some(account) = manager.get_account(account_idx) {
            out.push('\n');
            out.push_str(&indent);
            out.push_str(&format!("{}, {}, {}, ", account.name, format_f64(account.size), format_f64(account.initial_balance)));
        }
    }
    out
}

/// Two sections are canonically equal when they hold the same property keys with
/// the same (canonical) values. Comments, ordering and raw formatting are
/// intentionally ignored — only the model-meaningful content is compared.
fn sections_canonically_equal(a: &IniSection, b: &IniSection) -> bool {
    if a.properties.len() != b.properties.len() {
        return false;
    }
    for (key, a_prop) in &a.properties {
        match b.properties.get(key) {
            Some(b_prop) if b_prop.value == a_prop.value => {}
            _ => return false,
        }
    }
    true
}
