use std::collections::HashMap;
use std::sync::Arc;
use crate::apis::stdio::messages::{CommandSpec, ParameterSpec, ProgressInfo};
use crate::apis::stdio::session::Session;
use crate::io::ini_model_io::IniModelIO;
use crate::io::csv_io;
use chrono;
use crate::tid;

pub trait Command: Send + Sync {
    fn name(&self) -> &str;
    fn description(&self) -> &str;
    fn parameters(&self) -> Vec<ParameterSpec>;
    fn interruptible(&self) -> bool;
    
    fn execute(
        &self,
        session: &mut Session,
        params: serde_json::Value,
        _progress_sender: Box<dyn Fn(ProgressInfo) + Send>,
    ) -> Result<serde_json::Value, CommandError>;

    fn get_spec(&self) -> CommandSpec {
        CommandSpec {
            name: self.name().to_string(),
            description: self.description().to_string(),
            parameters: self.parameters(),
        }
    }
}

#[derive(Debug, thiserror::Error)]
pub enum CommandError {
    #[error("Invalid parameters: {0}")]
    InvalidParameters(String),
    
    #[error("Command execution error: {0}")]
    ExecutionError(String),
    
    #[error("Task was interrupted")]
    Interrupted,
    
    #[error("Model not loaded")]
    ModelNotLoaded,
    
    #[error("Data not loaded")]
    DataNotLoaded,
    
    #[error("IO error: {0}")]
    IoError(String),

    #[error("Result not found: {0}")]
    ResultNotFound(String),
}

pub struct CommandRegistry {
    commands: HashMap<String, Arc<dyn Command>>,
}

impl CommandRegistry {
    pub fn new() -> Self {
        let mut registry = Self {
            commands: HashMap::new(),
        };
        
        // Register built-in commands
        registry.register(Arc::new(GetVersionCommand));
        registry.register(Arc::new(GetStateCommand));
        registry.register(Arc::new(TestProgressCommand));
        registry.register(Arc::new(LoadModelFileCommand));
        registry.register(Arc::new(LoadModelStringCommand));
        registry.register(Arc::new(RunSimulationCommand));
        registry.register(Arc::new(RunCalibrationCommand));
        registry.register(Arc::new(GetResultCommand));
        registry.register(Arc::new(SaveResultsCommand));
        registry.register(Arc::new(EchoCommand));
        
        registry
    }
    
    pub fn register(&mut self, command: Arc<dyn Command>) {
        self.commands.insert(command.name().to_string(), command);
    }
    
    pub fn get_command(&self, name: &str) -> Option<Arc<dyn Command>> {
        self.commands.get(name).cloned()
    }
    
    pub fn get_all_specs(&self) -> Vec<CommandSpec> {
        self.commands.values().map(|cmd| cmd.get_spec()).collect()
    }
    
    pub fn list_commands(&self) -> Vec<&str> {
        self.commands.keys().map(|s| s.as_str()).collect()
    }
}

// Built-in commands

pub struct GetVersionCommand;

impl Command for GetVersionCommand {
    fn name(&self) -> &str {
        "get_version"
    }
    
    fn description(&self) -> &str {
        "Get kalixcli version information"
    }
    
    fn parameters(&self) -> Vec<ParameterSpec> {
        vec![]
    }
    
    fn interruptible(&self) -> bool {
        false
    }
    
    fn execute(
        &self,
        _session: &mut Session,
        _params: serde_json::Value,
        _progress_sender: Box<dyn Fn(ProgressInfo) + Send>,
    ) -> Result<serde_json::Value, CommandError> {
        Ok(serde_json::json!({
            "version": "0.1.0",
            "build_date": "2025-09-08",
            "features": ["stdio", "modeling", "calibration"]
        }))
    }
}

pub struct GetStateCommand;

impl Command for GetStateCommand {
    fn name(&self) -> &str {
        "get_state"
    }
    
    fn description(&self) -> &str {
        "Get current kalixcli state information"
    }
    
