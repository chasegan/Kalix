use std::collections::HashMap;
use std::f64;
use crate::io::csv_io::csv_string_to_f64_vec;
use crate::model::Model;
use crate::nodes::confluence_node::ConfluenceNode;
use crate::nodes::diversion_node::DiversionNode;
use crate::nodes::gr4j_node::Gr4jNode;
use crate::nodes::inflow_node::InflowNode;
use crate::nodes::routing_node::RoutingNode;
use crate::nodes::sacramento_node::SacramentoNode;
use crate::nodes::storage_node::StorageNode;
use crate::numerical::table::Table;

#[derive(Default)]
pub struct IniModelIO {
    pub name: String,
}


impl IniModelIO {
    pub fn new() -> IniModelIO {
        IniModelIO {
            ..Default::default()
        }
    }

    fn read_ini_file(&self, path: &str) -> Result<HashMap<String, HashMap<String, Option<String>>>, String> {
        // This is just a wrapper in case we want to change the lib we use for this.
        let map = ini!(safe path);
        map
    }

    pub fn read_model(&self, path: &str) -> Result<Model, String> {

        if let Ok(map) = self.read_ini_file(path) {

            // The first thing we want to read is the ini format version.
            // After that we will just loop through all the sections in the order they appear.
            let ini_format_version = map["attributes"]["ini_version"].clone().unwrap_or("".to_string());

            // Create a new model
            let mut model = Model::new();

            if ini_format_version == "0.0.1" {
                //TODO: take this parser and move it into a function defined in a file '0_0_1'. Maybe we have a new file for each version.

                //Iterate through the items in the ini_file
                for (k, v) in map {

                    if k == "attributes" {
                        // A section for model attributes
                        for (vp, _vv) in &v {
                            if vp == "ini_version" {
                                // Already read this
                            } else {
                                Err(format!("Unexpected attribute: {}", vp))?; //TODO: Why is there a questionmark here?
                            }
                        }
                    } else if k == "inputs" {
                        // We are in an input section
                        for (vp, _vv) in &v {
                            // Each vp is a path to an input file
                            print!("Loading data into model {}... ", vp.as_str());
                            model.load_input_data(vp.as_str())?; //TODO: Why is there a questionmark here?
                            println!("done.");
                        }
                    } else if k.starts_with("node.") {
                        let node_name = &k[5..];
                        let node_type = v["type"].clone().unwrap();
                        if node_type == "confluence" {
                            let mut n = ConfluenceNode::new();
                            n.name = node_name.to_string();
                            for (vp, _vv) in &v {
                                if vp == "type" {
                                    // skipping this
                                } else {
                                    Err(format!("Unexpected parameter '{}' for node '{}'", vp, node_name))?
                                }
                            }
                            model.add_node(Box::new(n));
                        } else if node_type == "diversion" {
                            let mut n = DiversionNode::new();
                            n.name = node_name.to_string();
                            for (vp, vv) in &v {
                                if vp == "demand" {
                                    n.demand_def.name = vv.clone().unwrap();
                                } else if vp == "type" {
                                    // skipping this
                                } else {
                                    Err(format!("Unexpected parameter '{}' for node '{}'", vp, node_name))?
                                }
                            }
                            model.add_node(Box::new(n));
                        } else if node_type == "gr4j" {
                            let mut n = Gr4jNode::new();
                            n.name = node_name.to_string();
                            for (vp, vv) in &v {
                                let vvc = vv.clone().unwrap();
                                if vp == "evap" {
                                    n.evap_mm_def.name = vvc.clone();
                                } else if vp == "rain" {
                                    n.rain_mm_def.name = vvc.clone();
                                } else if vp == "area" {
                                    n.area_km2 = vv.clone().unwrap().parse::<f64>().unwrap();
                                } else if vp == "params" {
                                    let params = csv_string_to_f64_vec(vv.clone().unwrap().as_str());
                                    n.gr4j_model.x1 = params[0];
                                    n.gr4j_model.x2 = params[1];
                                    n.gr4j_model.x3 = params[2];
                                    n.gr4j_model.x4 = params[3];
                                } else if vp == "type" {
                                    // skipping this
                                } else {
                                    Err(format!("Unexpected parameter '{}' for node '{}'", vp, node_name))?
                                }
                            }
                            model.add_node(Box::new(n));
                        } else if node_type == "inflow" {
                            let mut n = InflowNode::new();
                            n.name = node_name.to_string();
                            for (vp, vv) in &v {
                                if vp == "inflow" {
                                    n.inflow_def.name = vv.clone().unwrap();
                                } else if vp == "type" {
                                    // skipping this
                                } else {
                                    Err(format!("Unexpected parameter '{}' for node '{}'", vp, node_name))?
                                }
                            }
                            model.add_node(Box::new(n));
                        } else if node_type == "routing" {
                            let mut n = RoutingNode::new();
                            let mut r_flows: Option<Vec<f64>> = None;
                            let mut r_times: Option<Vec<f64>> = None;
                            n.name = node_name.to_string();
                            for (vp, vv) in &v {
                                let vvc = vv.clone().unwrap();
                                if vp == "lag" {
                                    n.set_lag(vvc.parse::<i32>().unwrap());
                                } else if vp == "divs" {
                                    n.set_divs(vvc.parse::<usize>().unwrap());
                                } else if vp == "x" {
                                    n.set_x(vvc.parse::<f64>().unwrap());
                                } else if vp == "index_flows" {
                                    let index_flows = csv_string_to_f64_vec(vvc.as_str());
                                    if let Some(index_times) = &r_times {
                                        n.set_routing_table(index_flows, index_times.clone());
                                    } else {
                                        r_flows = Some(index_flows);
                                    }
                                } else if vp == "index_times" {
                                    let index_times = csv_string_to_f64_vec(vvc.as_str());
                                    if let Some(index_flows) = &r_flows {
                                        n.set_routing_table(index_flows.clone(), index_times);
                                    } else {
                                        r_times = Some(index_times);
                                    }
                                } else if vp == "type" {
                                    // skipping this
                                } else {
                                    Err(format!("Unexpected parameter '{}' for node '{}'", vp, node_name))?
                                }
                            }
                            model.add_node(Box::new(n));
                        } else if node_type == "sacramento" {
                            let mut n = SacramentoNode::new();
                            n.name = node_name.to_string();
                            for (vp, vv) in &v {
                                let vvc = vv.clone().unwrap();
                                if vp == "evap" {
                                    n.evap_mm_def.name = vvc.clone();
                                } else if vp == "rain" {
                                    n.rain_mm_def.name = vvc.clone();
                                } else if vp == "area" {
                                    n.area_km2 = vvc.parse::<f64>().unwrap();
                                } else if vp == "params" {
                                    let params = csv_string_to_f64_vec(vvc.as_str());
                                    n.sacramento_model.set_params(params[0], params[1], params[2], params[3],
                                                                  params[4], params[5], params[6], params[7],
                                                                  params[8], params[9], params[10],params[11],
                                                                  params[12], params[13], params[14], params[15],params[16]);
                                } else if vp == "type" {
                                    // skipping this
                                } else {
                                    Err(format!("Unexpected parameter '{}' for node '{}'", vp, node_name))?
                                }
                            }
                            model.add_node(Box::new(n));
                        } else if node_type == "storage" {
                            let mut n = StorageNode::new();
                            n.name = node_name.to_string();
                            for (vp, vv) in &v {
                                let vvc = vv.clone().unwrap();
                                if vp == "evap" {
                                    n.evap_mm_def.name = vvc.clone();
                                } else if vp == "rain" {
                                    n.rain_mm_def.name = vvc.clone();
                                } else if vp == "seep" {
                                    n.seep_mm_def.name = vvc.clone();
                                } else if vp == "pond_demand" {
                                    n.demand_def.name = vvc.clone();
                                } else if vp == "dimensions_file" {
                                    n.d = Table::from_csv(vvc.as_str());
                                } else if vp == "type" {
                                    // skipping this
                                } else {
                                    Err(format!("Unexpected parameter '{}' for node '{}'", vp, node_name))?
                                }
                            }
                            model.add_node(Box::new(n));
                        } else {
                            Err(format!("Unexpected node type: {}", node_type))?
                        }
                    } else if k == "outputs" {
                        // We are in an output section
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
            } else {
                Err(format!("Unsupported model version: {}", ini_format_version))?;
            }
            Ok(model)
        } else {
            Err(format!("Could not read the file: {}", path))
        }
    }
}