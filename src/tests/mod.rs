#[cfg(test)]
mod test_helpers;

#[cfg(test)]
mod test_table;

#[cfg(test)]
mod test_timeseries;

#[cfg(test)]
mod test_gr4j_model;

#[cfg(test)]
mod test_gr4h_variant;

#[cfg(test)]
mod test_gr4h_validation;

#[cfg(test)]
mod test_node_inflow;

#[cfg(test)]
mod test_node_storage;

#[cfg(test)]
mod test_storage_exists;
mod test_storage_floor_blowup;
mod test_storage_spill_order_kink;

#[cfg(test)]
mod test_model;

#[cfg(test)]
mod test_misc;

#[cfg(test)]
mod test_gorilla;

#[cfg(test)]
mod test_csv_io;

#[cfg(test)]
mod test_functions_integration;

#[cfg(test)]
mod test_tid;

#[cfg(test)]
mod test_node_routing;

#[cfg(test)]
mod test_unit_hydrograph;

#[cfg(test)]
mod test_read_validation;
mod test_sacramento_model;

#[cfg(test)]
mod test_sacramento_node;

#[cfg(test)]
mod test_model_io_ini;
mod test_timeseries_input;
mod test_pixie;
mod test_model_with_function;

#[cfg(test)]
mod test_dynamic_input;

#[cfg(test)]
mod test_ini_with_functions;

#[cfg(test)]
mod test_constants_cache;

#[cfg(test)]
mod test_lookup_tables;

#[cfg(test)]
mod test_model_constant_optimisation;

#[cfg(test)]
mod test_ini_document;

#[cfg(test)]
mod test_sce;

#[cfg(test)]
mod test_linear_combination;

#[cfg(test)]
mod test_rainfall_weights;

#[cfg(test)]
mod test_linear_combination_save;

#[cfg(test)]
mod test_linear_combination_bug_fix;

#[cfg(test)]
mod test_input_validation;

#[cfg(test)]
mod test_fifo_buffer;

#[cfg(test)]
mod test_interpolation;

#[cfg(test)]
mod test_table_discontinuous;

#[cfg(test)]
mod test_programs;

#[cfg(test)]
mod test_sim_flags;

#[cfg(test)]
mod test_stateful_functions;

#[cfg(test)]
mod test_fn_section_io;

#[cfg(test)]
mod test_fn_inline;

#[cfg(test)]
mod test_var_blocks;

#[cfg(test)]
mod test_account_groups;


#[cfg(test)]
mod test_ras;

// Calendar built-ins: is_leap_year / month_at / days_in_month_at and the
// sim.days_in_* / sim.is_leap fields
mod test_calendar_builtins;

#[cfg(test)]
mod test_opportunistic_demand;

// Confluence order routing (regulated = <upstream node(s)>)
mod test_confluence_order_routing;
mod test_output_casing;