    fn parameters(&self) -> Vec<ParameterSpec> {
        vec![]
    }
    
    fn interruptible(&self) -> bool {
        false
    }
    
    fn execute(
        &self,
        session: &mut Session,
        _params: serde_json::Value,
        _progress_sender: Box<dyn Fn(ProgressInfo) + Send>,
    ) -> Result<serde_json::Value, CommandError> {
        let state_info = session.get_state_info();
        Ok(serde_json::to_value(state_info).unwrap())
    }
}

pub struct TestProgressCommand;

impl Command for TestProgressCommand {
    fn name(&self) -> &str {
        "test_progress"
    }
    
    fn description(&self) -> &str {
        "Test command that demonstrates progress reporting and interruption"
    }
    
    fn parameters(&self) -> Vec<ParameterSpec> {
        vec![
            ParameterSpec {
                name: "duration_seconds".to_string(),
                param_type: "integer".to_string(),
                required: false,
                default: Some(serde_json::json!(10)),
            }
        ]
    }
    
    fn interruptible(&self) -> bool {
        true
    }
    
    fn execute(
        &self,
        session: &mut Session,
        params: serde_json::Value,
        progress_sender: Box<dyn Fn(ProgressInfo) + Send>,
    ) -> Result<serde_json::Value, CommandError> {
        let duration = params.get("duration_seconds")
            .and_then(|v| v.as_i64())
            .unwrap_or(10) as u64;
        
        let total_steps = 100;
        let step_duration = std::time::Duration::from_millis(duration * 1000 / total_steps);
        
        for i in 0..=total_steps {
            // Check for interrupt
            if session.check_interrupt() {
                return Err(CommandError::Interrupted);
            }
            
            // Send progress update
            let progress = ProgressInfo {
                percent_complete: i as f64,
                current_step: format!("Step {} of {}", i, total_steps),
                estimated_remaining: if i < total_steps {
                    let remaining_steps = total_steps - i;
                    let remaining_seconds = remaining_steps * step_duration.as_millis() as u64 / 1000;
                    Some(format!("00:{:02}:{:02}", remaining_seconds / 60, remaining_seconds % 60))
                } else {
                    None
                },
                data: None,
                current: None,
                total: None,
                task_type: None,
            };
            
            progress_sender(progress);
            
            if i < total_steps {
                std::thread::sleep(step_duration);
            }
        }
        
        Ok(serde_json::json!({
            "completed": true,
            "total_steps": total_steps,
            "duration_seconds": duration
        }))
    }
}

pub struct LoadModelFileCommand;

impl Command for LoadModelFileCommand {
    fn name(&self) -> &str {
        "load_model_file"
    }
    
    fn description(&self) -> &str {
        "Load a hydrological model from a file path"
    }
    
    fn parameters(&self) -> Vec<ParameterSpec> {
        vec![
            ParameterSpec {
                name: "model_path".to_string(),
                param_type: "string".to_string(),
                required: true,
                default: None,
            }
        ]
    }
    
    fn interruptible(&self) -> bool {
        false
    }
    
    fn execute(
        &self,
        session: &mut Session,
        params: serde_json::Value,
        _progress_sender: Box<dyn Fn(ProgressInfo) + Send>,
    ) -> Result<serde_json::Value, CommandError> {
        // Extract parameters
        let model_path = params.get("model_path")
            .and_then(|v| v.as_str())
            .ok_or_else(|| CommandError::InvalidParameters("model_path is required".to_string()))?;

        // Load the model
        let ini_reader = IniModelIO::new();
        let model = ini_reader.read_model_file(model_path)
            .map_err(|e| CommandError::ExecutionError(format!("Failed to load model: {}", e)))?;

        // Store the model in the session
        session.set_model(model);
        
        let model_info = session.get_model()
            .map(|m| serde_json::json!({
                "nodes_count": m.nodes.len(),
                "inputs_count": m.inputs.len(),
                "outputs_count": m.outputs.len()
            }))
            .unwrap_or(serde_json::json!({}));
        
        Ok(serde_json::json!({
            "success": true,
            "model_path": model_path,
            "model_info": model_info
        }))
    }
}

pub struct LoadModelStringCommand;

impl Command for LoadModelStringCommand {
    fn name(&self) -> &str {
        "load_model_string"
    }
    
    fn description(&self) -> &str {
        "Load a hydrological model from an INI string"
    }
    
    fn parameters(&self) -> Vec<ParameterSpec> {
        vec![
            ParameterSpec {
                name: "model_ini".to_string(),
                param_type: "string".to_string(),
                required: true,
                default: None,
            }
        ]
    }
    
    fn interruptible(&self) -> bool {
        false
    }
    
    fn execute(
        &self,
        session: &mut Session,
        params: serde_json::Value,
        _progress_sender: Box<dyn Fn(ProgressInfo) + Send>,
    ) -> Result<serde_json::Value, CommandError> {
        // Extract parameters
        let model_ini = params.get("model_ini")
            .and_then(|v| v.as_str())
            .ok_or_else(|| CommandError::InvalidParameters("model_ini is required".to_string()))?;

        // Load the model from string
        let ini_reader = IniModelIO::new();
        let model = ini_reader.read_model_string(model_ini)
            .map_err(|e| CommandError::ExecutionError(format!("Failed to parse model: {}", e)))?;

        // Store the model in the session
        session.set_model(model);
        
        let model_info = session.get_model()
            .map(|m| serde_json::json!({
                "nodes_count": m.nodes.len(),
                "inputs_count": m.inputs.len(),
                "outputs_count": m.outputs.len()
            }))
            .unwrap_or(serde_json::json!({}));
        
        Ok(serde_json::json!({
            "success": true,
            "ini_length": model_ini.len(),
            "model_info": model_info
        }))
    }
}

pub struct EchoCommand;

impl Command for EchoCommand {
    fn name(&self) -> &str {
        "echo"
    }

    fn description(&self) -> &str {
        "Echo back the provided string"
    }

    fn parameters(&self) -> Vec<ParameterSpec> {
        vec![
            ParameterSpec {
                name: "string".to_string(),
                param_type: "string".to_string(),
                required: true,
                default: None,
            },
        ]
    }

    fn interruptible(&self) -> bool {
        false
    }

    fn execute(
        &self,
        _session: &mut Session,
        params: serde_json::Value,
        _progress_sender: Box<dyn Fn(ProgressInfo) + Send>,
    ) -> Result<serde_json::Value, CommandError> {
        // Extract parameters
        let string = params.get("string")
            .and_then(|v| v.as_str())
            .ok_or_else(|| CommandError::InvalidParameters("string is required".to_string()))?;

        // Return the echoed string
        Ok(serde_json::json!({
            "echoed": string
        }))
    }
}

pub struct GetResultCommand;

impl Command for GetResultCommand {
    fn name(&self) -> &str {
        "get_result"
    }

    fn description(&self) -> &str {
        "Retrieve timeseries result data from the model"
    }

    fn parameters(&self) -> Vec<ParameterSpec> {
        vec![
            ParameterSpec {
                name: "series_name".to_string(),
                param_type: "string".to_string(),
                required: true,
                default: None,
            },
            ParameterSpec {
                name: "format".to_string(),
                param_type: "string".to_string(),
                required: true,
                default: Some(serde_json::Value::String("csv".to_string())),
            },
        ]
    }

    fn interruptible(&self) -> bool {
        false
    }

    fn execute(
        &self,
        session: &mut Session,
        params: serde_json::Value,
        _progress_sender: Box<dyn Fn(ProgressInfo) + Send>,
    ) -> Result<serde_json::Value, CommandError> {
        // Extract parameters
        let series_name = params.get("series_name")
            .and_then(|v| v.as_str())
            .ok_or_else(|| CommandError::InvalidParameters("series_name is required".to_string()))?;

        let format = params.get("format")
            .and_then(|v| v.as_str())
            .unwrap_or("csv");

        if format != "csv" {
            return Err(CommandError::InvalidParameters("Only csv format is currently supported".to_string()));
        }

        // Get model and check if it exists
        let model = session.get_model()
            .ok_or(CommandError::ModelNotLoaded)?;

        // Find the series in the data cache
        let series_idx = model.data_cache.get_existing_series_idx(series_name)
            .ok_or_else(|| CommandError::ResultNotFound(format!("Timeseries '{}' not found in model results", series_name)))?;

        let timeseries = &model.data_cache.series[series_idx];

        // Build CSV data string
        let mut csv_data = String::new();

        // Add start timestamp and timestep
        let start_timestamp = tid::utils::u64_to_iso_datetime_string(timeseries.start_timestamp);

        csv_data.push_str(&format!("{},{}", start_timestamp, timeseries.step_size));

        // Add values
        for value in &timeseries.values {
            csv_data.push_str(&format!(",{}", value));
        }

        // Build response
        Ok(serde_json::json!({
            "series_name": series_name,
            "format": format,
            "metadata": {
                "start_timestamp": start_timestamp,
                "timestep_seconds": timeseries.step_size,
                "total_points": timeseries.values.len(),
                "units": "unknown" // TODO: Add units to timeseries struct
            },
            "data": csv_data
        }))
    }
}

pub struct RunSimulationCommand;

impl Command for RunSimulationCommand {
    fn name(&self) -> &str {
        "run_simulation"
    }
    
    fn description(&self) -> &str {
        "Execute model simulation with loaded model and data"
    }
    
    fn parameters(&self) -> Vec<ParameterSpec> {
        vec![] // No parameters for now
    }
    
    fn interruptible(&self) -> bool {
        true // This is a long-running operation
    }
    
    fn execute(
        &self,
        session: &mut Session,
        _params: serde_json::Value,
        progress_sender: Box<dyn Fn(ProgressInfo) + Send>,
    ) -> Result<serde_json::Value, CommandError> {
        use std::time::Instant;
        use std::sync::Arc;
        use std::sync::atomic::{AtomicU64, Ordering};
        
        // Get interrupt flag before getting mutable model reference
        let interrupt_flag = Arc::clone(&session.interrupt_flag);

        // Check if model is loaded
        let model = session.get_model_mut()
            .ok_or(CommandError::ModelNotLoaded)?;

        // Check if model has input data
        if model.inputs.is_empty() {
            return Err(CommandError::DataNotLoaded);
        }

        // Try to configure the model simulation period
        match model.configure() {
            Ok(_) => (),
            Err(e) => {
                return Err(CommandError::ExecutionError(format!("Configuration failed: {}", e)));
            }
        }
        
        // Get simulation info for result
        let start_timestamp = model.configuration.sim_start_timestamp;
        let end_timestamp = model.configuration.sim_end_timestamp;
        let stepsize = model.configuration.sim_stepsize;
        let total_timesteps = ((end_timestamp - start_timestamp) / stepsize) + 1;
        
        // Track progress timing for rate limiting (max 1 update per 200ms, 1 per percentage)
        let last_progress_time = Arc::new(std::sync::Mutex::new(Instant::now()));
        let last_progress_percent = Arc::new(AtomicU64::new(0));
        
        // Create progress callback for the model
        let progress_sender_clone = Arc::new(progress_sender);
        let progress_callback = {
            let last_time = Arc::clone(&last_progress_time);
            let last_percent = Arc::clone(&last_progress_percent);
            let sender = Arc::clone(&progress_sender_clone);
            
            Box::new(move |current_step: u64, total_steps: u64| {
                // Calculate percentage (0% to 100% range for simulation)
                let sim_progress = (current_step as f64 / total_steps as f64) * 100.0;
                let overall_progress = sim_progress;
                let overall_percent = overall_progress as u64;
                
                // Check rate limiting conditions
                let now = Instant::now();
                let should_update = {
                    let mut last_time_guard = last_time.lock().unwrap();
                    let time_elapsed = now.duration_since(*last_time_guard).as_millis() >= 200;
                    let percent_changed = overall_percent > last_percent.load(Ordering::Relaxed);
                    
                    if time_elapsed && percent_changed {
                        *last_time_guard = now;
                        last_percent.store(overall_percent, Ordering::Relaxed);
                        true
                    } else {
                        false
                    }
                };
                
                if should_update {
                    sender(ProgressInfo {
                        percent_complete: overall_progress,
                        current_step: format!("Running simulation - Processing timestep {} of {}", current_step + 1, total_steps),
                        estimated_remaining: None,
                        data: None,
                        current: None,
                        total: None,
                        task_type: None,
                    });
                }
            })
        };
        
        // Send initial progress message
        let progress_sender_clone = Arc::clone(&progress_sender_clone);
        progress_sender_clone(ProgressInfo {
            percent_complete: 0.0,
            current_step: format!("Running simulation - Processing timestep 1 of {}", total_timesteps),
            estimated_remaining: None,
            data: None,
            current: None,
            total: None,
            task_type: None,
        });

        // Simulation phase - 20% to 90%
        let simulation_start = Instant::now();

        // Run the simulation with interrupt checking
        let completed = model.run_with_interrupt(
            move || interrupt_flag.load(Ordering::Relaxed),
            Some(progress_callback)
        ).map_err(|e| CommandError::ExecutionError(format!("Simulation failed: {}", e)))?;
        
        let simulation_duration = simulation_start.elapsed();
        
        if !completed {
            // Simulation was interrupted
            return Err(CommandError::Interrupted);
        }

        // Send final progress message for 100% completion
        progress_sender_clone(ProgressInfo {
            percent_complete: 100.0,
            current_step: format!("Running simulation - Processing timestep {} of {}", total_timesteps, total_timesteps),
            estimated_remaining: None,
            data: None,
            current: None,
            total: None,
            task_type: None,
        });

        // Collect output information
        let outputs_generated: Vec<String> = model.outputs.clone();
        
        // Store simulation metadata in session results
        let simulation_metadata = serde_json::json!({
            "timestamp": chrono::Utc::now(),
            "duration_seconds": simulation_duration.as_secs(),
            "timesteps": total_timesteps,
            "outputs": outputs_generated.clone(),
        });
        session.store_result("last_simulation".to_string(), simulation_metadata);
        
        Ok(serde_json::json!({
            "simulation_completed": true,
            "timesteps_processed": total_timesteps,
            "outputs_generated": outputs_generated,
            "simulation_period": format!("{} to {}", 
                crate::tid::utils::u64_to_date_string(start_timestamp),
                crate::tid::utils::u64_to_date_string(end_timestamp)
            ),
            "execution_time_seconds": simulation_duration.as_secs(),
            "available_results": ["timeseries_data", "summary_statistics"]
        }))
    }
}

pub struct RunCalibrationCommand;

impl Command for RunCalibrationCommand {
    fn name(&self) -> &str {
        "run_calibration"
    }

    fn description(&self) -> &str {
        "Run model calibration with specified configuration"
    }

    fn parameters(&self) -> Vec<ParameterSpec> {
        vec![
            ParameterSpec {
                name: "config".to_string(),
                param_type: "string".to_string(),
                required: true,
                default: None,
            },
            ParameterSpec {
                name: "model_ini".to_string(),
                param_type: "string".to_string(),
                required: false,
                default: None,
            }
        ]
    }

    fn interruptible(&self) -> bool {
        true
    }

    fn execute(
        &self,
        session: &mut Session,
        params: serde_json::Value,
        progress_sender: Box<dyn Fn(ProgressInfo) + Send>,
    ) -> Result<serde_json::Value, CommandError> {
        use crate::numerical::opt::{
            CalibrationConfig, AlgorithmParams, CalibrationProblem,
            DifferentialEvolution, DEConfig, DEProgress
        };
        use crate::io::calibration_config_io::load_observed_timeseries;

        // Extract config string parameter
        let config_str = params.get("config")
            .and_then(|v| v.as_str())
            .ok_or_else(|| CommandError::InvalidParameters("config is required".to_string()))?;

        // Parse calibration configuration from INI format
        let config = CalibrationConfig::from_ini(config_str)
            .map_err(|e| CommandError::InvalidParameters(format!("Failed to parse calibration config: {}", e)))?;

        // Load model: prioritize inline model_ini parameter, otherwise use model_file from config
        let model = if let Some(model_ini) = params.get("model_ini").and_then(|v| v.as_str()) {
            // Use inline model (takes precedence)
            IniModelIO::new().read_model_string(model_ini)
                .map_err(|e| CommandError::ExecutionError(format!("Failed to parse inline model: {}", e)))?
        } else if let Some(model_file) = &config.model_file {
            // Fallback to model_file from config
            IniModelIO::new().read_model_file(model_file)
                .map_err(|e| CommandError::ExecutionError(format!("Failed to load model from '{}': {}", model_file, e)))?
        } else {
            return Err(CommandError::ExecutionError("Either 'model_ini' parameter or 'model_file' in config must be provided".to_string()));
        };

        // Load observed data
        let observed_timeseries = load_observed_timeseries(&config.observed_data_series)
            .map_err(|e| CommandError::ExecutionError(format!("Failed to load observed data: {}", e)))?;

        let observed_data = observed_timeseries.timeseries.values.clone();

        // Create calibration problem
        let mut problem = CalibrationProblem::new(
            model,
            config.parameter_config.clone(),
            observed_data,
            config.simulated_series.clone(),
        ).with_objective(config.objective_function);

        // Extract algorithm parameters
        let (population_size, de_f, de_cr) = match &config.algorithm {
            AlgorithmParams::DE { population_size, f, cr } => (*population_size, *f, *cr),
            _ => {
                return Err(CommandError::ExecutionError(
                    format!("Only 'DE' algorithm is currently supported, got: {}", config.algorithm.name())
                ));
            }
        };

        // Get interrupt flag
        let interrupt_flag = std::sync::Arc::clone(&session.interrupt_flag);

        // Create progress callback that sends STDIO progress messages
        let termination_evals = config.termination_evaluations;
        let progress_callback = Box::new(move |progress: &DEProgress| {
            // Check for interrupt
            if interrupt_flag.load(std::sync::atomic::Ordering::Relaxed) {
                return;
            }

            progress_sender(ProgressInfo {
                percent_complete: (progress.n_evaluations as f64 / termination_evals as f64) * 100.0,
                current_step: format!("{} evaluations, best fitness = {:.6}",
                    progress.n_evaluations, progress.best_fitness),
                estimated_remaining: None,
                data: Some(vec![progress.best_fitness]),
                current: Some(progress.n_evaluations as i64),
                total: Some(termination_evals as i64),
                task_type: Some("cal".to_string()),
            });
        });

        // Create DE optimizer
        let de_config = DEConfig {
            population_size,
            termination_evaluations: config.termination_evaluations,
            f: de_f,
            cr: de_cr,
            seed: config.random_seed,
            n_threads: config.n_threads,
            progress_callback: Some(progress_callback),
        };

        let optimizer = DifferentialEvolution::new(de_config);

        // Run optimization
        let result = optimizer.optimize(&mut problem);

        // Check if interrupted
        if session.check_interrupt() {
            return Err(CommandError::Interrupted);
        }

        // Get physical parameter values
        let params_physical = problem.config.evaluate(&result.best_params);

        // Build result
        Ok(serde_json::json!({
            "best_fitness": result.best_fitness,
            "generations": result.generations,
            "evaluations": result.n_evaluations,
            "params_normalized": result.best_params,
            "params_physical": params_physical.into_iter().collect::<std::collections::HashMap<_, _>>(),
            "success": result.success,
            "message": result.message
        }))
    }
}

pub struct SaveResultsCommand;

impl Command for SaveResultsCommand {
    fn name(&self) -> &str {
        "save_results"
    }

    fn description(&self) -> &str {
        "Save timeseries result data to file"
    }

    fn parameters(&self) -> Vec<ParameterSpec> {
        vec![
            ParameterSpec {
                name: "path".to_string(),
                param_type: "string".to_string(),
                required: false,
                default: None,
            },
            ParameterSpec {
                name: "format".to_string(),
                param_type: "string".to_string(),
                required: false,
                default: Some(serde_json::Value::String("csv".to_string())),
            },
        ]
    }

    fn interruptible(&self) -> bool {
        false
    }

    fn execute(
        &self,
        session: &mut Session,
        params: serde_json::Value,
        _progress_sender: Box<dyn Fn(ProgressInfo) + Send>,
    ) -> Result<serde_json::Value, CommandError> {
        use std::path::Path;

        // Extract parameters
        let format = params.get("format")
            .and_then(|v| v.as_str())
            .unwrap_or("csv");

        if format != "csv" {
            return Err(CommandError::InvalidParameters("Only csv format is currently supported".to_string()));
        }

        // Get model and check if it exists
        let model = session.get_model()
            .ok_or(CommandError::ModelNotLoaded)?;

        // Check if we have any outputs to save
        if model.outputs.is_empty() {
            return Err(CommandError::ExecutionError("No simulation results available to save. Run simulation first.".to_string()));
        }

        // Determine file path
        let file_path = if let Some(path) = params.get("path").and_then(|v| v.as_str()) {
            path.to_string()
        } else {
            // Generate default filename based on current timestamp
            let timestamp = chrono::Utc::now().format("%Y%m%d_%H%M%S");
            format!("simulation_results_{}.csv", timestamp)
        };

        // Collect timeseries references for output series
        let mut timeseries_refs = Vec::new();
        let mut series_count = 0;
        let mut total_timesteps = 0;

        for output_name in &model.outputs {
            if let Some(series_idx) = model.data_cache.get_existing_series_idx(output_name) {
                let timeseries = &model.data_cache.series[series_idx];
                timeseries_refs.push(timeseries);
                series_count += 1;

                // Track the number of timesteps (should be the same for all series)
                if total_timesteps == 0 {
                    total_timesteps = timeseries.values.len();
                }
            }
        }

        if timeseries_refs.is_empty() {
            return Err(CommandError::ExecutionError("No timeseries data found for output series".to_string()));
        }

        // Use the standard csv_io write_ts function
        csv_io::write_ts(&file_path, timeseries_refs)
            .map_err(|e| CommandError::IoError(format!("Failed to write CSV file: {}", String::from(e))))?;

        // Get the absolute path for the response
        let absolute_path = Path::new(&file_path)
            .canonicalize()
            .map(|p| p.to_string_lossy().to_string())
            .unwrap_or(file_path.clone());

        // Build response
        Ok(serde_json::json!({
            "path": absolute_path,
            "format": format,
            "n_series": series_count,
            "len": total_timesteps
        }))
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_command_registry() {
        let registry = CommandRegistry::new();
        let commands = registry.list_commands();

        assert!(commands.contains(&"get_version"));
        assert!(commands.contains(&"get_state"));
        assert!(commands.contains(&"test_progress"));
        assert!(commands.contains(&"load_model_file"));
        assert!(commands.contains(&"load_model_string"));
        assert!(commands.contains(&"run_simulation"));
        assert!(commands.contains(&"run_calibration"));
        assert!(commands.contains(&"get_result"));
        assert!(commands.contains(&"save_results"));
        assert!(commands.contains(&"echo"));
    }

    #[test]
    fn test_get_version_command() {
        let cmd = GetVersionCommand;
        let mut session = Session::new();
        
        let result = cmd.execute(
            &mut session,
            serde_json::json!({}),
            Box::new(|_| {}),
        ).unwrap();
        
        assert_eq!(result["version"], "0.1.0");
    }
}